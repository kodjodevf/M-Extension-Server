package mextensionserver.controller

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.online.HttpSource
import fi.iki.elonen.NanoHTTPD
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.impl.MExtensionServerLoader
import mextensionserver.impl.MihonInvoker
import mextensionserver.model.DataBody
import okhttp3.Cookie
import okhttp3.HttpUrl

class DalvikHandler {
    private val logger = KotlinLogging.logger {}
    private val objectMapper =
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        try {
            // Parse JSON body first to get extension data
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val json = body["postData"] ?: throw IllegalArgumentException("No JSON body")

            // Deserialize DataBody
            val dataBody = objectMapper.readValue(json, DataBody::class.java)

            val invocation =
                MExtensionServerLoader.invokeWithExtension(dataBody.data, dataBody.extensionId) { loadedExtension ->
                    val selectedSource = MihonInvoker.selectSource(loadedExtension.sources, dataBody)
                    MihonInvoker.preparePreferences(dataBody, selectedSource)

                    // Get domain from source
                    val domain =
                        selectedSource.let { source ->
                            try {
                                val baseUrl = source.javaClass.getMethod("getBaseUrl").invoke(source) as String
                                java.net.URI(baseUrl).host
                            } catch (e: Exception) {
                                logger.error(e) { "Error getting domain from source" }
                                null
                            }
                        } ?: "localhost"
                    val cleanDomain = domain.removePrefix("www.").removePrefix(".")

                    val network =
                        selectedSource.let { source ->
                            when (source) {
                                is HttpSource -> source.network
                                is AnimeHttpSource -> source.network
                                else -> null
                            }
                        }

                    val ua = (session.headers["user-agent"] ?: session.headers["User-Agent"])
                    if (ua != null) {
                        network?.setUA(ua)
                    }

                    // Intercept Cookie header and save to global cookie jar
                    val cookies =
                        (session.headers["cookie"] ?: session.headers["Cookie"])
                            ?.let { cookieHeader ->
                                cookieHeader
                                    .split(";")
                                    .mapNotNull { cookieStr ->
                                        val trimmed = cookieStr.trim()
                                        val parts = trimmed.split("=", limit = 2)
                                        if (parts.size == 2) {
                                            val name = parts[0].trim()
                                            val value = parts[1].trim()
                                            try {
                                                Cookie
                                                    .Builder()
                                                    .name(name)
                                                    .value(value)
                                                    .domain(cleanDomain)
                                                    .path("/")
                                                    .build()
                                            } catch (_: Exception) {
                                                null
                                            }
                                        } else {
                                            null
                                        }
                                    }.distinctBy { it.name }
                            }?.toList()

                    if (!cookies.isNullOrEmpty() && cleanDomain != "localhost") {
                        val httpUrl =
                            try {
                                HttpUrl
                                    .Builder()
                                    .scheme("https")
                                    .host(cleanDomain)
                                    .build()
                            } catch (_: Exception) {
                                null
                            }
                        if (httpUrl != null) {
                            network?.cookieJar?.addAll(httpUrl, cookies)
                        }
                    }

                    val cloudflareProxy =
                        (session.headers[CF_PROXY_HEADER] ?: session.headers["Cf-Proxy-Url"])
                    if (cloudflareProxy != null) {
                        network?.setCloudflareProxyUrl(cloudflareProxy)
                    }

                    val baseUrlHeader =
                        (session.headers[SOURCE_BASE_URL_HEADER] ?: session.headers["Source-Base-Url"])
                    val invocationBody =
                        if (baseUrlHeader.isNullOrBlank() || !dataBody.sourceBaseUrl.isNullOrBlank()) {
                            dataBody
                        } else {
                            dataBody.copy(sourceBaseUrl = baseUrlHeader)
                        }

                    MihonInvoker.invokeMethod(loadedExtension, invocationBody)
                }

            // Serialize response
            val responseJson = objectMapper.writeValueAsString(invocation.result)

            NanoHTTPD
                .newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    responseJson,
                ).apply {
                    addHeader("X-Mangayomi-Extension-Id", invocation.extensionId)
                }
        } catch (e: LinkageError) {
            errorResponse(e)
        } catch (e: Exception) {
            errorResponse(e)
        }

    private fun errorResponse(error: Throwable): NanoHTTPD.Response {
        logger.error(error) { "Error handling request" }
        val status =
            when (error) {
                is MExtensionServerLoader.ExtensionNotLoadedException -> NanoHTTPD.Response.Status.CONFLICT
                is eu.kanade.tachiyomi.network.HttpException -> {
                    when (error.code) {
                        400 -> NanoHTTPD.Response.Status.BAD_REQUEST
                        401 -> NanoHTTPD.Response.Status.UNAUTHORIZED
                        403 -> NanoHTTPD.Response.Status.FORBIDDEN
                        404 -> NanoHTTPD.Response.Status.NOT_FOUND
                        429 -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                        500 -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                        else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                    }
                }
                else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
            }
        val errorResponse =
            mapOf(
                "error" to (error.message ?: error.javaClass.simpleName),
                "code" to
                    when (error) {
                        is MExtensionServerLoader.ExtensionNotLoadedException -> 409
                        is eu.kanade.tachiyomi.network.HttpException -> error.code
                        else -> 500
                    },
            )
        val errorJson = objectMapper.writeValueAsString(errorResponse)
        return NanoHTTPD.newFixedLengthResponse(
            status,
            "application/json",
            errorJson,
        )
    }

    companion object {
        /** Optional per-request FlareSolverr / Byparr URL, sent by the client. */
        const val CF_PROXY_HEADER = "cf-proxy-url"

        /** Identifies which source of a multi-source extension the call is for. */
        const val SOURCE_BASE_URL_HEADER = "source-base-url"
    }
}
