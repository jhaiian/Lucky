package com.jhaiian.lucky.ui.player

import com.jhaiian.lucky.engine.BlackBars
import com.jhaiian.lucky.engine.VideoTransform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class BarZoom(val zoomX: Float, val zoomY: Float, val panX: Float, val panY: Float, val stretchLimited: Boolean) {
    val changed: Boolean
        get() = zoomX > 1.005f || zoomY > 1.005f
}

private const val MAX_STRETCH = 1.12
private const val MAX_BAR_ZOOM = 4.0
private const val MIN_PICTURE = 0.2
private const val ZOOM_NOISE = 1.005

private fun snapZoom(value: Double): Double =
    if (value < ZOOM_NOISE) 1.0 else ceil(value * 100.0 - 1e-6) / 100.0

fun barZoomFor(width: Float, height: Float, shape: VideoShape?, transform: VideoTransform, bars: BlackBars): BarZoom? {
    if (width <= 0f || height <= 0f || shape == null) return null
    val base = placementFor(width, height, shape, transform.copy(zoom = 1f, zoomX = 1f, zoomY = 1f, panX = 0f, panY = 0f))
    val baseX = abs(base.scaleX).toDouble()
    val baseY = base.scaleY.toDouble()
    if (baseX <= 0.0 || baseY <= 0.0) return null
    var shown = bars.rotated(transform.turns)
    if (transform.mirror) shown = shown.mirrored()
    val pictureWidth = 1.0 - shown.left - shown.right
    val pictureHeight = 1.0 - shown.top - shown.bottom
    if (pictureWidth < MIN_PICTURE || pictureHeight < MIN_PICTURE) return null
    var zoomX = (1.0 / (baseX * pictureWidth)).coerceIn(1.0, MAX_BAR_ZOOM)
    var zoomY = (1.0 / (baseY * pictureHeight)).coerceIn(1.0, MAX_BAR_ZOOM)
    val limited = max(zoomX, zoomY) / min(zoomX, zoomY) > MAX_STRETCH
    if (limited) {
        val uniform = min(zoomX, zoomY)
        zoomX = uniform
        zoomY = uniform
    }
    zoomX = snapZoom(zoomX)
    zoomY = snapZoom(zoomY)
    val panX = -(baseX * zoomX) * (shown.left - shown.right)
    val panY = -(baseY * zoomY) * (shown.top - shown.bottom)
    return BarZoom(zoomX.toFloat(), zoomY.toFloat(), panX.toFloat(), panY.toFloat(), limited)
}
