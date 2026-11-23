package com.jhaiian.lucky.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.jhaiian.lucky.engine.DisplayOptions
import com.jhaiian.lucky.engine.DisplayOutput
import com.jhaiian.lucky.engine.HdrOutput
import com.jhaiian.lucky.engine.MotionTarget

data class DisplayCaps(
    val hdr10: Boolean,
    val wideGamut: Boolean,
    val maxNits: Float = 0f,
    val averageNits: Float = 0f,
    val minNits: Float = 0f
) {
    companion object {
        val None = DisplayCaps(hdr10 = false, wideGamut = false)
    }
}

private fun Activity.currentDisplay(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { display }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }

private fun hdrTypes(display: Display): IntArray =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        display.mode.supportedHdrTypes
    } else {
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
    }

internal fun displayCaps(activity: Activity?): DisplayCaps {
    val display = activity?.currentDisplay() ?: return DisplayCaps.None
    val types = runCatching { hdrTypes(display) }.getOrDefault(IntArray(0))
    val hdr = runCatching { display.isHdr }.getOrDefault(false)
    val hdr10 = hdr && (Display.HdrCapabilities.HDR_TYPE_HDR10 in types || Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types)
    val wide = runCatching { activity.resources.configuration.isScreenWideColorGamut }.getOrDefault(false)
    val luminance = runCatching { display.hdrCapabilities }.getOrNull()
    fun nits(value: Float?): Float = value?.takeIf { it.isFinite() && it > 0f } ?: 0f
    return DisplayCaps(
        hdr10 = hdr10,
        wideGamut = wide,
        maxNits = nits(luminance?.desiredMaxLuminance),
        averageNits = nits(luminance?.desiredMaxAverageLuminance),
        minNits = nits(luminance?.desiredMinLuminance)
    )
}

private fun roundedHeadroom(ratio: Float): Float =
    if (ratio.isFinite() && ratio > 1f) kotlin.math.round(ratio * 4f) / 4f else 0f

@Composable
internal fun rememberHdrHeadroom(activity: Activity?): Float {
    var headroom by remember(activity) { mutableFloatStateOf(0f) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        DisposableEffect(activity) {
            val display = activity?.currentDisplay()
            if (activity == null || display == null || !runCatching { display.isHdrSdrRatioAvailable }.getOrDefault(false)) {
                onDispose { }
            } else {
                headroom = roundedHeadroom(runCatching { display.hdrSdrRatio }.getOrDefault(0f))
                val listener = java.util.function.Consumer<Display> { changed ->
                    headroom = roundedHeadroom(runCatching { changed.hdrSdrRatio }.getOrDefault(0f))
                }
                val registered = runCatching {
                    display.registerHdrSdrRatioChangedListener(ContextCompat.getMainExecutor(activity), listener)
                }.isSuccess
                onDispose {
                    if (registered) runCatching { display.unregisterHdrSdrRatioChangedListener(listener) }
                }
            }
        }
    }
    return headroom
}

internal fun displayOptions(
    caps: DisplayCaps,
    config: PlayerConfig,
    hdrOutput: HdrOutput = config.hdrOutput,
    headroom: Float = 0f
): DisplayOptions = DisplayOptions(
    hdr = when (hdrOutput) {
        HdrOutput.AUTO -> caps.hdr10
        HdrOutput.ALWAYS -> true
        HdrOutput.OFF -> false
    },
    wideColor = config.wideColor && caps.wideGamut,
    adaptiveToneMapping = config.adaptiveToneMapping,
    maxNits = caps.maxNits,
    averageNits = caps.averageNits,
    minNits = caps.minNits,
    headroom = headroom
)

internal fun applyColorMode(activity: Activity?, output: DisplayOutput) {
    val window = activity?.window ?: return
    val mode = when (output) {
        DisplayOutput.HDR10 -> ActivityInfo.COLOR_MODE_HDR
        DisplayOutput.DISPLAY_P3 -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        DisplayOutput.SRGB -> ActivityInfo.COLOR_MODE_DEFAULT
    }
    if (window.colorMode != mode) runCatching { window.colorMode = mode }
}

data class DolbyVision(val profile: Int, val compatibility: Int) {
    val needsReshape: Boolean get() = compatibility == 0

    val label: String get() = if (profile == 8) "$profile.$compatibility" else profile.toString()

    companion object {
        fun parse(format: String?): DolbyVision? {
            val parts = format?.takeIf { it.startsWith("dv:") }?.split(':') ?: return null
            if (parts.size < 3) return null
            val profile = parts[1].toIntOrNull() ?: return null
            val compatibility = parts[2].toIntOrNull() ?: return null
            return DolbyVision(profile, compatibility)
        }
    }
}

internal fun refreshRate(activity: Activity?): Float =
    activity?.currentDisplay()?.let { runCatching { it.refreshRate }.getOrNull() } ?: 0f

internal fun refreshFits(refresh: Float, frameRate: Double): Boolean {
    if (refresh <= 0f || frameRate <= 0.0) return false
    val ratio = refresh / frameRate
    return ratio >= 0.99 && kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.02
}

internal fun matchingModeId(activity: Activity?, frameRate: Double): Int {
    val display = activity?.currentDisplay() ?: return 0
    val current = runCatching { display.mode }.getOrNull() ?: return 0
    val candidates = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList()).filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && refreshFits(it.refreshRate, frameRate)
    }
    return candidates.maxByOrNull { it.refreshRate }?.modeId ?: 0
}

internal fun maxRefreshRate(activity: Activity?): Int {
    val display = activity?.currentDisplay() ?: return 120
    val current = runCatching { display.mode }.getOrNull()
    val rates = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList())
        .filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
        .map { kotlin.math.round(it.refreshRate).toInt() }
    return (rates.maxOrNull() ?: 60).coerceAtLeast(60)
}

internal fun interpolationModeId(activity: Activity?, target: MotionTarget, targetFps: Int, frameRate: Double?): Int {
    val display = activity?.currentDisplay() ?: return 0
    val current = runCatching { display.mode }.getOrNull() ?: return 0
    val modes = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList()).filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }
    if (modes.isEmpty()) return 0
    val wanted = when (target) {
        MotionTarget.SCREEN -> return modes.maxByOrNull { it.refreshRate }?.modeId ?: 0
        MotionTarget.DOUBLE -> (frameRate ?: 0.0) * 2.0
        MotionTarget.CUSTOM -> targetFps.toDouble()
    }
    if (wanted <= 0.0) return modes.maxByOrNull { it.refreshRate }?.modeId ?: 0
    val fitting = modes.filter { refreshFits(it.refreshRate, wanted) }
    if (fitting.isNotEmpty()) return fitting.minByOrNull { it.refreshRate }?.modeId ?: 0
    return modes.filter { it.refreshRate >= wanted - 0.5 }.minByOrNull { it.refreshRate }?.modeId
        ?: modes.maxByOrNull { it.refreshRate }?.modeId ?: 0
}

internal fun applyPreferredMode(activity: Activity?, modeId: Int) {
    val window = activity?.window ?: return
    val attributes = window.attributes
    if (attributes.preferredDisplayModeId != modeId) {
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }
}
