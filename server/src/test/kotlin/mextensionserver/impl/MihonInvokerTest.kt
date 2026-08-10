package mextensionserver.impl

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.preference.Preference
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import mextensionserver.model.DataBody
import mextensionserver.model.JFilterList
import mextensionserver.model.JGroupFilter
import mextensionserver.model.MangaResponse
import java.net.URLClassLoader
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MihonInvokerTest {
    @Test
    fun `reports stable package metadata for local APK imports`() {
        val jar = createTempFile(prefix = "mextensionserver-test-", suffix = ".jar").toFile()
        val packageInfo =
            PackageInfo().apply {
                packageName = "eu.kanade.tachiyomi.extension.en.localtest"
                versionName = "1.4.7"
                versionCode = 12
                applicationInfo =
                    ApplicationInfo().apply {
                        nonLocalizedLabel = "Tachiyomi: Local Test"
                        metaData = Bundle().apply { putString("tachiyomi.extension.nsfw", "1") }
                    }
            }
        val extension =
            MExtensionServerLoader.LoadedExtension(
                initialSources = listOf(NoLatestSource()),
                packageInfo = packageInfo,
                jarFile = jar,
                classLoader = URLClassLoader(emptyArray()),
            )

        val result =
            MihonInvoker.invokeMethod(
                extension,
                DataBody(method = "extensionInfo"),
            ) as Map<*, *>

        assertEquals("eu.kanade.tachiyomi.extension.en.localtest", result["packageName"])
        assertEquals("Local Test", result["name"])
        assertEquals("1.4.7", result["versionName"])
        assertEquals(12, result["versionCode"])
        assertEquals("ja", result["lang"])
        assertEquals(true, result["isNsfw"])
        assertEquals("manga", result["itemType"])
        assertEquals(
            listOf(
                mapOf(
                    "id" to "1",
                    "name" to "No latest",
                    "lang" to "ja",
                    "baseUrl" to "",
                ),
            ),
            result["sources"],
        )
        extension.close()
    }

    @Test
    fun `replace-present preference mode is explicit`() {
        val context =
            mutableListOf<Map<String, Any>>(
                mapOf(
                    "key" to "__mangatan_bridge_context__",
                    "preferenceApplyMode" to "replace-present",
                ),
            )

        assertTrue(
            MihonInvoker.shouldReplacePresentPreferences(
                DataBody(method = "sourcesAnime", preferences = context),
            ),
        )
        assertEquals(
            false,
            MihonInvoker.shouldReplacePresentPreferences(
                DataBody(method = "sourcesAnime", preferences = mutableListOf()),
            ),
        )
    }

    @Test
    fun `converts children of grouped anime filters`() {
        val child = TestCheckBox()
        val originalFilters =
            AnimeFilterList(
                TestGroup(listOf(child)),
            )
        val requestedFilters =
            listOf(
                JFilterList(
                    name = "Group",
                    type = null,
                    stateString = null,
                    stateInt = null,
                    stateList =
                        listOf(
                            JGroupFilter(
                                name = "Child",
                                type = null,
                                stateBoolean = true,
                                stateInt = null,
                            ),
                        ),
                    stateSort = null,
                ),
            )

        convertAnimeFilterList(originalFilters, requestedFilters)

        assertTrue(child.state)
    }

    @Test
    fun `keeps normalized value saved by rejecting preference listener`() {
        val preference = TestPreference("https://old.example")
        preference.setOnPreferenceChangeListener { pref, newValue ->
            val normalized = (newValue as String).substringBefore("/path")
            pref.saveNewValue(normalized)
            false
        }

        MihonInvoker.applyPreferenceChange(
            preference,
            "https://new.example/path",
        )

        assertEquals("https://new.example", preference.currentValue)
    }

    @Test
    fun `persists value accepted by preference listener`() {
        val preference = TestPreference("old")
        var valueSeenByListener: Any? = null
        preference.setOnPreferenceChangeListener { pref, _ ->
            valueSeenByListener = pref.currentValue
            true
        }

        MihonInvoker.applyPreferenceChange(preference, "new")

        assertEquals("old", valueSeenByListener)
        assertEquals("new", preference.currentValue)
    }

    @Test
    fun `uses popular feed when stale client requests unsupported latest feed`() {
        val method =
            MihonInvoker::class.java.getDeclaredMethod(
                "invokeGetLatestManga",
                Source::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true

        val result = method.invoke(MihonInvoker, NoLatestSource(), 1) as MangaResponse

        val mangas = requireNotNull(result.mangas)
        assertEquals(1, mangas.size)
        assertEquals("Popular fallback", mangas.single().title)
        assertEquals(false, result.hasNextPage)
    }

    private fun convertAnimeFilterList(
        originalFilters: AnimeFilterList,
        requestedFilters: List<JFilterList>,
    ) {
        val method =
            MihonInvoker::class.java.getDeclaredMethod(
                "convertAnimeFilterList",
                AnimeFilterList::class.java,
                List::class.java,
            )
        method.isAccessible = true
        method.invoke(MihonInvoker, originalFilters, requestedFilters)
    }

    private class TestCheckBox : AnimeFilter.CheckBox("Child")

    private class TestGroup(
        children: List<TestCheckBox>,
    ) : AnimeFilter.Group<TestCheckBox>("Group", children)

    private class TestPreference(
        initialValue: Any,
    ) : Preference(null) {
        private var value: Any = initialValue

        override fun getCurrentValue(): Any = value

        override fun saveNewValue(value: Any) {
            this.value = value
        }
    }

    private class NoLatestSource : CatalogueSource {
        override val id = 1L
        override val name = "No latest"
        override val lang = "ja"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            val manga =
                SManga.create().apply {
                    title = "Popular fallback"
                    url = "/popular"
                }
            return MangasPage(listOf(manga), hasNextPage = false)
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            error("Latest must not be called")
        }

        override fun getFilterList() = FilterList()
    }
}
