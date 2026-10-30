package mextensionserver.webview

import android.graphics.Bitmap
import android.graphics.Picture
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.print.PrintDocumentAdapter
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.SimpleWebResourceError
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebView.PictureListener
import android.webkit.WebView.VisualStateCallback
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewProvider.ScrollDelegate
import android.webkit.WebViewProvider.ViewDelegate
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * An `android.webkit.WebView` backed by a real WebView in the Mangayomi app.
 *
 * Commands (`loadUrl`, `evaluateJavascript`, ...) are sent to the host, and the
 * host streams back the page's lifecycle as it happens. Callbacks reach the
 * extension's [WebViewClient] and [WebChromeClient] on the WebView's looper in
 * the order the engine produced them, and the synchronous ones —
 * `shouldInterceptRequest`, `shouldOverrideUrlLoading` and JavaScript
 * interface calls — block the remote page until the extension answers, which
 * is what Android does.
 *
 * The remote WebView is created on the first navigation (or script) and lives
 * until [destroy]; one this server forgets to destroy is released after a long
 * idle period and recreated by the next command.
 */
class RemoteWebViewProvider(
    private val view: WebView,
) : WebViewProvider {
    private val logger = KotlinLogging.logger {}
    private val settings = KcefWebSettings()
    private val handler = Handler(view.webViewLooper)
    private val sessionId = UUID.randomUUID().toString()
    private val javascriptInterfaces: MutableMap<String, Any> = Collections.synchronizedMap(LinkedHashMap())
    private val viewDelegate = KcefWebViewProvider.KcefViewDelegate()
    private val scrollDelegate = KcefWebViewProvider.KcefScrollDelegate()
    private val sessionLock = Any()

    @Volatile
    private var viewClient = WebViewClient()

    @Volatile
    private var chromeClient = WebChromeClient()

    /** The connection the remote session lives on, null while there is none. */
    @Volatile
    private var connection: RemoteWebViewConnection? = null

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var originalUrl: String? = null

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var currentProgress: Int = 100

    @Volatile
    private var backAvailable = false

    @Volatile
    private var forwardAvailable = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var paused = false

    @Volatile
    private var lastActivity = System.nanoTime()

    private val session =
        object : RemoteWebViewConnection.Session {
            override fun onEvent(
                method: String,
                params: JsonObject,
            ) {
                touch()
                handleEvent(method, params)
            }

            override fun onRequest(
                method: String,
                params: JsonObject,
            ): JsonElement {
                touch()
                return handleRequest(method, params)
            }

            override fun onDisconnected(error: Throwable?) {
                handleDisconnected(error)
            }
        }

    override fun init(
        javaScriptInterfaces: Map<String, Any>?,
        privateBrowsing: Boolean,
    ) {
        javaScriptInterfaces?.let(javascriptInterfaces::putAll)
        // Start connecting now so the host's hello (and its real user-agent)
        // is usually in before the extension reads settings or loads a page.
        RemoteWebViewBridge.requestExecutor.execute {
            try {
                RemoteWebViewBridge.connection()
            } catch (error: Exception) {
                logger.warn(error) { "Could not connect to the WebView host" }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Navigation and scripts
    // ---------------------------------------------------------------------

    override fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String>?,
    ) {
        navigate("loadUrl", url) {
            put("url", url)
            putJsonObject("headers") {
                additionalHttpHeaders?.forEach { (name, value) -> put(name, value) }
            }
        }
    }

    override fun loadUrl(url: String) {
        loadUrl(url, emptyMap())
    }

    override fun postUrl(
        url: String,
        postData: ByteArray?,
    ) {
        navigate("postUrl", url) {
            put("url", url)
            put("body", Base64.getEncoder().encodeToString(postData ?: ByteArray(0)))
        }
    }

    override fun loadData(
        data: String,
        mimeType: String?,
        encoding: String?,
    ) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        navigate("loadData", baseUrl ?: "about:blank") {
            put("data", data)
            put("baseUrl", baseUrl)
            put("mimeType", mimeType ?: "text/html")
            put("encoding", encoding ?: "utf-8")
            put("historyUrl", historyUrl)
        }
    }

    override fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>?,
    ) {
        if (destroyed) return
        touch()
        val connection = bindConnection()
        if (connection == null) {
            resultCallback?.let { callback -> handler.post { callback.onReceiveValue(null) } }
            return
        }
        connection
            .request(
                "evaluateJavascript",
                buildJsonObject {
                    put("session", sessionId)
                    put("script", script.removePrefix("javascript:"))
                    put("config", config())
                },
            ).whenComplete { result, error ->
                if (error != null) logger.debug { "evaluateJavascript failed: ${error.message}" }
                val value = if (error == null) result?.asStringOrNull() ?: "null" else null
                resultCallback?.let { callback -> handler.post { callback.onReceiveValue(value) } }
            }
    }

    override fun stopLoading() = sessionCommand("stopLoading")

    override fun reload() = sessionCommand("reload")

    override fun canGoBack(): Boolean = backAvailable

    override fun goBack() = sessionCommand("goBack")

    override fun canGoForward(): Boolean = forwardAvailable

    override fun goForward() = sessionCommand("goForward")

    override fun canGoBackOrForward(steps: Int): Boolean =
        when {
            steps == 0 -> true
            steps < 0 -> backAvailable && steps == -1
            else -> forwardAvailable && steps == 1
        }

    override fun goBackOrForward(steps: Int) {
        when {
            steps < 0 -> goBack()
            steps > 0 -> goForward()
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        releaseRemoteSession()
    }

    /**
     * Releases the remote WebView of a session nobody used since [deadline],
     * keeping this provider usable: the next command recreates it.
     */
    internal fun releaseIfIdleSince(deadline: Long) {
        if (lastActivity - deadline < 0 && currentProgress >= 100) {
            logger.info { "Releasing WebView session idle for a long time" }
            releaseRemoteSession()
        }
    }

    private fun releaseRemoteSession() {
        val bound =
            synchronized(sessionLock) {
                connection.also { connection = null }
            } ?: return
        bound.unregister(sessionId)
        RemoteWebViewBridge.untrack(this)
        bound.command("destroy", buildJsonObject { put("session", sessionId) })
    }

    /** Binds this WebView to the current host connection, creating its session there. */
    private fun bindConnection(): RemoteWebViewConnection? {
        val current = RemoteWebViewBridge.connection() ?: return null
        synchronized(sessionLock) {
            val bound = connection
            if (bound === current) return current
            bound?.unregister(sessionId)
            current.register(sessionId, session)
            connection = current
        }
        RemoteWebViewBridge.track(this)
        return current
    }

    private fun navigate(
        method: String,
        url: String,
        build: JsonObjectBuilder.() -> Unit,
    ) {
        if (destroyed) return
        touch()
        currentUrl = url
        originalUrl = url
        currentProgress = 0
        val connection = bindConnection()
        if (connection == null) {
            handler.post { failLoad(url, "No WebView host is available") }
            return
        }
        val params =
            buildJsonObject {
                put("session", sessionId)
                put("config", config())
                put("cookies", RemoteWebViewCookies.forUrl(url))
                build()
            }
        connection.request(method, params).whenComplete { _, error ->
            // A dropped connection is reported once, by handleDisconnected.
            if (error != null && !destroyed && !connection.isClosed) {
                logger.warn { "WebView host could not $method $url: ${error.message}" }
                handler.post { failLoad(url, error.message ?: "WebView host error") }
            }
        }
    }

    private fun sessionCommand(method: String) {
        if (destroyed) return
        touch()
        connection?.command(method, buildJsonObject { put("session", sessionId) })
    }

    /** Reports a load the host could not start, in Android's callback order. */
    private fun failLoad(
        url: String,
        description: String,
    ) {
        if (destroyed) return
        currentProgress = 100
        viewClient.onPageStarted(view, url, null)
        viewClient.onReceivedError(
            view,
            RemoteWebResourceRequest(url, isForMainFrame = true),
            SimpleWebResourceError(WebViewClient.ERROR_CONNECT, description),
        )
        chromeClient.onProgressChanged(view, 100)
        viewClient.onPageFinished(view, url)
    }

    /** What the host needs to configure the WebView like this one. */
    private fun config(): JsonObject {
        val client = viewClient
        return buildJsonObject {
            put("userAgent", if (settings.hasCustomUserAgent()) settings.userAgentString else null)
            put("javaScriptEnabled", settings.javaScriptEnabled)
            put("domStorageEnabled", settings.domStorageEnabled)
            put("loadsImagesAutomatically", settings.loadsImagesAutomatically)
            put("blockNetworkImage", settings.blockNetworkImage)
            put("mediaPlaybackRequiresUserGesture", settings.mediaPlaybackRequiresUserGesture)
            put(
                "interceptRequests",
                client.overrides("shouldInterceptRequest", WebView::class.java, WebResourceRequest::class.java) ||
                    client.overrides("shouldInterceptRequest", WebView::class.java, String::class.java),
            )
            put(
                "overrideUrlLoading",
                client.overrides("shouldOverrideUrlLoading", WebView::class.java, WebResourceRequest::class.java) ||
                    client.overrides("shouldOverrideUrlLoading", WebView::class.java, String::class.java),
            )
            putJsonArray("javascriptInterfaces") {
                synchronized(javascriptInterfaces) { javascriptInterfaces.toMap() }.forEach { (name, target) ->
                    addJsonObject {
                        put("name", name)
                        putJsonArray("methods") { JavascriptInterfaces.methodNames(target).forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Host → extension
    // ---------------------------------------------------------------------

    private fun handleEvent(
        method: String,
        params: JsonObject,
    ) {
        val url = params.string("url")
        when (method) {
            "pageStarted" ->
                post {
                    url?.let { currentUrl = it }
                    viewClient.onPageStarted(view, url ?: currentUrl, null)
                }

            "pageFinished" -> {
                // Cookies first: an extension waiting on onPageFinished reads
                // them right away, typically from its OkHttp client.
                RemoteWebViewCookies.apply(url, params["cookies"] as? JsonArray)
                post {
                    url?.let { currentUrl = it }
                    currentProgress = 100
                    viewClient.onPageFinished(view, url ?: currentUrl)
                }
            }

            "cookies" -> RemoteWebViewCookies.apply(url, params["cookies"] as? JsonArray)

            "progressChanged" ->
                post {
                    val progress = (params.int("progress") ?: 0).coerceIn(0, 100)
                    currentProgress = progress
                    chromeClient.onProgressChanged(view, progress)
                }

            "receivedTitle" ->
                post {
                    currentTitle = params.string("title") ?: ""
                    chromeClient.onReceivedTitle(view, currentTitle)
                }

            "receivedError" ->
                post {
                    viewClient.onReceivedError(
                        view,
                        params.toRequest(),
                        SimpleWebResourceError(
                            params.int("errorCode") ?: WebViewClient.ERROR_UNKNOWN,
                            params.string("description") ?: "",
                        ),
                    )
                }

            "receivedHttpError" ->
                post {
                    viewClient.onReceivedHttpError(view, params.toRequest(), params.toErrorResponse())
                }

            "loadResource" -> post { url?.let { viewClient.onLoadResource(view, it) } }

            "updateVisitedHistory" ->
                post {
                    params.boolean("canGoBack")?.let { backAvailable = it }
                    params.boolean("canGoForward")?.let { forwardAvailable = it }
                    url?.let {
                        currentUrl = it
                        viewClient.doUpdateVisitedHistory(view, it, params.boolean("isReload") ?: false)
                    }
                }

            "consoleMessage" ->
                post {
                    chromeClient.onConsoleMessage(
                        ConsoleMessage(
                            params.string("message") ?: "",
                            params.string("sourceId") ?: "",
                            params.int("lineNumber") ?: 0,
                            consoleLevel(params.string("level")),
                        ),
                    )
                }

            "renderProcessGone" ->
                post {
                    val didCrash = params.boolean("didCrash") ?: true
                    viewClient.onRenderProcessGone(
                        view,
                        object : RenderProcessGoneDetail() {
                            override fun didCrash(): Boolean = didCrash

                            override fun rendererPriorityAtExit(): Int = -1
                        },
                    )
                }

            "sessionClosed" -> {
                // The host dropped the WebView itself; the next command recreates it.
                synchronized(sessionLock) {
                    connection?.unregister(sessionId)
                    connection = null
                }
                RemoteWebViewBridge.untrack(this)
            }

            else -> logger.debug { "Ignoring unknown WebView host event $method" }
        }
    }

    private fun handleRequest(
        method: String,
        params: JsonObject,
    ): JsonElement =
        when (method) {
            // Android calls this on a background thread and so do we.
            "shouldInterceptRequest" -> {
                if (destroyed) {
                    JsonNull
                } else {
                    viewClient.shouldInterceptRequest(view, params.toRequest())?.toJson() ?: JsonNull
                }
            }

            // Android calls this on the WebView's thread; the page waits for it.
            "shouldOverrideUrlLoading" -> {
                val decision = CompletableFuture<Boolean>()
                handler.post {
                    decision.complete(
                        !destroyed &&
                            try {
                                viewClient.shouldOverrideUrlLoading(view, params.toRequest())
                            } catch (error: Throwable) {
                                logger.error(error) { "shouldOverrideUrlLoading threw" }
                                false
                            },
                    )
                }
                JsonPrimitive(
                    try {
                        decision.get(DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        logger.warn { "shouldOverrideUrlLoading did not answer in time; allowing navigation" }
                        false
                    },
                )
            }

            // Android runs JavaScript interface methods on its JavaBridge thread.
            "javascriptInterface" -> {
                val name = params.string("name") ?: throw IllegalArgumentException("Missing interface name")
                val target =
                    javascriptInterfaces[name] ?: throw IllegalArgumentException("No JavaScript interface named $name")
                val args = (params["args"] as? JsonArray)?.toList().orEmpty()
                buildJsonObject {
                    put("value", JavascriptInterfaces.invoke(target, params.string("method") ?: "", args))
                }
            }

            else -> throw IllegalArgumentException("Unknown WebView request $method")
        }

    private fun handleDisconnected(error: Throwable?) {
        synchronized(sessionLock) { connection = null }
        RemoteWebViewBridge.untrack(this)
        if (destroyed || currentProgress >= 100) return
        val url = currentUrl ?: return
        logger.warn { "WebView host disconnected while loading $url" }
        handler.post {
            if (destroyed) return@post
            viewClient.onReceivedError(
                view,
                RemoteWebResourceRequest(url, isForMainFrame = true),
                SimpleWebResourceError(WebViewClient.ERROR_CONNECT, error?.message ?: "WebView host disconnected"),
            )
            currentProgress = 100
            chromeClient.onProgressChanged(view, 100)
            viewClient.onPageFinished(view, url)
        }
    }

    private fun post(block: () -> Unit) {
        handler.post {
            if (destroyed) return@post
            try {
                block()
            } catch (error: Throwable) {
                // Android would crash the app; an extension failure must not
                // take the WebView looper down with it.
                logger.error(error) { "WebView client callback threw" }
            }
        }
    }

    private fun touch() {
        lastActivity = System.nanoTime()
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    override fun getUrl(): String? = currentUrl

    override fun getOriginalUrl(): String? = originalUrl

    override fun getTitle(): String = currentTitle

    override fun getProgress(): Int = currentProgress

    override fun getSettings(): WebSettings = settings

    override fun setWebViewClient(client: WebViewClient?) {
        viewClient = client ?: WebViewClient()
    }

    override fun getWebViewClient(): WebViewClient = viewClient

    override fun setWebChromeClient(client: WebChromeClient?) {
        chromeClient = client ?: WebChromeClient()
    }

    override fun getWebChromeClient(): WebChromeClient = chromeClient

    // Takes effect on the next page load, as on Android.
    override fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ) {
        javascriptInterfaces[interfaceName] = obj
    }

    override fun removeJavascriptInterface(interfaceName: String) {
        javascriptInterfaces.remove(interfaceName)
    }

    override fun onPause() {
        paused = true
    }

    override fun onResume() {
        paused = false
    }

    override fun isPaused(): Boolean = paused

    override fun insertVisualStateCallback(
        requestId: Long,
        callback: VisualStateCallback,
    ) {
        post { callback.onComplete(requestId) }
    }

    // ---------------------------------------------------------------------
    // Features a headless remote WebView has no use for
    // ---------------------------------------------------------------------

    override fun setHorizontalScrollbarOverlay(overlay: Boolean) {}

    override fun setVerticalScrollbarOverlay(overlay: Boolean) {}

    override fun overlayHorizontalScrollbar(): Boolean = false

    override fun overlayVerticalScrollbar(): Boolean = false

    override fun getVisibleTitleHeight(): Int = 0

    override fun getCertificate(): SslCertificate? = null

    override fun setCertificate(certificate: SslCertificate) {}

    override fun savePassword(
        host: String,
        username: String,
        password: String,
    ) {}

    override fun setHttpAuthUsernamePassword(
        host: String,
        realm: String,
        username: String,
        password: String,
    ) {}

    override fun getHttpAuthUsernamePassword(
        host: String,
        realm: String,
    ): Array<String> = emptyArray()

    override fun setNetworkAvailable(networkUp: Boolean) {}

    override fun saveState(outState: Bundle): WebBackForwardList? = null

    override fun savePicture(
        b: Bundle,
        dest: File,
    ): Boolean = false

    override fun restorePicture(
        b: Bundle,
        src: File,
    ): Boolean = false

    override fun restoreState(inState: Bundle): WebBackForwardList? = null

    override fun saveWebArchive(filename: String) {}

    override fun saveWebArchive(
        basename: String,
        autoname: Boolean,
        callback: ValueCallback<String>?,
    ) {
        callback?.let { post { it.onReceiveValue(null) } }
    }

    override fun isPrivateBrowsingEnabled(): Boolean = false

    override fun pageUp(top: Boolean): Boolean = false

    override fun pageDown(bottom: Boolean): Boolean = false

    override fun clearView() {}

    override fun capturePicture(): Picture = Picture()

    override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter =
        throw UnsupportedOperationException("Printing is not supported by remote WebViews")

    override fun getScale(): Float = 1.0f

    override fun setInitialScale(scaleInPercent: Int) {}

    override fun invokeZoomPicker() {}

    override fun getHitTestResult(): HitTestResult = HitTestResult()

    override fun requestFocusNodeHref(hrefMsg: Message) {}

    override fun requestImageRef(msg: Message) {}

    override fun getFavicon(): Bitmap? = null

    override fun getTouchIconUrl(): String? = null

    override fun getContentHeight(): Int = 0

    override fun getContentWidth(): Int = 0

    override fun pauseTimers() {}

    override fun resumeTimers() {}

    override fun freeMemory() {}

    override fun clearCache(includeDiskFiles: Boolean) {}

    override fun clearFormData() {}

    override fun clearHistory() {}

    override fun clearSslPreferences() {}

    override fun copyBackForwardList(): WebBackForwardList? = null

    override fun setFindListener(listener: WebView.FindListener) {}

    override fun findNext(forward: Boolean) {}

    override fun findAll(find: String): Int = 0

    override fun findAllAsync(find: String) {}

    override fun showFindDialog(
        text: String,
        showIme: Boolean,
    ): Boolean = false

    override fun clearMatches() {}

    override fun documentHasImages(response: Message) {}

    override fun getWebViewRenderProcess(): WebViewRenderProcess? = null

    override fun setWebViewRenderProcessClient(
        executor: java.util.concurrent.Executor?,
        client: WebViewRenderProcessClient?,
    ) {}

    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = null

    override fun setDownloadListener(listener: DownloadListener?) {}

    override fun setPictureListener(listener: PictureListener?) {}

    override fun createWebMessageChannel(): Array<WebMessagePort> = emptyArray()

    override fun postMessageToMainFrame(
        message: WebMessage,
        targetOrigin: Uri,
    ) {}

    override fun setMapTrackballToArrowKeys(setMap: Boolean) {}

    override fun flingScroll(
        vx: Int,
        vy: Int,
    ) {}

    override fun getZoomControls(): View? = null

    override fun canZoomIn(): Boolean = false

    override fun canZoomOut(): Boolean = false

    override fun zoomBy(zoomFactor: Float): Boolean = false

    override fun zoomIn(): Boolean = false

    override fun zoomOut(): Boolean = false

    override fun dumpViewHierarchyWithProperties(
        out: BufferedWriter,
        level: Int,
    ) {}

    override fun findHierarchyView(
        className: String,
        hashCode: Int,
    ): View? = null

    override fun setRendererPriorityPolicy(
        rendererRequestedPriority: Int,
        waivedWhenNotVisible: Boolean,
    ) {}

    override fun getRendererRequestedPriority(): Int = 0

    override fun getRendererPriorityWaivedWhenNotVisible(): Boolean = false

    override fun getViewDelegate(): ViewDelegate = viewDelegate

    override fun getScrollDelegate(): ScrollDelegate = scrollDelegate

    override fun notifyFindDialogDismissed() {}

    private companion object {
        const val DECISION_TIMEOUT_SECONDS = 10L

        fun WebViewClient.overrides(
            name: String,
            vararg parameterTypes: Class<*>,
        ): Boolean =
            try {
                javaClass.getMethod(name, *parameterTypes).declaringClass != WebViewClient::class.java
            } catch (_: NoSuchMethodException) {
                false
            }

        fun consoleLevel(level: String?): ConsoleMessage.MessageLevel =
            when (level?.uppercase()) {
                "ERROR" -> ConsoleMessage.MessageLevel.ERROR
                "WARNING", "WARN" -> ConsoleMessage.MessageLevel.WARNING
                "DEBUG" -> ConsoleMessage.MessageLevel.DEBUG
                "TIP" -> ConsoleMessage.MessageLevel.TIP
                else -> ConsoleMessage.MessageLevel.LOG
            }

        fun JsonObject.toRequest(): WebResourceRequest =
            RemoteWebResourceRequest(
                url = string("url") ?: "",
                method = string("method") ?: "GET",
                headers = stringMap("headers"),
                isForMainFrame = boolean("isForMainFrame") ?: false,
                isRedirect = boolean("isRedirect") ?: false,
                hasGesture = boolean("hasGesture") ?: false,
            )

        fun JsonObject.toErrorResponse(): WebResourceResponse {
            val statusCode = (int("statusCode") ?: 500).coerceIn(400, 599)
            return WebResourceResponse(
                string("mimeType"),
                string("encoding"),
                statusCode,
                string("reasonPhrase")?.takeIf { it.isNotBlank() } ?: "Error",
                stringMap("responseHeaders"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }

        fun WebResourceResponse.toJson(): JsonElement {
            val body = data?.use { it.readBytes() }
            return buildJsonObject {
                put("statusCode", statusCode.takeIf { it in 100..599 } ?: 200)
                put("reasonPhrase", reasonPhrase?.takeIf { it.isNotBlank() } ?: "OK")
                put("mimeType", mimeType)
                put("encoding", encoding)
                putJsonObject("headers") {
                    responseHeaders?.forEach { (name, value) -> if (name != null && value != null) put(name, value) }
                }
                put("hasData", body != null)
                put("body", Base64.getEncoder().encodeToString(body ?: ByteArray(0)))
            }
        }
    }
}

/** A request the remote WebView reported, presented as Android's type. */
internal class RemoteWebResourceRequest(
    private val url: String,
    private val method: String = "GET",
    private val headers: Map<String, String> = emptyMap(),
    private val isForMainFrame: Boolean = false,
    private val isRedirect: Boolean = false,
    private val hasGesture: Boolean = false,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)

    override fun isForMainFrame(): Boolean = isForMainFrame

    override fun isRedirect(): Boolean = isRedirect

    override fun hasGesture(): Boolean = hasGesture

    override fun getMethod(): String = method

    override fun getRequestHeaders(): Map<String, String> = headers
}
