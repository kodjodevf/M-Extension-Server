package mextensionserver.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataBody(
    val data: String? = null, // Base64 encoded APK data
    val extensionId: String? = null, // Server-issued SHA-256 handle
    val method: String,
    val page: Int? = null,
    val search: String? = null,
    val filterList: List<JFilterList>? = null,
    val mangaData: MangaData? = null,
    val chapterData: ChapterData? = null,
    val animeData: AnimeData? = null,
    val episodeData: EpisodeData? = null,
    val preferences: MutableList<Map<String, Any>>? = null,
    val sourceId: String? = null,
    val lang: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MangaData(
    val url: String?,
    val title: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int? = null,
    val thumbnail_url: String? = null,
    val initialized: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChapterData(
    val url: String?,
    val name: String? = null,
    val date_upload: Long? = null,
    val chapter_number: Float? = null,
    val scanlator: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeData(
    val url: String?,
    val title: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int? = null,
    val thumbnail_url: String? = null,
    val initialized: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeData(
    val url: String?,
    val name: String? = null,
    val date_upload: Long? = null,
    val episode_number: Float? = null,
    val scanlator: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JFilterList(
    val name: String?,
    val type: String?,
    val stateString: String? = null,
    val stateInt: Int? = null,
    val stateList: List<JGroupFilter>? = null,
    val stateSort: JSortFilter? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JGroupFilter(
    val name: String?,
    val type: String?,
    val stateBoolean: Boolean? = null,
    val stateInt: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JSortFilter(
    val ascending: Boolean?,
    val index: Int?,
)
