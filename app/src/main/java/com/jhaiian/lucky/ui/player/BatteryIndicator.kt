package com.jhaiian.lucky.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jhaiian.lucky.R

enum class BatteryMood { NORMAL, CHARGING, FULL, SAVER, LOW, CRITICAL }

data class BatteryStatus(val level: Int, val charging: Boolean, val plugged: Boolean, val saver: Boolean) {
    val mood: BatteryMood
        get() = when {
            plugged && level >= 100 -> BatteryMood.FULL
            charging -> BatteryMood.CHARGING
            level <= 10 -> BatteryMood.CRITICAL
            level <= 20 -> BatteryMood.LOW
            saver -> BatteryMood.SAVER
            else -> BatteryMood.NORMAL
        }
}

data class ClockStatus(val time: String, val battery: BatteryStatus?)

private val ChargingGreen = Color(0xFF5BD47A)
private val SaverAmber = Color(0xFFFFCA28)
private val LowOrange = Color(0xFFFF9F43)
private val CriticalRed = Color(0xFFFF5C5C)

internal fun batteryAccent(mood: BatteryMood): Color = when (mood) {
    BatteryMood.CHARGING, BatteryMood.FULL -> ChargingGreen
    BatteryMood.SAVER -> SaverAmber
    BatteryMood.LOW -> LowOrange
    BatteryMood.CRITICAL -> CriticalRed
    BatteryMood.NORMAL -> Color.White
}

@Composable
internal fun batteryDescription(status: BatteryStatus): String = when (status.mood) {
    BatteryMood.FULL -> stringResource(R.string.player_battery_full)
    BatteryMood.CHARGING -> stringResource(R.string.player_battery_charging, status.level)
    BatteryMood.LOW, BatteryMood.CRITICAL -> stringResource(R.string.player_battery_low, status.level)
    BatteryMood.SAVER -> stringResource(R.string.player_battery_saver, status.level)
    BatteryMood.NORMAL -> stringResource(R.string.player_battery_level, status.level)
}

@Composable
internal fun rememberBatteryStatus(context: Context, active: Boolean): BatteryStatus? {
    var status by remember { mutableStateOf(readBattery(context, null)) }
    DisposableEffect(context, active) {
        if (active) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    status = readBattery(context, intent?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED })
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            val sticky = runCatching {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }.getOrNull()
            status = readBattery(context, sticky)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        } else {
            onDispose { }
        }
    }
    return status
}

private fun readBattery(context: Context, intent: Intent?): BatteryStatus? {
    val source = intent ?: runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val rawLevel = source?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = source?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val level = if (rawLevel >= 0 && scale > 0) {
        (rawLevel * 100f / scale).toInt()
    } else {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
    }
    if (level !in 0..100) return null
    val state = source?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = (source?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
        (plugged && state == BatteryManager.BATTERY_STATUS_FULL)
    val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    return BatteryStatus(level, charging, plugged, saver)
}

@Composable
internal fun BatteryGlyph(status: BatteryStatus, modifier: Modifier = Modifier) {
    val mood = status.mood
    val accent by animateColorAsState(batteryAccent(mood), tween(300), label = "batteryAccent")
    val outline by animateColorAsState(
        if (mood == BatteryMood.NORMAL || mood == BatteryMood.CHARGING || mood == BatteryMood.FULL) Color.White else batteryAccent(mood),
        tween(300),
        label = "batteryOutline"
    )
    val fraction by animateFloatAsState(status.level / 100f, tween(500), label = "batteryLevel")
    val animated = mood == BatteryMood.CHARGING || mood == BatteryMood.CRITICAL
    val pulse = if (animated) {
        val transition = rememberInfiniteTransition(label = "batteryPulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(if (mood == BatteryMood.CRITICAL) 650 else 1100), RepeatMode.Reverse),
            label = "batteryPulseValue"
        ).value
    } else {
        1f
    }
    val showBolt = mood == BatteryMood.CHARGING || mood == BatteryMood.FULL
    Canvas(
        modifier
            .size(width = 25.dp, height = 12.dp)
            .graphicsLayer { alpha = if (mood == BatteryMood.CRITICAL) 0.45f + 0.55f * pulse else 1f }
    ) {
        val stroke = 1.3.dp.toPx()
        val capWidth = 2.dp.toPx()
        val gap = 1.dp.toPx()
        val bodyWidth = size.width - capWidth - gap
        val bodyHeight = size.height
        drawRoundRect(
            color = outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyWidth - stroke, bodyHeight - stroke),
            cornerRadius = CornerRadius(3.2.dp.toPx()),
            style = Stroke(stroke)
        )
        val capHeight = bodyHeight * 0.42f
        drawRoundRect(
            color = outline,
            topLeft = Offset(bodyWidth + gap, (bodyHeight - capHeight) / 2f),
            size = Size(capWidth, capHeight),
            cornerRadius = CornerRadius(capWidth / 2f)
        )
        val inset = stroke + 1.2.dp.toPx()
        val innerWidth = bodyWidth - inset * 2f
        val innerHeight = bodyHeight - inset * 2f
        val innerRadius = CornerRadius(1.6.dp.toPx())
        val shown = fraction.coerceIn(if (status.level > 0) 0.08f else 0f, 1f)
        if (mood == BatteryMood.CHARGING && shown < 1f) {
            drawRoundRect(
                color = accent.copy(alpha = 0.32f * pulse),
                topLeft = Offset(inset, inset),
                size = Size(innerWidth, innerHeight),
                cornerRadius = innerRadius
            )
        }
        drawRoundRect(
            color = accent,
            topLeft = Offset(inset, inset),
            size = Size(innerWidth * shown, innerHeight),
            cornerRadius = innerRadius
        )
        if (showBolt) {
            val boltHeight = bodyHeight * 0.92f
            val boltWidth = boltHeight * 0.62f
            val left = (bodyWidth - boltWidth) / 2f
            val top = (bodyHeight - boltHeight) / 2f
            val bolt = Path().apply {
                moveTo(left + boltWidth * 0.62f, top)
                lineTo(left + boltWidth * 0.08f, top + boltHeight * 0.57f)
                lineTo(left + boltWidth * 0.46f, top + boltHeight * 0.57f)
                lineTo(left + boltWidth * 0.36f, top + boltHeight)
                lineTo(left + boltWidth * 0.92f, top + boltHeight * 0.41f)
                lineTo(left + boltWidth * 0.54f, top + boltHeight * 0.41f)
                close()
            }
            drawPath(bolt, Color.Black.copy(alpha = 0.55f), style = Stroke(1.6.dp.toPx()))
            drawPath(bolt, Color.White)
        }
    }
}
