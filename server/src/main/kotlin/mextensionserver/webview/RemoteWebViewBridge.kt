package mextensionserver.webview

import android.webkit.WebView
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes extension WebViews to real WebViews hosted by Mangayomi.
 *
 * The JVM has no browser engine of its own. When a client tells us where its
 * WebView host listens (see [configure]), every `android.webkit.WebView` an
 * extension creates becomes a [RemoteWebViewProvider] driving a WebView in the
 * app, with the Android callbacks forwarded live over one WebSocket.
 */
object RemoteWebViewBridge {
    const val PROTOCOL_VERSION = 1

    /** Header carrying the WebView host port on every `/dalvik` call. */
    const val PORT_HEADER = "x-mangayomi-webview-port"

    /** Header carrying the per-launch secret the host requires to accept us. */
    const val TOKEN_HEADER = "x-mangayomi-webview-token"

    private const val HELLO_TIMEOUT_MILLIS = 3_000L
    private const val IDLE_SESSION_NANOS = 15 * 60 * 1_000_000_000L

    private val logger = KotlinLogging.logger {}

    private data class Endpoint(
        val port: Int,
        val token: String,
    )

    @Volatile
    private var endpoint: Endpoint? = null

    private val connectionLock = Any()
    private var connection: RemoteWebViewConnection? = null
    private var connectionEndpoint: Endpoint? = null

    /** The host engine's user-agent, kept across reconnections. */
    @Volatile
    private var knownDefaultUserAgent: String? = null

    private val liveSessions: MutableSet<RemoteWebViewProvider> = ConcurrentHashMap.newKeySet()

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            // Offer no permessage-deflate: Dart answers it with a
            // client_max_window_bits parameter OkHttp then rejects (1010),
            // closing every connection right after it opens. Frames never
            // leave loopback, so compression buys nothing.
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .removeHeader("Sec-WebSocket-Extensions")
                        .build(),
                )
            }.build()
    }

    /**
     * Android invokes `shouldInterceptRequest` and JavaScript interfaces on
     * background threads; these are ours.
     */
    internal val requestExecutor =
        Executors.newCachedThreadPool(daemonThreads("RemoteWebView-JavaBridge"))

    init {
        Executors
            .newSingleThreadScheduledExecutor(daemonThreads("RemoteWebView-Reaper"))
            .scheduleWithFixedDelay(::reapIdleSessions, 1, 1, TimeUnit.MINUTES)
    }

    val isConfigured: Boolean get() = endpoint != null

    /** Registers the WebView provider and default user-agent hooks. */
    fun install() {
        WebView.setProviderFactory { view: WebView ->
            if (isConfigured) RemoteWebViewProvider(view) else KcefWebViewProvider(view)
        }
        KcefWebSettings.defaultUserAgentProvider = { defaultUserAgent() }
    }

    /**
     * Points the bridge at the client's WebView host. Called for every
     * extension call, so it is cheap when nothing changed; a new endpoint
     * replaces the old connection on next use.
     */
    fun configure(
        port: Int?,
        token: String?,
    ) {
        if (port == null || port <= 0 || token.isNullOrBlank()) return
        val next = Endpoint(port, token)
        if (endpoint != next) endpoint = next
    }

    /**
     * The live connection to the host, opening one when needed. Returns null
     * when no host was configured.
     */
    fun connection(): RemoteWebViewConnection? {
        val target = endpoint ?: return null
        synchronized(connectionLock) {
            val current = connection
            if (current != null && !current.isClosed && connectionEndpoint == target) return current
            current?.close()
            val created = connector(target.port, target.token)
            created.hello.thenAccept { hello ->
                if (hello.protocol != PROTOCOL_VERSION) {
                    logger.warn { "WebView host speaks protocol ${hello.protocol}, expected $PROTOCOL_VERSION" }
                }
                hello.defaultUserAgent?.let { knownDefaultUserAgent = it }
                logger.info { "Connected to ${hello.platform} WebView host (request interception: ${hello.interceptsRequests})" }
            }
            connection = created
            connectionEndpoint = target
            return created
        }
    }

    /** Opens a connection to the host on a port; replaced by tests. */
    internal var connector: (port: Int, token: String) -> RemoteWebViewConnection = ::open

    /** Forgets all state, for tests. */
    internal fun reset() {
        synchronized(connectionLock) {
            connection?.close()
            connection = null
            connectionEndpoint = null
        }
        endpoint = null
        knownDefaultUserAgent = null
        liveSessions.clear()
        connector = ::open
    }

    /**
     * The user-agent of the engine that renders pages. Waits briefly for the
     * host's hello the first time, so extensions reading it before their first
     * load see the real value.
     */
    fun defaultUserAgent(): String? {
        knownDefaultUserAgent?.let { return it }
        if (!isConfigured) return null
        return connection()?.awaitHello(HELLO_TIMEOUT_MILLIS)?.defaultUserAgent
    }

    internal fun track(provider: RemoteWebViewProvider) {
        liveSessions.add(provider)
    }

    internal fun untrack(provider: RemoteWebViewProvider) {
        liveSessions.remove(provider)
    }

    private fun open(
        port: Int,
        token: String,
    ): RemoteWebViewConnection {
        val connection = RemoteWebViewConnection(requestExecutor)
        val request =
            Request
                .Builder()
                .url("ws://127.0.0.1:$port/remote_webview")
                .header(TOKEN_HEADER, token)
                .build()
        val socket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        connection.receive(text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                        if (code == 1000) {
                            connection.onClosed(null)
                        } else {
                            logger.warn { "WebView host closed the connection: $code $reason" }
                            connection.onClosed(
                                RemoteWebViewConnection.RemoteException("WebView host closed the connection ($code $reason)"),
                            )
                        }
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        connection.onClosed(null)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        logger.warn { "WebView host connection failed: ${t.message ?: t.javaClass.simpleName}" }
                        connection.onClosed(t)
                    }
                },
            )
        connection.attach(
            object : RemoteWebViewConnection.Transport {
                override fun send(text: String): Boolean = socket.send(text)

                override fun close() {
                    socket.close(1000, null)
                }
            },
        )
        return connection
    }

    private fun reapIdleSessions() {
        val deadline = System.nanoTime() - IDLE_SESSION_NANOS
        liveSessions.toList().forEach {
            try {
                it.releaseIfIdleSince(deadline)
            } catch (error: Throwable) {
                logger.warn(error) { "Failed to release an idle WebView session" }
            }
        }
    }

    private fun daemonThreads(prefix: String): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${count.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
