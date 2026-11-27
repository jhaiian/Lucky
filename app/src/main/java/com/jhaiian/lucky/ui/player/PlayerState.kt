package com.jhaiian.lucky.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.DisplayOutput
import com.jhaiian.lucky.engine.Downscaler
import com.jhaiian.lucky.engine.HdrOutput
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.SmoothMotion
import com.jhaiian.lucky.engine.Upscaler
import com.jhaiian.lucky.engine.VideoTransform
import kotlin.math.abs

data class VideoShape(val width: Int, val height: Int, val displayAspect: Double, val rotation: Int) {
    val shownAspect: Double
        get() {
            val aspect = if (displayAspect > 0) displayAspect else if (height > 0) width.toDouble() / height else 1.0
            return if (abs(rotation % 180) == 90) 1.0 / aspect else aspect
        }
}

data class Placement(val scaleX: Float, val scaleY: Float, val translateX: Float, val translateY: Float) {
    fun rect(width: Float, height: Float): Rect {
        val shownWidth = abs(scaleX) * width
        val shownHeight = scaleY * height
        val centerX = width / 2f * (1f + translateX)
        val centerY = height / 2f * (1f + translateY)
        return Rect(centerX - shownWidth / 2f, centerY - shownHeight / 2f, centerX + shownWidth / 2f, centerY + shownHeight / 2f)
    }
}

fun placementFor(width: Float, height: Float, shape: VideoShape?, transform: VideoTransform): Placement {
    if (width <= 0f || height <= 0f || shape == null) return Placement(1f, 1f, 0f, 0f)
    val baseTurns = ((shape.rotation % 360 + 360) % 360) / 90
    val turns = ((baseTurns + transform.turns) % 4 + 4) % 4
    var source = if (shape.displayAspect > 0) shape.displayAspect else 1.0
    if (!source.isFinite() || source <= 0) source = 1.0
    val aspect = when {
        transform.aspect > 0f -> transform.aspect.toDouble()
        turns % 2 == 1 -> 1.0 / source
        else -> source
    }
    val surfaceAspect = width.toDouble() / height
    var (scaleX, scaleY) = when (transform.fit) {
        FitMode.FIT -> if (aspect > surfaceAspect) 1.0 to surfaceAspect / aspect else aspect / surfaceAspect to 1.0
        FitMode.FILL -> if (aspect > surfaceAspect) aspect / surfaceAspect to 1.0 else 1.0 to surfaceAspect / aspect
        FitMode.STRETCH -> 1.0 to 1.0
        FitMode.ORIGINAL -> {
            val pictureHeight = shape.height.coerceAtLeast(1).toDouble()
            val pictureWidth = pictureHeight * source
            val shownHeight = if (turns % 2 == 1) pictureWidth else pictureHeight
            val shownWidth = when {
                transform.aspect > 0f -> shownHeight * aspect
                turns % 2 == 1 -> pictureHeight
                else -> pictureWidth
            }
            shownWidth / width to shownHeight / height
        }
    }
    val zoom = transform.zoom.toDouble().coerceIn(0.1, 12.0)
    val zoomX = transform.zoomX.toDouble().coerceIn(0.1, 12.0)
    val zoomY = transform.zoomY.toDouble().coerceIn(0.1, 12.0)
    scaleX *= zoom * zoomX
    scaleY *= zoom * zoomY
    val limitX = (scaleX - 1.0).coerceAtLeast(0.0)
    val limitY = (scaleY - 1.0).coerceAtLeast(0.0)
    val translateX = transform.panX.toDouble().coerceIn(-limitX, limitX)
    val translateY = transform.panY.toDouble().coerceIn(-limitY, limitY)
    return Placement(
        scaleX = (if (transform.mirror) -scaleX else scaleX).toFloat(),
        scaleY = scaleY.toFloat(),
        translateX = translateX.toFloat(),
        translateY = translateY.toFloat()
    )
}

data class PanLimits(val x: Float, val y: Float) {
    companion object {
        val None = PanLimits(0f, 0f)
    }
}

fun panLimitsFor(width: Float, height: Float, shape: VideoShape?, transform: VideoTransform): PanLimits {
    val centered = placementFor(width, height, shape, transform.copy(panX = 0f, panY = 0f))
    return PanLimits(
        x = (abs(centered.scaleX) - 1f).coerceAtLeast(0f),
        y = (centered.scaleY - 1f).coerceAtLeast(0f)
    )
}

enum class BarScanState { IDLE, SCANNING, REMOVED, NONE, LIMITED, UNCLEAR, FAILED }

class SleepTimer(val endsAtElapsed: Long?, val minutes: Int, val endOfVideo: Boolean)

@Stable
class PlayerUiState(
    fit: FitMode,
    speed: Float,
    repeat: RepeatMode,
    orientation: OrientationMode,
    brightness: Float,
    upscaler: Upscaler,
    downscaler: Downscaler,
    smoothMotion: SmoothMotion,
    motionQuality: MotionQuality,
    hdrOutput: HdrOutput
) {
    var fit by mutableStateOf(fit)
    var hdrOutput by mutableStateOf(hdrOutput)
    var upscaler by mutableStateOf(upscaler)
    var downscaler by mutableStateOf(downscaler)
    var smoothMotion by mutableStateOf(smoothMotion)
    var motionQuality by mutableStateOf(motionQuality)
    var aspect by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var zoomX by mutableFloatStateOf(1f)
    var zoomY by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var turns by mutableIntStateOf(0)
    var mirror by mutableStateOf(false)
    var audioOnly by mutableStateOf(false)
    var barScan by mutableStateOf(BarScanState.IDLE)
    var barApplied by mutableStateOf<VideoTransform?>(null)
    var speed by mutableFloatStateOf(speed)
    var repeat by mutableStateOf(repeat)
    var orientation by mutableStateOf(orientation)
    var manualOrientation by mutableStateOf<Int?>(null)
    var locked by mutableStateOf(false)
    var sleepTimer by mutableStateOf<SleepTimer?>(null)
    var subtitleDelayMs by mutableLongStateOf(0L)
    var subtitleScale by mutableFloatStateOf(1f)
    var audioDelayMs by mutableIntStateOf(0)
    var loopStartMs by mutableStateOf<Long?>(null)
    var loopEndMs by mutableStateOf<Long?>(null)
    var volumeBoost by mutableFloatStateOf(1f)
    var backgroundSession by mutableStateOf(false)
    var brightness by mutableFloatStateOf(brightness)
    var videoShape by mutableStateOf<VideoShape?>(null)
    var playing by mutableStateOf(false)
    var hasVideo by mutableStateOf(false)
    var hdrContent by mutableStateOf(false)
    var displayOutput by mutableStateOf(DisplayOutput.SRGB)
    var togglePlayback: () -> Unit = {}

    val transform: VideoTransform
        get() = VideoTransform(fit, aspect, zoom, zoomX, zoomY, panX, panY, turns, mirror)

    val barStatus: BarScanState
        get() = when (barScan) {
            BarScanState.REMOVED, BarScanState.LIMITED -> if (barApplied == transform) barScan else BarScanState.IDLE
            else -> barScan
        }

    val zoomed: Boolean
        get() = abs(zoom - 1f) > 0.01f || abs(zoomX - 1f) > 0.01f || abs(zoomY - 1f) > 0.01f || panX != 0f || panY != 0f

    fun restoreView(view: VideoTransform) {
        fit = view.fit
        aspect = view.aspect
        zoom = view.zoom
        zoomX = view.zoomX
        zoomY = view.zoomY
        panX = view.panX
        panY = view.panY
        turns = view.turns
        mirror = view.mirror
    }

    fun resetZoom() {
        zoom = 1f
        zoomX = 1f
        zoomY = 1f
        panX = 0f
        panY = 0f
    }

    fun resetForNewVideo() {
        resetZoom()
        barScan = BarScanState.IDLE
        barApplied = null
        turns = 0
        mirror = false
        subtitleDelayMs = 0L
        audioDelayMs = 0
        loopStartMs = null
        loopEndMs = null
    }
}
