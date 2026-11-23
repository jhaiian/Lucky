package com.jhaiian.lucky.engine

import android.os.ParcelFileDescriptor
import android.view.Surface
import org.json.JSONObject

object LuckyEngine {

    private val loadFailure: Throwable? = try {
        System.loadLibrary("lucky")
        null
    } catch (error: Throwable) {
        error
    }

    val isAvailable: Boolean
        get() = loadFailure == null

    fun engineInfo(): EngineInfo? {
        if (!isAvailable) return null
        return runCatching { EngineInfo.fromJson(JSONObject(nativeEngineInfo())) }.getOrNull()
    }

    fun probe(detachedFd: Int): ProbeResult {
        if (!isAvailable) {
            runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
            return ProbeResult.Failure(null)
        }
        return runCatching {
            val json = JSONObject(nativeProbe(detachedFd))
            val media = json.optJSONObject("media")
            if (media != null) {
                ProbeResult.Success(MediaInfo.fromJson(media))
            } else {
                ProbeResult.Failure(json.stringOrNull("error"))
            }
        }.getOrElse { ProbeResult.Failure(it.message) }
    }

    internal fun createPlayer(detachedFd: Int, sink: AudioSink?, preferHardware: Boolean, renderer: Int, autoplay: Boolean, preload: Boolean): Long {
        if (!isAvailable) {
            runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
            return 0L
        }
        return nativePlayerCreate(detachedFd, sink, preferHardware, renderer, autoplay, preload)
    }

    internal fun createThumbnailer(detachedFd: Int): Long {
        if (!isAvailable) {
            runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
            return 0L
        }
        return nativeThumbnailerCreate(detachedFd)
    }

    internal fun thumbnailerFrame(handle: Long, positionMs: Long, maxWidth: Int, maxHeight: Int): IntArray? =
        nativeThumbnailerFrame(handle, positionMs, maxWidth, maxHeight)

    internal fun releaseThumbnailer(handle: Long) = nativeThumbnailerRelease(handle)

    internal fun play(handle: Long) = nativePlayerPlay(handle)

    internal fun activate(handle: Long) = nativePlayerActivate(handle)

    internal fun pause(handle: Long) = nativePlayerPause(handle)

    internal fun seek(handle: Long, positionMs: Long, exact: Boolean) = nativePlayerSeek(handle, positionMs, exact)

    internal fun stepFrame(handle: Long, forward: Boolean) = nativePlayerStepFrame(handle, forward)

    internal fun requestScreenshot(handle: Long): Long = nativePlayerRequestScreenshot(handle)

    internal fun pollScreenshot(handle: Long, ticket: Long): IntArray? = nativePlayerPollScreenshot(handle, ticket)

    internal fun setLoopRange(handle: Long, startMs: Long, endMs: Long) = nativePlayerSetLoopRange(handle, startMs, endMs)

    internal fun clearLoopRange(handle: Long) = nativePlayerClearLoopRange(handle)

    internal fun setSurface(handle: Long, surface: Surface?) = nativePlayerSetSurface(handle, surface)

    internal fun redraw(handle: Long) = nativePlayerRedraw(handle)

    internal fun status(handle: Long): String = nativePlayerStatus(handle)

    internal fun release(handle: Long) = nativePlayerRelease(handle)

    internal fun selectAudio(handle: Long, index: Int) = nativePlayerSelectAudio(handle, index)

    internal fun selectSubtitle(handle: Long, index: Int) = nativePlayerSelectSubtitle(handle, index)

    internal fun subtitles(handle: Long, afterId: Long): String = nativePlayerSubtitles(handle, afterId)

    internal fun subtitleBitmap(handle: Long, id: Long, rect: Int): IntArray? = nativePlayerSubtitleBitmap(handle, id, rect)

    internal fun setViewTransform(handle: Long, fit: Int, aspect: Float, zoom: Float, zoomX: Float, zoomY: Float, panX: Float, panY: Float, turns: Int, mirror: Boolean) =
        nativePlayerSetViewTransform(handle, fit, aspect, zoom, zoomX, zoomY, panX, panY, turns, mirror)

    internal fun setSpeed(handle: Long, speed: Float) = nativePlayerSetSpeed(handle, speed)

    internal fun setScaler(handle: Long, mode: Int) = nativePlayerSetScaler(handle, mode)

    internal fun setDownscaler(handle: Long, mode: Int) = nativePlayerSetDownscaler(handle, mode)

    internal fun setAntiring(handle: Long, strength: Float) = nativePlayerSetAntiring(handle, strength)

    internal fun setSmoothMotion(handle: Long, mode: Int) = nativePlayerSetSmoothMotion(handle, mode)

    internal fun setAudioEffects(
        handle: Long,
        delayMs: Int,
        gain: Float,
        night: Boolean,
        dialogue: Boolean,
        channels: Int,
        balance: Float,
        normalize: Boolean,
        bands: FloatArray
    ) = nativePlayerSetAudioEffects(handle, delayMs, gain, night, dialogue, channels, balance, normalize, bands)

    internal fun setAudioOutput(handle: Long, mode: Int, passthrough: Int) = nativePlayerSetAudioOutput(handle, mode, passthrough)

    internal fun beginAudio(handle: Long) = nativePlayerBeginAudio(handle)

    internal fun audioRouteChanged(handle: Long) = nativePlayerAudioRouteChanged(handle)

    internal fun setMotion(
        handle: Long,
        level: Int,
        protection: Int,
        scene: Int,
        target: Int,
        fps: Int,
        anime: Boolean,
        lowFpsOnly: Boolean,
        learned: Int
    ) = nativePlayerSetMotion(handle, level, protection, scene, target, fps, anime, lowFpsOnly, learned)

    internal fun setPerformance(handle: Long, autoQuality: Boolean, fastDecode: Boolean, softwareFallback: Boolean, learnedCost: Float) =
        nativePlayerSetPerformance(handle, autoQuality, fastDecode, softwareFallback, learnedCost)

    internal fun setDeinterlace(handle: Long, mode: Int) = nativePlayerSetDeinterlace(handle, mode)

    internal fun setDisplay(
        handle: Long,
        hdr: Boolean,
        wideColor: Boolean,
        adaptive: Boolean,
        maxNits: Float,
        averageNits: Float,
        minNits: Float,
        headroom: Float
    ) = nativePlayerSetDisplay(handle, hdr, wideColor, adaptive, maxNits, averageNits, minNits, headroom)

    internal fun addSubtitle(handle: Long, fd: Int, name: String): Int = nativePlayerAddSubtitle(handle, fd, name)

    internal fun renderSubtitles(handle: Long, timeMs: Long, width: Int, height: Int, scale: Float, lift: Float, force: Boolean): IntArray? =
        nativePlayerRenderSubtitles(handle, timeMs, width, height, scale, lift, force)

    internal fun setSubtitleStyle(
        handle: Long,
        font: String,
        size: Float,
        primary: Int,
        outline: Int,
        back: Int,
        bold: Boolean,
        outlineWidth: Float,
        shadow: Float,
        margin: Float,
        boxed: Boolean
    ) = nativePlayerSetSubtitleStyle(handle, font, size, primary, outline, back, bold, outlineWidth, shadow, margin, boxed)

    internal fun setTextSubtitles(handle: Long, libass: Boolean) = nativePlayerSetTextSubtitles(handle, libass)

    @JvmStatic
    private external fun nativeEngineInfo(): String

    @JvmStatic
    private external fun nativeProbe(fd: Int): String

    @JvmStatic
    private external fun nativePlayerCreate(fd: Int, sink: AudioSink?, preferHardware: Boolean, renderer: Int, autoplay: Boolean, preload: Boolean): Long

    @JvmStatic
    private external fun nativePlayerPlay(handle: Long)

    @JvmStatic
    private external fun nativePlayerActivate(handle: Long)

    @JvmStatic
    private external fun nativePlayerPause(handle: Long)

    @JvmStatic
    private external fun nativePlayerSeek(handle: Long, positionMs: Long, exact: Boolean)

    @JvmStatic
    private external fun nativePlayerStepFrame(handle: Long, forward: Boolean)

    @JvmStatic
    private external fun nativePlayerRequestScreenshot(handle: Long): Long

    @JvmStatic
    private external fun nativePlayerPollScreenshot(handle: Long, ticket: Long): IntArray?

    @JvmStatic
    private external fun nativePlayerSetLoopRange(handle: Long, startMs: Long, endMs: Long)

    @JvmStatic
    private external fun nativePlayerClearLoopRange(handle: Long)

    @JvmStatic
    private external fun nativePlayerSetSurface(handle: Long, surface: Surface?)

    @JvmStatic
    private external fun nativePlayerRedraw(handle: Long)

    @JvmStatic
    private external fun nativePlayerStatus(handle: Long): String

    @JvmStatic
    private external fun nativePlayerRelease(handle: Long)

    @JvmStatic
    private external fun nativePlayerSelectAudio(handle: Long, index: Int)

    @JvmStatic
    private external fun nativePlayerSelectSubtitle(handle: Long, index: Int)

    @JvmStatic
    private external fun nativePlayerSubtitles(handle: Long, afterId: Long): String

    @JvmStatic
    private external fun nativePlayerSubtitleBitmap(handle: Long, id: Long, rect: Int): IntArray?

    @JvmStatic
    private external fun nativePlayerSetViewTransform(handle: Long, fit: Int, aspect: Float, zoom: Float, zoomX: Float, zoomY: Float, panX: Float, panY: Float, turns: Int, mirror: Boolean)

    @JvmStatic
    private external fun nativePlayerSetSpeed(handle: Long, speed: Float)

    @JvmStatic
    private external fun nativePlayerSetScaler(handle: Long, mode: Int)

    @JvmStatic
    private external fun nativePlayerSetDownscaler(handle: Long, mode: Int)

    @JvmStatic
    private external fun nativePlayerSetPerformance(handle: Long, autoQuality: Boolean, fastDecode: Boolean, softwareFallback: Boolean, learnedCost: Float)

    @JvmStatic
    private external fun nativePlayerSetSmoothMotion(handle: Long, mode: Int)

    @JvmStatic
    private external fun nativePlayerSetAudioEffects(
        handle: Long,
        delayMs: Int,
        gain: Float,
        night: Boolean,
        dialogue: Boolean,
        channels: Int,
        balance: Float,
        normalize: Boolean,
        bands: FloatArray
    )

    @JvmStatic
    private external fun nativePlayerSetAudioOutput(handle: Long, mode: Int, passthrough: Int)

    @JvmStatic
    private external fun nativePlayerBeginAudio(handle: Long)

    @JvmStatic
    private external fun nativePlayerAudioRouteChanged(handle: Long)

    @JvmStatic
    private external fun nativePlayerSetMotion(
        handle: Long,
        level: Int,
        protection: Int,
        scene: Int,
        target: Int,
        fps: Int,
        anime: Boolean,
        lowFpsOnly: Boolean,
        learned: Int
    )

    @JvmStatic
    private external fun nativePlayerSetAntiring(handle: Long, strength: Float)

    @JvmStatic
    private external fun nativePlayerSetDeinterlace(handle: Long, mode: Int)

    @JvmStatic
    private external fun nativePlayerSetDisplay(
        handle: Long,
        hdr: Boolean,
        wideColor: Boolean,
        adaptive: Boolean,
        maxNits: Float,
        averageNits: Float,
        minNits: Float,
        headroom: Float
    )

    @JvmStatic
    private external fun nativePlayerAddSubtitle(handle: Long, fd: Int, name: String): Int

    @JvmStatic
    private external fun nativePlayerRenderSubtitles(
        handle: Long,
        timeMs: Long,
        width: Int,
        height: Int,
        scale: Float,
        lift: Float,
        force: Boolean
    ): IntArray?

    @JvmStatic
    private external fun nativePlayerSetSubtitleStyle(
        handle: Long,
        font: String,
        size: Float,
        primary: Int,
        outline: Int,
        back: Int,
        bold: Boolean,
        outlineWidth: Float,
        shadow: Float,
        margin: Float,
        boxed: Boolean
    )

    @JvmStatic
    private external fun nativePlayerSetTextSubtitles(handle: Long, libass: Boolean)

    @JvmStatic
    private external fun nativeThumbnailerCreate(fd: Int): Long

    @JvmStatic
    private external fun nativeThumbnailerFrame(handle: Long, positionMs: Long, maxWidth: Int, maxHeight: Int): IntArray?

    @JvmStatic
    private external fun nativeThumbnailerRelease(handle: Long)
}
