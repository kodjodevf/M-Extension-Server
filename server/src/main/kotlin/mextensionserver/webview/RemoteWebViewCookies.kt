package mextensionserver.webview

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Keeps the remote WebView's cookies and the extension's cookies the same
 * store, as on Android where the WebView `CookieManager` backs OkHttp too.
 *
 * Here they are two stores — `android.webkit.CookieManager` and the network
 * cookie jar — so both are pushed to the WebView before a load and both
 * receive what the WebView ends up with.
 */
internal object RemoteWebViewCookies {
    private val logger = KotlinLogging.logger {}

    private val networkJar
        get() = runCatching { Injekt.get<NetworkHelper>().cookieJar }.getOrNull()

    /** Cookies to install in the WebView before it loads [url]. */
    fun forUrl(url: String?): JsonArray {
        val httpUrl = url?.toHttpUrlOrNull() ?: return JsonArray(emptyList())
        val cookies = linkedMapOf<String, Cookie>()

        // CookieManager only keeps name=value pairs for a URL; treat them as
        // host cookies so the WebView sends them back to the same site.
        runCatching { CookieManager.getInstance().getCookie(httpUrl.toString()) }
            .getOrNull()
            ?.split(";")
            ?.mapNotNull { pair ->
                val parts = pair.trim().split("=", limit = 2)
                val name = parts[0].trim()
                if (name.isEmpty()) return@mapNotNull null
                runCatching {
                    Cookie
                        .Builder()
                        .name(name)
                        .value(parts.getOrElse(1) { "" }.trim())
                        .hostOnlyDomain(httpUrl.host)
                        .path("/")
                        .build()
                }.getOrNull()
            }?.forEach { cookies[it.name] = it }

        // The jar's cookies carry their real attributes and win on conflicts.
        networkJar?.loadForRequest(httpUrl)?.forEach { cookies[it.name] = it }

        return buildJsonArray {
            cookies.values.forEach { cookie ->
                addJsonObject {
                    put("name", cookie.name)
                    put("value", cookie.value)
                    put("domain", if (cookie.hostOnly) cookie.domain else ".${cookie.domain}")
                    put("path", cookie.path)
                    if (cookie.persistent) put("expiresDate", cookie.expiresAt)
                    put("isSecure", cookie.secure)
                    put("isHttpOnly", cookie.httpOnly)
                }
            }
        }
    }

    /** Stores cookies the WebView reported for [url] in both stores. */
    fun apply(
        url: String?,
        cookies: JsonArray?,
    ) {
        if (cookies.isNullOrEmpty()) return
        val httpUrl = url?.toHttpUrlOrNull() ?: return
        val parsed = cookies.mapNotNull { (it as? JsonObject)?.toCookie(httpUrl) }
        if (parsed.isEmpty()) return

        networkJar?.addAll(httpUrl, parsed)
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        parsed.forEach { cookie ->
            try {
                cookieManager.setCookie(httpUrl.toString(), cookie.toString())
            } catch (error: Exception) {
                logger.debug(error) { "CookieManager rejected ${cookie.name} for ${httpUrl.host}" }
            }
        }
    }

    private fun JsonObject.toCookie(url: HttpUrl): Cookie? {
        val name = string("name")?.takeIf { it.isNotEmpty() } ?: return null
        val domain = string("domain")?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            Cookie
                .Builder()
                .name(name)
                .value(string("value") ?: "")
                .apply {
                    val host = domain?.removePrefix(".")
                    when {
                        host == null -> hostOnlyDomain(url.host)
                        domain.startsWith(".") -> domain(host)
                        else -> hostOnlyDomain(host)
                    }
                    path(string("path")?.takeIf { it.startsWith("/") } ?: "/")
                    long("expiresDate")?.takeIf { it > 0 }?.let(::expiresAt)
                    if (boolean("isSecure") == true) secure()
                    if (boolean("isHttpOnly") == true) httpOnly()
                }.build()
        } catch (error: IllegalArgumentException) {
            logger.debug(error) { "Ignoring malformed cookie $name from the WebView host" }
            null
        }
    }
}
