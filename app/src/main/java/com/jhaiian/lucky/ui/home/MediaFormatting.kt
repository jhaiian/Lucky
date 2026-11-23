package com.jhaiian.lucky.ui.home

import android.content.Context
import android.text.format.Formatter
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.HdrFormat
import com.jhaiian.lucky.engine.StreamKind
import com.jhaiian.lucky.engine.VideoInfo
import java.util.Locale
import java.util.MissingResourceException

private val iso3ToLanguage: Map<String, String> by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { code ->
            try {
                put(Locale.forLanguageTag(code).isO3Language, code)
            } catch (_: MissingResourceException) {
            }
        }
        put("fre", "fr")
        put("ger", "de")
        put("dut", "nl")
        put("chi", "zh")
        put("cze", "cs")
        put("gre", "el")
        put("per", "fa")
        put("rum", "ro")
        put("slo", "sk")
        put("alb", "sq")
        put("arm", "hy")
        put("baq", "eu")
        put("bur", "my")
        put("geo", "ka")
        put("ice", "is")
        put("mac", "mk")
        put("mao", "mi")
        put("may", "ms")
        put("tib", "bo")
        put("wel", "cy")
        put("fil", "fil")
    }
}

fun languageDisplayName(code: String): String {
    val normalized = code.trim().lowercase(Locale.ROOT)
    val tag = when {
        normalized.length == 3 -> iso3ToLanguage[normalized] ?: normalized
        else -> normalized
    }
    val locale = Locale.forLanguageTag(tag)
    val display = locale.getDisplayName(Locale.getDefault())
    if (display.isBlank() || display.equals(tag, ignoreCase = true)) return code
    return display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

fun formatSize(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

fun formatBitRate(context: Context, bitsPerSecond: Long): String {
    return if (bitsPerSecond >= 1_000_000) {
        context.getString(R.string.value_bitrate_mbps, String.format(Locale.getDefault(), "%.1f", bitsPerSecond / 1_000_000.0))
    } else {
        context.getString(R.string.value_bitrate_kbps, (bitsPerSecond / 1000).toString())
    }
}

fun formatFrameRate(context: Context, fps: Double): String {
    val rounded = if (kotlin.math.abs(fps - Math.round(fps)) < 0.005) {
        Math.round(fps).toString()
    } else {
        String.format(Locale.getDefault(), "%.3f", fps).trimEnd('0').trimEnd('.', ',')
    }
    return context.getString(R.string.value_frame_rate, rounded)
}

fun formatSampleRate(context: Context, hertz: Int): String {
    val khz = hertz / 1000.0
    val text = if (hertz % 1000 == 0) (hertz / 1000).toString() else String.format(Locale.getDefault(), "%.1f", khz)
    return context.getString(R.string.value_sample_rate_khz, text)
}

fun hdrLabel(context: Context, video: VideoInfo): String? = when (video.hdr) {
    HdrFormat.DOLBY_VISION -> video.dolbyVisionProfile?.let {
        context.getString(R.string.hdr_dolby_vision_profile, it)
    } ?: context.getString(R.string.hdr_dolby_vision)
    HdrFormat.HDR10 -> context.getString(R.string.hdr_hdr10)
    HdrFormat.HLG -> context.getString(R.string.hdr_hlg)
    null -> null
}

fun streamKindLabel(context: Context, kind: StreamKind, coverArt: Boolean): String {
    if (coverArt) return context.getString(R.string.track_cover_art)
    return context.getString(
        when (kind) {
            StreamKind.VIDEO -> R.string.track_video
            StreamKind.AUDIO -> R.string.track_audio
            StreamKind.SUBTITLE -> R.string.track_subtitle
            StreamKind.ATTACHMENT -> R.string.track_attachment
            StreamKind.DATA -> R.string.track_data
            StreamKind.UNKNOWN -> R.string.track_other
        }
    )
}

fun codecDisplayName(codec: String): String = when (codec.lowercase(Locale.ROOT)) {
    "h264" -> "H.264 / AVC"
    "hevc" -> "H.265 / HEVC"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vp8" -> "VP8"
    "vvc" -> "H.266 / VVC"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4 Part 2"
    "aac" -> "AAC"
    "ac3" -> "Dolby Digital"
    "eac3" -> "Dolby Digital Plus"
    "truehd" -> "Dolby TrueHD"
    "dts" -> "DTS"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "flac" -> "FLAC"
    "mp3" -> "MP3"
    "alac" -> "ALAC"
    "subrip" -> "SubRip (SRT)"
    "ass" -> "ASS"
    "webvtt" -> "WebVTT"
    "hdmv_pgs_subtitle" -> "PGS"
    "dvd_subtitle" -> "VobSub"
    "mov_text" -> "MP4 Timed Text"
    else -> codec.uppercase(Locale.ROOT)
}
