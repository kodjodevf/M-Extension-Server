package mextensionserver.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays the Mangayomi side of the WebView protocol in memory: records the
 * requests the server sends, answers them, and pushes events and requests back.
 */
class FakeWebViewHost {
    data class Received(
        val method: String,
        val params: JsonObject,
    )

    private val nextId = AtomicLong(1_000)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val inbox = LinkedBlockingQueue<Received>()
    private val deliveries = Executors.newSingleThreadExecutor()

    /** Answers server requests by method; the default answers with null. */
    @Volatile
    var responder: (Received) -> JsonElement = { JsonNull }

    val connection = RemoteWebViewConnection(Executors.newCachedThreadPool())

    init {
        connection.attach(
            object : RemoteWebViewConnection.Transport {
                override fun send(text: String): Boolean {
                    deliveries.execute { onServerMessage(text) }
                    return true
                }

                override fun close() {}
            },
        )
    }

    fun hello(defaultUserAgent: String = "HostEngine/1.0") {
        deliver(
            buildJsonObject {
                put("type", "evt")
                put("method", "hello")
                put(
                    "params",
                    buildJsonObject {
                        put("protocol", RemoteWebViewBridge.PROTOCOL_VERSION)
                        put("platform", "test")
                        put("defaultUserAgent", defaultUserAgent)
                        put("interceptsRequests", true)
                    },
                )
            },
        )
    }

    /** Next request the server sent, waiting for it. */
    fun nextRequest(timeoutSeconds: Long = 5): Received =
        inbox.poll(timeoutSeconds, TimeUnit.SECONDS) ?: error("The server sent no request")

    /** Waits for a request of [method], skipping others. */
    fun awaitRequest(method: String): Received {
        while (true) {
            val received = nextRequest()
            if (received.method == method) return received
        }
    }

    fun event(
        method: String,
        params: JsonObject,
    ) {
        deliver(
            buildJsonObject {
                put("type", "evt")
                put("method", method)
                put("params", params)
            },
        )
    }

    /** Sends a request to the server and returns its response message. */
    fun request(
        method: String,
        params: JsonObject,
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        deliver(
            buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        return future.get(5, TimeUnit.SECONDS)
    }

    fun disconnect() {
        connection.onClosed(RuntimeException("host went away"))
    }

    private fun deliver(message: JsonObject) {
        deliveries.execute { connection.receive(message.toString()) }
    }

    private fun onServerMessage(text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        val type = message["type"]?.jsonPrimitive?.content
        val id = message["id"]?.jsonPrimitive?.long
        when (type) {
            "res" -> pending.remove(id)?.complete(message)
            "req" -> {
                val received =
                    Received(
                        message["method"]!!.jsonPrimitive.content,
                        message["params"] as JsonObject,
                    )
                inbox.add(received)
                val result = responder(received)
                deliver(
                    buildJsonObject {
                        put("type", "res")
                        put("id", id)
                        put("result", result)
                    },
                )
            }
        }
    }

    companion object {
        fun params(
            session: String,
            vararg values: Pair<String, Any?>,
        ): JsonObject =
            buildJsonObject {
                put("session", session)
                values.forEach { (key, value) ->
                    when (value) {
                        null -> put(key, JsonNull)
                        is JsonElement -> put(key, value)
                        is Boolean -> put(key, value)
                        is Number -> put(key, value)
                        else -> put(key, JsonPrimitive(value.toString()))
                    }
                }
            }
    }
}
