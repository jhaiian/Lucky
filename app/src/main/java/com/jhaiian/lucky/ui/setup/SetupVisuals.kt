package com.jhaiian.lucky.ui.setup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val TAU = (2.0 * PI).toFloat()

internal val LocalSetupInstant = compositionLocalOf { false }

private class MarkDot(val path: Path, val center: Offset)

private object LuckyMark {
    val Tile = Color(0xFF2B1460)
    val Fill = Color(0xFFFFC53A)
    const val VIEWPORT = 108f
    val Pivot = Offset(40f, 54f)

    private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

    val body: Path by lazy {
        parse("M42,29.5 Q37.6,33.5 38.8,39.5 Q37.2,46 38.9,53 Q37.3,60 38.8,66.5 Q37.6,72.5 42,78.5 L43.73,78.04 L80.73,57.04 A3.5,3.5 0 0 0 80.73,50.96 L43.73,29.96 Z")
    }

    val dots: List<MarkDot> by lazy {
        listOf(
            MarkDot(
                parse("M57.89,43.5 Q58.53,45.48 57.24,47.09 Q55.94,48.7 53.97,49.42 Q52.01,50.14 50.36,48.82 Q48.71,47.5 48.06,45.51 Q47.42,43.52 48.75,41.93 Q50.09,40.34 52.03,39.68 Q53.97,39.02 55.61,40.27 Q57.25,41.53 57.89,43.5 Z"),
                Offset(53f, 44.6f)
            ),
            MarkDot(
                parse("M56.36,59.68 Q57.74,61.29 58.08,63.38 Q58.43,65.48 56.84,66.91 Q55.25,68.34 53.13,68.66 Q51.01,68.98 49.64,67.34 Q48.26,65.71 47.97,63.64 Q47.68,61.56 49.25,60.19 Q50.82,58.81 52.9,58.44 Q54.98,58.07 56.36,59.68 Z"),
                Offset(53f, 63.5f)
            ),
            MarkDot(
                parse("M70.47,55.57 Q70.06,57.58 68.16,58.35 Q66.25,59.12 64.22,58.71 Q62.19,58.3 61.49,56.37 Q60.78,54.44 61.2,52.46 Q61.62,50.49 63.49,49.76 Q65.37,49.04 67.37,49.39 Q69.38,49.74 70.12,51.65 Q70.87,53.56 70.47,55.57 Z"),
                Offset(65.8f, 54.1f)
            )
        )
    }
}

private object Clapper {
    val Board = Color(0xFF1D1828)
    val Stripe = Color(0xFFF5F2FA)
    val Hinge = Color(0xFF8D88A0)
}

@Composable
internal fun rememberLoopClock(periodMillis: Int, label: String): State<Float> {
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = label
    )
}

@Composable
private fun rememberEntrance(instant: Boolean): Animatable<Float, AnimationVector1D> = remember { Animatable(if (instant) 1f else 0f) }

private fun DrawScope.drawLuckyMark(topLeft: Offset, extent: Float, tile: Float, body: Float, dots: List<Float>) {
    val unit = extent / LuckyMark.VIEWPORT
    if (tile > 0f) {
        val side = extent * tile
        val inset = (extent - side) / 2f
        drawRoundRect(
            color = LuckyMark.Tile,
            topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
            size = Size(side, side),
            cornerRadius = CornerRadius(24f * unit * tile)
        )
    }
    if (body <= 0f) return
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(unit, unit, Offset.Zero)
    }) {
        scale(body, body, LuckyMark.Pivot) {
            drawPath(LuckyMark.body, LuckyMark.Fill)
            LuckyMark.dots.forEachIndexed { index, dot ->
                val value = dots.getOrElse(index) { 0f }
                if (value > 0f) {
                    scale(value, value, dot.center) {
                        drawPath(dot.path, LuckyMark.Tile)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawFilmStrip(centerY: Float, height: Float, travel: Float, band: Color, window: Color, angle: Float) {
    val pitch = height * 1.3f
    val holeWidth = pitch * 0.16f
    val holeHeight = height * 0.1f
    val holeInset = height * 0.07f
    val frameWidth = pitch * 0.8f
    val frameHeight = height * 0.6f
    val reach = size.width * 0.35f
    val top = centerY - height / 2f
    rotate(angle, Offset(size.width / 2f, centerY)) {
        drawRect(band, topLeft = Offset(-reach, top), size = Size(size.width + reach * 2f, height))
        val shift = -(travel * pitch * 6f) % pitch
        var x = -reach + shift - pitch
        while (x < size.width + reach) {
            drawRoundRect(
                color = window,
                topLeft = Offset(x + (pitch - frameWidth) / 2f, centerY - frameHeight / 2f),
                size = Size(frameWidth, frameHeight),
                cornerRadius = CornerRadius(height * 0.06f)
            )
            for (slot in 0..1) {
                val holeX = x + slot * pitch / 2f + (pitch / 2f - holeWidth) / 2f
                drawRoundRect(window, Offset(holeX, top + holeInset), Size(holeWidth, holeHeight), CornerRadius(holeHeight * 0.35f))
                drawRoundRect(window, Offset(holeX, top + height - holeInset - holeHeight), Size(holeWidth, holeHeight), CornerRadius(holeHeight * 0.35f))
            }
            x += pitch
        }
    }
}

private fun dotPulse(clock: Float, index: Int): Float {
    val phase = clock - index * 0.06f
    return if (phase in 0f..0.1f) 1f + 0.18f * sin(PI.toFloat() * phase / 0.1f) else 1f
}

@Composable
internal fun LuckyMarkHero(modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val instant = LocalSetupInstant.current
    val tile = rememberEntrance(instant)
    val body = rememberEntrance(instant)
    val strip = rememberEntrance(instant)
    val dots = remember { List(3) { Animatable(if (instant) 1f else 0f) } }
    val travel = rememberLoopClock(14000, "mark_travel")
    val bob = rememberLoopClock(5200, "mark_bob")
    val pulse = rememberLoopClock(4800, "mark_pulse")
    LaunchedEffect(Unit) {
        if (tile.value >= 1f) return@LaunchedEffect
        launch { tile.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 200f)) }
        delay(220)
        launch { strip.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        launch { body.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
        delay(280)
        dots.forEachIndexed { index, dot ->
            launch {
                delay(index * 110L)
                dot.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
            }
        }
    }
    val band = colors.primary.copy(alpha = 0.16f)
    val window = colors.background
    Canvas(modifier) {
        val extent = size.minDimension * 0.46f
        val float = sin(bob.value * TAU) * extent * 0.025f
        val reveal = strip.value
        if (reveal > 0f) {
            translate(left = (1f - reveal) * size.width * 0.4f) {
                drawFilmStrip(
                    centerY = center.y + extent * 0.04f,
                    height = extent * 0.5f,
                    travel = travel.value,
                    band = band.copy(alpha = band.alpha * reveal),
                    window = window.copy(alpha = reveal),
                    angle = -7f
                )
            }
        }
        val clock = pulse.value
        drawLuckyMark(
            topLeft = Offset(center.x - extent / 2f, center.y - extent / 2f + float),
            extent = extent,
            tile = tile.value,
            body = body.value,
            dots = dots.mapIndexed { index, dot -> dot.value * dotPulse(clock, index) }
        )
    }
}

private fun formatClock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
internal fun PlayerPreviewHero(modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val instant = LocalSetupInstant.current
    val progress = rememberLoopClock(16000, "preview_progress")
    val pop = rememberEntrance(instant)
    LaunchedEffect(Unit) {
        if (pop.value < 1f) pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 160f))
    }
    val elapsed by remember { derivedStateOf { (progress.value * 768f).toInt() } }
    val primary = colors.primary
    val sky = lerp(colors.background, primary, 0.34f)
    val skyLow = lerp(colors.background, primary, 0.1f)
    val far = lerp(colors.cardBackground, primary, 0.5f)
    val near = lerp(colors.background, colors.onSurface, 0.2f)
    val track = colors.divider
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val byHeight = (maxHeight - 128.dp) / 0.5625f + 24.dp
        val cardWidth = minOf(maxWidth * 0.86f, byHeight, 380.dp).coerceAtLeast(180.dp)
        Column(
            modifier = Modifier
                .width(cardWidth)
                .graphicsLayer {
                    val v = pop.value
                    scaleX = 0.9f + 0.1f * v
                    scaleY = 0.9f + 0.1f * v
                    alpha = v.coerceIn(0f, 1f)
                    translationY = (1f - v) * 24.dp.toPx()
                }
                .clip(RoundedCornerShape(26.dp))
                .background(colors.cardBackground)
                .padding(12.dp)
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                drawPreviewScene(progress.value, sky, skyLow, primary, far, near)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.settings_preview_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(primary)
                ) {
                    Icon(Icons.Rounded.Pause, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .drawBehind {
                        val bar = 4.dp.toPx()
                        val y = size.height / 2f
                        val filled = size.width * progress.value
                        drawRoundRect(track, Offset(0f, y - bar / 2f), Size(size.width, bar), CornerRadius(bar / 2f))
                        drawRoundRect(primary, Offset(0f, y - bar / 2f), Size(filled, bar), CornerRadius(bar / 2f))
                        drawCircle(primary, 6.dp.toPx(), Offset(filled.coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), y))
                    }
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = formatClock(elapsed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.settings_preview_duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )
            }
        }
    }
}

private fun DrawScope.drawPreviewScene(t: Float, sky: Color, skyLow: Color, sun: Color, far: Color, near: Color) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(sky, skyLow)))
    val sunAlpha = sin(PI.toFloat() * t).coerceIn(0f, 1f)
    val sunCenter = Offset(w * (0.14f + 0.72f * t), h * (0.66f - 0.4f * sin(PI.toFloat() * t)))
    drawCircle(sun.copy(alpha = 0.22f * sunAlpha), h * 0.2f, sunCenter)
    drawCircle(sun.copy(alpha = sunAlpha), h * 0.11f, sunCenter)
    drawRidge(baseline = 0.6f, amplitude = 0.07f, frequency = 1.3f, phase = t * TAU, color = far)
    drawRidge(baseline = 0.78f, amplitude = 0.06f, frequency = 0.8f, phase = t * TAU * 2f + 1.7f, color = near)
}

private fun DrawScope.drawRidge(baseline: Float, amplitude: Float, frequency: Float, phase: Float, color: Color) {
    val w = size.width
    val h = size.height
    val steps = 48
    val path = Path().apply {
        moveTo(0f, h)
        for (i in 0..steps) {
            val x = w * i / steps
            val wave = sin((i.toFloat() / steps) * TAU * frequency + phase) * 0.7f +
                sin((i.toFloat() / steps) * TAU * frequency * 2.3f + phase * 0.5f) * 0.3f
            lineTo(x, h * (baseline + amplitude * wave))
        }
        lineTo(w, h)
        close()
    }
    drawPath(path, color)
}

private val TileTints = listOf(0.18f, 0.34f, 0.12f, 0.26f, 0.42f, 0.22f)

@Composable
internal fun LibraryScanHero(ready: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val instant = LocalSetupInstant.current
    val tiles = remember { List(TileTints.size) { Animatable(if (instant) 1f else 0f) } }
    val done = remember { Animatable(if (ready) 1f else 0f) }
    val scan = rememberLoopClock(2800, "library_scan")
    LaunchedEffect(Unit) {
        tiles.forEachIndexed { index, tile ->
            if (tile.value < 1f) {
                launch {
                    delay(120L + index * 70L)
                    tile.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 260f))
                }
            }
        }
    }
    LaunchedEffect(ready) {
        done.animateTo(if (ready) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 240f))
    }
    val primary = colors.primary
    val onPrimary = colors.onPrimary
    val base = colors.surfaceVariant
    val pill = colors.background.copy(alpha = 0.7f)
    Canvas(modifier) {
        val gap = 8.dp.toPx()
        val gridWidth = minOf(size.width * 0.78f, size.height * 1.35f)
        val tileWidth = (gridWidth - gap * 2f) / 3f
        val tileHeight = tileWidth * 9f / 16f
        val gridHeight = tileHeight * 2f + gap
        val left = center.x - gridWidth / 2f
        val top = center.y - gridHeight / 2f
        val radius = CornerRadius(tileWidth * 0.08f)
        val finished = done.value.coerceIn(0f, 1f)
        val sweep = top - tileHeight * 0.4f + (gridHeight + tileHeight * 0.8f) * scan.value
        val scanAlpha = 1f - finished
        TileTints.forEachIndexed { index, tint ->
            val v = tiles[index].value
            if (v <= 0f) return@forEachIndexed
            val col = index % 3
            val row = index / 3
            val tileLeft = left + col * (tileWidth + gap)
            val tileTop = top + row * (tileHeight + gap)
            val tileCenter = Offset(tileLeft + tileWidth / 2f, tileTop + tileHeight / 2f)
            val glow = ((1f - abs(tileCenter.y - sweep) / (tileHeight * 1.1f)).coerceIn(0f, 1f) * scanAlpha)
            scale(0.6f + 0.4f * v, 0.6f + 0.4f * v, tileCenter) {
                val alpha = v.coerceIn(0f, 1f)
                drawRoundRect(lerp(base, primary, tint).copy(alpha = alpha), Offset(tileLeft, tileTop), Size(tileWidth, tileHeight), radius)
                if (glow > 0f) {
                    drawRoundRect(primary.copy(alpha = 0.22f * glow * alpha), Offset(tileLeft, tileTop), Size(tileWidth, tileHeight), radius)
                }
                val play = tileHeight * 0.16f
                val playPath = Path().apply {
                    moveTo(tileCenter.x - play * 0.4f, tileCenter.y - play * 0.55f)
                    lineTo(tileCenter.x + play * 0.6f, tileCenter.y)
                    lineTo(tileCenter.x - play * 0.4f, tileCenter.y + play * 0.55f)
                    close()
                }
                drawPath(playPath, onPrimary.copy(alpha = 0.55f * alpha))
                val pillWidth = tileWidth * 0.3f
                val pillHeight = tileHeight * 0.16f
                drawRoundRect(
                    pill.copy(alpha = pill.alpha * alpha),
                    Offset(tileLeft + tileWidth - pillWidth - tileWidth * 0.06f, tileTop + tileHeight - pillHeight - tileHeight * 0.1f),
                    Size(pillWidth, pillHeight),
                    CornerRadius(pillHeight / 2f)
                )
            }
        }
        if (scanAlpha > 0f) {
            clipRect(left - gap, top - gap, left + gridWidth + gap, top + gridHeight + gap) {
                val band = tileHeight * 0.9f
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, primary.copy(alpha = 0.2f * scanAlpha)),
                        startY = sweep - band,
                        endY = sweep
                    ),
                    topLeft = Offset(left - gap, sweep - band),
                    size = Size(gridWidth + gap * 2f, band)
                )
                drawLine(
                    primary.copy(alpha = scanAlpha),
                    Offset(left - gap, sweep),
                    Offset(left + gridWidth + gap, sweep),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        if (done.value > 0f) {
            val badge = tileHeight * 0.42f * done.value
            val badgeCenter = Offset(left + gridWidth - badge * 0.2f, top + badge * 0.2f)
            drawCircle(colors.background, badge + 3.dp.toPx(), badgeCenter)
            drawCircle(primary, badge, badgeCenter)
            val check = Path().apply {
                moveTo(badgeCenter.x - badge * 0.42f, badgeCenter.y + badge * 0.02f)
                lineTo(badgeCenter.x - badge * 0.12f, badgeCenter.y + badge * 0.32f)
                lineTo(badgeCenter.x + badge * 0.44f, badgeCenter.y - badge * 0.28f)
            }
            val measure = PathMeasure()
            measure.setPath(check, false)
            val segment = Path()
            measure.getSegment(0f, measure.length * finished, segment, true)
            drawPath(segment, onPrimary, style = Stroke(badge * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun DrawScope.drawStripes(bounds: Rect, stripe: Float, skew: Float, color: Color) {
    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
        var x = bounds.left - abs(skew) - stripe * 2f
        while (x < bounds.right + abs(skew)) {
            val path = Path().apply {
                moveTo(x, bounds.top)
                lineTo(x + stripe, bounds.top)
                lineTo(x + stripe + skew, bounds.bottom)
                lineTo(x + skew, bounds.bottom)
                close()
            }
            drawPath(path, color)
            x += stripe * 2f
        }
    }
}

@Composable
internal fun ClapperHero(modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val haptics = rememberHaptics()
    val board = remember { Animatable(0f) }
    val arm = remember { Animatable(-30f) }
    val burst = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val bob = rememberLoopClock(5600, "clapper_bob")
    LaunchedEffect(Unit) {
        launch { board.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 200f)) }
        delay(560)
        arm.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
        haptics.click()
        launch { burst.animateTo(1f, tween(560, easing = LinearOutSlowInEasing)) }
        launch { shake.animateTo(1f, tween(380, easing = LinearEasing)) }
        arm.animateTo(-4f, tween(90, easing = LinearOutSlowInEasing))
        arm.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 520f))
    }
    val primary = colors.primary
    val outline = colors.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        val width = minOf(size.width * 0.62f, size.height * 0.95f)
        val armHeight = width * 0.15f
        val gap = armHeight * 0.14f
        val bodyHeight = width * 0.56f
        val total = armHeight + gap + bodyHeight
        val pop = board.value
        val float = sin(bob.value * TAU) * width * 0.015f
        val s = shake.value
        val jolt = if (s > 0f && s < 1f) sin(s * TAU * 3f) * (1f - s) * 5.dp.toPx() else 0f
        val left = center.x - width / 2f + jolt
        val top = center.y - total / 2f + float
        val corner = CornerRadius(width * 0.035f)
        val bodyTop = top + armHeight + gap
        val stroke = Stroke(1.dp.toPx())
        scale(0.7f + 0.3f * pop, 0.7f + 0.3f * pop, center) {
            val alpha = pop.coerceIn(0f, 1f)
            val boardColor = Clapper.Board.copy(alpha = alpha)
            val stripeColor = Clapper.Stripe.copy(alpha = alpha)
            drawRoundRect(boardColor, Offset(left, bodyTop), Size(width, bodyHeight), corner)
            drawRoundRect(outline.copy(alpha = outline.alpha * alpha), Offset(left, bodyTop), Size(width, bodyHeight), corner, style = stroke)
            val bandHeight = armHeight * 0.9f
            drawStripes(
                Rect(left, bodyTop, left + width, bodyTop + bandHeight),
                stripe = armHeight * 0.9f,
                skew = -armHeight * 0.55f,
                color = stripeColor
            )
            val lineHeight = bodyHeight * 0.07f
            val lineLeft = left + width * 0.08f
            listOf(0.56f to primary, 0.4f to Clapper.Stripe.copy(alpha = 0.4f), 0.48f to Clapper.Stripe.copy(alpha = 0.4f))
                .forEachIndexed { index, (fraction, tone) ->
                    val y = bodyTop + bandHeight + bodyHeight * (0.2f + index * 0.18f)
                    drawRoundRect(
                        tone.copy(alpha = tone.alpha * alpha),
                        Offset(lineLeft, y),
                        Size(width * fraction, lineHeight),
                        CornerRadius(lineHeight / 2f)
                    )
                }
            val hinge = Offset(left + armHeight * 0.1f, top + armHeight + gap / 2f)
            rotate(arm.value, hinge) {
                drawRoundRect(boardColor, Offset(left, top), Size(width, armHeight), corner)
                drawRoundRect(outline.copy(alpha = outline.alpha * alpha), Offset(left, top), Size(width, armHeight), corner, style = stroke)
                drawStripes(
                    Rect(left, top, left + width, top + armHeight),
                    stripe = armHeight * 0.9f,
                    skew = armHeight * 0.55f,
                    color = stripeColor
                )
            }
            drawCircle(Clapper.Hinge.copy(alpha = alpha), armHeight * 0.2f, hinge)
            val b = burst.value
            if (b > 0f && b < 1f) {
                val origin = Offset(left + width, top + armHeight + gap / 2f)
                val rayStroke = 3.dp.toPx()
                for (i in 0 until 5) {
                    val angle = (-62f + i * 22f) * PI.toFloat() / 180f
                    val start = width * (0.05f + 0.1f * b)
                    val length = width * 0.09f * (1f - 0.5f * b)
                    val direction = Offset(cos(angle), sin(angle))
                    drawLine(
                        primary.copy(alpha = 1f - b),
                        origin + direction * start,
                        origin + direction * (start + length),
                        strokeWidth = rayStroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
