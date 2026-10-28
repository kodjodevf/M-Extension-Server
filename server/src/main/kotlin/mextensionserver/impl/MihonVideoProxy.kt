package mextensionserver.impl

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Keeps extension video requests inside the bridge.
 *
 * Some anime extensions return a localhost HLS proxy or require their OkHttp
 * interceptors and request headers for every playlist and segment. Neither is
 * directly reachable from an iOS client, so media URLs are registered here and
 * recursively rewritten through the bridge.
 */
internal object MihonVideoProxy {
    private const val MAX_ENTRIES = 32768
    private val uriAttribute = Regex("""URI=(["'])(.*?)\1""")
    private val mediaExtension = Regex("""\.([A-Za-z0-9]{1,8})$""")

    data class VideoData(
        val statusCode: Int,
        val contentType: String,
        val stream: InputStream,
        val contentLength: Long,
        val responseHeaders: Map<String, String>,
    )

    private data class Entry(
        val key: String,
        val client: OkHttpClient,
        val url: HttpUrl?,
        val headers: Headers,
        val localFile: java.io.File? = null,
    )

    private val loopbackClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(2, TimeUnit.MINUTES)
            .build()

    private val lock = Any()
    private val entriesByToken = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val tokensByKey = mutableMapOf<String, String>()

    @Volatile
    private var port: Int = 0

    fun configure(port: Int) {
        require(port > 0) { "Video proxy port must be positive" }
        this.port = port
    }

    fun proxy(
        source: AnimeHttpSource,
        video: Video,
    ): Video {
        val client = source.client
        val headers =
            runCatching {
                val defaultHeaders = source.headers
                val builder = defaultHeaders.newBuilder()
                video.headers?.forEach { (name, value) ->
                    builder.set(name, value)
                }
                builder.build()
            }.getOrDefault(video.headers ?: Headers.Builder().build())
        val proxiedVideoUrl =
            video.videoUrl?.let { register(client, it, headers, suffixHint = video.url.mediaFileSuffix()) }
                ?: video.videoUrl
        return Video(
            url = video.url.ifEmpty { video.videoUrl.orEmpty() },
            quality = video.quality,
            videoUrl = proxiedVideoUrl,
            headers = headers,
            subtitleTracks = video.subtitleTracks.map { proxyTrack(client, headers, it) },
            audioTracks = video.audioTracks.map { proxyTrack(client, headers, it) },
            timestamps = video.timestamps,
        ).also {
            it.resolution = video.resolution
            it.bitrate = video.bitrate
            it.preferred = video.preferred
            it.mpvArgs = video.mpvArgs
            it.ffmpegStreamArgs = video.ffmpegStreamArgs
            it.ffmpegVideoArgs = video.ffmpegVideoArgs
        }
    }

    internal fun register(
        client: OkHttpClient,
        url: String,
        headers: Headers = Headers.Builder().build(),
        suffixHint: String? = null,
    ): String? {
        val currentPort = port
        val target = url.toHttpUrlOrNull()
        if (currentPort <= 0 || target == null || target.scheme !in setOf("http", "https")) {
            return null
        }

        val key =
            buildString {
                append(System.identityHashCode(client))
                append('\u0000')
                append(target)
                append('\u0000')
                append(suffixHint.orEmpty())
                headers.forEach { (name, value) ->
                    append('\u0000')
                    append(name)
                    append(':')
                    append(value)
                }
            }
        val token =
            synchronized(lock) {
                tokensByKey[key]?.let { existingToken ->
                    entriesByToken[existingToken] = Entry(key, client, target, headers)
                    return@synchronized existingToken
                }

                while (entriesByToken.size >= MAX_ENTRIES) {
                    val iterator = entriesByToken.entries.iterator()
                    val eldest = iterator.next()
                    iterator.remove()
                    tokensByKey.remove(eldest.value.key)
                }

                UUID.randomUUID().toString().also { newToken ->
                    entriesByToken[newToken] = Entry(key, client, target, headers)
                    tokensByKey[key] = newToken
                }
            }
        return proxyUrl(currentPort, token, target, suffixHint)
    }

    fun registerLocalFile(
        file: java.io.File,
        suffixHint: String? = null,
    ): String? {
        val currentPort = port
        if (currentPort <= 0 || !file.exists()) {
            return null
        }
        val key = "file\u0000${file.absolutePath}"
        val token =
            synchronized(lock) {
                tokensByKey[key]?.let { existingToken ->
                    entriesByToken[existingToken] =
                        Entry(key, loopbackClient, null, Headers.Builder().build(), file)
                    return@synchronized existingToken
                }

                while (entriesByToken.size >= MAX_ENTRIES) {
                    val iterator = entriesByToken.entries.iterator()
                    val eldest = iterator.next()
                    iterator.remove()
                    tokensByKey.remove(eldest.value.key)
                }

                UUID.randomUUID().toString().also { newToken ->
                    entriesByToken[newToken] =
                        Entry(key, loopbackClient, null, Headers.Builder().build(), file)
                    tokensByKey[key] = newToken
                }
            }
        val suffix = suffixHint ?: if (file.extension.isNotEmpty()) ".${file.extension}" else ""
        return "http://127.0.0.1:$currentPort/video/$token$suffix"
    }

    fun fetch(
        token: String,
        range: String? = null,
    ): VideoData? {
        val registryToken = token.substringBefore('.')
        val entry = synchronized(lock) { entriesByToken[registryToken] } ?: return null
        if (entry.localFile != null) {
            if (!entry.localFile.exists()) return null
            val mimeType =
                when {
                    entry.localFile.name.endsWith(".srt", ignoreCase = true) -> "application/x-subrip"
                    entry.localFile.name.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
                    entry.localFile.name.endsWith(".ass", ignoreCase = true) -> "text/x-ssa"
                    else -> "text/plain; charset=utf-8"
                }
            return VideoData(
                statusCode = 200,
                contentType = mimeType,
                stream = entry.localFile.inputStream(),
                contentLength = entry.localFile.length(),
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*"),
            )
        }

        val targetUrl = entry.url ?: return null
        val request =
            Request
                .Builder()
                .url(targetUrl)
                .headers(entry.headers)
                .apply {
                    // Extension-owned loopback M3U8 servers often forward the
                    // player's Accept-Encoding header to their upstream CDN.
                    // Supplying gzip explicitly disables OkHttp's transparent
                    // decompression there, so compressed playlists and media
                    // can be parsed as if they were raw HLS bytes.
                    if (targetUrl.isLoopback()) {
                        header("Accept-Encoding", "identity")
                    }
                    if (!range.isNullOrBlank()) {
                        header("Range", range)
                    }
                }.build()

        val requestClient = if (targetUrl.isLoopback()) loopbackClient else entry.client
        val response = requestClient.newCall(request).execute()
        try {
            val body = requireNotNull(response.body) { "Extension returned an empty video response" }
            val contentType = body.contentType()?.toString() ?: "application/octet-stream"
            val responseHeaders =
                listOf("Accept-Ranges", "Content-Range", "Cache-Control")
                    .mapNotNull { name -> response.header(name)?.let { name to it } }
                    .toMap()

            if (response.isSuccessful && isHlsManifest(contentType, response.request.url, body.source().peek())) {
                val bytes = body.bytes()
                val rewritten =
                    rewriteManifest(
                        client = entry.client,
                        baseUrl = response.request.url,
                        headers = entry.headers,
                        bytes = bytes,
                    )
                response.close()
                return VideoData(
                    statusCode = response.code,
                    contentType = "application/vnd.apple.mpegurl",
                    stream = ByteArrayInputStream(rewritten),
                    contentLength = rewritten.size.toLong(),
                    responseHeaders = responseHeaders,
                )
            }

            if (response.isSuccessful && targetUrl.isLoopback() && contentType.contains("mp2t", ignoreCase = true)) {
                val bytes = body.bytes()
                val repaired = MpegTsSanitizer.repair(bytes)
                response.close()
                return VideoData(
                    statusCode = response.code,
                    contentType = contentType,
                    stream = ByteArrayInputStream(repaired),
                    contentLength = repaired.size.toLong(),
                    responseHeaders = responseHeaders,
                )
            }

            return VideoData(
                statusCode = response.code,
                contentType = contentType,
                stream = ResponseClosingInputStream(body.byteStream(), response),
                contentLength = body.contentLength(),
                responseHeaders = responseHeaders,
            )
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    internal fun proxyTrack(
        client: OkHttpClient,
        headers: Headers,
        track: Track,
    ): Track {
        val url = track.url
        if (url.startsWith("file://", ignoreCase = true) || url.startsWith("file:", ignoreCase = true)) {
            val file =
                runCatching {
                    if (url.startsWith("file://", ignoreCase = true)) {
                        java.io.File(java.net.URI(url))
                    } else {
                        java.io.File(url.removePrefix("file:"))
                    }
                }.getOrNull() ?: java.io.File(url.removePrefix("file://"))
            if (file.exists()) {
                val registered =
                    registerLocalFile(file, if (file.name.endsWith(".srt", ignoreCase = true)) ".srt" else null)
                if (registered != null) {
                    return Track(url = registered, lang = track.lang)
                }
            }
        }
        return Track(
            url = register(client, track.url, headers) ?: track.url,
            lang = track.lang,
        )
    }

    private fun rewriteManifest(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        headers: Headers,
        bytes: ByteArray,
    ): ByteArray {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val segmentSuffix = if (text.contains("#EXT-X-MAP")) ".m4s" else ".ts"
        var pendingSegmentUri = false
        val rewritten =
            text
                .lineSequence()
                .map { line ->
                    when {
                        line.isBlank() -> line
                        line.startsWith("#") -> {
                            if (line.startsWith("#EXTINF")) pendingSegmentUri = true
                            val suffixHint =
                                when {
                                    line.startsWith("#EXT-X-KEY") -> ".key"
                                    line.startsWith("#EXT-X-MAP") -> ".mp4"
                                    line.startsWith("#EXT-X-MEDIA") ||
                                        line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> ".m3u8"
                                    else -> null
                                }
                            uriAttribute.replace(line) { match ->
                                val quote = match.groupValues[1]
                                val original = match.groupValues[2]
                                val proxy =
                                    registerResolved(client, baseUrl, original, headers, suffixHint)
                                        ?: original
                                "URI=$quote$proxy$quote"
                            }
                        }
                        else -> {
                            val suffixHint = if (pendingSegmentUri) segmentSuffix else ".m3u8"
                            pendingSegmentUri = false
                            registerResolved(client, baseUrl, line.trim(), headers, suffixHint) ?: line
                        }
                    }
                }.joinToString("\n")
        return rewritten.toByteArray(StandardCharsets.UTF_8)
    }

    private fun registerResolved(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        url: String,
        headers: Headers,
        suffixHint: String?,
    ): String? {
        val resolved = baseUrl.resolve(url) ?: return null
        val absoluteProxy = register(client, resolved.toString(), headers, suffixHint) ?: return null
        return absoluteProxy.toHttpUrl().encodedPath
    }

    private fun isHlsManifest(
        contentType: String,
        url: HttpUrl,
        source: okio.BufferedSource,
    ): Boolean {
        if (contentType.contains("mpegurl", ignoreCase = true)) {
            return true
        }
        if (!url.encodedPath.endsWith(".m3u8", ignoreCase = true) && !contentType.contains("octet-stream", ignoreCase = true)) {
            return false
        }
        source.request(64)
        val prefix = source.readByteArray(minOf(source.buffer.size, 64L))
        return prefix
            .toString(StandardCharsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .startsWith("#EXTM3U")
    }

    private class ResponseClosingInputStream(
        stream: InputStream,
        private val response: Response,
    ) : FilterInputStream(stream) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private fun HttpUrl.isLoopback(): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

    private fun proxyUrl(
        currentPort: Int,
        token: String,
        target: HttpUrl,
        suffixHint: String?,
    ): String = "http://127.0.0.1:$currentPort/video/$token${target.mediaFileSuffix().ifEmpty { suffixHint.orEmpty() }}"

    private fun String.mediaFileSuffix(): String = toHttpUrlOrNull()?.mediaFileSuffix().orEmpty()

    private fun HttpUrl.mediaFileSuffix(): String {
        val fileName = pathSegments.lastOrNull().orEmpty()
        val extension = mediaExtension.find(fileName)?.groupValues?.get(1) ?: return ""
        return ".${extension.lowercase()}"
    }

    fun clear() {
        synchronized(lock) {
            entriesByToken.clear()
            tokensByKey.clear()
        }
        port = 0
    }
}
