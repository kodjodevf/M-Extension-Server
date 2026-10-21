package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryCookieJarTest {
    @Test
    fun `the last cookie with the same name wins`() {
        val url = "https://www.kumanga.com/mangalist?page=2".toHttpUrl()
        val jar = MemoryCookieJar()
        // What KuManga sends: delete the old value, then set the new one.
        val cookies =
            listOf(
                "sec_info=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0",
                "sec_info=fresh; Max-Age=1800; path=/",
            ).map { Cookie.parse(url, it)!! }

        jar.saveFromResponse(url, cookies)

        assertEquals(listOf("sec_info=fresh"), jar.loadForRequest(url).map { "${it.name}=${it.value}" })
    }
}
