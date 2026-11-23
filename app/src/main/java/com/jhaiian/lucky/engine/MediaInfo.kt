package com.jhaiian.lucky.engine

import org.json.JSONObject

internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.longOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

internal fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)

internal fun JSONObject.doubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

data class EngineInfo(
    val engine: String,
    val abi: String,
    val ffmpeg: String,
    val avcodec: String,
    val avformat: String,
    val dav1d: String
) {
    companion object {
        fun fromJson(json: JSONObject) = EngineInfo(
            engine = json.optString("engine"),
            abi = json.optString("abi"),
            ffmpeg = json.optString("ffmpeg"),
            avcodec = json.optString("avcodec"),
            avformat = json.optString("avformat"),
            dav1d = json.optString("dav1d")
        )
    }
}

sealed interface ProbeResult {
    data class Success(val media: MediaInfo) : ProbeResult
    data class Failure(val message: String?) : ProbeResult
}

enum class StreamKind {
    VIDEO, AUDIO, SUBTITLE, ATTACHMENT, DATA, UNKNOWN;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

enum class HdrFormat {
    DOLBY_VISION, HDR10, HLG;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class VideoInfo(
    val width: Int,
    val height: Int,
    val frameRate: Double?,
    val pixelFormat: String?,
    val bitDepth: Int?,
    val colorSpace: String?,
    val colorPrimaries: String?,
    val colorTransfer: String?,
    val colorRange: String?,
    val hdr: HdrFormat?,
    val dolbyVisionProfile: String?,
    val rotation: Int,
    val interlaced: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject) = VideoInfo(
            width = json.optInt("width"),
            height = json.optInt("height"),
            frameRate = json.doubleOrNull("frameRate"),
            pixelFormat = json.stringOrNull("pixelFormat"),
            bitDepth = json.intOrNull("bitDepth"),
            colorSpace = json.stringOrNull("colorSpace"),
            colorPrimaries = json.stringOrNull("colorPrimaries"),
            colorTransfer = json.stringOrNull("colorTransfer"),
            colorRange = json.stringOrNull("colorRange"),
            hdr = HdrFormat.parse(json.stringOrNull("hdr")),
            dolbyVisionProfile = json.stringOrNull("dolbyVisionProfile"),
            rotation = json.optInt("rotation"),
            interlaced = json.optBoolean("interlaced")
        )
    }
}

data class AudioInfo(
    val sampleRate: Int,
    val channels: Int,
    val channelLayout: String?,
    val sampleFormat: String?
) {
    companion object {
        fun fromJson(json: JSONObject) = AudioInfo(
            sampleRate = json.optInt("sampleRate"),
            channels = json.optInt("channels"),
            channelLayout = json.stringOrNull("channelLayout"),
            sampleFormat = json.stringOrNull("sampleFormat")
        )
    }
}

data class StreamInfo(
    val index: Int,
    val kind: StreamKind,
    val codec: String,
    val codecLong: String?,
    val profile: String?,
    val bitRate: Long?,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isCoverArt: Boolean,
    val softwareDecoder: String?,
    val video: VideoInfo?,
    val audio: AudioInfo?
) {
    companion object {
        fun fromJson(json: JSONObject) = StreamInfo(
            index = json.optInt("index"),
            kind = StreamKind.parse(json.stringOrNull("kind")),
            codec = json.optString("codec"),
            codecLong = json.stringOrNull("codecLong"),
            profile = json.stringOrNull("profile"),
            bitRate = json.longOrNull("bitRate"),
            language = json.stringOrNull("language"),
            title = json.stringOrNull("title"),
            isDefault = json.optBoolean("isDefault"),
            isForced = json.optBoolean("isForced"),
            isCoverArt = json.optBoolean("isCoverArt"),
            softwareDecoder = json.stringOrNull("softwareDecoder"),
            video = json.optJSONObject("video")?.let(VideoInfo::fromJson),
            audio = json.optJSONObject("audio")?.let(AudioInfo::fromJson)
        )
    }
}

data class MediaInfo(
    val container: String,
    val containerLong: String?,
    val durationMs: Long?,
    val startTimeMs: Long?,
    val bitRate: Long?,
    val sizeBytes: Long?,
    val seekable: Boolean,
    val title: String?,
    val chapters: Int,
    val streams: List<StreamInfo>
) {
    companion object {
        fun fromJson(json: JSONObject): MediaInfo {
            val array = json.optJSONArray("streams")
            val streams = buildList {
                if (array != null) {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { add(StreamInfo.fromJson(it)) }
                    }
                }
            }
            return MediaInfo(
                container = json.optString("container"),
                containerLong = json.stringOrNull("containerLong"),
                durationMs = json.longOrNull("durationMs"),
                startTimeMs = json.longOrNull("startTimeMs"),
                bitRate = json.longOrNull("bitRate"),
                sizeBytes = json.longOrNull("sizeBytes"),
                seekable = json.optBoolean("seekable"),
                title = json.stringOrNull("title"),
                chapters = json.optInt("chapters"),
                streams = streams
            )
        }
    }
}
