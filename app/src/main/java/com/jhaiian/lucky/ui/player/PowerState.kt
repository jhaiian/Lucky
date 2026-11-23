package com.jhaiian.lucky.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.jhaiian.lucky.engine.MotionOptions
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.SmoothMotion

data class PowerState(val saver: Boolean, val thermal: Int)

internal const val THERMAL_MODERATE = 2
internal const val THERMAL_SEVERE = 3

val thermalSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

@RequiresApi(Build.VERSION_CODES.Q)
private object ThermalWatch {
    fun current(manager: PowerManager): Int = manager.currentThermalStatus

    fun listen(context: Context, manager: PowerManager, onChange: (Int) -> Unit): () -> Unit {
        val listener = PowerManager.OnThermalStatusChangedListener { status -> onChange(status) }
        manager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        return { runCatching { manager.removeThermalStatusListener(listener) } }
    }
}

@Composable
internal fun rememberPowerState(): PowerState {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var state by remember(manager) {
        mutableStateOf(
            PowerState(
                saver = manager?.isPowerSaveMode == true,
                thermal = if (manager != null && thermalSupported) runCatching { ThermalWatch.current(manager) }.getOrDefault(0) else 0
            )
        )
    }
    DisposableEffect(manager) {
        if (manager == null) {
            return@DisposableEffect onDispose { }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state = state.copy(saver = manager.isPowerSaveMode)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val stopThermal = if (thermalSupported) {
            runCatching { ThermalWatch.listen(context, manager) { status -> state = state.copy(thermal = status) } }.getOrNull()
        } else {
            null
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            stopThermal?.invoke()
        }
    }
    return state
}

internal data class EffectiveMotion(val mode: SmoothMotion, val options: MotionOptions)

internal fun effectiveMotion(mode: SmoothMotion, config: PlayerConfig, power: PowerState, quality: MotionQuality, learned: Int): EffectiveMotion {
    val base = MotionOptions(
        quality = quality,
        target = config.motionTarget,
        targetFps = config.motionTargetFps,
        protection = config.motionProtection,
        scene = config.motionScene,
        anime = config.motionAnime,
        lowFrameRateOnly = config.motionLowFpsOnly,
        learned = learned
    )
    if (mode != SmoothMotion.INTERPOLATE) return EffectiveMotion(mode, base)
    if (config.motionBatterySaver && power.saver) return EffectiveMotion(SmoothMotion.OFF, base)
    if (config.motionHeat && power.thermal >= THERMAL_SEVERE) return EffectiveMotion(SmoothMotion.OFF, base)
    if (config.motionHeat && power.thermal >= THERMAL_MODERATE) return EffectiveMotion(mode, base.copy(quality = MotionQuality.FAST))
    return EffectiveMotion(mode, base)
}
