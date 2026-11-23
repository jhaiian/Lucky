package com.jhaiian.lucky.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.jhaiian.lucky.R
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.roundToInt

internal const val ACTION_PIP_TOGGLE = "com.jhaiian.lucky.action.PIP_TOGGLE"

private fun dialogWindow(view: View): Window? {
    var current: ViewParent? = view as? ViewParent ?: view.parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}

@Composable
internal fun ImmersiveDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = dialogWindow(view)
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

internal fun requestedOrientation(mode: OrientationMode, shape: VideoShape?, manual: Int?): Int {
    if (manual != null) return manual
    return when (mode) {
        OrientationMode.VIDEO -> {
            val aspect = shape?.shownAspect ?: return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            when {
                aspect < 0.95 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                aspect > 1.05 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        OrientationMode.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

internal fun systemBrightness(context: Context): Float =
    runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
        .getOrDefault(0.5f)
        .coerceIn(0.01f, 1f)

internal fun applyWindowBrightness(activity: Activity?, level: Float) {
    val window = activity?.window ?: return
    val attributes = window.attributes
    val target = if (level < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level.coerceIn(0.01f, 1f)
    if (attributes.screenBrightness != target) {
        attributes.screenBrightness = target
        window.attributes = attributes
    }
}

internal class VolumeControl(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val max = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1

    fun level(): Float = (manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / max.toFloat()

    fun set(level: Float): Float {
        val index = (level.coerceIn(0f, 1f) * max).roundToInt()
        runCatching {
            if (manager?.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
                manager?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            }
        }
        return level.coerceIn(0f, 1f)
    }
}

internal fun pipSupported(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

@RequiresApi(Build.VERSION_CODES.O)
internal fun buildPipParams(
    context: Context,
    shape: VideoShape?,
    playing: Boolean,
    autoEnter: Boolean,
    sourceHint: android.graphics.Rect?
): PictureInPictureParams {
    val aspect = (shape?.shownAspect ?: (16.0 / 9.0)).coerceIn(0.4185, 2.39)
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((aspect * 10_000).roundToInt(), 10_000))
    if (sourceHint != null && !sourceHint.isEmpty) builder.setSourceRectHint(sourceHint)
    val label = context.getString(if (playing) R.string.player_pause else R.string.player_play)
    val icon = Icon.createWithResource(context, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
    val intent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_PIP_TOGGLE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    builder.setActions(listOf(RemoteAction(icon, label, label, intent)))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(false)
    }
    return builder.build()
}

@Composable
internal fun rememberInPictureInPicture(activity: ComponentActivity?): Boolean {
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

@Composable
internal fun rememberClockText(context: Context, active: Boolean): Pair<String, Int?> {
    val state by produceState(currentStatus(context), active) {
        while (active) {
            value = currentStatus(context)
            delay(15_000)
        }
    }
    return state
}

private fun currentStatus(context: Context): Pair<String, Int?> {
    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date())
    val battery = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    return time to battery
}
