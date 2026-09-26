package eu.kanade.tachiyomi.network.interceptor

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Solves Cloudflare challenges through an external FlareSolverr- / Byparr-
 * compatible proxy, when the client supplies one.
 *
 * Extensions make their requests here, not in the client, so this interceptor
 * is the only place that ever sees the challenge. Until now it did nothing, and
 * there is no local fallback either: [xyz.nulldev.androidcompat.webkit.KcefWebViewProvider]
 * is registered as the WebView provider but `KCEF.init` is never called, so no
 * browser is available to solve one. A challenged source simply failed.
 *
 * Does nothing when no proxy URL has been supplied, which keeps the previous
 * behaviour for every client that does not set one.
 */
class CloudflareInterceptor(
    private val proxyUrl: () -> String,
    private val setUserAgent: (String) -> Unit,
    private val storeCookies: (HttpUrl, List<Cookie>) -> Unit,
) : Interceptor {
    private val logger = KotlinLogging.logger {}

    private val objectMapper =
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

    /** Kept separate so solving never recurses back through this interceptor. */
    private val solverClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(SOLVER_TIMEOUT_MS / 1000L + 15, TimeUnit.SECONDS)
            .callTimeout(SOLVER_TIMEOUT_MS / 1000L + 30, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) {
            return response
        }

        val url = proxyUrl().trim()
        if (url.isEmpty()) {
            logger.debug { "Cloudflare challenge on ${originalRequest.url.host}, but no proxy is configured" }
            return response
        }

        logger.debug { "Cloudflare challenge on ${originalRequest.url.host}, solving through the configured proxy" }

        val solution =
            try {
                solve(url, originalRequest.url.toString())
            } catch (e: Exception) {
                logger.warn(e) { "Proxy call failed, returning the original response" }
                null
            } ?: return response

        response.close()
        return chain.proceed(applySolution(originalRequest, solution))
    }

    private fun solve(
        proxyUrl: String,
        targetUrl: String,
    ): FlareSolution? {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "cmd" to "request.get",
                    "url" to targetUrl,
                    "maxTimeout" to SOLVER_TIMEOUT_MS,
                ),
            )

        val request =
            Request
                .Builder()
                .url(proxyUrl)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

        solverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Proxy answered ${response.code}" }
                return null
            }

            val body = response.body?.string().orEmpty()
            if (body.isEmpty()) return null

            val parsed = objectMapper.readValue<FlareResponse>(body)
            if (!parsed.status.equals("ok", ignoreCase = true)) {
                logger.warn { "Proxy reported status '${parsed.status}': ${parsed.message}" }
                return null
            }

            val solution = parsed.solution ?: return null

            // A solution with neither cookies nor a user-agent cannot change the
            // outcome, so there is nothing to retry with.
            return solution.takeIf { it.cookies.isNotEmpty() || it.userAgent.isNotEmpty() }
        }
    }

    /**
     * Stores the returned cookies and user-agent, then rebuilds the request.
     *
     * The cookies go into the shared jar, so OkHttp's own bridge attaches them
     * to the retried call and to every later request to that host. The
     * user-agent has to be set on the request directly: [UserAgentInterceptor]
     * has already run by this point and only fills in a missing header anyway.
     */
    private fun applySolution(
        request: Request,
        solution: FlareSolution,
    ): Request {
        val cookies = solution.cookies.mapNotNull { it.toOkHttpCookie() }
        if (cookies.isNotEmpty()) {
            cookies.groupBy { it.domain }.forEach { (domain, domainCookies) ->
                val url =
                    HttpUrl
                        .Builder()
                        .scheme("https")
                        .host(domain)
                        .build()
                storeCookies(url, domainCookies)
            }
        }

        if (solution.userAgent.isEmpty()) return request

        setUserAgent(solution.userAgent)
        return request
            .newBuilder()
            .header("User-Agent", solution.userAgent)
            .build()
    }

    private fun FlareCookie.toOkHttpCookie(): Cookie? {
        val host = domain.removePrefix(".")
        if (name.isEmpty() || host.isEmpty()) return null

        return runCatching {
            Cookie
                .Builder()
                .name(name)
                .value(value)
                .also { builder ->
                    if (domain.startsWith(".")) builder.domain(host) else builder.hostOnlyDomain(host)
                    if (!path.isNullOrEmpty()) builder.path(path)
                    if (secure == true) builder.secure()
                    if (httpOnly == true) builder.httpOnly()
                    if (expires != null && expires > 0) builder.expiresAt((expires * 1000).toLong())
                }.build()
        }.getOrNull()
    }

    private data class FlareResponse(
        val status: String? = null,
        val message: String? = null,
        val solution: FlareSolution? = null,
    )

    private data class FlareSolution(
        val status: Int = 0,
        val userAgent: String = "",
        val cookies: List<FlareCookie> = emptyList(),
    )

    private data class FlareCookie(
        val name: String = "",
        val value: String = "",
        val domain: String = "",
        val path: String? = null,
        val expires: Double? = null,
        val httpOnly: Boolean? = null,
        val secure: Boolean? = null,
    )

    companion object {
        private const val SOLVER_TIMEOUT_MS = 60_000
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
    }
}
