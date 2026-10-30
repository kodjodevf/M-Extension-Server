package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.MemoryCookieJar
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.webview.RemoteWebViewBridge
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Solves a Cloudflare challenge the way the Android app does: open the page in
 * a real WebView, let the challenge run, and wait for a new `cf_clearance`.
 *
 * The WebView is a remote one hosted by the client, so its cookies land in
 * [cookieJar] through the WebView bridge before `onPageFinished` fires.
 */
class WebViewCloudflareSolver(
    private val context: Context,
    private val cookieJar: MemoryCookieJar,
) {
    private val logger = KotlinLogging.logger {}

    val isAvailable: Boolean get() = RemoteWebViewBridge.isConfigured

    /**
     * Returns the user-agent the challenge was solved with, which later requests
     * must send for the clearance to be accepted, or null when it was not solved.
     */
    fun solve(request: Request): String? {
        val looper = Looper.getMainLooper() ?: return null
        val handler = Handler(looper)
        val previousClearance = clearance(request.url)
        val done = CountDownLatch(1)
        var webView: WebView? = null
        var challengeSeen = false

        handler.post {
            try {
                val view = WebView(context)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.webViewClient =
                    object : WebViewClient() {
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true && errorResponse?.statusCode in CHALLENGE_CODES) {
                                challengeSeen = true
                            }
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            val current = clearance(request.url)
                            // Done once a new clearance arrived, or when the page
                            // loaded without a challenge and there is nothing to wait for.
                            if ((current != null && current != previousClearance) || !challengeSeen) {
                                done.countDown()
                            }
                        }
                    }
                val headers =
                    request.headers
                        .toMultimap()
                        .filterKeys { !it.equals("user-agent", true) && !it.equals("cookie", true) }
                        .mapValues { it.value.last() }
                view.loadUrl(request.url.toString(), headers)
            } catch (error: Throwable) {
                logger.warn(error) { "Could not open a WebView to solve the challenge" }
                done.countDown()
            }
        }

        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logger.warn { "Cloudflare challenge on ${request.url.host} not solved within ${TIMEOUT_SECONDS}s" }
        }
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        val solved = clearance(request.url)?.let { it != previousClearance } ?: false
        if (!solved) return null
        return RemoteWebViewBridge.defaultUserAgent()
    }

    private fun clearance(url: HttpUrl): String? = cookieJar.loadForRequest(url).firstOrNull { it.name == CLEARANCE_COOKIE }?.value

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val CLEARANCE_COOKIE = "cf_clearance"
        val CHALLENGE_CODES = setOf(403, 503)
    }
}
