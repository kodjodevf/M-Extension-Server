package mextensionserver.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteWebViewConnectionTest {
    private class RecordingTransport : RemoteWebViewConnection.Transport {
        val sent = mutableListOf<JsonObject>()
        var open = true

        override fun send(text: String): Boolean {
            if (!open) return false
            synchronized(sent) { sent += Json.parseToJsonElement(text).jsonObject }
            return true
        }

        override fun close() {
            open = false
        }
    }

    private class RecordingSession : RemoteWebViewConnection.Session {
        val events = mutableListOf<String>()
        var disconnectedWith: Throwable? = null

        override fun onEvent(
            method: String,
            params: JsonObject,
        ) {
            events += method
        }

        override fun onRequest(
            method: String,
            params: JsonObject,
        ): JsonElement =
            when (method) {
                "echo" -> params["value"]!!
                else -> throw IllegalStateException("cannot $method")
            }

        override fun onDisconnected(error: Throwable?) {
            disconnectedWith = error
        }
    }

    private val transport = RecordingTransport()
    private val connection = RemoteWebViewConnection { it.run() }.apply { attach(transport) }

    @Test
    fun `request is answered by the matching response`() {
        val future = connection.request("evaluateJavascript", buildJsonObject { put("session", "s") })
        val id =
            transport.sent
                .single()["id"]!!
                .jsonPrimitive.content

        connection.receive("""{"type":"res","id":$id,"result":"\"42\""}""")

        assertEquals(JsonPrimitive("\"42\""), future.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `an error response fails the request`() {
        val future = connection.request("loadUrl", buildJsonObject { put("session", "s") })
        val id =
            transport.sent
                .single()["id"]!!
                .jsonPrimitive.content

        connection.receive("""{"type":"res","id":$id,"error":"no such session"}""")

        val failure = assertFailsWith<ExecutionException> { future.get(1, TimeUnit.SECONDS) }
        assertIs<RemoteWebViewConnection.RemoteException>(failure.cause)
        assertEquals("no such session", failure.cause!!.message)
    }

    @Test
    fun `events reach their session in order`() {
        val session = RecordingSession()
        connection.register("s", session)

        connection.receive("""{"type":"evt","method":"pageStarted","params":{"session":"s"}}""")
        connection.receive("""{"type":"evt","method":"pageFinished","params":{"session":"s"}}""")
        connection.receive("""{"type":"evt","method":"pageStarted","params":{"session":"other"}}""")

        assertEquals(listOf("pageStarted", "pageFinished"), session.events)
    }

    @Test
    fun `host requests are answered with the session result or its error`() {
        connection.register("s", RecordingSession())

        connection.receive("""{"type":"req","id":7,"method":"echo","params":{"session":"s","value":true}}""")
        connection.receive("""{"type":"req","id":8,"method":"explode","params":{"session":"s"}}""")
        connection.receive("""{"type":"req","id":9,"method":"echo","params":{"session":"missing"}}""")

        val responses = transport.sent.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(JsonPrimitive(true), responses["7"]!!["result"])
        assertEquals("cannot explode", responses["8"]!!["error"]!!.jsonPrimitive.content)
        assertEquals("Unknown WebView session", responses["9"]!!["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hello describes the host`() {
        connection.receive(
            """{"type":"evt","method":"hello","params":{"protocol":1,"platform":"ios","defaultUserAgent":"UA","interceptsRequests":true}}""",
        )

        val hello = connection.awaitHello(100)!!
        assertEquals(1, hello.protocol)
        assertEquals("ios", hello.platform)
        assertEquals("UA", hello.defaultUserAgent)
        assertTrue(hello.interceptsRequests)
    }

    @Test
    fun `closing fails pending requests and tells every session`() {
        val session = RecordingSession()
        connection.register("s", session)
        val future = connection.request("loadUrl", buildJsonObject { put("session", "s") })

        connection.onClosed(RuntimeException("gone"))

        assertFailsWith<ExecutionException> { future.get(1, TimeUnit.SECONDS) }
        assertEquals("gone", session.disconnectedWith?.message)
        assertTrue(connection.isClosed)
        assertNull(connection.awaitHello(10))
        assertTrue(connection.request("loadUrl", buildJsonObject {}).isCompletedExceptionally)
    }

    @Test
    fun `oversized messages are refused instead of killing the socket`() {
        val huge = "x".repeat(RemoteWebViewConnection.MAX_MESSAGE_CHARS)

        val future = connection.request("loadData", buildJsonObject { put("data", huge) })

        assertTrue(future.isCompletedExceptionally)
        assertTrue(transport.sent.isEmpty())
        assertFalse(connection.isClosed)
    }
}
