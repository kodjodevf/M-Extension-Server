package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnore
import eu.kanade.tachiyomi.network.ProgressListener
import okhttp3.Headers
import rx.subjects.Subject

data class Track(
    val url: String,
    val lang: String,
)

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

open class Video(
    val url: String = "",
    val quality: String = "",
    var videoUrl: String? = null,
    val headers: Headers? = null,
    // "url", "language-label-2", "url2", "language-label-2"
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
) : ProgressListener {
    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(url, quality, videoUrl, headers, subtitleTracks, audioTracks, emptyList())

    // extensions-lib 16 replaced url/quality with videoUrl/videoTitle and turned
    // Video into a data class. Sources built against it call this constructor,
    // read the fields below and use copy(), so keep both shapes working.
    constructor(
        videoUrl: String,
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        internalData: String = "",
        initialized: Boolean = false,
    ) : this(videoUrl, videoTitle, videoUrl, headers, subtitleTracks, audioTracks, timestamps) {
        this.resolution = resolution
        this.bitrate = bitrate
        this.preferred = preferred
        this.mpvArgs = mpvArgs
        this.ffmpegStreamArgs = ffmpegStreamArgs
        this.ffmpegVideoArgs = ffmpegVideoArgs
        this.internalData = internalData
        this.initialized = initialized
    }

    // The lib 16 fields stay out of the JSON sent to the client, which keeps
    // reading url/quality/videoUrl.
    @get:JsonIgnore
    val videoTitle: String get() = quality

    @get:JsonIgnore
    var resolution: Int? = null

    @get:JsonIgnore
    var bitrate: Int? = null

    @get:JsonIgnore
    var preferred: Boolean = false

    @get:JsonIgnore
    var mpvArgs: List<Pair<String, String>> = emptyList()

    @get:JsonIgnore
    var ffmpegStreamArgs: List<Pair<String, String>> = emptyList()

    @get:JsonIgnore
    var ffmpegVideoArgs: List<Pair<String, String>> = emptyList()

    @get:JsonIgnore
    var internalData: String = ""

    @get:JsonIgnore
    var initialized: Boolean = false

    fun copy(
        videoUrl: String = this.videoUrl ?: url,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
    ): Video =
        Video(
            videoUrl,
            videoTitle,
            resolution,
            bitrate,
            headers,
            preferred,
            subtitleTracks,
            audioTracks,
            timestamps,
            mpvArgs,
            ffmpegStreamArgs,
            ffmpegVideoArgs,
            internalData,
            initialized,
        )

    @Transient
    @Volatile
    var status: Int = 0
        set(value) {
            field = value
            statusSubject?.onNext(value)
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var progress: Int = 0
        set(value) {
            progressSubject?.onNext(value)
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var totalBytesDownloaded: Long = 0L

    @Transient
    @Volatile
    var totalContentLength: Long = 0L

    @Transient
    @Volatile
    var bytesDownloaded: Long = 0L
        set(value) {
            totalBytesDownloaded +=
                if (value < field) {
                    value
                } else {
                    value - field
                }
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    private var statusSubject: Subject<Int, Int>? = null

    @Transient
    private var progressSubject: Subject<Int, Int>? = null

    @Transient
    private var statusCallback: ((Video) -> Unit)? = null

    override fun update(
        bytesRead: Long,
        contentLength: Long,
        done: Boolean,
    ) {
        bytesDownloaded = bytesRead
        if (contentLength > totalContentLength) {
            totalContentLength = contentLength
        }
        val newProgress =
            if (totalContentLength > 0) {
                (100 * totalBytesDownloaded / totalContentLength).toInt()
            } else {
                -1
            }
        if (progress != newProgress) progress = newProgress
    }

    fun setStatusSubject(subject: Subject<Int, Int>?) {
        this.statusSubject = subject
    }

    fun setProgressSubject(subject: Subject<Int, Int>?) {
        this.progressSubject = subject
    }

    fun setStatusCallback(f: ((Video) -> Unit)?) {
        statusCallback = f
    }

    companion object {
        const val QUEUE = 0
        const val LOAD_VIDEO = 1
        const val DOWNLOAD_IMAGE = 2
        const val READY = 3
        const val ERROR = 4
    }
}
