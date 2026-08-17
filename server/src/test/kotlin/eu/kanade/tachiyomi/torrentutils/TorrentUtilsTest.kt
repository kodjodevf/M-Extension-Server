package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentutils.bencode.BencodeParser
import eu.kanade.tachiyomi.torrentutils.bencode.BencodeValue
import eu.kanade.tachiyomi.torrentutils.bencode.BencodeWriter
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TreeMap

class TorrentUtilsTest {
    @Test
    fun testBencodeInteger() {
        val bytes = "i42e".toByteArray(Charsets.UTF_8)
        val value = BencodeParser.parse(ByteArrayInputStream(bytes))
        assertTrue(value is BencodeValue.Integer)
        assertEquals(42L, (value as BencodeValue.Integer).value)

        val out = ByteArrayOutputStream()
        BencodeWriter.write(value, out)
        assertEquals("i42e", out.toString(Charsets.UTF_8))
    }

    @Test
    fun testBencodeString() {
        val bytes = "4:spam".toByteArray(Charsets.UTF_8)
        val value = BencodeParser.parse(ByteArrayInputStream(bytes))
        assertTrue(value is BencodeValue.ByteString)
        assertEquals("spam", (value as BencodeValue.ByteString).toUTF8String())

        val out = ByteArrayOutputStream()
        BencodeWriter.write(value, out)
        assertEquals("4:spam", out.toString(Charsets.UTF_8))
    }

    @Test
    fun testBencodeDictionaryAndTorrentHelpers() {
        val dict = TreeMap<BencodeValue.ByteString, BencodeValue>()
        val infoDict = TreeMap<BencodeValue.ByteString, BencodeValue>()

        infoDict[BencodeValue.ByteString.fromUTF8String("name")] =
            BencodeValue.ByteString.fromUTF8String("Test Anime Episode 01")
        infoDict[BencodeValue.ByteString.fromUTF8String("length")] =
            BencodeValue.Integer(1024L * 1024L * 500L)

        dict[BencodeValue.ByteString.fromUTF8String("announce")] =
            BencodeValue.ByteString.fromUTF8String("http://tracker.example.com/announce")
        dict[BencodeValue.ByteString.fromUTF8String("info")] =
            BencodeValue.Dictionary(infoDict)

        val out = ByteArrayOutputStream()
        BencodeWriter.write(BencodeValue.Dictionary(dict), out)

        val torrentDetails =
            TorrentHelpers.parseTorrentDetailsFromTorrentFileContent(
                ByteArrayInputStream(out.toByteArray()),
            )

        assertEquals("Test Anime Episode 01", torrentDetails.title)
        assertEquals(524288000L, torrentDetails.size)
        assertEquals(1, torrentDetails.files.size)
        assertEquals("Test Anime Episode 01", torrentDetails.files[0].path)
        assertEquals("http://tracker.example.com/announce", torrentDetails.trackers[0])
        assertNotNull(torrentDetails.hash)
        assertTrue(torrentDetails.hash.isNotEmpty())
    }

    @Test
    fun testMagnetLinkParsing() =
        runBlocking {
            val hash = "0123456789abcdef0123456789abcdef01234567"
            val magnet = "magnet:?xt=urn:btih:$hash&dn=Sample+Episode&tr=http%3A%2F%2Ftracker.com%2Fannounce"
            val info = TorrentUtils.getTorrentInfo(magnet, "Sample Episode")

            assertEquals("Sample Episode", info.title)
            assertEquals(hash, info.hash)
            assertEquals(1, info.files.size)
            assertEquals(hash, info.files[0].torrentHash)
            assertEquals("http://tracker.com/announce", info.trackers[0])

            val generatedMagnet = info.files[0].toMagnetURI()
            assertTrue(generatedMagnet.contains("xt=urn:btih:$hash"))
            assertTrue(generatedMagnet.contains("index=1"))
        }

    @Test
    fun testTorrentFileAndInfoModels() {
        val file =
            TorrentFile(
                path = "Episode 01.mkv",
                indexFile = 1,
                size = 123456L,
                torrentHash = "abc123hash",
                trackers = listOf("http://tracker.org/announce"),
            )
        val info =
            TorrentInfo(
                title = "Anime Title",
                files = listOf(file),
                hash = "abc123hash",
                size = 123456L,
                trackers = listOf("http://tracker.org/announce"),
            )

        assertEquals("Anime Title", info.title)
        assertEquals("Episode 01.mkv", info.files[0].path)
        val magnet = file.toMagnetURI()
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:abc123hash"))
    }
}
