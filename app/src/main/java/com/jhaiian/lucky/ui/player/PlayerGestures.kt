package com.jhaiian.lucky.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SwipeKind { SEEK, BRIGHTNESS, VOLUME }

interface PlayerGestureHandler {
    fun onTap()
    fun onDoubleTap(zone: Int)
    fun onHoldStart()
    fun onHoldEnd()
    fun onSwipeStart(kind: SwipeKind)
    fun onSwipe(kind: SwipeKind, delta: Float, extent: Float)
    fun onSwipeEnd(kind: SwipeKind)
    fun onTransform(zoom: Float, panX: Float, panY: Float)
    fun onTransformEnd()
}

data class GestureOptions(
    val locked: Boolean,
    val doubleTap: Boolean,
    val hold: Boolean,
    val swipeSeek: Boolean,
    val swipeBrightness: Boolean,
    val swipeVolume: Boolean,
    val swapSides: Boolean,
    val pinch: Boolean
)

@Composable
fun Modifier.playerGestures(options: GestureOptions, handler: PlayerGestureHandler): Modifier {
    val current by rememberUpdatedState(handler)
    return this
        .pointerInput(options.locked, options.doubleTap, options.hold) {
            coroutineScope {
                var suppressTap = false
                detectTapGestures(
                    onPress = {
                        suppressTap = false
                        if (options.hold && !options.locked) {
                            val job = launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                suppressTap = true
                                current.onHoldStart()
                            }
                            tryAwaitRelease()
                            job.cancel()
                            if (suppressTap) current.onHoldEnd()
                        }
                    },
                    onDoubleTap = if (options.doubleTap && !options.locked) {
                        { offset ->
                            val zone = when {
                                offset.x < size.width / 3f -> -1
                                offset.x > size.width * 2f / 3f -> 1
                                else -> 0
                            }
                            current.onDoubleTap(zone)
                        }
                    } else {
                        null
                    },
                    onTap = {
                        if (!suppressTap) current.onTap()
                    }
                )
            }
        }
        .pointerInput(options) {
            if (options.locked) return@pointerInput
            val edge = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val nearEdge = down.position.y < edge || down.position.y > size.height - edge
                var mode = 0
                var kind: SwipeKind? = null
                var travel = Offset.Zero
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (pressed >= 2 && options.pinch && (mode == 0 || mode == 4 || mode == -1)) {
                        mode = 4
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) current.onTransform(zoom, pan.x, pan.y)
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (mode == 4) {
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id && it.pressed }
                        ?: event.changes.firstOrNull { it.pressed }
                        ?: break
                    val delta = change.positionChange()
                    when (mode) {
                        0 -> {
                            travel += delta
                            if (travel.getDistance() > slop) {
                                val horizontal = abs(travel.x) > abs(travel.y)
                                val left = down.position.x < size.width / 2f
                                val brightnessSide = if (options.swapSides) !left else left
                                kind = when {
                                    nearEdge -> null
                                    horizontal -> SwipeKind.SEEK.takeIf { options.swipeSeek }
                                    brightnessSide -> SwipeKind.BRIGHTNESS.takeIf { options.swipeBrightness }
                                    else -> SwipeKind.VOLUME.takeIf { options.swipeVolume }
                                }
                                val chosen = kind
                                if (chosen == null) {
                                    mode = -1
                                } else {
                                    mode = 1
                                    current.onSwipeStart(chosen)
                                    if (chosen == SwipeKind.SEEK) {
                                        current.onSwipe(chosen, travel.x, size.width.toFloat())
                                    } else {
                                        current.onSwipe(chosen, travel.y, size.height.toFloat())
                                    }
                                    change.consume()
                                }
                            }
                        }
                        1 -> {
                            val chosen = kind ?: break
                            if (chosen == SwipeKind.SEEK) {
                                current.onSwipe(chosen, delta.x, size.width.toFloat())
                            } else {
                                current.onSwipe(chosen, delta.y, size.height.toFloat())
                            }
                            change.consume()
                        }
                    }
                }
                when (mode) {
                    1 -> kind?.let { current.onSwipeEnd(it) }
                    4 -> current.onTransformEnd()
                }
            }
        }
}

sealed interface GestureHint {
    data class Seek(val target: Long, val delta: Long, val duration: Long?) : GestureHint
    data class Level(val kind: SwipeKind, val value: Float) : GestureHint
    data class Zoom(val percent: Int) : GestureHint
    data class Speed(val speed: Float) : GestureHint
    data class Message(val text: String, val icon: ImageVector?) : GestureHint
}

fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).roundToInt() / 100f
    val text = if (rounded % 1f == 0f) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.2f", rounded).trimEnd('0').trimEnd('.', ',')
    }
    return "$text×"
}

@Composable
fun BoxScope.GestureHintOverlay(hint: GestureHint?) {
    val colors = LocalLuckyColors.current
    val shown = remember { HintHolder() }
    if (hint != null) shown.last = hint
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.9f),
        exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.94f),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 72.dp)
    ) {
        val current = shown.last ?: return@AnimatedVisibility
        Row(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (current) {
                is GestureHint.Seek -> {
                    Icon(
                        if (current.delta < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            buildString {
                                append(formatDuration(current.target))
                                current.duration?.let { append(" / ").append(formatDuration(it)) }
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            (if (current.delta < 0) "−" else "+") + formatDuration(abs(current.delta)),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                is GestureHint.Level -> {
                    val icon = when (current.kind) {
                        SwipeKind.BRIGHTNESS -> if (current.value < 0.4f) Icons.Rounded.BrightnessLow else Icons.Rounded.BrightnessHigh
                        else -> if (current.value <= 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp
                    }
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .width(140.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(current.value.coerceIn(0f, 1f))
                                .background(colors.primary)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${(current.value * 100).roundToInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(44.dp)
                    )
                }
                is GestureHint.Zoom -> {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${current.percent}%", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                is GestureHint.Speed -> {
                    Icon(Icons.Rounded.FastForward, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.player_hint_speed, formatSpeed(current.speed)),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                is GestureHint.Message -> {
                    current.icon?.let {
                        Icon(it, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(current.text, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private class HintHolder {
    var last: GestureHint? = null
}

data class TapSeekFeedback(val zone: Int, val seconds: Int, val stamp: Long)

@Composable
fun BoxScope.TapSeekRipple(feedback: TapSeekFeedback?) {
    val colors = LocalLuckyColors.current
    val holder = remember { TapHolder() }
    if (feedback != null) holder.last = feedback
    val last = holder.last
    val alignment = if ((last?.zone ?: 1) < 0) Alignment.CenterStart else Alignment.CenterEnd
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(320)),
        modifier = Modifier
            .align(alignment)
            .fillMaxHeight()
            .fillMaxWidth(0.36f)
    ) {
        val current = holder.last ?: return@AnimatedVisibility
        val leftSide = current.zone < 0
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .clip(
                    if (leftSide) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                )
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (leftSide) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(34.dp)
                )
                Text(
                    stringResource(if (leftSide) R.string.player_hint_rewind_seconds else R.string.player_hint_forward_seconds, current.seconds),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private class TapHolder {
    var last: TapSeekFeedback? = null
}
