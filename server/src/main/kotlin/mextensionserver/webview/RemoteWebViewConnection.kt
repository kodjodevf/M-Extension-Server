package mextensionserver.webview

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One WebSocket between this server and the Mangayomi instance that hosts the
 * real WebViews.
 *
 * Wire format (JSON text frames), symmetric in both directions:
 * - `{"type":"req","id":1,"method":"loadUrl","params":{...}}` expects exactly one
 * - `{"type":"res","id":1,"result":...}` or `{"type":"res","id":1,"error":"..."}`
 * - `{"type":"evt","method":"pageFinished","params":{...}}` expects no answer.
 *
 * Every session-scoped message carries `params.session`. Events are delivered
 * to their session in the order they arrive; requests from the host
 * (`shouldInterceptRequest`, ...) run on [requestExecutor] so a slow WebView
 * client never stalls the socket reader.
 */
class RemoteWebViewConnection(
    private val requestExecutor: Executor,
) {
    /** What the connection writes to; the OkHttp WebSocket in production. */
    interface Transport {
        /** Queues [text]; false once the transport is closed or over capacity. */
        fun send(text: String): Boolean

        fun close()
    }

    /** A remote WebView session, as seen by the connection. */
    interface Session {
        /** Called on the socket reader thread, in arrival order. */
        fun onEvent(
            method: String,
            params: JsonObject,
        )

        /** Called on a request thread; the returned value is the response result. */
        fun onRequest(
            method: String,
            params: JsonObject,
        ): JsonElement

        fun onDisconnected(error: Throwable?)
    }

    /** What the host reported about itself right after connecting. */
    data class Hello(
        val protocol: Int,
        val platform: String,
        val defaultUserAgent: String?,
        val interceptsRequests: Boolean,
    )

    class RemoteException(
        message: String,
    ) : RuntimeException(message)

    private val logger = KotlinLogging.logger {}
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var transport: Transport? = null

    @Volatile
    private var closedError: Throwable? = null

    @Volatile
    var isClosed: Boolean = false
        private set

    val hello: CompletableFuture<Hello> = CompletableFuture()

    fun attach(transport: Transport) {
        this.transport = transport
    }

    fun register(
        sessionId: String,
        session: Session,
    ) {
        sessions[sessionId] = session
    }

    fun unregister(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * Sends a request and returns its eventual result. The future fails with
     * [RemoteException] when the host answers with an error, and with the
     * transport error when the connection drops first.
     */
    fun request(
        method: String,
        params: JsonObject,
    ): CompletableFuture<JsonElement> {
        val future = CompletableFuture<JsonElement>()
        closedError?.let {
            future.completeExceptionally(it)
            return future
        }
        val id = nextId.getAndIncrement()
        pending[id] = future
        val message =
            buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", method)
                put("params", params)
            }
        if (!send(message)) {
            pending.remove(id)
            future.completeExceptionally(closedError ?: RemoteException("WebView host connection is closed"))
        }
        return future
    }

    /** Fire-and-forget variant of [request] that logs a failure instead of returning it. */
    fun command(
        method: String,
        params: JsonObject,
    ) {
        request(method, params).whenComplete { _, error ->
            if (error != null && !isClosed) {
                logger.warn { "WebView host rejected $method: ${error.message}" }
            }
        }
    }

    /** Entry point for every text frame received from the host. */
    fun receive(text: String) {
        val message =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (error: Exception) {
                logger.warn(error) { "Ignoring malformed WebView host message" }
                return
            }
        val params = (message["params"] as? JsonObject) ?: JsonObject(emptyMap())
        when (message.string("type")) {
            "res" -> {
                val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
                val future = pending.remove(id) ?: return
                val error = message.string("error")
                if (error != null) {
                    future.completeExceptionally(RemoteException(error))
                } else {
                    future.complete(message["result"] ?: JsonNull)
                }
            }

            "evt" -> {
                val method = message.string("method") ?: return
                if (method == "hello") {
                    hello.complete(
                        Hello(
                            protocol = params.int("protocol") ?: 0,
                            platform = params.string("platform") ?: "unknown",
                            defaultUserAgent = params.string("defaultUserAgent")?.takeIf { it.isNotBlank() },
                            interceptsRequests = params.boolean("interceptsRequests") ?: false,
                        ),
                    )
                    return
                }
                val session = params.string("session")?.let(sessions::get) ?: return
                try {
                    session.onEvent(method, params)
                } catch (error: Throwable) {
                    logger.error(error) { "WebView session failed to handle $method" }
                }
            }

            "req" -> {
                val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
                val method = message.string("method") ?: return
                val session = params.string("session")?.let(sessions::get)
                requestExecutor.execute {
                    val response =
                        try {
                            if (session == null) throw RemoteException("Unknown WebView session")
                            val result = session.onRequest(method, params)
                            buildJsonObject {
                                put("type", "res")
                                put("id", id)
                                put("result", result)
                            }
                        } catch (error: Throwable) {
                            logger.warn(error) { "WebView request $method failed" }
                            buildJsonObject {
                                put("type", "res")
                                put("id", id)
                                put("error", error.message ?: error.javaClass.simpleName)
                            }
                        }
                    if (!send(response)) {
                        logger.warn { "Could not answer WebView request $method: connection closed" }
                    }
                }
            }
        }
    }

    /** Called once by the transport when the socket closes or fails. */
    fun onClosed(error: Throwable?) {
        if (isClosed) return
        val cause = error ?: RemoteException("WebView host closed the connection")
        closedError = cause
        isClosed = true
        hello.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        val closedSessions = sessions.values.toList()
        sessions.clear()
        closedSessions.forEach {
            try {
                it.onDisconnected(cause)
            } catch (failure: Throwable) {
                logger.error(failure) { "WebView session failed to handle disconnection" }
            }
        }
    }

    fun close() {
        transport?.close()
        onClosed(null)
    }

    /** Waits for the host's hello, returning null when it does not come in time. */
    fun awaitHello(timeoutMillis: Long): Hello? =
        try {
            hello.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }

    private fun send(message: JsonObject): Boolean {
        val current = transport ?: return false
        if (isClosed) return false
        val text = message.toString()
        if (text.length > MAX_MESSAGE_CHARS) {
            logger.warn { "Dropping ${text.length}-character WebView message: over the transport limit" }
            return false
        }
        return current.send(text)
    }

    companion object {
        /**
         * OkHttp closes a WebSocket whose outgoing queue exceeds 16 MiB, which
         * would take every session down with it. Oversized messages (a huge
         * intercepted response body) are refused individually instead.
         */
        const val MAX_MESSAGE_CHARS = 12 * 1024 * 1024
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.stringMap(key: String): Map<String, String> =
    (this[key] as? JsonObject)
        ?.mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
        }?.toMap()
        .orEmpty()

internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
