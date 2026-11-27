package com.jhaiian.lucky.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class TrackInfo(
    val index: Int,
    val codec: String,
    val language: String?,
    val title: String?,
    val channels: Int?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val external: Boolean = false
) {
    companion object {
        fun listFrom(array: JSONArray?): List<TrackInfo> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                add(
                    TrackInfo(
                        index = json.optInt("index"),
                        codec = json.optString("codec"),
                        language = json.stringOrNull("language"),
                        title = json.stringOrNull("title"),
                        channels = json.intOrNull("channels"),
                        isDefault = json.optBoolean("isDefault"),
                        isForced = json.optBoolean("isForced"),
                        external = json.optBoolean("external")
                    )
                )
            }
        }
    }
}

data class SubtitleRect(val x: Int, val y: Int, val width: Int, val height: Int)

data class SubtitleStyle(
    val font: String = "sans-serif",
    val size: Float = 0.06f,
    val primaryColor: Int = 0xFFFFFFFF.toInt(),
    val outlineColor: Int = 0xFF000000.toInt(),
    val backColor: Int = 0xCC000000.toInt(),
    val bold: Boolean = false,
    val outlineWidth: Float = 0.0036f,
    val shadow: Float = 0f,
    val bottomMargin: Float = 0.06f,
    val box: Boolean = false
)

data class Chapter(val startMs: Long, val endMs: Long?, val title: String?) {
    companion object {
        fun listFrom(array: JSONArray?): List<Chapter> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                add(
                    Chapter(
                        startMs = json.optLong("startMs"),
                        endMs = if (json.isNull("endMs") || !json.has("endMs")) null else json.optLong("endMs"),
                        title = json.optString("title").takeIf { json.has("title") && !json.isNull("title") && it.isNotBlank() }
                    )
                )
            }
        }
    }
}

sealed interface SubtitleFrame {
    data object Unchanged : SubtitleFrame
    data object Empty : SubtitleFrame
    class Image(val x: Int, val y: Int, val bitmap: Bitmap) : SubtitleFrame
}

data class SubtitleCue(
    val id: Long,
    val startMs: Long,
    val endMs: Long?,
    val text: String?,
    val frameWidth: Int,
    val frameHeight: Int,
    val rects: List<SubtitleRect>
) {
    companion object {
        fun from(json: JSONObject): SubtitleCue {
            val rects = buildList {
                val array = json.optJSONArray("rects")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val rect = array.optJSONObject(i) ?: continue
                        add(SubtitleRect(rect.optInt("x"), rect.optInt("y"), rect.optInt("width"), rect.optInt("height")))
                    }
                }
            }
            return SubtitleCue(
                id = json.optLong("id"),
                startMs = json.optLong("startMs"),
                endMs = json.longOrNull("endMs"),
                text = json.stringOrNull("text"),
                frameWidth = json.optInt("frameWidth"),
                frameHeight = json.optInt("frameHeight"),
                rects = rects
            )
        }
    }
}

data class SubtitleBatch(val generation: Long, val revision: Long, val cues: List<SubtitleCue>)

enum class PlaybackState {
    OPENING, PAUSED, PLAYING, BUFFERING, ENDED, ERROR;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OPENING
    }
}

data class PlayerStatus(
    val state: PlaybackState = PlaybackState.OPENING,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val seekable: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val displayAspect: Double = 0.0,
    val rotation: Int = 0,
    val frameRate: Double? = null,
    val videoCodec: String? = null,
    val videoDecoder: String? = null,
    val decoderKind: String? = null,
    val pixelFormat: String? = null,
    val hdrFormat: String? = null,
    val displayOutput: DisplayOutput = DisplayOutput.SRGB,
    val renderMs: Double = 0.0,
    val renderPeakMs: Double = 0.0,
    val costCap: Double = 0.0,
    val smoothBlending: Boolean = false,
    val learnedCost: Double = -1.0,
    val motionLevel: Int = 0,
    val motionScale: Int = 0,
    val outputFps: Double = 0.0,
    val learnedMotion: Int = 0,
    val activeKernel: Int = 0,
    val scalingDirection: Int = 0,
    val decoderCatchUp: Int = 0,
    val deinterlaced: Boolean = false,
    val renderer: String? = null,
    val audioCodec: String? = null,
    val audioDecoder: String? = null,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioOutput: AudioOutputKind? = null,
    val audioPassthrough: PassthroughCodec? = null,
    val hasVideo: Boolean = true,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    val assActive: Boolean = false,
    val textAss: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val decodedFrames: Long = 0L,
    val presentedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val decoderFallbacks: Int = 0,
    val error: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject) = PlayerStatus(
            state = PlaybackState.parse(json.stringOrNull("state")),
            positionMs = json.optLong("positionMs"),
            durationMs = json.longOrNull("durationMs"),
            seekable = json.optBoolean("seekable"),
            videoWidth = json.optInt("videoWidth"),
            videoHeight = json.optInt("videoHeight"),
            displayAspect = json.optDouble("displayAspect", 0.0),
            rotation = json.optInt("rotation"),
            frameRate = json.doubleOrNull("frameRate"),
            videoCodec = json.stringOrNull("videoCodec"),
            videoDecoder = json.stringOrNull("videoDecoder"),
            decoderKind = json.stringOrNull("decoderKind"),
            pixelFormat = json.stringOrNull("pixelFormat"),
            hdrFormat = json.stringOrNull("hdrFormat"),
            displayOutput = DisplayOutput.fromCode(json.optInt("displayOutput")),
            renderMs = json.optDouble("renderMs", 0.0),
            renderPeakMs = json.optDouble("renderPeakMs", 0.0),
            costCap = json.optDouble("costCap", 0.0),
            smoothBlending = json.optBoolean("smoothBlending"),
            learnedCost = json.optDouble("learnedCost", -1.0),
            motionLevel = json.optInt("motionLevel", 0),
            motionScale = json.optInt("motionScale", 0),
            outputFps = json.optDouble("outputFps", 0.0),
            learnedMotion = json.optInt("learnedMotion", 0),
            activeKernel = json.optInt("activeKernel", 0),
            scalingDirection = json.optInt("scalingDirection", 0),
            decoderCatchUp = json.optInt("decoderCatchUp", 0),
            deinterlaced = json.optBoolean("deinterlaced"),
            renderer = json.stringOrNull("renderer"),
            audioCodec = json.stringOrNull("audioCodec"),
            audioDecoder = json.stringOrNull("audioDecoder"),
            audioSampleRate = json.intOrNull("audioSampleRate"),
            audioChannels = json.intOrNull("audioChannels"),
            audioOutput = AudioOutputKind.parse(json.stringOrNull("audioOutput")),
            audioPassthrough = PassthroughCodec.fromKey(json.stringOrNull("audioPassthrough")),
            hasVideo = json.optBoolean("hasVideo", true),
            audioTracks = TrackInfo.listFrom(json.optJSONArray("audioTracks")),
            subtitleTracks = TrackInfo.listFrom(json.optJSONArray("subtitleTracks")),
            selectedAudio = json.intOrNull("selectedAudio"),
            selectedSubtitle = json.intOrNull("selectedSubtitle"),
            assActive = json.optBoolean("assActive"),
            textAss = json.optBoolean("textAss"),
            chapters = Chapter.listFrom(json.optJSONArray("chapters")),
            decodedFrames = json.optLong("decodedFrames"),
            presentedFrames = json.optLong("presentedFrames"),
            droppedFrames = json.optLong("droppedFrames"),
            decoderFallbacks = json.optInt("decoderFallbacks"),
            error = json.stringOrNull("error")
        )
    }
}

enum class RendererPreference(val code: Int) {
    OPENGL(0), VULKAN(1);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OPENGL
    }
}

enum class DisplayOutput {
    SRGB, DISPLAY_P3, HDR10;

    companion object {
        fun fromCode(code: Int) = when (code) {
            1, 3 -> DISPLAY_P3
            2 -> HDR10
            else -> SRGB
        }
    }
}

enum class HdrOutput {
    AUTO, ALWAYS, OFF;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

data class DisplayOptions(
    val hdr: Boolean,
    val wideColor: Boolean,
    val adaptiveToneMapping: Boolean,
    val maxNits: Float = 0f,
    val averageNits: Float = 0f,
    val minNits: Float = 0f,
    val headroom: Float = 0f
)

enum class ScaleFilter(val kernel: Int) {
    BILINEAR(0),
    NEAREST(1),
    HERMITE(2),
    GAUSSIAN(3),
    MITCHELL(4),
    ROBIDOUX(5),
    BICUBIC(6),
    ROBIDOUX_SHARP(7),
    SPLINE16(8),
    SPLINE36(9),
    SPLINE64(10),
    LANCZOS2(11),
    LANCZOS3(12),
    LANCZOS4(13),
    GINSENG(14),
    AREA(15),
    TRIANGLE(16),
    EWA_ROBIDOUX(17),
    EWA_LANCZOS(18),
    EWA_LANCZOS_SHARP(19),
    EWA_LANCZOS4(20);

    companion object {
        fun fromKernel(kernel: Int) = entries.firstOrNull { it.kernel == kernel } ?: BILINEAR
    }
}

enum class ScaleTier { LIGHT, MEDIUM, HEAVY, VERY_HEAVY }

enum class SmoothMotion(val code: Int) {
    OFF(0), SHARP(1), SMOOTH(2), INTERPOLATE(3);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OFF
    }
}

enum class Deinterlace(val code: Int) {
    OFF(0), AUTO(1), ON(2);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class AudioChannels(val code: Int) {
    STEREO(0), MONO(1), SWAPPED(2);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STEREO
    }
}

enum class AudioNormalize {
    OFF, NORMALIZE;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OFF
    }
}

enum class AudioOutputMode(val code: Int) {
    STEREO(0), AUTO(1);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class PassthroughCodec(val bit: Int, val key: String) {
    AC3(1, "ac3"),
    EAC3(2, "eac3"),
    DTS(4, "dts"),
    DTS_HD(8, "dtshd"),
    TRUEHD(16, "truehd");

    companion object {
        fun fromKey(value: String?) = entries.firstOrNull { it.key == value }
    }
}

enum class AudioOutputKind {
    STEREO, MONO, MULTICHANNEL, PASSTHROUGH;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class AudioOutputOptions(
    val mode: AudioOutputMode = AudioOutputMode.AUTO,
    val passthrough: Boolean = false,
    val codecs: Set<PassthroughCodec> = PassthroughCodec.entries.toSet()
) {
    val passthroughMask: Int
        get() = if (passthrough) codecs.fold(0) { mask, codec -> mask or codec.bit } else 0
}

enum class EqPreset(val gains: List<Float>) {
    FLAT(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS(listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE(listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    VOCAL(listOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f)),
    CINEMA(listOf(4f, 3f, 2f, 0f, -1f, 1f, 2f, 2f, 1f, 1f)),
    ROCK(listOf(5f, 4f, 2f, -1f, -2f, -1f, 1f, 3f, 4f, 5f)),
    POP(listOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, -1f, -1f)),
    JAZZ(listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    CLASSICAL(listOf(4f, 3f, 2f, 1f, 0f, 0f, 0f, 2f, 3f, 4f)),
    CUSTOM(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f));

    companion object {
        val bandFrequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val MAX_GAIN = 12f

        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FLAT
    }
}

data class AudioEffects(
    val delayMs: Int = 0,
    val gain: Float = 1f,
    val night: Boolean = false,
    val dialogue: Boolean = false,
    val channels: AudioChannels = AudioChannels.STEREO,
    val balance: Float = 0f,
    val normalize: Boolean = false,
    val equalizer: List<Float> = EqPreset.FLAT.gains
)

enum class MotionQuality(val code: Int) {
    AUTO(0), FAST(1), BALANCED(2), HIGH(3);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

enum class MotionTarget(val code: Int) {
    SCREEN(0), DOUBLE(4), CUSTOM(5);

    companion object {
        const val MIN_FPS = 30
        const val DEFAULT_FPS = 60

        fun parse(value: String?): MotionTarget = when {
            value == null -> SCREEN
            value.startsWith("FPS_", ignoreCase = true) -> CUSTOM
            else -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SCREEN
        }

        fun legacyFps(value: String?): Int? =
            value?.takeIf { it.startsWith("FPS_", ignoreCase = true) }?.substring(4)?.toIntOrNull()
    }
}

data class MotionOptions(
    val quality: MotionQuality = MotionQuality.AUTO,
    val target: MotionTarget = MotionTarget.SCREEN,
    val targetFps: Int = MotionTarget.DEFAULT_FPS,
    val anime: Boolean = false,
    val lowFrameRateOnly: Boolean = false,
    val learned: Int = 0
)

enum class RefreshMatch {
    SEAMLESS, ALWAYS;

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SEAMLESS
    }
}

enum class Upscaler(val filter: ScaleFilter, val reads: Int, val cost: Int, val round: Boolean = false) {
    NEAREST(ScaleFilter.NEAREST, 1, 1),
    BILINEAR(ScaleFilter.BILINEAR, 1, 1),
    HERMITE(ScaleFilter.HERMITE, 4, 4),
    GAUSSIAN(ScaleFilter.GAUSSIAN, 16, 16),
    MITCHELL(ScaleFilter.MITCHELL, 16, 16),
    ROBIDOUX(ScaleFilter.ROBIDOUX, 16, 16),
    BICUBIC(ScaleFilter.BICUBIC, 16, 16),
    ROBIDOUX_SHARP(ScaleFilter.ROBIDOUX_SHARP, 16, 16),
    SPLINE16(ScaleFilter.SPLINE16, 16, 16),
    LANCZOS(ScaleFilter.LANCZOS2, 16, 16),
    EWA_ROBIDOUX(ScaleFilter.EWA_ROBIDOUX, 13, 20, round = true),
    SPLINE36(ScaleFilter.SPLINE36, 36, 36),
    GINSENG(ScaleFilter.GINSENG, 36, 36),
    LANCZOS3(ScaleFilter.LANCZOS3, 36, 36),
    SPLINE64(ScaleFilter.SPLINE64, 64, 64),
    LANCZOS4(ScaleFilter.LANCZOS4, 64, 64),
    EWA_LANCZOS_SHARP(ScaleFilter.EWA_LANCZOS_SHARP, 32, 100, round = true),
    EWA_LANCZOS(ScaleFilter.EWA_LANCZOS, 33, 110, round = true),
    EWA_LANCZOS4(ScaleFilter.EWA_LANCZOS4, 44, 128, round = true);

    val code: Int get() = filter.kernel

    val tier: ScaleTier
        get() = when {
            cost <= 4 -> ScaleTier.LIGHT
            cost <= 20 -> ScaleTier.MEDIUM
            cost <= 36 -> ScaleTier.HEAVY
            else -> ScaleTier.VERY_HEAVY
        }

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BILINEAR
    }
}

enum class Downscaler(val filter: ScaleFilter, val reads: Int) {
    BILINEAR(ScaleFilter.BILINEAR, 1),
    AREA(ScaleFilter.AREA, 4),
    TRIANGLE(ScaleFilter.TRIANGLE, 9),
    HERMITE(ScaleFilter.HERMITE, 9),
    GAUSSIAN(ScaleFilter.GAUSSIAN, 36),
    MITCHELL(ScaleFilter.MITCHELL, 36),
    ROBIDOUX(ScaleFilter.ROBIDOUX, 36),
    BICUBIC(ScaleFilter.BICUBIC, 36),
    SPLINE16(ScaleFilter.SPLINE16, 36),
    LANCZOS(ScaleFilter.LANCZOS2, 36),
    SPLINE36(ScaleFilter.SPLINE36, 64),
    LANCZOS3(ScaleFilter.LANCZOS3, 64),
    SPLINE64(ScaleFilter.SPLINE64, 64),
    LANCZOS4(ScaleFilter.LANCZOS4, 64);

    val code: Int get() = filter.kernel

    val tier: ScaleTier
        get() = when {
            reads <= 4 -> ScaleTier.LIGHT
            reads <= 9 -> ScaleTier.MEDIUM
            reads <= 36 -> ScaleTier.HEAVY
            else -> ScaleTier.VERY_HEAVY
        }

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BILINEAR
    }
}

enum class FitMode(val code: Int) {
    FIT(0), FILL(1), STRETCH(2), ORIGINAL(3);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FIT
    }
}

data class VideoTransform(
    val fit: FitMode = FitMode.FIT,
    val aspect: Float = 0f,
    val zoom: Float = 1f,
    val zoomX: Float = 1f,
    val zoomY: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val turns: Int = 0,
    val mirror: Boolean = false
)

class LuckyPlayer private constructor(handle: Long, private val sink: AudioSink, private val route: AudioRoute) : AutoCloseable {

    private val handle = AtomicLong(handle)
    private val lock = ReentrantReadWriteLock()

    val isReleased: Boolean
        get() = handle.get() == 0L

    private inline fun <T> withHandle(fallback: T, action: (Long) -> T): T {
        if (handle.get() == 0L) return fallback
        return lock.read {
            val current = handle.get()
            if (current == 0L) fallback else action(current)
        }
    }

    fun play() {
        withHandle(Unit) { LuckyEngine.play(it) }
    }

    fun pause() {
        withHandle(Unit) { LuckyEngine.pause(it) }
    }

    fun activate() {
        withHandle(Unit) { LuckyEngine.activate(it) }
    }

    fun seekTo(positionMs: Long, exact: Boolean = true) {
        withHandle(Unit) { LuckyEngine.seek(it, positionMs.coerceAtLeast(0L), exact) }
    }

    fun stepFrame(forward: Boolean) {
        withHandle(Unit) { LuckyEngine.stepFrame(it, forward) }
    }

    suspend fun screenshot(): Bitmap? {
        val ticket = withHandle(0L) { LuckyEngine.requestScreenshot(it) }
        if (ticket == 0L) return null
        repeat(SCREENSHOT_POLLS) {
            val data = withHandle<IntArray?>(null) { LuckyEngine.pollScreenshot(it, ticket) } ?: return null
            when (data.firstOrNull()) {
                1 -> {
                    if (data.size < 3) return null
                    val width = data[1]
                    val height = data[2]
                    if (width <= 0 || height <= 0 || data.size < 3 + width * height) return null
                    return Bitmap.createBitmap(data, 3, width, width, height, Bitmap.Config.ARGB_8888)
                }
                2 -> return null
            }
            delay(SCREENSHOT_POLL_MS)
        }
        return null
    }

    fun setLoopRange(startMs: Long, endMs: Long) {
        withHandle(Unit) { LuckyEngine.setLoopRange(it, startMs.coerceAtLeast(0L), endMs) }
    }

    fun clearLoopRange() {
        withHandle(Unit) { LuckyEngine.clearLoopRange(it) }
    }

    fun setSurface(surface: Surface?) {
        withHandle(Unit) { LuckyEngine.setSurface(it, surface) }
    }

    fun redraw() {
        withHandle(Unit) { LuckyEngine.redraw(it) }
    }

    fun status(): PlayerStatus {
        val json = withHandle<String?>(null) { LuckyEngine.status(it) } ?: return PlayerStatus()
        return runCatching { PlayerStatus.fromJson(JSONObject(json)) }.getOrDefault(PlayerStatus())
    }

    fun selectAudio(index: Int) {
        withHandle(Unit) { LuckyEngine.selectAudio(it, index) }
    }

    fun selectSubtitle(index: Int?) {
        withHandle(Unit) { LuckyEngine.selectSubtitle(it, index ?: -1) }
    }

    fun subtitles(afterRevision: Long): SubtitleBatch? {
        val raw = withHandle<String?>(null) { LuckyEngine.subtitles(it, afterRevision) } ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("cues")
            val cues = buildList {
                if (array != null) {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { add(SubtitleCue.from(it)) }
                    }
                }
            }
            SubtitleBatch(json.optLong("generation"), json.optLong("revision"), cues)
        }.getOrNull()
    }

    fun subtitleBitmap(id: Long, rect: Int): Bitmap? {
        val data = withHandle<IntArray?>(null) { LuckyEngine.subtitleBitmap(it, id, rect) } ?: return null
        if (data.size < 2) return null
        val width = data[0]
        val height = data[1]
        if (width <= 0 || height <= 0 || data.size < 2 + width * height) return null
        return Bitmap.createBitmap(data.copyOfRange(2, 2 + width * height), width, height, Bitmap.Config.ARGB_8888)
    }

    fun setViewTransform(transform: VideoTransform) {
        withHandle(Unit) {
            LuckyEngine.setViewTransform(
                it,
                transform.fit.code,
                transform.aspect,
                transform.zoom,
                transform.zoomX,
                transform.zoomY,
                transform.panX,
                transform.panY,
                transform.turns,
                transform.mirror
            )
        }
    }

    fun addSubtitle(context: Context, uri: Uri, name: String): Int? {
        if (isReleased) return null
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val fd = descriptor.detachFd()
        val id = withHandle(RELEASED) { LuckyEngine.addSubtitle(it, fd, name) }
        if (id == RELEASED) {
            runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
            return null
        }
        return id.takeIf { it >= 0 }
    }

    fun renderSubtitles(timeMs: Long, width: Int, height: Int, scale: Float, lift: Float, force: Boolean): SubtitleFrame {
        if (isReleased) return SubtitleFrame.Empty
        val data = withHandle<IntArray?>(null) { LuckyEngine.renderSubtitles(it, timeMs, width, height, scale, lift, force) }
            ?: return SubtitleFrame.Unchanged
        return when (data.firstOrNull()) {
            1 -> {
                if (data.size < 5) return SubtitleFrame.Unchanged
                val w = data[3]
                val h = data[4]
                if (w <= 0 || h <= 0 || data.size < 5 + w * h) return SubtitleFrame.Empty
                SubtitleFrame.Image(data[1], data[2], Bitmap.createBitmap(data, 5, w, w, h, Bitmap.Config.ARGB_8888))
            }
            2 -> SubtitleFrame.Empty
            else -> SubtitleFrame.Unchanged
        }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        withHandle(Unit) {
            LuckyEngine.setSubtitleStyle(
                it,
                style.font,
                style.size,
                style.primaryColor,
                style.outlineColor,
                style.backColor,
                style.bold,
                style.outlineWidth,
                style.shadow,
                style.bottomMargin,
                style.box
            )
        }
    }

    fun setTextSubtitles(libass: Boolean) {
        withHandle(Unit) { LuckyEngine.setTextSubtitles(it, libass) }
    }

    fun setUpscaler(upscaler: Upscaler) {
        withHandle(Unit) { LuckyEngine.setScaler(it, upscaler.code) }
    }

    fun setDownscaler(downscaler: Downscaler) {
        withHandle(Unit) { LuckyEngine.setDownscaler(it, downscaler.code) }
    }

    fun setPerformance(autoQuality: Boolean, fastDecode: Boolean, softwareFallback: Boolean, learnedCost: Float) {
        withHandle(Unit) { LuckyEngine.setPerformance(it, autoQuality, fastDecode, softwareFallback, learnedCost) }
    }

    fun setDeinterlace(mode: Deinterlace) {
        withHandle(Unit) { LuckyEngine.setDeinterlace(it, mode.code) }
    }

    fun setSmoothMotion(mode: SmoothMotion) {
        withHandle(Unit) { LuckyEngine.setSmoothMotion(it, mode.code) }
    }

    fun setAudioEffects(effects: AudioEffects) {
        withHandle(Unit) {
            LuckyEngine.setAudioEffects(
                it,
                effects.delayMs,
                effects.gain,
                effects.night,
                effects.dialogue,
                effects.channels.code,
                effects.balance,
                effects.normalize,
                effects.equalizer.toFloatArray()
            )
        }
    }

    fun setAudioOutput(options: AudioOutputOptions) {
        withHandle(Unit) { LuckyEngine.setAudioOutput(it, options.mode.code, options.passthroughMask) }
    }

    fun beginAudio() {
        withHandle(Unit) { LuckyEngine.beginAudio(it) }
    }

    private fun watchRoute() {
        route.listen { withHandle(Unit) { LuckyEngine.audioRouteChanged(it) } }
    }

    fun setMotion(options: MotionOptions) {
        withHandle(Unit) {
            LuckyEngine.setMotion(
                it,
                options.quality.code,
                options.target.code,
                options.targetFps,
                options.anime,
                options.lowFrameRateOnly,
                options.learned
            )
        }
    }

    fun setAntiring(percent: Int) {
        withHandle(Unit) { LuckyEngine.setAntiring(it, percent.coerceIn(0, 100) / 100f) }
    }

    fun setDisplay(options: DisplayOptions) {
        withHandle(Unit) {
            LuckyEngine.setDisplay(
                it,
                options.hdr,
                options.wideColor,
                options.adaptiveToneMapping,
                options.maxNits,
                options.averageNits,
                options.minNits,
                options.headroom
            )
        }
    }

    fun setSpeed(speed: Float) {
        withHandle(Unit) {
            sink.setSpeed(speed)
            LuckyEngine.setSpeed(it, speed)
        }
    }

    override fun close() {
        route.stopListening()
        val current = handle.getAndSet(0L)
        if (current != 0L) lock.write { LuckyEngine.release(current) }
    }

    companion object {
        private const val RELEASED = Int.MIN_VALUE
        private const val SCREENSHOT_POLLS = 100
        private const val SCREENSHOT_POLL_MS = 30L

        fun open(
            context: Context,
            uri: Uri,
            preferHardware: Boolean = true,
            renderer: RendererPreference = RendererPreference.OPENGL,
            autoplay: Boolean = true,
            preload: Boolean = false
        ): LuckyPlayer? {
            if (!LuckyEngine.isAvailable) return null
            val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            val route = AudioRoute(context)
            val sink = AudioSink(route)
            val handle = LuckyEngine.createPlayer(descriptor.detachFd(), sink, preferHardware, renderer.code, autoplay, preload)
            return if (handle != 0L) LuckyPlayer(handle, sink, route).also { it.watchRoute() } else null
        }
    }
}
