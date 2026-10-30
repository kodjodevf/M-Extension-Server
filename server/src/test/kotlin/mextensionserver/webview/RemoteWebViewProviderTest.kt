package mextensionserver.webview

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mextensionserver.webview.FakeWebViewHost.Companion.params
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteWebViewProviderTest {
    private lateinit var host: FakeWebViewHost
    private lateinit var looper: Looper
    private lateinit var handler: Handler

    @BeforeTest
    fun setUp() {
        if (CookieHandler.getDefault() !is java.net.CookieManager) {
            CookieHandler.setDefault(java.net.CookieManager())
        }
        host = FakeWebViewHost()
        RemoteWebViewBridge.reset()
        RemoteWebViewBridge.connector = { _, _ -> host.connection }
        RemoteWebViewBridge.configure(4242, "secret")
        RemoteWebViewBridge.install()

        val ready = CompletableFuture<Looper>()
        Thread({
            Looper.prepare()
            ready.complete(Looper.myLooper())
            Looper.loop()
        }, "WebView looper").apply {
            isDaemon = true
            start()
        }
        looper = ready.get(5, TimeUnit.SECONDS)
        handler = Handler(looper)
    }

    @AfterTest
    fun tearDown() {
        looper.quit()
        RemoteWebViewBridge.reset()
    }

    private fun <T> onLooper(block: () -> T): T {
        val result = CompletableFuture<T>()
        handler.post {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun newWebView(client: WebViewClient): WebView =
        onLooper {
            WebView(Application()).apply { webViewClient = client }
        }

    private fun awaitCall(
        client: RecordingClient,
        call: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (call !in client.calls) {
            check(System.nanoTime() < deadline) { "No \"$call\" callback; got ${client.calls}" }
            Thread.sleep(10)
        }
    }

    private fun JsonObject.session(): String = this["session"]!!.jsonPrimitive.content

    private class RecordingClient : WebViewClient() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val finished = CountDownLatch(1)
        var interceptThread: String? = null
        var looperThread: String? = null

        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: Bitmap?,
        ) {
            looperThread = Thread.currentThread().name
            calls += "started $url"
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            interceptThread = Thread.currentThread().name
            calls += "intercept ${request.url} main=${request.isForMainFrame}"
            if (request.url.toString().endsWith("/player.js")) {
                return WebResourceResponse("text/javascript", "utf-8", ByteArrayInputStream("var x = 1;".toByteArray()))
            }
            return null
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            calls += "error ${error.errorCode} ${request.url}"
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            calls += "finished $url"
            finished.countDown()
        }
    }

    @Test
    fun `a load streams Android callbacks and serves intercepted responses`() {
        val client = RecordingClient()
        val webView = newWebView(client)
        onLooper {
            webView.settings.userAgentString = "Custom/1.0"
            webView.loadUrl("https://example.org/watch", mapOf("Referer" to "https://example.org/"))
        }

        val load = host.awaitRequest("loadUrl").params
        val session = load.session()
        assertEquals("https://example.org/watch", load["url"]!!.jsonPrimitive.content)
        assertEquals("https://example.org/", load["headers"]!!.jsonObject["Referer"]!!.jsonPrimitive.content)
        val config = load["config"]!!.jsonObject
        assertEquals("Custom/1.0", config["userAgent"]!!.jsonPrimitive.content)
        assertTrue(config["interceptRequests"]!!.jsonPrimitive.boolean)

        host.event("pageStarted", params(session, "url" to "https://example.org/watch"))
        awaitCall(client, "started https://example.org/watch")
        val intercepted =
            host
                .request(
                    "shouldInterceptRequest",
                    params(session, "url" to "https://cdn.example.org/player.js", "method" to "GET", "isForMainFrame" to false),
                )["result"]!!
                .jsonObject
        val passedThrough =
            host.request(
                "shouldInterceptRequest",
                params(session, "url" to "https://cdn.example.org/style.css", "method" to "GET"),
            )["result"]
        host.event(
            "pageFinished",
            params(
                session,
                "url" to "https://example.org/watch",
                "cookies" to
                    buildJsonArray {
                        addJsonObject {
                            put("name", "cf_clearance")
                            put("value", "solved")
                            put("domain", "example.org")
                            put("path", "/")
                        }
                    },
            ),
        )

        assertTrue(client.finished.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(
                "started https://example.org/watch",
                "intercept https://cdn.example.org/player.js main=false",
                "intercept https://cdn.example.org/style.css main=false",
                "finished https://example.org/watch",
            ),
            client.calls,
        )
        assertEquals("var x = 1;", String(Base64.getDecoder().decode(intercepted["body"]!!.jsonPrimitive.content)))
        assertEquals("text/javascript", intercepted["mimeType"]!!.jsonPrimitive.content)
        assertEquals(200, intercepted["statusCode"]!!.jsonPrimitive.content.toInt())
        assertEquals(kotlinx.serialization.json.JsonNull, passedThrough)
        assertTrue(client.interceptThread != "WebView looper", "shouldInterceptRequest must not run on the WebView thread")
        assertEquals("WebView looper", client.looperThread)
        assertTrue(CookieManager.getInstance().getCookie("https://example.org/").contains("cf_clearance=solved"))
        assertEquals("https://example.org/watch", onLooper { webView.url })
    }

    @Test
    fun `evaluateJavascript returns the host result on the WebView thread`() {
        host.responder = { if (it.method == "evaluateJavascript") JsonPrimitive("\"42\"") else kotlinx.serialization.json.JsonNull }
        val webView = newWebView(WebViewClient())
        val result = CompletableFuture<Pair<String?, String>>()

        onLooper {
            webView.evaluateJavascript("document.title") { value ->
                result.complete(value to Thread.currentThread().name)
            }
        }

        val request = host.awaitRequest("evaluateJavascript").params
        assertEquals("document.title", request["script"]!!.jsonPrimitive.content)
        assertEquals("\"42\"" to "WebView looper", result.get(5, TimeUnit.SECONDS))
    }

    class Payloads {
        val received = CompletableFuture<String>()

        @JavascriptInterface
        fun passPayload(html: String) {
            received.complete(html)
        }

        @JavascriptInterface
        fun answer(): Int = 42
    }

    @Test
    fun `javascript interfaces are declared to the host and invoked synchronously`() {
        val payloads = Payloads()
        val webView = newWebView(WebViewClient())
        onLooper {
            webView.addJavascriptInterface(payloads, "android")
            webView.loadUrl("https://example.org/")
        }

        val load = host.awaitRequest("loadUrl").params
        val interfaces = load["config"]!!.jsonObject["javascriptInterfaces"] as JsonArray
        assertEquals(
            "android",
            interfaces
                .single()
                .jsonObject["name"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            listOf("answer", "passPayload"),
            interfaces
                .single()
                .jsonObject["methods"]!!
                .jsonArray
                .map { it.jsonPrimitive.content },
        )

        val response =
            host.request(
                "javascriptInterface",
                params(
                    load.session(),
                    "name" to "android",
                    "method" to "passPayload",
                    "args" to buildJsonArray { add(JsonPrimitive("<html/>")) },
                ),
            )
        val answer =
            host.request(
                "javascriptInterface",
                params(load.session(), "name" to "android", "method" to "answer", "args" to JsonArray(emptyList())),
            )

        assertEquals("<html/>", payloads.received.get(5, TimeUnit.SECONDS))
        assertNull(response["error"])
        assertEquals(
            42,
            answer["result"]!!
                .jsonObject["value"]!!
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `shouldOverrideUrlLoading is decided on the WebView thread`() {
        val thread = CompletableFuture<String>()
        val webView =
            newWebView(
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): Boolean {
                        thread.complete(Thread.currentThread().name)
                        return request.url.host == "ads.example.org"
                    }
                },
            )
        onLooper { webView.loadUrl("https://example.org/") }
        val load = host.awaitRequest("loadUrl").params
        assertTrue(load["config"]!!.jsonObject["overrideUrlLoading"]!!.jsonPrimitive.boolean)

        val blocked = host.request("shouldOverrideUrlLoading", params(load.session(), "url" to "https://ads.example.org/x"))
        val allowed = host.request("shouldOverrideUrlLoading", params(load.session(), "url" to "https://example.org/next"))

        assertEquals(JsonPrimitive(true), blocked["result"])
        assertEquals(JsonPrimitive(false), allowed["result"])
        assertEquals("WebView looper", thread.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `losing the host during a load reports an error then finishes the page`() {
        val client = RecordingClient()
        val webView = newWebView(client)
        onLooper { webView.loadUrl("https://example.org/slow") }
        host.awaitRequest("loadUrl")

        host.disconnect()

        assertTrue(client.finished.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("error ${WebViewClient.ERROR_CONNECT} https://example.org/slow", "finished https://example.org/slow"),
            client.calls,
        )
    }

    @Test
    fun `destroy releases the remote WebView and default user agent is the host's`() {
        host.hello(defaultUserAgent = "Mozilla/5.0 HostEngine")
        val webView = newWebView(WebViewClient())
        onLooper { webView.loadUrl("https://example.org/") }
        val session = host.awaitRequest("loadUrl").params.session()

        onLooper { webView.destroy() }

        val destroy = host.awaitRequest("destroy").params
        assertEquals(session, destroy.session())
        assertEquals("Mozilla/5.0 HostEngine", RemoteWebViewBridge.defaultUserAgent())
        assertEquals("Mozilla/5.0 HostEngine", onLooper { WebView(Application()).settings.userAgentString })
        assertEquals("Mozilla/5.0 HostEngine", WebSettings.getDefaultUserAgent(Application()))
    }
}
