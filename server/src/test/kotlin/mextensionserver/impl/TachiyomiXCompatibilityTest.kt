package mextensionserver.impl

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mextensionserver.model.EpisodeData
import mextensionserver.model.JChapter
import mextensionserver.model.JManga
import mextensionserver.model.MangaData
import okhttp3.OkHttpClient
import rx.Observable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TachiyomiXCompatibilityTest {
    @AfterTest
    fun clearMetadataCache() {
        MihonMetadataCache.clear()
    }

    @Test
    fun `details endpoint uses native combined update API`() {
        val source = NativeSource()

        val result = invokeDetails(source)

        assertEquals("Updated title", result.title)
        assertEquals(listOf(UpdateRequest(fetchDetails = true, fetchChapters = false)), source.requests)
    }

    @Test
    fun `chapters endpoint uses native combined update API`() {
        val source = NativeSource()

        val result = invokeChapters(source)

        assertEquals(listOf("Chapter 1"), result.map(JChapter::name))
        assertEquals(listOf(UpdateRequest(fetchDetails = false, fetchChapters = true)), source.requests)
    }

    @Test
    fun `legacy catalogue source falls back to split APIs`() =
        runBlocking {
            val source = LegacySource()
            val manga = newManga("Original title")
            val existingChapters = listOf(newChapter("Existing chapter"))

            val details =
                source.getMangaUpdate(
                    manga = manga,
                    chapters = existingChapters,
                    fetchDetails = true,
                    fetchChapters = false,
                )
            val chapters =
                source.getMangaUpdate(
                    manga = manga,
                    chapters = existingChapters,
                    fetchDetails = false,
                    fetchChapters = true,
                )

            assertEquals("Legacy details", details.manga.title)
            assertSame(existingChapters, details.chapters)
            assertSame(manga, chapters.manga)
            assertEquals(listOf("Legacy chapter"), chapters.chapters.map(SChapter::name))
            assertEquals(1, source.detailsRequests)
            assertEquals(1, source.chapterRequests)
        }

    @Test
    fun `manga and chapter models retain 1_6 memo metadata`() {
        val memo = buildJsonObject { put("token", JsonPrimitive("value")) }
        val manga = newManga("Title").apply { this.memo = memo }
        val chapter = newChapter("Chapter").apply { this.memo = memo }

        assertSame(memo, manga.memo)
        assertSame(memo, chapter.memo)
    }

    @Test
    fun `memo metadata survives stateless bridge models`() {
        val source = NativeSource()
        val memo = buildJsonObject { put("id", JsonPrimitive(42)) }
        val originalManga = newManga("Title").apply { this.memo = memo }
        val originalChapter = newChapter("Chapter").apply { this.memo = memo }
        val restoredManga = newManga("Title")
        val restoredChapter = newChapter("Chapter")

        MihonMetadataCache.remember(source, originalManga)
        MihonMetadataCache.remember(source, originalChapter)
        MihonMetadataCache.restore(source, restoredManga)
        MihonMetadataCache.restore(source, restoredChapter)

        assertSame(memo, restoredManga.memo)
        assertSame(memo, restoredChapter.memo)
    }

    @Test
    fun `legacy anime source uses getVideoList without calling getHosterList`() {
        val source = LegacyAnimeSource()
        val videos = invokeVideos(source)

        assertEquals(1, source.videoListCalls)
        assertEquals(1, videos.size)
        assertEquals("1080p", videos.first().quality)
        assertEquals("https://cdn.example.com/legacy.mp4", videos.first().url)
    }

    @Test
    fun `lib 16 anime source resolves hosters, prefixes hoster name and sorts preferred first`() {
        val source = Lib16AnimeSource()
        val videos = invokeVideos(source)

        assertEquals(2, videos.size)
        assertEquals("Sendvid - 1080p", videos[0].quality)
        assertEquals("https://cdn.example.com/sendvid-resolved.m3u8", videos[0].url)
        assertEquals("Vidmoly - 720p", videos[1].quality)
        assertEquals("https://cdn.example.com/vidmoly.mp4", videos[1].url)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeVideos(source: AnimeCatalogueSource): List<Video> {
        val method =
            MihonInvoker::class.java.getDeclaredMethod(
                "invokeGetVideoList",
                AnimeCatalogueSource::class.java,
                EpisodeData::class.java,
            )
        method.isAccessible = true
        return method.invoke(
            MihonInvoker,
            source,
            EpisodeData(url = "/ep/1", name = "Episode 1", date_upload = 0L, episode_number = 1f, scanlator = null),
        ) as List<Video>
    }

    private fun invokeDetails(source: Source): JManga {
        val method =
            MihonInvoker::class.java.getDeclaredMethod(
                "invokeGetDetailsManga",
                Source::class.java,
                MangaData::class.java,
            )
        method.isAccessible = true
        return method.invoke(MihonInvoker, source, mangaData()) as JManga
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeChapters(source: Source): List<JChapter> {
        val method =
            MihonInvoker::class.java.getDeclaredMethod(
                "invokeGetChapterList",
                Source::class.java,
                MangaData::class.java,
            )
        method.isAccessible = true
        return method.invoke(MihonInvoker, source, mangaData()) as List<JChapter>
    }

    private data class UpdateRequest(
        val fetchDetails: Boolean,
        val fetchChapters: Boolean,
    )

    private class NativeSource : Source {
        override val id = 1L
        override val name = "Native 1.6 source"
        val requests = mutableListOf<UpdateRequest>()

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            requests += UpdateRequest(fetchDetails, fetchChapters)
            return SMangaUpdate(
                manga = if (fetchDetails) newManga("Updated title") else manga,
                chapters = if (fetchChapters) listOf(newChapter("Chapter 1")) else chapters,
            )
        }

        override suspend fun getMangaDetails(manga: SManga): SManga = error("The bridge must use getMangaUpdate")

        override suspend fun getChapterList(manga: SManga): List<SChapter> = error("The bridge must use getMangaUpdate")
    }

    private class LegacySource : CatalogueSource {
        override val id = 2L
        override val name = "Legacy source"
        override val lang = "en"
        override val supportsLatest = false
        var detailsRequests = 0
        var chapterRequests = 0

        override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
            detailsRequests++
            return Observable.just(newManga("Legacy details"))
        }

        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
            chapterRequests++
            return Observable.just(listOf(newChapter("Legacy chapter")))
        }
    }

    private class LegacyAnimeSource : AnimeHttpSource() {
        override val name = "Legacy Anime"
        override val baseUrl = "https://anime.example.com"
        override val lang = "en"
        override val supportsLatest = false
        override val client: OkHttpClient = OkHttpClient()
        var videoListCalls = 0

        override suspend fun getVideoList(episode: SEpisode): List<Video> {
            videoListCalls++
            return listOf(Video("https://cdn.example.com/legacy.mp4", "1080p", "https://cdn.example.com/legacy.mp4"))
        }
    }

    private class Lib16AnimeSource : AnimeHttpSource() {
        override val name = "Lib16 Anime"
        override val baseUrl = "https://anime.example.com"
        override val lang = "en"
        override val supportsLatest = false
        override val client: OkHttpClient = OkHttpClient()

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
            listOf(
                Hoster(hosterUrl = "https://vidmoly.example.com/e/1", hosterName = "Vidmoly"),
                Hoster(hosterUrl = "https://sendvid.example.com/e/1", hosterName = "Sendvid"),
            )

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            when (hoster.hosterName) {
                "Vidmoly" ->
                    listOf(
                        Video(
                            videoUrl = "https://cdn.example.com/vidmoly.mp4",
                            videoTitle = "720p",
                            initialized = true,
                        ),
                    )
                "Sendvid" ->
                    listOf(
                        Video(
                            videoUrl = "https://sendvid.example.com/unresolved",
                            videoTitle = "1080p",
                            preferred = true,
                            initialized = false,
                        ),
                    )
                else -> emptyList()
            }

        override suspend fun resolveVideo(video: Video): Video =
            video.copy(
                videoUrl = "https://cdn.example.com/sendvid-resolved.m3u8",
                initialized = true,
            )
    }

    private companion object {
        fun newManga(title: String): SManga =
            SManga.create().apply {
                url = "/manga"
                this.title = title
            }

        fun newChapter(name: String): SChapter =
            SChapter.create().apply {
                url = "/chapter"
                this.name = name
            }

        fun mangaData() =
            MangaData(
                url = "/manga",
                title = "Original title",
                artist = null,
                author = null,
                description = null,
                genre = null,
                status = 0,
                thumbnail_url = null,
                initialized = false,
            )
    }
}
