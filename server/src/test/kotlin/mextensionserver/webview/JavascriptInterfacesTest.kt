package mextensionserver.webview

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JavascriptInterfacesTest {
    class Bridge {
        var payload: String? = null

        @JavascriptInterface
        fun passPayload(value: String?) {
            payload = value
        }

        @JavascriptInterface
        fun add(
            a: Int,
            b: Double,
        ): Double = a + b

        @JavascriptInterface
        fun isReady(flag: Boolean): String = "ready=$flag"

        @JavascriptInterface
        fun fail(): String = throw IllegalStateException("boom")

        fun hidden(): String = "not exposed"
    }

    class Unannotated {
        fun ping(): String = "pong"
    }

    @Test
    fun `only annotated methods are exposed`() {
        assertEquals(listOf("add", "fail", "isReady", "passPayload"), JavascriptInterfaces.methodNames(Bridge()))
        assertFailsWith<NoSuchMethodException> { JavascriptInterfaces.invoke(Bridge(), "hidden", emptyList()) }
    }

    @Test
    fun `arguments are converted to parameter types`() {
        val bridge = Bridge()

        JavascriptInterfaces.invoke(bridge, "passPayload", listOf(JsonPrimitive("<html>")))
        assertEquals("<html>", bridge.payload)

        assertEquals(JsonPrimitive(3.5), JavascriptInterfaces.invoke(bridge, "add", listOf(JsonPrimitive(1.9), JsonPrimitive(2.5))))
        assertEquals(JsonPrimitive("ready=true"), JavascriptInterfaces.invoke(bridge, "isReady", listOf(JsonPrimitive(true))))
    }

    @Test
    fun `objects passed to a string parameter arrive as JSON and void returns null`() {
        val bridge = Bridge()

        val result =
            JavascriptInterfaces.invoke(
                bridge,
                "passPayload",
                listOf(buildJsonObject { put("a", 1) }),
            )

        assertEquals("""{"a":1}""", bridge.payload)
        assertEquals(JsonNull, result)
    }

    @Test
    fun `a throwing method surfaces its own exception`() {
        val failure = assertFailsWith<IllegalStateException> { JavascriptInterfaces.invoke(Bridge(), "fail", emptyList()) }
        assertEquals("boom", failure.message)
    }

    @Test
    fun `objects without annotations fall back to their public methods`() {
        assertEquals(listOf("ping"), JavascriptInterfaces.methodNames(Unannotated()))
        assertEquals(JsonPrimitive("pong"), JavascriptInterfaces.invoke(Unannotated(), "ping", emptyList()))
    }
}
