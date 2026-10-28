package xyz.nulldev.androidcompat.webkit

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.print.PrintDocumentAdapter
import android.util.Log
import android.util.SparseArray
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.textclassifier.TextClassifier
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
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
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.callback.CefCallback
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefPermissionHandler
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefPostData
import org.cef.network.CefPostDataElement
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import org.kodein.di.DI
import org.kodein.di.conf.global
import org.kodein.di.instance
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.collections.Map
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.javaMethod

class KcefWebViewProvider(
    private val view: WebView,
) : WebViewProvider {
    private val settings = KcefWebSettings()
    private var viewClient = WebViewClient()
    private var chromeClient = WebChromeClient()
    private val mappings: MutableList<FunctionMapping> = mutableListOf()
    private val urlHttpMapping: MutableMap<String, String> = mutableMapOf()
    private var initialRequestData: InitialRequestData? = null

    private var kcefClient: KCEFClient? = null
    private var browser: KCEFBrowser? = null
    private var initializationFailure: Throwable? = null
    private var currentUrl: String? = null
    private var currentTitle: String = ""
    private var isPaused: Boolean = false
    private val viewDelegate = KcefViewDelegate()
    private val scrollDelegate = KcefScrollDelegate()

    private val handler = Handler(view.webViewLooper)

    companion object {
        const val TAG = "KcefWebViewProvider"
        const val QUERY_FN = "__\$_suwayomiQuery"
        const val QUERY_CANCEL_FN = "__\$_suwayomiQueryCancel"

        @Volatile
        var bridgePort: Int = 0

        private val initHandler: InitBrowserHandler by DI.global.instance<InitBrowserHandler>()
    }

    public interface InitBrowserHandler {
        public fun init(provider: KcefWebViewProvider): Unit
    }

    private data class InitialRequestData(
        private val additionalHttpHeaders: Map<String, String>? = null,
        private val myPostData: ByteArray? = null,
    ) {
        fun apply(request: CefRequest?) {
            request?.apply {
                Log.v(TAG, "Initial request: applying headers and post data")
                if (!additionalHttpHeaders.isNullOrEmpty()) {
                    additionalHttpHeaders.forEach {
                        setHeaderByName(it.key, it.value, true)
                    }
                }

                if (myPostData != null) {
                    this.postData =
                        CefPostData.create().apply {
                            addElement(
                                CefPostDataElement.create().apply {
                                    setToBytes(myPostData.size, myPostData)
                                },
                            )
                        }
                }
            }
        }
    }

    private class CefWebResourceRequest(
        val request: CefRequest?,
        val frame: CefFrame?,
        val redirect: Boolean,
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(request?.url)

        override fun isForMainFrame(): Boolean = frame?.isMain ?: false

        override fun isRedirect(): Boolean = redirect

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = request?.method ?: "GET"

        override fun getRequestHeaders(): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            request?.getHeaderMap(headers)
            return headers
        }
    }

    private class CefPermissionRequest(
        private val url: String,
        private val permissionMask: Int,
        private val callback: CefMediaAccessCallback,
    ) : PermissionRequest() {
        override fun getOrigin(): Uri = Uri.parse(url)

        override fun getResources(): Array<String> {
            val retVal = mutableListOf<String>()
            if ((permissionMask and (1 shl 0)) > 0) retVal.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if ((permissionMask and (1 shl 1)) > 0) retVal.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            return retVal.toTypedArray()
        }

        override fun grant(resources: Array<String>) {
            // TODO: respect given resource grant
            callback.Continue(permissionMask)
        }

        override fun deny() {
            callback.Cancel()
        }
    }

    private inner class DisplayHandler : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            browser: CefBrowser,
            level: CefSettings.LogSeverity,
            message: String,
            source: String,
            line: Int,
        ): Boolean {
            Log.v(TAG, "$source:$line[$level]: $message")
            return true
        }

        override fun onAddressChange(
            browser: CefBrowser,
            frame: CefFrame,
            url: String,
        ) {
            Log.d(TAG, "Navigate to $url")
        }

        override fun onTitleChange(
            browser: CefBrowser,
            title: String,
        ) {
            currentTitle = title
            handler.post { chromeClient.onReceivedTitle(view, title) }
        }

        override fun onStatusMessage(
            browser: CefBrowser,
            value: String,
        ) {
            Log.v(TAG, "Status update: $value")
        }
    }

    private inner class LoadHandler : CefLoadHandlerAdapter() {
        override fun onLoadEnd(
            browser: CefBrowser,
            frame: CefFrame,
            httpStatusCode: Int,
        ) {
            val url = frame.url ?: ""
            Log.v(TAG, "Load end $url")
            handler.post {
                if (httpStatusCode == 404) {
                    viewClient.onReceivedError(
                        view,
                        WebViewClient.ERROR_FILE_NOT_FOUND,
                        "Not Found",
                        url,
                    )
                }
                if (httpStatusCode == 429) {
                    viewClient.onReceivedError(
                        view,
                        WebViewClient.ERROR_TOO_MANY_REQUESTS,
                        "Too Many Requests",
                        url,
                    )
                }
                if (httpStatusCode >= 400) {
                    // TODO: create request and response
                    // viewClient.onReceivedHttpError(_view, ...);
                }
                viewClient.onPageFinished(view, url)
                chromeClient.onProgressChanged(view, 100)
            }
        }

        override fun onLoadError(
            browser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String,
        ) {
            Log.w(TAG, "Load error ($failedUrl) [$errorCode]: $errorText")
            // TODO: translate correctly
            handler.post {
                viewClient.onReceivedError(view, WebViewClient.ERROR_UNKNOWN, errorText, frame.url)
            }
        }

        override fun onLoadStart(
            browser: CefBrowser,
            frame: CefFrame,
            transitionType: CefRequest.TransitionType,
        ) {
            Log.v(TAG, "Load start, pushing mappings")
            mappings.forEach {
                val js =
                    """
                        window.${it.interfaceName} = window.${it.interfaceName} || {}
                        window.${it.interfaceName}.${it.functionName} = async function() {
                            const args = await Promise.all(Array.from(arguments));
                            return new Promise((resolve, reject) => {
                                window.${QUERY_FN}({
                                    request: JSON.stringify({
                                        functionName: ${Json.encodeToString(it.functionName)},
                                        interfaceName: ${Json.encodeToString(it.interfaceName)},
                                        args,
                                    }),
                                    persistent: false,
                                    onSuccess: resolve,
                                    onFailure: (_, err) => reject(err),
                                })
                            });
                        }
                        """
                browser.executeJavaScript(js, "SUWAYOMI ${it.toNice()}", 0)
            }

            handler.post { viewClient.onPageStarted(view, frame.url, null) }
        }
    }

    private inner class MessageRouterHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            val invoke =
                try {
                    Json.decodeFromString<FunctionCall>(request)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid request received $e")
                    return false
                }
            // TODO: Use a map
            mappings
                .find {
                    it.functionName == invoke.functionName &&
                        it.interfaceName == invoke.interfaceName
                }?.let {
                    handler.post {
                        try {
                            Log.v(
                                TAG,
                                "Received request to invoke ${it.toNice()} with ${invoke.args.size} args",
                            )
                            // NOTE: first argument is
                            // implicitly this
                            val retval = it.fn.call(it.obj, *invoke.args)
                            callback.success(retval.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "JS-invoke on ${it.toNice()} failed:", e)
                            callback.failure(0, e.message)
                        }
                    }
                    return true
                }
            return false
        }
    }

    private abstract class ArrayResponseResourceHandler : CefResourceHandlerAdapter() {
        protected var resolvedData: ByteArray? = null
        protected var readOffset = 0

        override fun getResponseHeaders(
            response: CefResponse,
            responseLength: IntRef,
            redirectUrl: StringRef,
        ) {
            responseLength.set(resolvedData?.size ?: 0)
            response.status = 200
            response.statusText = "OK"
            response.mimeType = "text/html"
        }

        override fun readResponse(
            dataOut: ByteArray,
            bytesToRead: Int,
            bytesRead: IntRef,
            callback: CefCallback,
        ): Boolean {
            val data = resolvedData ?: return false
            val bytesToTransfer = Math.min(bytesToRead, data.size - readOffset)
            Log.v(
                TAG,
                "readResponse: $readOffset/${data.size}, reading $bytesToRead->$bytesToTransfer",
            )
            data.copyInto(dataOut, startIndex = readOffset, endIndex = readOffset + bytesToTransfer)
            bytesRead.set(bytesToTransfer)
            readOffset += bytesToTransfer
            return bytesToTransfer != 0
        }
    }

    private inner class WebResponseResourceHandler(
        val webResponse: WebResourceResponse,
    ) : ArrayResponseResourceHandler() {
        override fun processRequest(
            request: CefRequest,
            callback: CefCallback,
        ): Boolean {
            Log.v(TAG, "Handling request from client's response for ${request.url}")
            try {
                resolvedData = webResponse.data.readAllBytes()
                Log.v(TAG, "Resolved client response for ${resolvedData?.size} bytes")
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read client data", e)
            }
            callback.Continue()
            return true
        }

        override fun getResponseHeaders(
            response: CefResponse,
            responseLength: IntRef,
            redirectUrl: StringRef,
        ) {
            super.getResponseHeaders(response, responseLength, redirectUrl)
            webResponse.responseHeaders?.forEach { response.setHeaderByName(it.key, it.value, true) }
            response.status = webResponse.statusCode
            response.mimeType = webResponse.mimeType
        }
    }

    private inner class HtmlResponseResourceHandler(
        val html: String,
    ) : ArrayResponseResourceHandler() {
        override fun processRequest(
            request: CefRequest,
            callback: CefCallback,
        ): Boolean {
            Log.v(TAG, "Handling request from HTML cache for ${request.url}")
            resolvedData = html.toByteArray()
            callback.Continue()
            return true
        }
    }

    private inner class ResourceRequestHandler : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): Boolean {
            initialRequestData?.apply(request)
            initialRequestData = null
            request.setHeaderByName("user-agent", settings.userAgentString, true)

            // TODO: we should be calling this on the handler, since CEF calls us on its IO thread
            // thus if a client tried to use WebView#loadUrl as the docs suggest, this fails
            val cancel =
                viewClient.shouldOverrideUrlLoading(
                    view,
                    CefWebResourceRequest(request, frame, false),
                )
            Log.v(TAG, "Resource ${request?.url}, result is cancel? $cancel")

            handler.post { viewClient.onLoadResource(view, frame?.url) }

            return cancel || settings.blockNetworkLoads
        }

        override fun getResourceHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
        ): CefResourceHandler? {
            val isInitialLoad = frame.url == "" && request.method == "GET"
            Log.v(TAG, "Request ${request.method} ${request.url} is initial? $isInitialLoad")
            // NOTE: we should be calling this on the handler, since CEF calls us on its IO thread
            // but docs say "This method is called on a thread other than the UI thread" so should be fine
            val response =
                if (isInitialLoad) {
                    null
                } else {
                    viewClient.shouldInterceptRequest(
                        view,
                        CefWebResourceRequest(request, frame, false),
                    )
                }
            if (response == null) {
                // prefer user's response override
                urlHttpMapping.get(request.url.trimEnd('/'))?.let {
                    return HtmlResponseResourceHandler(it)
                }
            }
            response ?: return null
            return WebResponseResourceHandler(response)
        }
    }

    private inner class RequestHandler : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler? = ResourceRequestHandler()

        override fun onRenderProcessTerminated(
            browser: CefBrowser,
            status: CefRequestHandler.TerminationStatus,
        ) {
            handler.post {
                viewClient.onRenderProcessGone(
                    view,
                    object : RenderProcessGoneDetail() {
                        override fun didCrash(): Boolean = status == CefRequestHandler.TerminationStatus.TS_PROCESS_CRASHED

                        override fun rendererPriorityAtExit(): Int = -1
                    },
                )
            }
        }
    }

    private inner class PermissionHandler : CefPermissionHandler {
        override fun onRequestMediaAccessPermission(
            browser: CefBrowser,
            frame: CefFrame,
            requesting_url: String,
            requested_permissions: Int,
            callback: CefMediaAccessCallback,
        ): Boolean {
            handler.post {
                Log.v(TAG, "Checking permission for $requesting_url: $requested_permissions")
                chromeClient.onPermissionRequest(CefPermissionRequest(requesting_url, requested_permissions, callback))
            }
            return true
        }
    }

    override fun init(
        javaScriptInterfaces: Map<String, Any>?,
        privateBrowsing: Boolean,
    ) {
        Log.v(TAG, "KcefWebViewProvider: initialize")
        destroy()
        initializationFailure = null
        try {
            kcefClient =
                KCEF.newClientBlocking().apply {
                    addDisplayHandler(DisplayHandler())
                    addLoadHandler(LoadHandler())
                    addRequestHandler(RequestHandler())
                    addPermissionHandler(PermissionHandler())

                    val config = CefMessageRouter.CefMessageRouterConfig()
                    config.jsQueryFunction = QUERY_FN
                    config.jsCancelFunction = QUERY_CANCEL_FN
                    addMessageRouter(CefMessageRouter.create(config, MessageRouterHandler()))
                }
        } catch (error: Throwable) {
            initializationFailure = error
            Log.w(TAG, "Desktop WebView (CEF) is unavailable; will use bridge or stub if needed", error)
            return
        }
        initHandler.init(this)
    }

    private fun requireKcefClient(): KCEFClient =
        kcefClient ?: throw IllegalStateException("Desktop WebView is unavailable", initializationFailure)

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

    override fun destroy() {
        browser?.close(true)
        browser?.dispose()
        browser = null
        kcefClient?.dispose()
        kcefClient = null
    }

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

    override fun loadUrl(
        loadUrl: String,
        additionalHttpHeaders: Map<String, String>,
    ) {
        currentUrl = loadUrl
        browser?.close(true)
        browser?.dispose()
        browser = null
        chromeClient.onProgressChanged(view, 0)
        initialRequestData = InitialRequestData(additionalHttpHeaders = additionalHttpHeaders)

        val client = kcefClient
        if (client != null) {
            try {
                browser =
                    client
                        .createBrowser(
                            loadUrl,
                            CefRendering.OFFSCREEN,
                        ).apply {
                            createImmediately()
                        }
                Log.d(TAG, "Page loaded at URL $loadUrl")
                return
            } catch (e: Exception) {
                Log.w(TAG, "CEF createBrowser failed: $e, falling back to bridge/stub")
            }
        }

        // CEF is unavailable or failed to initialize
        if (bridgePort > 0) {
            val combinedHeaders = HashMap(additionalHttpHeaders)
            if (settings.userAgentString.isNotEmpty()) {
                combinedHeaders["User-Agent"] = settings.userAgentString
            }
            executeBridgeWebview(bridgePort, loadUrl, combinedHeaders)
        } else {
            Log.w(TAG, "Desktop WebView is unavailable and no bridge configured; failing gracefully for $loadUrl")
            handler.post {
                viewClient.onReceivedError(
                    view,
                    WebViewClient.ERROR_UNKNOWN,
                    "Desktop WebView is unavailable",
                    loadUrl,
                )
                viewClient.onPageFinished(view, loadUrl)
            }
        }
    }

    override fun loadUrl(url: String) {
        loadUrl(url, mapOf())
    }

    override fun postUrl(
        url: String,
        postData: ByteArray,
    ) {
        currentUrl = url
        browser?.close(true)
        browser?.dispose()
        browser = null
        chromeClient.onProgressChanged(view, 0)
        initialRequestData = InitialRequestData(myPostData = postData)

        val client = kcefClient
        if (client != null) {
            try {
                browser =
                    client
                        .createBrowser(
                            url,
                            CefRendering.OFFSCREEN,
                        ).apply {
                            createImmediately()
                        }
                Log.d(TAG, "Page posted at URL $url")
                return
            } catch (e: Exception) {
                Log.w(TAG, "CEF createBrowser failed: $e, falling back to bridge/stub")
            }
        }

        if (bridgePort > 0) {
            val headers =
                if (settings.userAgentString.isNotEmpty()) {
                    mapOf("User-Agent" to settings.userAgentString)
                } else {
                    emptyMap()
                }
            executeBridgeWebview(bridgePort, url, headers, postData = postData)
        } else {
            Log.w(TAG, "Desktop WebView is unavailable and no bridge configured; failing gracefully for $url")
            handler.post {
                viewClient.onReceivedError(
                    view,
                    WebViewClient.ERROR_UNKNOWN,
                    "Desktop WebView is unavailable",
                    url,
                )
                viewClient.onPageFinished(view, url)
            }
        }
    }

    override fun loadData(
        data: String,
        mimeType: String,
        encoding: String,
    ) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String?,
    ) {
        currentUrl = baseUrl ?: historyUrl
        baseUrl?.let { urlHttpMapping[it.trimEnd('/')] = data }
        browser?.close(true)
        browser?.dispose()
        browser = null
        chromeClient.onProgressChanged(view, 0)

        val client = kcefClient
        if (client != null) {
            try {
                browser =
                    (
                        baseUrl?.let { url ->
                            client.createBrowser(
                                url,
                                CefRendering.OFFSCREEN,
                            )
                        }
                            ?: run {
                                client.createBrowserWithHtml(
                                    data,
                                    KCEFBrowser.BLANK_URI,
                                    CefRendering.OFFSCREEN,
                                )
                            }
                    ).apply {
                        createImmediately()
                    }
                Log.d(TAG, "Page loaded from data at base URL $baseUrl")
                return
            } catch (e: Exception) {
                Log.w(TAG, "CEF createBrowser failed: $e, falling back to stub")
            }
        }

        handler.post {
            chromeClient.onProgressChanged(view, 100)
            viewClient.onPageFinished(view, baseUrl ?: "")
        }
    }

    override fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>,
    ) {
        val b = browser
        if (b != null) {
            b.evaluateJavaScript(
                script.removePrefix("javascript:"),
                {
                    Log.v(TAG, "JS returned: $it")
                    handler.post { resultCallback.onReceiveValue(it) }
                },
            )
        } else if (bridgePort > 0) {
            Thread {
                try {
                    val jsResult = executeBridgeJavascript(bridgePort, currentUrl ?: "", script)
                    handler.post { resultCallback.onReceiveValue(jsResult) }
                } catch (e: Exception) {
                    Log.w(TAG, "Bridge JS evaluation failed", e)
                    handler.post { resultCallback.onReceiveValue(null) }
                }
            }.start()
        } else {
            handler.post { resultCallback.onReceiveValue(null) }
        }
    }

    override fun saveWebArchive(filename: String) {}

    override fun saveWebArchive(
        basename: String,
        autoname: Boolean,
        callback: ValueCallback<String>,
    ) {
        callback.onReceiveValue(null)
    }

    override fun stopLoading() {
        browser?.stopLoad()
    }

    override fun reload() {
        if (browser != null) {
            browser?.reload()
        } else {
            currentUrl?.let { loadUrl(it) }
        }
    }

    override fun canGoBack(): Boolean = browser?.canGoBack() ?: false

    override fun goBack() {
        browser?.goBack()
    }

    override fun canGoForward(): Boolean = browser?.canGoForward() ?: false

    override fun goForward() {
        browser?.goForward()
    }

    override fun canGoBackOrForward(steps: Int): Boolean = false

    override fun goBackOrForward(steps: Int) {}

    override fun isPrivateBrowsingEnabled(): Boolean = false

    override fun pageUp(top: Boolean): Boolean = false

    override fun pageDown(bottom: Boolean): Boolean = false

    override fun insertVisualStateCallback(
        requestId: Long,
        callback: VisualStateCallback,
    ) {
        callback.onComplete(requestId)
    }

    override fun clearView() {}

    override fun capturePicture(): Picture = Picture()

    override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter =
        throw UnsupportedOperationException("PrintDocumentAdapter not supported")

    override fun getScale(): Float = 1.0f

    override fun setInitialScale(scaleInPercent: Int) {}

    override fun invokeZoomPicker() {}

    override fun getHitTestResult(): HitTestResult = HitTestResult()

    override fun requestFocusNodeHref(hrefMsg: Message) {}

    override fun requestImageRef(msg: Message) {}

    override fun getUrl(): String = browser?.url ?: currentUrl ?: ""

    override fun getOriginalUrl(): String = browser?.url ?: currentUrl ?: ""

    override fun getTitle(): String = currentTitle

    override fun getFavicon(): Bitmap? = null

    override fun getTouchIconUrl(): String? = null

    override fun getProgress(): Int = 100

    override fun getContentHeight(): Int = 0

    override fun getContentWidth(): Int = 0

    override fun pauseTimers() {}

    override fun resumeTimers() {}

    override fun onPause() {
        isPaused = true
    }

    override fun onResume() {
        isPaused = false
    }

    override fun isPaused(): Boolean = isPaused

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

    override fun setWebViewClient(client: WebViewClient) {
        viewClient = client
    }

    override fun getWebViewClient(): WebViewClient = viewClient

    override fun getWebViewRenderProcess(): WebViewRenderProcess? = null

    override fun setWebViewRenderProcessClient(
        executor: Executor?,
        client: WebViewRenderProcessClient?,
    ) {}

    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = null

    override fun setDownloadListener(listener: DownloadListener) {}

    override fun setWebChromeClient(client: WebChromeClient) {
        chromeClient = client
    }

    override fun getWebChromeClient(): WebChromeClient = chromeClient

    override fun setPictureListener(listener: PictureListener) {}

    @Serializable
    private data class FunctionCall(
        val interfaceName: String,
        val functionName: String,
        val args: Array<String>,
    )

    private data class FunctionMapping(
        val interfaceName: String,
        val functionName: String,
        val obj: Any,
        val fn: KFunction<*>,
    ) {
        fun toNice(): String = "$interfaceName.$functionName"
    }

    override fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ) {
        val cls = obj::class as KClass<Any>
        mappings.addAll(
            cls.declaredMemberFunctions.map {
                // This is ridiculous, but necessary, otherwise "public final" throws
                it.javaMethod?.isAccessible = true
                val map = FunctionMapping(interfaceName, it.name, obj, it)
                Log.v(TAG, "Exposing: ${map.toNice()}")
                map
            },
        )
    }

    override fun removeJavascriptInterface(interfaceName: String) {
        mappings.removeAll { it.interfaceName == interfaceName }
    }

    override fun createWebMessageChannel(): Array<WebMessagePort> = emptyArray()

    override fun postMessageToMainFrame(
        message: WebMessage,
        targetOrigin: Uri,
    ) {}

    override fun getSettings(): WebSettings = settings

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

    @SuppressWarnings("unused")
    override fun setTextClassifier(textClassifier: TextClassifier?) {}

    override fun getTextClassifier(): TextClassifier = TextClassifier.NO_OP

    // -------------------------------------------------------------------------
    // Provider internal methods
    // -------------------------------------------------------------------------

    override fun getViewDelegate(): ViewDelegate = viewDelegate

    override fun getScrollDelegate(): ScrollDelegate = scrollDelegate

    override fun notifyFindDialogDismissed() {}

    private fun executeBridgeWebview(
        port: Int,
        url: String,
        headers: Map<String, String>? = null,
        postData: ByteArray? = null,
        scripts: List<String>? = null,
    ) {
        Thread {
            try {
                val jsonPayload =
                    buildJsonObject {
                        put("url", JsonPrimitive(url))
                        headers?.let { h ->
                            put(
                                "headers",
                                buildJsonObject {
                                    h.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                                },
                            )
                        }
                        postData?.let {
                            put("postData", JsonPrimitive(String(it, Charsets.UTF_8)))
                        }
                        scripts?.let { s ->
                            put(
                                "scripts",
                                buildJsonArray {
                                    s.forEach { add(JsonPrimitive(it)) }
                                },
                            )
                        }
                        put("time", JsonPrimitive(20))
                    }

                val conn =
                    (
                        java.net
                            .URI("http://127.0.0.1:$port/bridge_webview")
                            .toURL()
                            .openConnection() as java.net.HttpURLConnection
                    ).apply {
                        requestMethod = "POST"
                        connectTimeout = 25000
                        readTimeout = 25000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                conn.outputStream.use { os ->
                    os.write(jsonPayload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = Json { ignoreUnknownKeys = true }
                    val responseObj = json.parseToJsonElement(responseStr).jsonObject

                    val finalUrl = responseObj["url"]?.jsonPrimitive?.contentOrNull ?: url
                    val html = responseObj["html"]?.jsonPrimitive?.contentOrNull
                    currentUrl = finalUrl

                    html?.let {
                        urlHttpMapping[url.trimEnd('/')] = it
                        urlHttpMapping[finalUrl.trimEnd('/')] = it
                    }

                    // Save cookies
                    val cookiesArray = responseObj["cookies"]?.jsonArray
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookiesArray?.forEach { element ->
                        val cookieObj = element.jsonObject
                        val name = cookieObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val value = cookieObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
                        val domain = cookieObj["domain"]?.jsonPrimitive?.contentOrNull
                        val path = cookieObj["path"]?.jsonPrimitive?.contentOrNull
                        if (name.isNotEmpty()) {
                            val cookieStr =
                                buildString {
                                    append("$name=$value")
                                    domain?.let { append("; Domain=$it") }
                                    path?.let { append("; Path=$it") }
                                }
                            cookieManager.setCookie(finalUrl, cookieStr)
                        }
                    }

                    handler.post {
                        chromeClient.onProgressChanged(view, 100)
                        viewClient.onPageFinished(view, finalUrl)
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    Log.w(TAG, "Bridge returned error: $err")
                    handler.post {
                        viewClient.onReceivedError(view, WebViewClient.ERROR_UNKNOWN, err, url)
                        viewClient.onPageFinished(view, url)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bridge request failed for $url", e)
                handler.post {
                    viewClient.onReceivedError(
                        view,
                        WebViewClient.ERROR_HOST_LOOKUP,
                        e.message ?: "Bridge request failed",
                        url,
                    )
                    viewClient.onPageFinished(view, url)
                }
            }
        }.start()
    }

    private fun executeBridgeJavascript(
        port: Int,
        url: String,
        script: String,
    ): String? {
        val jsonPayload =
            buildJsonObject {
                put("url", JsonPrimitive(url))
                put("scripts", buildJsonArray { add(JsonPrimitive(script)) })
                put("time", JsonPrimitive(10))
            }
        val conn =
            (
                java.net
                    .URI(
                        "http://127.0.0.1:$port/evaluateJavascriptViaWebview",
                    ).toURL()
                    .openConnection() as java.net.HttpURLConnection
            ).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        conn.outputStream.use { os ->
            os.write(jsonPayload.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        if (code == 200) {
            val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            val responseObj = json.parseToJsonElement(responseStr).jsonObject
            return responseObj["result"]?.jsonPrimitive?.contentOrNull
        }
        return null
    }

    // -------------------------------------------------------------------------
    // View / ViewGroup delegation methods
    // -------------------------------------------------------------------------

    class KcefViewDelegate : ViewDelegate {
        override fun shouldDelayChildPressedState(): Boolean = false

        override fun onProvideVirtualStructure(structure: android.view.ViewStructure) {}

        override fun onProvideAutofillVirtualStructure(
            @SuppressWarnings("unused") structure: android.view.ViewStructure,
            @SuppressWarnings("unused") flags: Int,
        ) {}

        override fun autofill(
            @SuppressWarnings("unused") values: SparseArray<AutofillValue>,
        ) {}

        override fun isVisibleToUserForAutofill(
            @SuppressWarnings("unused") virtualId: Int,
        ): Boolean = true

        override fun onProvideContentCaptureStructure(
            @SuppressWarnings("unused") structure: android.view.ViewStructure,
            @SuppressWarnings("unused") flags: Int,
        ) {}

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = null

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {}

        override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {}

        override fun performAccessibilityAction(
            action: Int,
            arguments: Bundle,
        ): Boolean = false

        override fun setOverScrollMode(mode: Int) {}

        override fun setScrollBarStyle(style: Int) {}

        override fun onDrawVerticalScrollBar(
            canvas: Canvas,
            scrollBar: Drawable,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {}

        override fun onOverScrolled(
            scrollX: Int,
            scrollY: Int,
            clampedX: Boolean,
            clampedY: Boolean,
        ) {}

        override fun onWindowVisibilityChanged(visibility: Int) {}

        override fun onDraw(canvas: Canvas) {}

        override fun setLayoutParams(layoutParams: LayoutParams) {}

        override fun performLongClick(): Boolean = false

        override fun onConfigurationChanged(newConfig: Configuration) {}

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null

        override fun onDragEvent(event: DragEvent): Boolean = false

        override fun onKeyMultiple(
            keyCode: Int,
            repeatCount: Int,
            event: KeyEvent,
        ): Boolean = false

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = false

        override fun onKeyUp(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = false

        override fun onAttachedToWindow() {}

        override fun onDetachedFromWindow() {}

        override fun onMovedToDisplay(
            displayId: Int,
            config: Configuration,
        ) {}

        override fun onVisibilityChanged(
            changedView: View,
            visibility: Int,
        ) {}

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {}

        override fun onFocusChanged(
            focused: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect,
        ) {}

        override fun setFrame(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ): Boolean = false

        override fun onSizeChanged(
            w: Int,
            h: Int,
            ow: Int,
            oh: Int,
        ) {}

        override fun onScrollChanged(
            l: Int,
            t: Int,
            oldl: Int,
            oldt: Int,
        ) {}

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = false

        override fun onTouchEvent(ev: MotionEvent): Boolean = false

        override fun onHoverEvent(event: MotionEvent): Boolean = false

        override fun onGenericMotionEvent(event: MotionEvent): Boolean = false

        override fun onTrackballEvent(ev: MotionEvent): Boolean = false

        override fun requestFocus(
            direction: Int,
            previouslyFocusedRect: Rect,
        ): Boolean = false

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {}

        override fun requestChildRectangleOnScreen(
            child: View,
            rect: Rect,
            immediate: Boolean,
        ): Boolean = false

        override fun setBackgroundColor(color: Int) {}

        override fun setLayerType(
            layerType: Int,
            paint: Paint,
        ) {}

        override fun preDispatchDraw(canvas: Canvas) {}

        override fun onStartTemporaryDetach() {}

        override fun onFinishTemporaryDetach() {}

        override fun onActivityResult(
            requestCode: Int,
            resultCode: Int,
            data: Intent,
        ) {}

        override fun getHandler(originalHandler: Handler): Handler = originalHandler

        override fun findFocus(originalFocusedView: View): View = originalFocusedView

        @SuppressWarnings("unused")
        override fun onCheckIsTextEditor(): Boolean = false

        @SuppressWarnings("unused")
        override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets? = null

        @SuppressWarnings("unused")
        override fun onResolvePointerIcon(
            event: MotionEvent,
            pointerIndex: Int,
        ): PointerIcon? = null
    }

    class KcefScrollDelegate : ScrollDelegate {
        override fun computeHorizontalScrollRange(): Int = 0

        override fun computeHorizontalScrollOffset(): Int = 0

        override fun computeVerticalScrollRange(): Int = 0

        override fun computeVerticalScrollOffset(): Int = 0

        override fun computeVerticalScrollExtent(): Int = 0

        override fun computeScroll() {}
    }
}
