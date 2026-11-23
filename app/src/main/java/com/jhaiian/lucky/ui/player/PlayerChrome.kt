package com.jhaiian.lucky.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlin.math.PI
import kotlin.math.sin

@Immutable
internal data class PlayerPalette(val icon: Color, val accent: Color, val onAccent: Color, val active: Color)

internal val LocalPlayerPalette = compositionLocalOf { PlayerPalette(Color.White, Color.White, Color.Black, Color.White) }

@Composable
internal fun rememberPlayerPalette(icon: PlayerTint, accent: PlayerTint): PlayerPalette {
    val theme = LocalLuckyColors.current.primary
    return remember(icon, accent, theme) {
        val accentColor = accent.resolve(theme)
        val iconColor = icon.resolve(theme)
        PlayerPalette(
            icon = iconColor,
            accent = accentColor,
            onAccent = if (accentColor.luminance() > 0.5f) Color.Black else Color.White,
            active = if (iconColor == accentColor) Color.White else accentColor
        )
    }
}

@Composable
internal fun PlayerSeekBar(
    style: SeekStyle,
    value: Float,
    enabled: Boolean,
    playing: Boolean,
    markers: List<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    loopStart: Float? = null,
    loopEnd: Float? = null
) {
    val palette = LocalPlayerPalette.current
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val track = Color.White.copy(alpha = if (enabled) 0.3f else 0.15f)
    val active = if (enabled) palette.accent else Color.White.copy(alpha = 0.5f)
    val wave by animateFloatAsState(if (playing && style == SeekStyle.SQUIGGLE) 1f else 0f, tween(400), label = "wave")
    val phase = if (style == SeekStyle.SQUIGGLE && playing) {
        val transition = rememberInfiniteTransition(label = "seek")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "phase"
        )
        animated
    } else {
        0f
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures {
                    change((it.x / size.width).coerceIn(0f, 1f))
                    finished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { change((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { finished() },
                    onDragCancel = { finished() },
                    onHorizontalDrag = { pointer, _ ->
                        pointer.consume()
                        change((pointer.position.x / size.width).coerceIn(0f, 1f))
                    }
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val width = size.width
            val center = size.height / 2f
            val progress = value.coerceIn(0f, 1f) * width
            when (style) {
                SeekStyle.SLIDER -> {
                    val thickness = 4.dp.toPx()
                    drawLine(track, Offset(0f, center), Offset(width, center), thickness, StrokeCap.Round)
                    drawLine(active, Offset(0f, center), Offset(progress, center), thickness, StrokeCap.Round)
                    drawMarkers(markers, center, 4.dp.toPx())
                    drawCircle(active, 8.dp.toPx(), Offset(progress, center))
                }
                SeekStyle.THIN -> {
                    val thickness = 3.dp.toPx()
                    drawLine(track, Offset(0f, center), Offset(width, center), thickness, StrokeCap.Round)
                    drawLine(active, Offset(0f, center), Offset(progress, center), thickness, StrokeCap.Round)
                    drawMarkers(markers, center, 3.dp.toPx())
                    drawCircle(active, 6.dp.toPx(), Offset(progress, center))
                }
                SeekStyle.BAR -> {
                    val height = 14.dp.toPx()
                    val radius = CornerRadius(height / 2f)
                    drawRoundRect(track, Offset(0f, center - height / 2f), Size(width, height), radius)
                    if (progress > 0f) {
                        drawRoundRect(active, Offset(0f, center - height / 2f), Size(progress.coerceAtLeast(height), height), radius)
                    }
                    drawMarkers(markers, center, height)
                }
                SeekStyle.SQUIGGLE -> {
                    val stroke = 4.dp.toPx()
                    val amplitude = 4.dp.toPx() * wave
                    val wavelength = 22.dp.toPx()
                    val step = 2.dp.toPx()
                    drawLine(track, Offset(progress, center), Offset(width, center), stroke, StrokeCap.Round)
                    val path = Path()
                    path.moveTo(0f, center)
                    var x = 0f
                    while (x <= progress) {
                        path.lineTo(x, center + amplitude * sin(x / wavelength * 2f * PI.toFloat() - phase))
                        x += step
                    }
                    drawPath(path, active, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    drawMarkers(markers, center, stroke)
                    drawRoundRect(
                        active,
                        Offset(progress - 2.dp.toPx(), center - 10.dp.toPx()),
                        Size(4.dp.toPx(), 20.dp.toPx()),
                        CornerRadius(2.dp.toPx())
                    )
                }
                SeekStyle.DOTS -> {
                    val radius = 2.dp.toPx()
                    val thumb = 7.dp.toPx()
                    var x = radius
                    while (x <= width - radius) {
                        drawCircle(if (x <= progress) active else track, radius, Offset(x, center))
                        x += 10.dp.toPx()
                    }
                    drawCircle(active, thumb, Offset(progress.coerceIn(thumb, (width - thumb).coerceAtLeast(thumb)), center))
                }
            }
            drawLoopRange(loopStart, loopEnd, center, palette.active)
        }
    }
}

private fun DrawScope.drawLoopRange(start: Float?, end: Float?, center: Float, color: Color) {
    if (start == null && end == null) return
    val width = size.width
    if (start != null && end != null && end > start) {
        val height = 12.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(start * width, center - height / 2f),
            size = Size((end - start) * width, height),
            cornerRadius = CornerRadius(height / 2f)
        )
    }
    val tick = 3.dp.toPx()
    val cap = 5.dp.toPx()
    val reach = 11.dp.toPx()
    if (start != null) {
        val x = start * width
        drawRoundRect(color, Offset(x - tick / 2f, center - reach), Size(tick, reach * 2f), CornerRadius(tick / 2f))
        drawRoundRect(color, Offset(x - tick / 2f, center - reach), Size(cap + tick, tick), CornerRadius(tick / 2f))
    }
    if (end != null) {
        val x = end * width
        drawRoundRect(color, Offset(x - tick / 2f, center - reach), Size(tick, reach * 2f), CornerRadius(tick / 2f))
        drawRoundRect(color, Offset(x - cap - tick / 2f, center + reach - tick), Size(cap + tick, tick), CornerRadius(tick / 2f))
    }
}

private fun DrawScope.drawMarkers(markers: List<Float>, center: Float, height: Float) {
    val width = 2.dp.toPx()
    markers.forEach { marker ->
        drawRect(
            color = Color.Black.copy(alpha = 0.55f),
            topLeft = Offset(marker * size.width - width / 2f, center - height / 2f),
            size = Size(width, height)
        )
    }
}
