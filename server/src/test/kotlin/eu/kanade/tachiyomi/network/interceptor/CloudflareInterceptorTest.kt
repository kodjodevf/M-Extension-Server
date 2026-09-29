package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.MemoryCookieJar
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudflareInterceptorTest {
    /** Answers 403 like Cloudflare does until the caller presents the clearance cookie. */
    private class ChallengedSite : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            val cleared = session.headers["cookie"]?.contains(CLEARANCE) == true
            return if (cleared) {
                newFixedLengthResponse(Response.Status.OK, "text/plain", session.headers["user-agent"].orEmpty())
            } else {
                newFixedLengthResponse(Response.Status.FORBIDDEN, "text/html", "challenge").apply {
                    addHeader("Server", "cloudflare")
                }
            }
        }
    }

    private class FakeSolver : NanoHTTPD(0) {
        var calls = 0

        override fun serve(session: IHTTPSession): Response {
            calls++
            session.parseBody(mutableMapOf())
            val body =
                """
                {"status":"ok","message":"Challenge solved!","solution":{"status":200,
                "userAgent":"$SOLVED_UA","cookies":[{"name":"$CLEARANCE","value":"abc",
                "domain":"localhost","path":"/","secure":false,"httpOnly":false}]}}
                """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }
    }

    private fun clientFor(
        proxyUrl: String,
        onUserAgent: (String) -> Unit = {},
    ): OkHttpClient {
        val jar = MemoryCookieJar()
        return OkHttpClient
            .Builder()
            .cookieJar(jar)
            .addInterceptor(CloudflareInterceptor({ proxyUrl }, onUserAgent, jar::addAll))
            .build()
    }

    @Test
    fun `solves the challenge through the proxy and retries the request`() {
        val site = ChallengedSite().apply { start() }
        val solver = FakeSolver().apply { start() }
        var reportedUserAgent = ""

        try {
            val client = clientFor("http://localhost:${solver.listeningPort}/v1") { reportedUserAgent = it }
            val response =
                client
                    .newCall(Request.Builder().url("http://localhost:${site.listeningPort}/").build())
                    .execute()

            response.use {
                assertEquals(200, it.code)
                // The site echoes the user-agent it was finally called with.
                assertEquals(SOLVED_UA, it.body?.string())
            }
            assertEquals(1, solver.calls)
            assertEquals(SOLVED_UA, reportedUserAgent, "the solved user-agent must be kept for later requests")
        } finally {
            site.stop()
            solver.stop()
        }
    }

    @Test
    fun `passes the blocked response through when no proxy is configured`() {
        val site = ChallengedSite().apply { start() }
        val solver = FakeSolver().apply { start() }

        try {
            val client = clientFor("")
            val response =
                client
                    .newCall(Request.Builder().url("http://localhost:${site.listeningPort}/").build())
                    .execute()

            response.use { assertEquals(403, it.code) }
            assertEquals(0, solver.calls, "the proxy must not be called when none is configured")
        } finally {
            site.stop()
            solver.stop()
        }
    }

    @Test
    fun `leaves responses that are not a Cloudflare block untouched`() {
        val plain =
            object : NanoHTTPD(0) {
                override fun serve(session: IHTTPSession): Response =
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nope")
            }.apply { start() }
        val solver = FakeSolver().apply { start() }

        try {
            val client = clientFor("http://localhost:${solver.listeningPort}/v1")
            val response =
                client
                    .newCall(Request.Builder().url("http://localhost:${plain.listeningPort}/").build())
                    .execute()

            response.use { assertEquals(404, it.code) }
            assertTrue(solver.calls == 0)
        } finally {
            plain.stop()
            solver.stop()
        }
    }

    private companion object {
        const val CLEARANCE = "cf_clearance"
        const val SOLVED_UA = "Mozilla/5.0 (X11; Linux x86_64) Chrome/140.0.0.0 Safari/537.36"
    }
}
