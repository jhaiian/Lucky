package com.jhaiian.lucky.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jhaiian.lucky.R
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.PlayQueue
import com.jhaiian.lucky.data.RememberedTracks
import com.jhaiian.lucky.data.SavedSubtitle
import com.jhaiian.lucky.data.ShowMemory
import com.jhaiian.lucky.data.ScreenshotStore
import com.jhaiian.lucky.data.TrackChoice
import com.jhaiian.lucky.data.TrackLanguage
import com.jhaiian.lucky.data.VideoFiles
import com.jhaiian.lucky.data.VideoViewMemory
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.engine.AudioEffects
import com.jhaiian.lucky.engine.AudioNormalize
import com.jhaiian.lucky.engine.DisplayOutput
import com.jhaiian.lucky.engine.Downscaler
import com.jhaiian.lucky.engine.BarDetection
import com.jhaiian.lucky.engine.BlackBarDetector
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.LuckyPlayer
import com.jhaiian.lucky.engine.PlaybackState
import com.jhaiian.lucky.playback.NowPlaying
import com.jhaiian.lucky.playback.NowPlayingState
import com.jhaiian.lucky.playback.PlaybackControls
import com.jhaiian.lucky.engine.PlayerStatus
import com.jhaiian.lucky.engine.RefreshMatch
import com.jhaiian.lucky.engine.RendererPreference
import com.jhaiian.lucky.engine.SeekPreview
import com.jhaiian.lucky.engine.VideoTransform
import com.jhaiian.lucky.engine.SmoothMotion
import com.jhaiian.lucky.engine.SubtitleCue
import com.jhaiian.lucky.engine.SubtitleFrame
import com.jhaiian.lucky.engine.Upscaler
import com.jhaiian.lucky.ui.findActivity
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HISTORY_INTERVAL_MS = 5_000L
private const val NOW_PLAYING_POLL_MS = 500L
private const val NOW_PLAYING_DRIFT_MS = 1_500L
private const val PREVIEW_OPEN_DELAY_MS = 1_500L
private const val VIEW_SAVE_DELAY_MS = 500L
private const val RESUME_NOTICE_MS = 6_000L
private const val ZOOM_RESET_MS = 4_000L
private const val VOLUME_OVERLAY_MS = 1_400L
private const val BACKGROUND_SERVICE_WAIT_MS = 2_000L
private const val BACKGROUND_LEAVE_DELAY_MS = 300L
private const val STRIP_START_DELAY_MS = 3_000L
private const val STRIP_MIN_CORES = 6
private const val SWIPE_SEEK_RANGE_MS = 100_000f
private const val LOOP_MIN_MS = 500L
private const val SCREENSHOT_NOTICE_MS = 6_000L

private class SurfaceSlot {
    var surface: Surface? = null
    var player: LuckyPlayer? = null
}

private class TrackMemory {
    var audio: TrackChoice? = null
    var subtitle: SavedSubtitle? = null
}

private class ViewMemory {
    var baseFit = FitMode.FIT
    var baseAspect = 0f
    var restored: VideoTransform? = null
    var ready = false
}

private class SavedExternal(val uri: String, val name: String)

private class GestureScratch {
    var startPosition = 0L
    var target = 0L
    var travel = 0f
    var level = 0f
    var lastPreview = 0L
}

@Composable
fun PlayerScreen(
    uri: Uri,
    title: String?,
    recordHistory: Boolean = true,
    minimized: Boolean = false,
    onMinimize: (() -> Unit)? = null,
    onClose: () -> Unit,
    onOpen: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val config = rememberPlayerConfig()
    val inPip = rememberInPictureInPicture(activity)
    val prefs = remember { LuckyPrefs.get(context) }
    val ui = remember {
        PlayerUiState(
            fit = config.defaultFit,
            speed = if (config.rememberSpeed) prefs.getFloat(LuckyPrefs.PLAYER_SPEED, 1f).coerceIn(0.25f, 4f) else 1f,
            repeat = config.repeat,
            orientation = config.orientation,
            brightness = if (config.rememberBrightness) prefs.getFloat(LuckyPrefs.PLAYER_BRIGHTNESS, -1f) else -1f,
            upscaler = Upscaler.parse(prefs.getString(LuckyPrefs.UPSCALER, null)),
            downscaler = Downscaler.parse(prefs.getString(LuckyPrefs.DOWNSCALER, null)),
            smoothMotion = config.smoothMotion,
            motionQuality = config.motionQuality,
            hdrOutput = config.hdrOutput
        ).also {
            if (config.rememberVolume) it.volumeBoost = prefs.getFloat(LuckyPrefs.PLAYER_VOLUME_BOOST, 1f).coerceIn(1f, VOLUME_BOOST_MAX)
        }
    }
    val pipAvailable = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipSupported(context) }
    val latestConfig by rememberUpdatedState(config)
    DisposableEffect(Unit) {
        onDispose { NowPlaying.release() }
    }
    LaunchedEffect(config.backgroundPlay, ui.backgroundSession) {
        if (!config.backgroundPlay && !ui.backgroundSession) NowPlaying.release()
    }

    DisposableEffect(activity, minimized) {
        val lucky = activity as? LuckyActivity
        if (minimized) {
            lucky?.setPlayerImmersive(false)
        } else if (lucky != null) {
            lucky.setPlayerImmersive(true)
        } else {
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { lucky?.setPlayerImmersive(false) }
    }

    val requested = requestedOrientation(ui.orientation, ui.videoShape, ui.manualOrientation)
    val originalOrientation = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = originalOrientation }
    }
    LaunchedEffect(requested, inPip, minimized) {
        if (!inPip) activity?.requestedOrientation = if (minimized) originalOrientation else requested
    }

    val hdrBoost = config.hdrBoost && ui.hdrContent && ui.brightness < 0f && !inPip
    LaunchedEffect(ui.brightness, hdrBoost, minimized) {
        applyWindowBrightness(activity, if (minimized) -1f else if (hdrBoost) 1f else ui.brightness)
    }
    LaunchedEffect(ui.displayOutput, minimized) { applyColorMode(activity, if (minimized) DisplayOutput.SRGB else ui.displayOutput) }
    DisposableEffect(activity) {
        onDispose {
            applyColorMode(activity, DisplayOutput.SRGB)
            applyWindowBrightness(activity, -1f)
            if (latestConfig.rememberBrightness) prefs.edit().putFloat(LuckyPrefs.PLAYER_BRIGHTNESS, ui.brightness).apply()
        }
    }
    LaunchedEffect(ui.speed) {
        if (latestConfig.rememberSpeed) prefs.edit().putFloat(LuckyPrefs.PLAYER_SPEED, ui.speed).apply()
    }
    LaunchedEffect(ui.volumeBoost) {
        if (latestConfig.rememberVolume) prefs.edit().putFloat(LuckyPrefs.PLAYER_VOLUME_BOOST, ui.volumeBoost).apply()
    }

    var sourceHint by remember { mutableStateOf<android.graphics.Rect?>(null) }
    val autoEnter = config.autoPip && ui.playing && ui.hasVideo && !ui.locked && !minimized && !ui.backgroundSession
    if (pipAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LaunchedEffect(ui.videoShape, ui.playing, autoEnter, sourceHint) {
            delay(250)
            runCatching { activity?.setPictureInPictureParams(buildPipParams(context, ui.videoShape, ui.playing, autoEnter, sourceHint)) }
        }
        DisposableEffect(activity) {
            onDispose {
                runCatching { activity?.setPictureInPictureParams(buildPipParams(context, null, false, false, null)) }
            }
        }
    }
    val enterPip: () -> Unit = {
        if (pipAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { activity?.enterPictureInPictureMode(buildPipParams(context, ui.videoShape, ui.playing, autoEnter, sourceHint)) }
        }
    }
    val latestAutoEnter by rememberUpdatedState(autoEnter)
    val latestEnterPip by rememberUpdatedState(enterPip)
    DisposableEffect(activity) {
        val listener = Runnable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && latestAutoEnter) latestEnterPip()
        }
        activity?.addOnUserLeaveHintListener(listener)
        onDispose { activity?.removeOnUserLeaveHintListener(listener) }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == ACTION_PIP_TOGGLE) ui.togglePlayback()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val scope = rememberCoroutineScope()
    val slot = remember { SurfaceSlot() }
    val preloader = remember { NextItemPreloader(context.applicationContext, scope) }
    DisposableEffect(preloader) {
        onDispose { preloader.cancel() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (minimized) {
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(0, 0) { placeable.place(-placeable.width * 2, 0) }
                    }
                } else {
                    Modifier
                }
            )
            .background(Color.Black)
    ) {
        if (!minimized) AndroidExternalSurface(modifier = Modifier.fillMaxSize()) {
            onSurface { surface, _, _ ->
                slot.surface = surface
                if (!ui.audioOnly) slot.player?.setSurface(surface)
                surface.onChanged { _, _ -> slot.player?.redraw() }
                surface.onDestroyed {
                    slot.surface = null
                    slot.player?.setSurface(null)
                }
            }
        }
        key(uri) {
            PlayerSession(
                uri = uri,
                title = title,
                recordHistory = recordHistory,
                ui = ui,
                config = config,
                inPip = inPip,
                pipAvailable = pipAvailable,
                slot = slot,
                preloader = preloader,
                minimized = minimized,
                onMinimize = onMinimize,
                onClose = onClose,
                onOpen = onOpen,
                onEnterPip = enterPip,
                onSourceHint = { sourceHint = it }
            )
        }
    }
}

@Composable
private fun PlayerSession(
    uri: Uri,
    title: String?,
    recordHistory: Boolean,
    ui: PlayerUiState,
    config: PlayerConfig,
    inPip: Boolean,
    pipAvailable: Boolean,
    slot: SurfaceSlot,
    preloader: NextItemPreloader,
    minimized: Boolean,
    onMinimize: (() -> Unit)?,
    onClose: () -> Unit,
    onOpen: (String, String?) -> Unit,
    onEnterPip: () -> Unit,
    onSourceHint: (android.graphics.Rect?) -> Unit
) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<LuckyPlayer?>(null) }
    var openFailed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(PlayerStatus()) }
    var statusKey by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lockedHintVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    val quickScrollState = rememberScrollState()
    var quickTab by rememberSaveable { mutableStateOf(QuickTab.PLAYBACK) }
    var showSpeed by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var preview by remember { mutableStateOf<SeekPreview?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var holding by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<GestureHint?>(null) }
    var hintStamp by remember { mutableIntStateOf(0) }
    var gestureActive by remember { mutableStateOf(false) }
    var zoomResetShown by remember { mutableStateOf(false) }
    var zoomResetStamp by remember { mutableIntStateOf(0) }
    var tapFeedback by remember { mutableStateOf<TapSeekFeedback?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val scratch = remember { GestureScratch() }
    val volume = remember { VolumeControl(context) }
    val currentPlayer by rememberUpdatedState(player)
    val prefs = remember { LuckyPrefs.get(context) }
    val volumeConfig by rememberUpdatedState(config)
    var volumeIndicator by remember { mutableStateOf<VolumeIndicator?>(null) }
    var volumeStamp by remember { mutableIntStateOf(0) }
    LaunchedEffect(volume) {
        if (volumeConfig.rememberVolume && volume.adjustable) {
            val saved = prefs.getFloat(LuckyPrefs.PLAYER_VOLUME, -1f)
            if (saved > 0f) volume.set(saved)
        }
    }
    DisposableEffect(volume) {
        onDispose {
            if (volumeConfig.rememberVolume && volume.adjustable) prefs.edit().putFloat(LuckyPrefs.PLAYER_VOLUME, volume.level()).apply()
        }
    }
    LaunchedEffect(volumeStamp, gestureActive) {
        if (volumeIndicator != null && !gestureActive) {
            delay(VOLUME_OVERLAY_MS)
            volumeIndicator = null
        }
    }
    var brightnessIndicator by remember { mutableStateOf<Float?>(null) }
    var brightnessStamp by remember { mutableIntStateOf(0) }
    LaunchedEffect(brightnessStamp, gestureActive) {
        if (brightnessIndicator != null && !gestureActive) {
            delay(VOLUME_OVERLAY_MS)
            brightnessIndicator = null
        }
    }
    val keyActivity = remember(context) { context.findActivity() as? LuckyActivity }
    DisposableEffect(keyActivity, minimized, inPip) {
        val handler: (Boolean) -> Boolean = handler@{ raise ->
            if (!volume.adjustable) return@handler false
            if (raise) {
                if (volume.atMax()) {
                    ui.volumeBoost = ((ui.volumeBoost + VOLUME_BOOST_STEP) * 10f).roundToInt().div(10f).coerceAtMost(VOLUME_BOOST_MAX)
                } else {
                    volume.step(raise = true)
                }
            } else if (ui.volumeBoost > 1f) {
                ui.volumeBoost = ((ui.volumeBoost - VOLUME_BOOST_STEP) * 10f).roundToInt().div(10f).coerceAtLeast(1f)
            } else {
                volume.step(raise = false)
            }
            val level = volume.level()
            if (volumeConfig.rememberVolume) prefs.edit().putFloat(LuckyPrefs.PLAYER_VOLUME, level).apply()
            volumeIndicator = VolumeIndicator(level, ui.volumeBoost)
            volumeStamp++
            true
        }
        if (keyActivity != null && !minimized && !inPip) keyActivity.volumeKeyHandler = handler
        onDispose {
            if (keyActivity?.volumeKeyHandler === handler) keyActivity.volumeKeyHandler = null
        }
    }
    var preferHardware by remember { mutableStateOf(prefs.getBoolean(LuckyPrefs.HARDWARE_DECODING, true)) }
    var renderer by remember { mutableStateOf(RendererPreference.parse(prefs.getString(LuckyPrefs.RENDERER, null))) }
    val power = rememberPowerState()
    val motion = effectiveMotion(
        ui.smoothMotion,
        config,
        power,
        ui.motionQuality,
        prefs.getInt(LuckyPrefs.LEARNED_MOTION_PREFIX + renderer.name, 0)
    )
    val latestMotion by rememberUpdatedState(motion)
    val audioEffects = AudioEffects(
        delayMs = ui.audioDelayMs,
        gain = ui.volumeBoost,
        night = config.audioNight,
        dialogue = config.audioDialogue,
        channels = config.audioChannels,
        balance = config.audioBalance,
        normalize = config.audioNormalize,
        equalizer = config.equalizer.bands
    )
    val latestAudio by rememberUpdatedState(audioEffects)
    val audioOutput = config.audioOutput
    val latestAudioOutput by rememberUpdatedState(audioOutput)
    val subtitleStyle = remember(config.subtitles, ui.subtitleScale) { nativeSubtitleStyle(context, config.subtitles, ui.subtitleScale) }
    val latestSubtitleStyle by rememberUpdatedState(subtitleStyle)
    val configuration = LocalConfiguration.current
    val caps = remember(context, configuration) { displayCaps(context.findActivity()) }
    val headroom = rememberHdrHeadroom(context.findActivity())
    val display = remember(caps, config, ui.hdrOutput, headroom) { displayOptions(caps, config, ui.hdrOutput, headroom) }
    val applySettings by rememberUpdatedState<(LuckyPlayer) -> Unit>({ target ->
        target.setSpeed(ui.speed)
        target.setViewTransform(ui.transform)
        target.setUpscaler(ui.upscaler)
        target.setDownscaler(ui.downscaler)
        target.setAntiring(config.antiring)
        target.setAudioEffects(latestAudio)
        target.setAudioOutput(latestAudioOutput)
        target.setMotion(latestMotion.options)
        target.setSmoothMotion(latestMotion.mode)
        target.setDisplay(display)
        target.setPerformance(config.autoQuality, config.fastDecode, config.softwareFallback, prefs.getFloat(LuckyPrefs.LEARNED_COST_PREFIX + renderer.name, 0f))
        target.setDeinterlace(config.deinterlace)
        target.setTextSubtitles(config.textSubtitlesLibass)
        target.setSubtitleStyle(latestSubtitleStyle)
    })
    var showRemaining by remember { mutableStateOf(config.showRemaining) }
    val uriKey = remember(uri) { uri.toString() }
    val autoSubtitles = remember { LuckyPrefs.autoSubtitles(context) }
    val historyEnabled = remember { LuckyPrefs.watchHistoryEnabled(context) && recordHistory }
    val showKey = remember(uriKey) { ShowMemory.keyFor((title ?: uri.lastPathSegment)?.substringAfterLast('/')) }
    var delaysReady by remember(uriKey) { mutableStateOf(false) }
    var resumeAtMs by remember(uri) {
        mutableStateOf(
            if (LuckyPrefs.resumePlayback(context)) WatchHistory.get(context, uri.toString())?.takeIf { it.resumable }?.positionMs else null
        )
    }
    var resumeNotice by remember(uri) { mutableStateOf(resumeAtMs) }
    var subtitlesApplied by remember(uri) { mutableStateOf(false) }
    val trackMemory = remember(uri) { TrackMemory() }
    val externalSubtitles = remember(uri) { mutableMapOf<Int, SavedExternal>() }
    fun rememberedTracks(): RememberedTracks? {
        val stored = if (historyEnabled) WatchHistory.get(context, uriKey)?.tracks else null
        val show = if (historyEnabled) ShowMemory.get(context, showKey) else null
        val audio = trackMemory.audio ?: stored?.audio ?: show?.audio
        val subtitle = trackMemory.subtitle ?: stored?.subtitle ?: show?.subtitle
        return if (audio == null && subtitle == null) null else RememberedTracks(audio, subtitle)
    }

    val viewMemory = remember(uriKey) { ViewMemory() }
    LaunchedEffect(Unit) {
        viewMemory.baseFit = ui.fit
        viewMemory.baseAspect = ui.aspect
        ui.resetForNewVideo()
        val savedView = VideoViewMemory.get(context, uriKey) ?: if (historyEnabled) ShowMemory.get(context, showKey)?.view else null
        savedView?.let { saved ->
            ui.restoreView(saved)
            viewMemory.restored = saved
        }
        viewMemory.ready = true
        if (historyEnabled) {
            ShowMemory.get(context, showKey)?.let { show ->
                ui.subtitleDelayMs = show.subtitleDelayMs
                ui.audioDelayMs = show.audioDelayMs
            }
        }
        delaysReady = true
    }
    LaunchedEffect(showKey, delaysReady, ui.subtitleDelayMs, ui.audioDelayMs) {
        if (!delaysReady || !historyEnabled || showKey == null) return@LaunchedEffect
        delay(VIEW_SAVE_DELAY_MS)
        val saved = ShowMemory.get(context, showKey)
        val subtitleDelay = ui.subtitleDelayMs
        val audioDelay = ui.audioDelayMs
        if ((saved?.subtitleDelayMs ?: 0L) != subtitleDelay || (saved?.audioDelayMs ?: 0) != audioDelay) {
            ShowMemory.saveDelays(context, showKey, subtitleDelay, audioDelay)
        }
    }
    LaunchedEffect(uriKey, ui.transform) {
        if (!viewMemory.ready) return@LaunchedEffect
        delay(VIEW_SAVE_DELAY_MS)
        VideoViewMemory.save(context, uriKey, ui.transform, VideoTransform(fit = viewMemory.baseFit, aspect = viewMemory.baseAspect))
        if (historyEnabled) ShowMemory.saveView(context, showKey, ui.transform)
    }
    DisposableEffect(uriKey) {
        onDispose {
            if (viewMemory.ready) {
                VideoViewMemory.save(context, uriKey, ui.transform, VideoTransform(fit = viewMemory.baseFit, aspect = viewMemory.baseAspect))
                if (historyEnabled) ShowMemory.saveView(context, showKey, ui.transform)
                val restored = viewMemory.restored
                if (restored != null) {
                    if (ui.fit == restored.fit) ui.fit = viewMemory.baseFit
                    if (ui.aspect == restored.aspect) ui.aspect = viewMemory.baseAspect
                }
            }
        }
    }

    fun flash(value: GestureHint) {
        hint = value
        hintStamp++
    }

    LaunchedEffect(hintStamp, gestureActive) {
        if (!gestureActive && hint != null) {
            delay(900)
            hint = null
        }
    }
    LaunchedEffect(zoomResetStamp) {
        if (zoomResetShown) {
            delay(ZOOM_RESET_MS)
            zoomResetShown = false
        }
    }
    LaunchedEffect(tapFeedback) {
        if (tapFeedback != null) {
            delay(700)
            tapFeedback = null
        }
    }

    DisposableEffect(uri, preferHardware, renderer) {
        val resume = resumeAtMs
        val preferredAudio = TrackLanguage.preferred(context, prefs.getString(LuckyPrefs.PREFERRED_AUDIO_LANGUAGE, TrackLanguage.FILE_DEFAULT))
        val remembered = rememberedTracks()
        val handoff = preloader.take(uriKey, preferHardware, renderer)
        var job: Job? = null
        if (handoff != null) {
            resumeAtMs = null
            subtitlesApplied = true
            handoff.activate()
            slot.player = handoff
            slot.surface?.takeIf { !ui.audioOnly }?.let { handoff.setSurface(it) }
            handoff.play()
            status = handoff.status()
            statusKey = uriKey
            player = handoff
        } else {
            job = scope.launch {
                var owned: LuckyPlayer? = null
                try {
                    val opened = withContext(Dispatchers.IO + NonCancellable) {
                        LuckyPlayer.open(
                            context,
                            uri,
                            preferHardware = preferHardware,
                            renderer = renderer,
                            autoplay = resume == null && preferredAudio == null && remembered == null
                        )
                    }
                    if (opened == null) {
                        openFailed = true
                        return@launch
                    }
                    owned = opened
                    ensureActive()
                    applySettings(opened)
                    opened.beginAudio()
                    val restored = remembered?.let { restoreTracks(opened, it) }
                    val subtitleRestored = restored?.subtitle == true
                    if (restored?.audio != true && preferredAudio != null) chooseAudioTrack(opened, preferredAudio)
                    if (resume != null) {
                        opened.seekTo(resume)
                        resumeAtMs = null
                    }
                    if (resume != null || preferredAudio != null || remembered != null) opened.play()
                    if (!autoSubtitles && !subtitlesApplied && !subtitleRestored) {
                        opened.selectSubtitle(null)
                        subtitlesApplied = true
                    }
                    slot.player = opened
                    player = opened
                    owned = null
                    slot.surface?.takeIf { !ui.audioOnly }?.let { opened.setSurface(it) }
                    attachSidecars(
                        context,
                        opened,
                        uri,
                        autoSubtitles && !subtitleRestored,
                        remembered?.subtitle?.takeIf { !subtitleRestored }
                    ) { id, sidecarUri, name -> externalSubtitles[id] = SavedExternal(sidecarUri, name) }
                } finally {
                    owned?.let { closeInBackground(it) }
                }
            }
        }
        onDispose {
            job?.cancel()
            val released = player
            player = null
            if (slot.player === released) slot.player = null
            if (released != null) {
                released.setSurface(null)
                Thread { released.close() }.start()
            }
        }
    }

    var anchorPosition by remember { mutableLongStateOf(0L) }
    var anchorUptime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        val key = uriKey
        while (isActive && !active.isReleased) {
            status = active.status()
            statusKey = key
            anchorPosition = status.positionMs
            anchorUptime = SystemClock.uptimeMillis()
            delay(if (status.state == PlaybackState.PLAYING) 100L else 250L)
        }
    }

    val cues = remember { mutableStateListOf<SubtitleCue>() }
    LaunchedEffect(player) {
        cues.clear()
        val active = player ?: return@LaunchedEffect
        var generation = -1L
        var revision = 0L
        while (isActive && !active.isReleased) {
            val batch = withContext(Dispatchers.Default) { active.subtitles(revision) }
            if (batch != null) {
                if (batch.generation != generation) {
                    generation = batch.generation
                    cues.clear()
                    revision = 0L
                    val fresh = withContext(Dispatchers.Default) { active.subtitles(0L) }
                    if (fresh == null || fresh.generation != generation) {
                        generation = -1L
                        delay(50L)
                        continue
                    }
                    fresh.cues.forEach { cues.add(it) }
                    revision = fresh.revision
                } else {
                    batch.cues.forEach { cue ->
                        val existing = cues.indexOfFirst { it.id == cue.id }
                        if (existing >= 0) cues[existing] = cue else cues.add(cue)
                    }
                    revision = batch.revision
                }
                val horizon = status.positionMs - 10_000L - abs(ui.subtitleDelayMs)
                cues.removeAll { (it.endMs ?: Long.MAX_VALUE) < horizon }
            }
            delay(200L)
        }
    }

    val playing = status.state == PlaybackState.PLAYING || status.state == PlaybackState.BUFFERING
    LaunchedEffect(playing) { ui.playing = playing }
    LaunchedEffect(status.hasVideo, player) { if (player != null) ui.hasVideo = status.hasVideo }
    LaunchedEffect(status.videoWidth, status.videoHeight, status.displayAspect, status.rotation) {
        if (status.videoWidth > 0 && status.videoHeight > 0) {
            ui.videoShape = VideoShape(status.videoWidth, status.videoHeight, status.displayAspect, status.rotation)
        }
    }

    val effectiveSpeed = if (holding && config.holdSpeed > 0f) config.holdSpeed else ui.speed
    LaunchedEffect(player, effectiveSpeed) { player?.setSpeed(effectiveSpeed) }
    val transform = ui.transform
    LaunchedEffect(player, transform) { player?.setViewTransform(transform) }
    LaunchedEffect(player, ui.upscaler) { player?.setUpscaler(ui.upscaler) }
    LaunchedEffect(player, ui.downscaler) { player?.setDownscaler(ui.downscaler) }
    LaunchedEffect(player, config.antiring) { player?.setAntiring(config.antiring) }
    LaunchedEffect(config.smoothMotion) { ui.smoothMotion = config.smoothMotion }
    LaunchedEffect(config.motionQuality) { ui.motionQuality = config.motionQuality }
    LaunchedEffect(player, audioEffects) { player?.setAudioEffects(audioEffects) }
    LaunchedEffect(player, audioOutput) { player?.setAudioOutput(audioOutput) }
    LaunchedEffect(player, subtitleStyle) { player?.setSubtitleStyle(subtitleStyle) }
    LaunchedEffect(player, config.textSubtitlesLibass) { player?.setTextSubtitles(config.textSubtitlesLibass) }
    LaunchedEffect(player, ui.loopStartMs, ui.loopEndMs) {
        val loopStart = ui.loopStartMs
        val loopEnd = ui.loopEndMs
        if (loopStart != null && loopEnd != null) player?.setLoopRange(loopStart, loopEnd) else player?.clearLoopRange()
    }
    LaunchedEffect(player, motion) {
        player?.setMotion(motion.options)
        player?.setSmoothMotion(motion.mode)
    }
    LaunchedEffect(player, status.learnedMotion) {
        if (player != null && status.learnedMotion > 0) {
            prefs.edit().putInt(LuckyPrefs.LEARNED_MOTION_PREFIX + renderer.name, status.learnedMotion).apply()
        }
    }
    val videoFrameRate = status.frameRate
    val interpolating = motion.mode == SmoothMotion.INTERPOLATE
    LaunchedEffect(config.refreshMatch, videoFrameRate, interpolating, motion.options.target, motion.options.targetFps) {
        val host = context.findActivity()
        val mode = when {
            interpolating -> interpolationModeId(host, motion.options.target, motion.options.targetFps, videoFrameRate)
            config.refreshMatch == RefreshMatch.ALWAYS && videoFrameRate != null -> matchingModeId(host, videoFrameRate)
            else -> 0
        }
        applyPreferredMode(host, mode)
    }
    DisposableEffect(Unit) {
        onDispose { applyPreferredMode(context.findActivity(), 0) }
    }
    LaunchedEffect(player, config.autoQuality, config.fastDecode, config.softwareFallback) {
        player?.setPerformance(config.autoQuality, config.fastDecode, config.softwareFallback, prefs.getFloat(LuckyPrefs.LEARNED_COST_PREFIX + renderer.name, 0f))
    }
    LaunchedEffect(player, config.deinterlace) { player?.setDeinterlace(config.deinterlace) }
    LaunchedEffect(player, status.learnedCost) {
        if (player != null && status.learnedCost >= 0.0) {
            prefs.edit().putFloat(LuckyPrefs.LEARNED_COST_PREFIX + renderer.name, status.learnedCost.toFloat()).apply()
        }
    }
    LaunchedEffect(player, display) { player?.setDisplay(display) }
    LaunchedEffect(player, status.hdrFormat) { if (player != null) ui.hdrContent = status.hdrFormat != null }
    LaunchedEffect(player, status.displayOutput) { if (player != null) ui.displayOutput = status.displayOutput }
    val fallbackText = stringResource(R.string.player_decoder_fallback)
    val dolbyVision = DolbyVision.parse(status.hdrFormat)
    val dolbyVisionText = dolbyVision?.let { stringResource(R.string.player_dovi_software, it.label) }
    LaunchedEffect(player, dolbyVision) {
        if (player != null && dolbyVision?.needsReshape == true && preferHardware && dolbyVisionText != null) {
            flash(GestureHint.Message(dolbyVisionText, Icons.Rounded.Memory))
        }
    }
    LaunchedEffect(status.decoderFallbacks) {
        if (status.decoderFallbacks > 0) flash(GestureHint.Message(fallbackText, Icons.Rounded.Memory))
    }

    val placement = placementFor(containerSize.width.toFloat(), containerSize.height.toFloat(), ui.videoShape, transform)
    LaunchedEffect(placement, containerOrigin, containerSize) {
        if (containerSize.width > 0) {
            val rect = placement.rect(containerSize.width.toFloat(), containerSize.height.toFloat())
            val left = (rect.left.coerceAtLeast(0f) + containerOrigin.x).roundToInt()
            val top = (rect.top.coerceAtLeast(0f) + containerOrigin.y).roundToInt()
            val right = (rect.right.coerceAtMost(containerSize.width.toFloat()) + containerOrigin.x).roundToInt()
            val bottom = (rect.bottom.coerceAtMost(containerSize.height.toFloat()) + containerOrigin.y).roundToInt()
            onSourceHint(if (right > left && bottom > top) android.graphics.Rect(left, top, right, bottom) else null)
        }
    }

    val sheetOpen = (showQuick || showSpeed || showSubtitles || showAudio || showEqualizer) && !SidePanelPin.pinned
    LaunchedEffect(controlsVisible, playing, interaction, scrubFraction, sheetOpen, config.controlsTimeoutSeconds) {
        if (controlsVisible && playing && scrubFraction == null && !sheetOpen && config.controlsTimeoutSeconds > 0) {
            delay(config.controlsTimeoutSeconds * 1000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(lockedHintVisible, interaction) {
        if (lockedHintVisible) {
            delay(2500)
            lockedHintVisible = false
        }
    }

    val view = LocalView.current
    DisposableEffect(playing, minimized) {
        view.keepScreenOn = playing && !minimized
        onDispose { view.keepScreenOn = false }
    }

    val backgroundPlay = config.backgroundPlay || ui.backgroundSession
    val latestBackgroundPlay by rememberUpdatedState(backgroundPlay)
    val latestInPip by rememberUpdatedState(inPip)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) ui.backgroundSession = false
            if (event == Lifecycle.Event.ON_STOP) {
                val keepPlaying = latestBackgroundPlay &&
                    NowPlaying.serviceActive.value &&
                    !latestInPip &&
                    context.findActivity()?.isFinishing != true
                if (keepPlaying) {
                    controlsVisible = false
                } else {
                    currentPlayer?.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val focusRequest = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    currentPlayer?.pause()
                }
            }
            .build()
    }
    val hasAudio = status.audioCodec != null
    LaunchedEffect(playing, hasAudio) {
        if (playing && hasAudio && audioManager != null) {
            val result = audioManager.requestAudioFocus(focusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) currentPlayer?.pause()
        }
    }
    DisposableEffect(audioManager) {
        onDispose { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) currentPlayer?.pause()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val latestStatus by rememberUpdatedState(status)
    val saveHistory: () -> Unit = {
        val current = latestStatus
        val duration = current.durationMs
        if (historyEnabled && statusKey == uriKey && duration != null && duration > 0 && current.state != PlaybackState.OPENING && current.state != PlaybackState.ERROR) {
            val position = if (current.state == PlaybackState.ENDED) duration else current.positionMs
            WatchHistory.record(context, uriKey, title, position, duration, trackMemory.audio, trackMemory.subtitle)
        }
    }
    val rememberAudio: (Int) -> Unit = { index ->
        val info = latestStatus.audioTracks.firstOrNull { it.index == index }
        trackMemory.audio = when {
            index < 0 -> TrackChoice(-1, null, null)
            info != null -> TrackChoice(info.index, info.language, info.codec)
            else -> null
        }
        if (historyEnabled) ShowMemory.saveTracks(context, showKey, trackMemory.audio, null)
        saveHistory()
    }
    val rememberSubtitle: (Int?) -> Unit = { index ->
        val info = index?.let { id -> latestStatus.subtitleTracks.firstOrNull { it.index == id } }
        val external = index?.let { externalSubtitles[it] }
        trackMemory.subtitle = when {
            index == null -> SavedSubtitle(null, null, null)
            info?.external == true -> {
                val name = external?.name ?: info.title
                if (name == null) null else SavedSubtitle(null, external?.uri, name)
            }
            info != null -> SavedSubtitle(TrackChoice(info.index, info.language, info.codec), null, null)
            else -> null
        }
        if (historyEnabled) ShowMemory.saveTracks(context, showKey, null, trackMemory.subtitle)
        saveHistory()
    }
    LaunchedEffect(playing, uriKey) {
        while (isActive && playing) {
            delay(HISTORY_INTERVAL_MS)
            saveHistory()
        }
        saveHistory()
    }
    DisposableEffect(uriKey) {
        onDispose { saveHistory() }
    }

    val next = remember(uriKey, ui.repeat) { PlayQueue.next(uriKey, wrap = ui.repeat == RepeatMode.ALL) }
    val previous = remember(uriKey) { PlayQueue.previous(uriKey) }
    val queueIndex = remember(uriKey) { PlayQueue.indexOf(uriKey) }
    val queueSize = PlayQueue.items.size
    val inQueue = queueIndex >= 0 && queueSize > 1

    val lowRam = remember(context) { isLowRamDevice(context) }
    val preloadTarget = next?.takeIf {
        !lowRam && !power.saver && ui.repeat != RepeatMode.ONE && (config.autoplayNext || ui.repeat == RepeatMode.ALL) && ui.sleepTimer?.endOfVideo != true
    }
    LaunchedEffect(player, preloadTarget) {
        val active = player ?: return@LaunchedEffect
        val target = preloadTarget
        if (target == null) {
            preloader.cancel()
            return@LaunchedEffect
        }
        var armed = false
        while (isActive && !active.isReleased) {
            val current = latestStatus
            val duration = current.durationMs ?: 0L
            val usable = duration > 0L && statusKey == uriKey && current.state != PlaybackState.OPENING && current.state != PlaybackState.ERROR
            armed = usable && preloadRearmed(armed, duration - current.positionMs, duration)
            val unchanged = PlayQueue.next(uriKey, wrap = ui.repeat == RepeatMode.ALL)?.uri == target.uri
            if (armed && unchanged) {
                if (preloader.wants(target.uri)) {
                    preloader.preload(
                        target,
                        PreloadSetup(
                            preferHardware = preferHardware,
                            renderer = renderer,
                            preferredAudio = TrackLanguage.preferred(context, prefs.getString(LuckyPrefs.PREFERRED_AUDIO_LANGUAGE, TrackLanguage.FILE_DEFAULT)),
                            autoSubtitles = autoSubtitles,
                            remembered = if (historyEnabled) {
                                WatchHistory.get(context, target.uri)?.tracks
                                    ?: ShowMemory.get(context, ShowMemory.keyFor((target.title ?: Uri.parse(target.uri).lastPathSegment)?.substringAfterLast('/')))?.tracks
                            } else {
                                null
                            },
                            apply = { opened -> applySettings(opened) }
                        )
                    )
                }
            } else {
                preloader.cancel()
            }
            delay(500L)
        }
    }

    val previewable = player != null && status.hasVideo && status.seekable && (status.durationMs ?: 0L) > 0L
    DisposableEffect(uriKey, previewable) {
        var opened: SeekPreview? = null
        val job = scope.launch {
            if (!previewable) return@launch
            delay(PREVIEW_OPEN_DELAY_MS)
            val created = withContext(Dispatchers.IO + NonCancellable) {
                SeekPreview.open(context, uri) { _, bitmap -> if (scrubFraction != null) previewBitmap = bitmap }
            }
            if (created == null) return@launch
            if (!isActive) {
                created.close()
                return@launch
            }
            opened = created
            preview = created
        }
        onDispose {
            job.cancel()
            preview = null
            previewBitmap = null
            opened?.close()
        }
    }
    val stripAllowed = !lowRam && !power.saver && power.thermal < THERMAL_MODERATE &&
        status.decoderKind == "hardware" && Runtime.getRuntime().availableProcessors() >= STRIP_MIN_CORES
    LaunchedEffect(preview, stripAllowed) {
        val active = preview ?: return@LaunchedEffect
        if (!stripAllowed) {
            active.prefetch(emptyList())
            return@LaunchedEffect
        }
        delay(STRIP_START_DELAY_MS)
        val duration = latestStatus.durationMs ?: return@LaunchedEffect
        active.prefetch(stripPositions(duration))
    }

    val bump: () -> Unit = {
        controlsVisible = true
        interaction++
    }
    val togglePlayback: () -> Unit = {
        currentPlayer?.let {
            when (latestStatus.state) {
                PlaybackState.PLAYING, PlaybackState.BUFFERING -> it.pause()
                PlaybackState.ENDED -> {
                    it.seekTo(0L)
                    it.play()
                }
                else -> it.play()
            }
        }
    }
    SideEffect { ui.togglePlayback = togglePlayback }
    val skip: (Long) -> Unit = { delta ->
        currentPlayer?.let { active ->
            val current = latestStatus
            val duration = current.durationMs
            var target = current.positionMs + delta
            if (duration != null) target = target.coerceAtMost(duration)
            active.seekTo(target.coerceAtLeast(0L))
        }
    }
    val goNext: (() -> Unit)? = next?.let { item -> { onOpen(item.uri, item.title) } }
    val goPrevious: (() -> Unit)? = if (inQueue) {
        {
            if (latestStatus.positionMs > 5_000L || previous == null) {
                currentPlayer?.seekTo(0L)
            } else {
                onOpen(previous.uri, previous.title)
            }
        }
    } else {
        null
    }

    val latestGoNext by rememberUpdatedState(goNext)
    val latestGoPrevious by rememberUpdatedState(goPrevious)
    DisposableEffect(Unit) {
        val controls = object : PlaybackControls {
            override fun play() {
                val active = currentPlayer ?: return
                if (latestStatus.state == PlaybackState.ENDED) active.seekTo(0L)
                active.play()
            }

            override fun pause() {
                currentPlayer?.pause()
            }

            override fun seekTo(positionMs: Long) {
                currentPlayer?.seekTo(positionMs)
            }

            override fun next() {
                latestGoNext?.invoke()
            }

            override fun previous() {
                val back = latestGoPrevious
                if (back != null) back() else currentPlayer?.seekTo(0L)
            }

            override fun stop() {
                currentPlayer?.pause()
            }
        }
        NowPlaying.controls = controls
        onDispose { if (NowPlaying.controls === controls) NowPlaying.controls = null }
    }

    val fallbackTitle = stringResource(R.string.app_name)
    val nowPlayingTitle = remember(uriKey, title) { title ?: displayName(context, uri)?.let { VideoFiles.baseNameOf(it) } } ?: fallbackTitle
    val latestNowPlayingTitle by rememberUpdatedState(nowPlayingTitle)
    val latestHasNext by rememberUpdatedState(next != null)
    val latestHasPrevious by rememberUpdatedState(inQueue)
    val latestEffectiveSpeed by rememberUpdatedState(effectiveSpeed)
    fun nowPlayingSnapshot(): NowPlayingState {
        val current = status
        return NowPlayingState(
            uri = uriKey,
            title = latestNowPlayingTitle,
            playing = current.state == PlaybackState.PLAYING || current.state == PlaybackState.BUFFERING,
            positionMs = current.positionMs,
            durationMs = current.durationMs ?: 0L,
            speed = latestEffectiveSpeed,
            hasNext = latestHasNext,
            hasPrevious = latestHasPrevious,
            seekable = current.seekable
        )
    }
    LaunchedEffect(player, backgroundPlay) {
        val active = player ?: return@LaunchedEffect
        if (!backgroundPlay) return@LaunchedEffect
        var last: NowPlayingState? = null
        var lastAt = 0L
        while (isActive && !active.isReleased) {
            if (statusKey == uriKey) {
                val snapshot = nowPlayingSnapshot()
                val now = SystemClock.elapsedRealtime()
                val previous = last
                val changed = previous == null || snapshot.copy(positionMs = 0L) != previous.copy(positionMs = 0L) || run {
                    val elapsed = if (previous.playing) ((now - lastAt) * previous.speed).toLong() else 0L
                    abs(snapshot.positionMs - (previous.positionMs + elapsed)) > NOW_PLAYING_DRIFT_MS
                }
                if (changed) {
                    NowPlaying.publish(snapshot)
                    last = snapshot
                    lastAt = now
                }
            }
            delay(NOW_PLAYING_POLL_MS)
        }
    }
    LaunchedEffect(backgroundPlay, playing, player) {
        if (!backgroundPlay || !playing || player == null || NowPlaying.serviceActive.value) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        NowPlaying.publish(nowPlayingSnapshot())
        NowPlaying.startService(context)
    }

    val surfaceDetached = remember { BooleanArray(1) }
    LaunchedEffect(player, ui.audioOnly) {
        val active = player ?: return@LaunchedEffect
        if (ui.audioOnly) {
            active.setSurface(null)
            surfaceDetached[0] = true
        } else if (surfaceDetached[0]) {
            surfaceDetached[0] = false
            slot.surface?.let {
                active.setSurface(it)
                active.redraw()
            }
        }
    }

    LaunchedEffect(player, resumeNotice, statusKey) {
        if (player == null || resumeNotice == null || statusKey != uriKey) return@LaunchedEffect
        delay(RESUME_NOTICE_MS)
        resumeNotice = null
    }

    LaunchedEffect(status.state) {
        if (status.state != PlaybackState.ENDED) return@LaunchedEffect
        val timer = ui.sleepTimer
        if (timer?.endOfVideo == true) {
            ui.sleepTimer = null
            return@LaunchedEffect
        }
        when {
            ui.repeat == RepeatMode.ONE || (ui.repeat == RepeatMode.ALL && next == null) -> {
                delay(250)
                currentPlayer?.seekTo(0L)
                currentPlayer?.play()
            }
            next != null && (config.autoplayNext || ui.repeat == RepeatMode.ALL) -> {
                if (!preloader.isReady(next.uri)) delay(600)
                onOpen(next.uri, next.title)
            }
        }
    }

    val sleepStopped = stringResource(R.string.player_sleep_stopped)
    LaunchedEffect(ui.sleepTimer) {
        val end = ui.sleepTimer?.endsAtElapsed ?: return@LaunchedEffect
        delay((end - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        currentPlayer?.pause()
        ui.sleepTimer = null
        flash(GestureHint.Message(sleepStopped, Icons.Rounded.Bedtime))
    }

    BackHandler(enabled = !minimized) {
        val minimize = onMinimize
        when {
            ui.locked -> {
                ui.locked = false
                bump()
            }
            minimize != null && backgroundPlay && playing && !inPip -> {
                showQuick = false
                showSpeed = false
                showSubtitles = false
                showAudio = false
                showEqualizer = false
                showChapters = false
                SidePanelPin.pinned = false
                minimize()
            }
            else -> onClose()
        }
    }

    val lockedText = stringResource(R.string.player_locked)
    val chapterIndex = currentChapterIndex(status.chapters, status.positionMs)
    val chapterTitle = status.chapters.getOrNull(chapterIndex)?.let { chapter ->
        chapter.title ?: stringResource(R.string.player_chapter_number, chapterIndex + 1)
    }
    val loadFailedText = stringResource(R.string.player_subtitle_load_failed)
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        val active = currentPlayer
        if (picked != null && active != null) {
            scope.launch {
                val name = displayName(context, picked) ?: picked.lastPathSegment.orEmpty()
                val id = withContext(Dispatchers.IO) { active.addSubtitle(context, picked, name) }
                if (id != null) {
                    runCatching { context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    externalSubtitles[id] = SavedExternal(picked.toString(), name)
                    active.selectSubtitle(id)
                    rememberSubtitle(id)
                } else {
                    flash(GestureHint.Message(loadFailedText, Icons.Rounded.ClosedCaptionDisabled))
                }
            }
        }
    }
    val fitLabels = FitMode.entries.associateWith { stringResource(FitModeLabels.getValue(it)) }
    val repeatLabels = RepeatMode.entries.associateWith { stringResource(it.labelRes) }
    val rotateLandscape = stringResource(R.string.player_orientation_landscape)
    val rotatePortrait = stringResource(R.string.player_orientation_portrait)
    val landscapeNow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun clampPan() {
        val clamped = placementFor(containerSize.width.toFloat(), containerSize.height.toFloat(), ui.videoShape, ui.transform)
        ui.panX = clamped.translateX
        ui.panY = clamped.translateY
    }

    val handler = object : PlayerGestureHandler {
        override fun onTap() {
            if (ui.locked) {
                lockedHintVisible = !lockedHintVisible
                interaction++
            } else {
                controlsVisible = !controlsVisible
                interaction++
            }
        }

        override fun onDoubleTap(zone: Int) {
            when (config.doubleTap) {
                DoubleTapAction.PLAY_PAUSE -> togglePlayback()
                DoubleTapAction.SEEK -> {
                    if (zone == 0) {
                        togglePlayback()
                    } else {
                        skip(zone * config.seekStepMs)
                        val now = SystemClock.uptimeMillis()
                        val step = (config.seekStepMs / 1000).toInt()
                        val previousFeedback = tapFeedback
                        val seconds = if (previousFeedback != null && previousFeedback.zone == zone && now - previousFeedback.stamp < 1_000) {
                            previousFeedback.seconds + step
                        } else {
                            step
                        }
                        tapFeedback = TapSeekFeedback(zone, seconds, now)
                    }
                }
                DoubleTapAction.OFF -> Unit
            }
        }

        override fun onHoldStart() {
            if (config.holdSpeed > 0f && latestStatus.state == PlaybackState.PLAYING) {
                holding = true
                gestureActive = true
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                flash(GestureHint.Speed(config.holdSpeed))
            }
        }

        override fun onHoldEnd() {
            holding = false
            gestureActive = false
        }

        override fun onSwipeStart(kind: SwipeKind) {
            gestureActive = true
            scratch.travel = 0f
            when (kind) {
                SwipeKind.SEEK -> {
                    scratch.startPosition = latestStatus.positionMs
                    scratch.target = scratch.startPosition
                    scratch.lastPreview = 0L
                }
                SwipeKind.BRIGHTNESS -> scratch.level = when {
                    ui.brightness >= 0f -> ui.brightness
                    config.hdrBoost && ui.hdrContent && !inPip -> 1f
                    else -> systemBrightness(context)
                }
                SwipeKind.VOLUME -> scratch.level = if (ui.volumeBoost > 1f && volume.atMax()) {
                    1f + (ui.volumeBoost - 1f) / (VOLUME_BOOST_MAX - 1f)
                } else {
                    volume.level()
                }
            }
        }

        override fun onSwipe(kind: SwipeKind, delta: Float, extent: Float) {
            when (kind) {
                SwipeKind.SEEK -> {
                    val current = latestStatus
                    val duration = current.durationMs ?: return
                    if (!current.seekable || extent <= 0f) return
                    scratch.travel += delta
                    val offset = (scratch.travel / extent * SWIPE_SEEK_RANGE_MS).toLong()
                    scratch.target = (scratch.startPosition + offset).coerceIn(0L, duration)
                    flash(GestureHint.Seek(scratch.target, scratch.target - scratch.startPosition, duration))
                    val now = SystemClock.uptimeMillis()
                    if (now - scratch.lastPreview > 160) {
                        scratch.lastPreview = now
                        currentPlayer?.seekTo(scratch.target, exact = false)
                    }
                }
                SwipeKind.BRIGHTNESS -> {
                    scratch.level = (scratch.level - delta / (extent * 0.7f)).coerceIn(0.01f, 1f)
                    ui.brightness = scratch.level
                    brightnessIndicator = scratch.level
                    brightnessStamp++
                }
                SwipeKind.VOLUME -> {
                    scratch.level = (scratch.level - delta / (extent * 0.7f)).coerceIn(0f, 2f)
                    if (scratch.level <= 1f) {
                        volume.set(scratch.level)
                        ui.volumeBoost = 1f
                    } else {
                        volume.set(1f)
                        if (volume.atMax()) {
                            ui.volumeBoost = (100f + (scratch.level - 1f) * (VOLUME_BOOST_MAX - 1f) * 100f).roundToInt() / 100f
                        } else {
                            scratch.level = 1f
                            ui.volumeBoost = 1f
                        }
                    }
                    volumeIndicator = VolumeIndicator(volume.level(), ui.volumeBoost)
                    volumeStamp++
                }
            }
        }

        override fun onSwipeEnd(kind: SwipeKind) {
            gestureActive = false
            if (kind == SwipeKind.VOLUME) {
                volumeStamp++
                if (volumeConfig.rememberVolume) prefs.edit().putFloat(LuckyPrefs.PLAYER_VOLUME, volume.level()).apply()
            }
            if (kind == SwipeKind.SEEK && scratch.target != scratch.startPosition) {
                currentPlayer?.seekTo(scratch.target, exact = true)
            }
        }

        override fun onTransform(zoom: Float, panX: Float, panY: Float) {
            if (containerSize.width <= 0 || containerSize.height <= 0) return
            gestureActive = true
            ui.zoom = (ui.zoom * zoom).coerceIn(0.5f, 8f)
            ui.panX += 2f * panX / containerSize.width
            ui.panY += 2f * panY / containerSize.height
            clampPan()
            zoomResetShown = false
            flash(GestureHint.Zoom((ui.zoom * 100).roundToInt()))
        }

        override fun onTransformEnd() {
            gestureActive = false
            if (abs(ui.zoom - 1f) < 0.06f) {
                ui.zoom = 1f
                clampPan()
            }
            zoomResetShown = ui.zoomed
            zoomResetStamp++
        }
    }

    val loopPosition: () -> Long = {
        extrapolatedPosition(anchorPosition, anchorUptime, status.state == PlaybackState.PLAYING, effectiveSpeed)
            .coerceIn(0L, status.durationMs ?: Long.MAX_VALUE)
    }
    val startLoop: () -> Unit = {
        val position = loopPosition()
        ui.loopStartMs = position
        if ((ui.loopEndMs ?: Long.MAX_VALUE) < position + LOOP_MIN_MS) ui.loopEndMs = null
        flash(GestureHint.Message(context.getString(R.string.player_ab_a_set, formatDuration(position)), Icons.Rounded.Loop))
    }
    val endLoop: () -> Unit = {
        val start = ui.loopStartMs
        val position = loopPosition()
        when {
            start == null -> flash(GestureHint.Message(context.getString(R.string.player_ab_need_a), Icons.Rounded.Loop))
            position < start + LOOP_MIN_MS -> flash(GestureHint.Message(context.getString(R.string.player_ab_b_after_a), Icons.Rounded.Loop))
            else -> {
                ui.loopEndMs = position
                flash(GestureHint.Message(context.getString(R.string.player_ab_looping, formatDuration(start), formatDuration(position)), Icons.Rounded.Loop))
            }
        }
    }
    val clearLoop: () -> Unit = {
        ui.loopStartMs = null
        ui.loopEndMs = null
        flash(GestureHint.Message(context.getString(R.string.player_ab_cleared), Icons.Rounded.Loop))
    }
    val cycleLoop: () -> Unit = {
        when {
            ui.loopStartMs == null -> startLoop()
            ui.loopEndMs == null -> endLoop()
            else -> clearLoop()
        }
        bump()
    }

    var screenshotNotice by remember { mutableStateOf<ScreenshotNotice?>(null) }
    var screenshotBusy by remember { mutableStateOf(false) }
    LaunchedEffect(screenshotNotice) {
        if (screenshotNotice != null) {
            delay(SCREENSHOT_NOTICE_MS)
            screenshotNotice = null
        }
    }
    fun takeScreenshot() {
        val active = currentPlayer ?: return
        if (screenshotBusy || !latestStatus.hasVideo) return
        if (!ScreenshotStore.hasPermission(context)) {
            val host = context.findActivity() as? LuckyActivity
            val denied = { flash(GestureHint.Message(context.getString(R.string.player_screenshot_permission), Icons.Rounded.PhotoCamera)) }
            if (host == null) {
                denied()
            } else {
                host.requestLegacyStoragePermission { granted -> if (granted) takeScreenshot() else denied() }
            }
            return
        }
        screenshotBusy = true
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        scope.launch {
            try {
                val bitmap = active.screenshot()
                val uri = bitmap?.let { withContext(Dispatchers.IO) { ScreenshotStore.save(context, it) } }
                if (bitmap != null && uri != null) {
                    screenshotNotice = ScreenshotNotice(uri, withContext(Dispatchers.Default) { screenshotPreview(bitmap) })
                } else {
                    flash(GestureHint.Message(context.getString(R.string.player_screenshot_failed), Icons.Rounded.PhotoCamera))
                }
            } finally {
                screenshotBusy = false
            }
        }
    }
    val launchScreenshotIntent: (android.content.Intent) -> Unit = { intent ->
        val host = context.findActivity() ?: context
        runCatching { host.startActivity(intent) }
            .onFailure { flash(GestureHint.Message(context.getString(R.string.player_screenshot_failed), Icons.Rounded.PhotoCamera)) }
    }

    val barJob = remember { arrayOfNulls<Job>(1) }
    fun removeBlackBars() {
        if (ui.barScan == BarScanState.SCANNING) return
        val duration = latestStatus.durationMs ?: 0L
        ui.barScan = BarScanState.SCANNING
        barJob[0]?.cancel()
        barJob[0] = scope.launch {
            try {
                val detection = BlackBarDetector.detect(context, uri, duration, ui.videoShape?.shownAspect ?: 0.0)
                ui.barScan = when (detection) {
                    is BarDetection.Detected -> {
                        val plan = barZoomFor(
                            containerSize.width.toFloat(),
                            containerSize.height.toFloat(),
                            ui.videoShape,
                            ui.transform,
                            detection.bars
                        )
                        when {
                            plan == null -> BarScanState.FAILED
                            !plan.changed -> BarScanState.LIMITED
                            else -> {
                                ui.zoom = 1f
                                ui.zoomX = plan.zoomX
                                ui.zoomY = plan.zoomY
                                ui.panX = plan.panX
                                ui.panY = plan.panY
                                clampPan()
                                if (plan.stretchLimited) BarScanState.LIMITED else BarScanState.REMOVED
                            }
                        }
                    }
                    BarDetection.Clean -> BarScanState.NONE
                    BarDetection.Unclear -> BarScanState.UNCLEAR
                    BarDetection.Unreadable -> BarScanState.FAILED
                }
                ui.barApplied = ui.transform
            } finally {
                if (ui.barScan == BarScanState.SCANNING) ui.barScan = BarScanState.IDLE
            }
        }
    }

    val quickActions = QuickSettingsActions(
        onFit = {
            ui.fit = it
            clampPan()
        },
        onAspect = {
            ui.aspect = it
            clampPan()
        },
        onZoom = {
            ui.zoom = it.coerceIn(0.5f, 8f)
            clampPan()
        },
        onZoomX = {
            ui.zoomX = it.coerceIn(0.5f, 4f)
            clampPan()
        },
        onZoomY = {
            ui.zoomY = it.coerceIn(0.5f, 4f)
            clampPan()
        },
        onResetZoom = { ui.resetZoom() },
        onPanX = {
            ui.panX = it
            clampPan()
        },
        onPanY = {
            ui.panY = it
            clampPan()
        },
        onRemoveBars = { removeBlackBars() },
        onRotate = {
            ui.turns = (ui.turns + 1) % 4
            ui.resetZoom()
        },
        onMirror = { ui.mirror = !ui.mirror },
        onSpeed = { ui.speed = it.coerceIn(0.25f, 4f) },
        onOrientation = {
            ui.orientation = it
            ui.manualOrientation = null
        },
        onRepeat = { ui.repeat = it },
        onSleep = { minutes, endOfVideo ->
            ui.sleepTimer = when {
                endOfVideo -> SleepTimer(null, 0, true)
                minutes != null -> SleepTimer(SystemClock.elapsedRealtime() + minutes * 60_000L, minutes, false)
                else -> null
            }
        },
        onUpscaler = { ui.upscaler = it },
        onDownscaler = { ui.downscaler = it },
        onHdrOutput = { ui.hdrOutput = it },
        onSmoothMotion = { ui.smoothMotion = it },
        onMotionQuality = { ui.motionQuality = it },
        onLoopStart = startLoop,
        onLoopEnd = endLoop,
        onLoopClear = clearLoop,
        onScreenshot = {
            showQuick = false
            takeScreenshot()
        },
        onAudioOnly = { ui.audioOnly = !ui.audioOnly }
    )
    val subtitleControls = SubtitleControls(
        delayMs = ui.subtitleDelayMs,
        scale = ui.subtitleScale,
        look = config.subtitles,
        onDelay = { ui.subtitleDelayMs = it.coerceIn(-60_000L, 60_000L) },
        onScale = { ui.subtitleScale = it },
        onLook = { look ->
            prefs.edit()
                .putInt(LuckyPrefs.SUBTITLE_SIZE, look.sizePercent)
                .putString(LuckyPrefs.SUBTITLE_COLOR, look.color.key)
                .putString(LuckyPrefs.SUBTITLE_EDGE, look.edge.key)
                .putBoolean(LuckyPrefs.SUBTITLE_BOLD, look.bold)
                .putString(LuckyPrefs.SUBTITLE_POSITION, look.position.key)
                .putBoolean(LuckyPrefs.SUBTITLE_SYSTEM_STYLE, look.followSystem)
                .apply()
        }
    )
    val audioControls = AudioControlsState(
        delayMs = ui.audioDelayMs,
        boost = ui.volumeBoost,
        night = config.audioNight,
        dialogue = config.audioDialogue,
        channels = config.audioChannels,
        balance = config.audioBalance,
        normalize = config.audioNormalize,
        preset = config.equalizer.preset,
        onDelay = { ui.audioDelayMs = it },
        onBoost = { ui.volumeBoost = it.coerceIn(1f, VOLUME_BOOST_MAX) },
        onNight = { prefs.edit().putBoolean(LuckyPrefs.AUDIO_NIGHT, it).apply() },
        onDialogue = { prefs.edit().putBoolean(LuckyPrefs.AUDIO_DIALOGUE, it).apply() },
        onNormalize = { prefs.edit().putString(LuckyPrefs.AUDIO_NORMALIZE, (if (it) AudioNormalize.NORMALIZE else AudioNormalize.OFF).name).apply() },
        onChannels = { prefs.edit().putString(LuckyPrefs.AUDIO_CHANNELS, it.name).apply() },
        onBalance = { prefs.edit().putFloat(LuckyPrefs.AUDIO_BALANCE, it).apply() },
        onEqualizer = {
            showAudio = false
            showEqualizer = true
        }
    )
    val motionOnText = stringResource(R.string.player_motion_on)
    val motionOffText = stringResource(R.string.player_motion_off)
    val look = config.look
    val palette = rememberPlayerPalette(look.iconTint, look.accentTint)
    val chromeStyle = remember(look.buttonStyle, look.playShape, look.glow) { PlayerChromeStyle(look.buttonStyle, look.playShape, look.glow) }
    val docked = look.layout == ControlsLayout.DOCKED
    val controlsScale = look.controlsSize.scale

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerSize = coordinates.size
                containerOrigin = coordinates.positionInWindow()
            }
    ) {
        val audioOnlyShown = ui.audioOnly && status.hasVideo
        if (audioOnlyShown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        if ((!status.hasVideo || audioOnlyShown) && player != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (audioOnlyShown) Icons.Rounded.Headphones else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(if (inPip) 40.dp else 96.dp)
                )
                if (!inPip) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    if (audioOnlyShown) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.player_audio_only_on),
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (status.hasVideo && (cues.isNotEmpty() || status.assActive)) {
            SubtitleOverlay(
                cues = cues,
                position = {
                    extrapolatedPosition(anchorPosition, anchorUptime, status.state == PlaybackState.PLAYING, effectiveSpeed) - ui.subtitleDelayMs
                },
                placement = placement,
                videoWidth = status.videoWidth,
                videoHeight = status.videoHeight,
                raised = controlsVisible && !inPip && !ui.locked,
                style = config.subtitles,
                raiseBy = if (docked) 168.dp else 120.dp,
                scale = ui.subtitleScale,
                assActive = status.assActive,
                textAss = status.textAss,
                renderAss = { time, width, height, scale, lift, force ->
                    currentPlayer?.renderSubtitles(time, width, height, scale, lift, force) ?: SubtitleFrame.Empty
                },
                loadBitmap = { id, rect -> currentPlayer?.subtitleBitmap(id, rect) },
                modifier = if (inPip) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            )
        }

        if (!inPip) {
            Box(
                Modifier
                    .fillMaxSize()
                    .playerGestures(
                        GestureOptions(
                            locked = ui.locked,
                            doubleTap = config.doubleTap != DoubleTapAction.OFF,
                            hold = config.holdSpeed > 0f,
                            swipeSeek = config.swipeSeek,
                            swipeBrightness = config.swipeBrightness,
                            swipeVolume = config.swipeVolume,
                            swapSides = config.swapSides,
                            pinch = config.pinchZoom
                        ),
                        handler
                    )
            )
        }

        val loading = player == null && !openFailed || status.state == PlaybackState.OPENING || status.state == PlaybackState.BUFFERING
        if (loading && status.state != PlaybackState.ERROR) {
            CircularProgressIndicator(
                color = colors.primary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (inPip) 28.dp else 48.dp)
            )
        }

        if (!inPip) {
            TapSeekRipple(tapFeedback)
            GestureHintOverlay(hint)
            BrightnessOverlay(brightnessIndicator)
            VolumeOverlay(volumeIndicator)
            ZoomResetChip(visible = zoomResetShown && !ui.locked) {
                ui.resetZoom()
                zoomResetShown = false
                flash(GestureHint.Zoom(100))
            }
            screenshotNotice?.let { notice ->
                ScreenshotNoticeCard(
                    notice = notice,
                    onOpen = {
                        launchScreenshotIntent(ScreenshotStore.openIntent(notice.uri))
                        screenshotNotice = null
                    },
                    onShare = {
                        launchScreenshotIntent(ScreenshotStore.shareIntent(notice.uri, context.getString(R.string.player_screenshot_share_title)))
                        screenshotNotice = null
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 12.dp, top = 64.dp, end = 12.dp)
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                )
            }
        }

        val errorMessage = when {
            openFailed -> ""
            status.state == PlaybackState.ERROR -> status.error.orEmpty()
            else -> null
        }

        if (!inPip && ui.locked) {
            CompositionLocalProvider(LocalPlayerPalette provides palette, LocalPlayerChromeStyle provides chromeStyle) {
                LockedOverlay(
                    visible = lockedHintVisible,
                    onUnlock = {
                        ui.locked = false
                        lockedHintVisible = false
                        bump()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 24.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = !inPip && ((controlsVisible && !ui.locked) || errorMessage != null),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            val visibility = this
            CompositionLocalProvider(LocalPlayerPalette provides palette, LocalPlayerChromeStyle provides chromeStyle) {
                Box(Modifier.fillMaxSize()) {
                    PlayerScrim(look.scrim, docked)
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        val compact = maxWidth < 420.dp
                        val wide = maxWidth >= 600.dp
                        val chromeReady = errorMessage == null && player != null
                        val stepsAvailable = chromeReady && status.hasVideo && status.state == PlaybackState.PAUSED
                        val stepBack: () -> Unit = {
                            currentPlayer?.stepFrame(false)
                            bump()
                        }
                        val stepForward: () -> Unit = {
                            currentPlayer?.stepFrame(true)
                            bump()
                        }
                        val onToggle: () -> Unit = {
                            togglePlayback()
                            bump()
                        }
                        val onRewind: () -> Unit = {
                            skip(-config.seekStepMs)
                            bump()
                        }
                        val onForward: () -> Unit = {
                            skip(config.seekStepMs)
                            bump()
                        }
                        val onPrevious: (() -> Unit)? = goPrevious?.let { action -> { action(); bump() } }
                        val onNext: (() -> Unit)? = if (inQueue) goNext?.let { action -> { action(); bump() } } else null
                        val quickState = QuickButtonState(
                            speed = ui.speed,
                            aspectActive = ui.aspect > 0f || ui.zoomed,
                            repeat = ui.repeat,
                            mirror = ui.mirror,
                            rotated = ui.manualOrientation != null,
                            loopStartMs = ui.loopStartMs,
                            loopEndMs = ui.loopEndMs,
                            interpolating = ui.smoothMotion == SmoothMotion.INTERPOLATE
                        )
                        val seekRow: @Composable () -> Unit = {
                            PlayerSeekRow(
                                status = status,
                                scrubFraction = scrubFraction,
                                showRemaining = showRemaining,
                                style = look.seekStyle,
                                timeLayout = look.timeLayout,
                                onToggleRemaining = {
                                    showRemaining = !showRemaining
                                    prefs.edit().putBoolean(LuckyPrefs.PLAYER_SHOW_REMAINING, showRemaining).apply()
                                    bump()
                                },
                                onScrub = { fraction ->
                                    scrubFraction = fraction
                                    status.durationMs?.let { duration ->
                                        val target = (duration * fraction).toLong()
                                        currentPlayer?.seekTo(target, exact = false)
                                        preview?.let { active ->
                                            active.request(target)
                                            active.peek(target)?.let { previewBitmap = it }
                                        }
                                    }
                                    bump()
                                },
                                onScrubFinished = {
                                    val fraction = scrubFraction
                                    val duration = status.durationMs
                                    if (fraction != null && duration != null) {
                                        currentPlayer?.seekTo((duration * fraction).toLong(), exact = true)
                                    }
                                    scrubFraction = null
                                    previewBitmap = null
                                    bump()
                                },
                                previewEnabled = !inPip && preview?.unavailable == false,
                                previewBitmap = previewBitmap,
                                loopStartMs = ui.loopStartMs,
                                loopEndMs = ui.loopEndMs
                            )
                        }
                        val quickRow: @Composable (Modifier, Alignment) -> Unit = { rowModifier, rowAlignment ->
                            PlayerQuickButtons(
                                buttons = config.quickButtons,
                                order = config.quickOrder,
                                state = quickState,
                                pipAvailable = pipAvailable && status.hasVideo,
                                screenshotAvailable = status.hasVideo,
                                onMotion = {
                                    val enable = ui.smoothMotion != SmoothMotion.INTERPOLATE
                                    ui.smoothMotion = if (enable) {
                                        SmoothMotion.INTERPOLATE
                                    } else {
                                        config.smoothMotion.takeIf { it != SmoothMotion.INTERPOLATE } ?: SmoothMotion.OFF
                                    }
                                    flash(GestureHint.Message(if (enable) motionOnText else motionOffText, Icons.Rounded.AutoAwesomeMotion))
                                    bump()
                                },
                                onAspect = {
                                    val modes = FitMode.entries
                                    val nextMode = modes[(modes.indexOf(ui.fit) + 1) % modes.size]
                                    ui.fit = nextMode
                                    ui.aspect = 0f
                                    ui.resetZoom()
                                    flash(GestureHint.Message(fitLabels.getValue(nextMode), Icons.Rounded.AspectRatio))
                                    bump()
                                },
                                onRotate = {
                                    ui.manualOrientation = if (landscapeNow) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                    flash(GestureHint.Message(if (landscapeNow) rotatePortrait else rotateLandscape, Icons.Rounded.ScreenRotation))
                                    bump()
                                },
                                onSpeed = {
                                    showSpeed = true
                                    bump()
                                },
                                onRepeat = {
                                    val modes = RepeatMode.entries
                                    val nextMode = modes[(modes.indexOf(ui.repeat) + 1) % modes.size]
                                    ui.repeat = nextMode
                                    flash(GestureHint.Message(repeatLabels.getValue(nextMode), Icons.Rounded.Repeat))
                                    bump()
                                },
                                onMirror = {
                                    ui.mirror = !ui.mirror
                                    bump()
                                },
                                onPip = onEnterPip,
                                onBackground = {
                                    val active = currentPlayer
                                    if (active != null && !active.isReleased) {
                                        ui.backgroundSession = true
                                        if (!playing) active.play()
                                        scope.launch {
                                            withTimeoutOrNull(BACKGROUND_SERVICE_WAIT_MS) { NowPlaying.serviceActive.first { it } }
                                            delay(BACKGROUND_LEAVE_DELAY_MS)
                                            context.findActivity()?.moveTaskToBack(true)
                                        }
                                    }
                                },
                                onLoop = cycleLoop,
                                onScreenshot = {
                                    takeScreenshot()
                                    bump()
                                },
                                modifier = rowModifier,
                                alignment = rowAlignment
                            )
                        }
                        Column(Modifier.fillMaxSize()) {
                            val clock = if (config.showClock) rememberClockText(context, controlsVisible) else null
                            PlayerTopBar(
                                title = title,
                                subtitle = listOfNotNull(
                                    chapterTitle,
                                    if (inQueue) stringResource(R.string.player_queue_position, queueIndex + 1, queueSize) else null
                                ).joinToString(" · ").ifEmpty { null },
                                clock = clock,
                                compact = compact,
                                onBack = onClose,
                                onChapters = if (status.chapters.size > 1 && TopButton.CHAPTERS.key in config.topButtons) {
                                    {
                                        showChapters = true
                                        bump()
                                    }
                                } else {
                                    null
                                },
                                onSubtitles = if (player != null && status.hasVideo && TopButton.SUBTITLES.key in config.topButtons) {
                                    {
                                        showSubtitles = true
                                        bump()
                                    }
                                } else {
                                    null
                                },
                                subtitlesOn = status.selectedSubtitle != null,
                                onAudio = if (player != null && (status.audioCodec != null || status.audioTracks.isNotEmpty()) && TopButton.AUDIO.key in config.topButtons) {
                                    {
                                        showAudio = true
                                        bump()
                                    }
                                } else {
                                    null
                                },
                                onQuickSettings = {
                                    showQuick = true
                                    bump()
                                },
                                onStats = if (TopButton.STATS.key in config.topButtons || showStats) {
                                    {
                                        showStats = !showStats
                                        bump()
                                    }
                                } else {
                                    null
                                },
                                statsOn = showStats,
                                modifier = with(visibility) { chromeMotion(look.motion, ChromeEdge.TOP) }
                            )
                            BoxWithConstraints(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                if (!docked) {
                                    val stepsBelow = stepsAvailable && maxHeight >= 196.dp * controlsScale
                                    val stepsInline = stepsAvailable && !stepsBelow && maxWidth >= 600.dp
                                    if (chromeReady) {
                                        PlayerCenterControls(
                                            state = status.state,
                                            scale = controlsScale,
                                            seekStepMs = config.seekStepMs,
                                            showSeekButtons = config.seekButtons,
                                            onToggle = onToggle,
                                            onRewind = onRewind,
                                            onForward = onForward,
                                            onPrevious = onPrevious,
                                            onNext = onNext,
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .then(with(visibility) { chromeMotion(look.motion, ChromeEdge.CENTER) }),
                                            onStepBack = if (stepsInline) stepBack else null,
                                            onStepForward = if (stepsInline) stepForward else null,
                                            compact = maxWidth < 360.dp
                                        )
                                    }
                                    if (stepsBelow) {
                                        PlayerStepControls(
                                            scale = controlsScale,
                                            onStepBack = stepBack,
                                            onStepForward = stepForward,
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 6.dp)
                                        )
                                    }
                                } else if (stepsAvailable && !wide) {
                                    PlayerStepControls(
                                        scale = controlsScale,
                                        onStepBack = stepBack,
                                        onStepForward = stepForward,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 10.dp)
                                    )
                                }
                                if (showStats && player != null) {
                                    StatsPanel(
                                        status = status,
                                        speed = effectiveSpeed,
                                        upscaler = ui.upscaler,
                                        downscaler = ui.downscaler,
                                        preferHardware = preferHardware,
                                        onDecoderChange = { hardware ->
                                            if (hardware != preferHardware) {
                                                resumeAtMs = status.positionMs
                                                prefs.edit().putBoolean(LuckyPrefs.HARDWARE_DECODING, hardware).apply()
                                                preferHardware = hardware
                                            }
                                            bump()
                                        },
                                        renderer = renderer,
                                        onRendererChange = { choice ->
                                            if (choice != renderer) {
                                                resumeAtMs = status.positionMs
                                                prefs.edit().putString(LuckyPrefs.RENDERER, choice.name).apply()
                                                renderer = choice
                                            }
                                            bump()
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(12.dp)
                                    )
                                }
                            }
                            if (chromeReady && docked) {
                                PlayerPanel(
                                    Modifier
                                        .then(with(visibility) { chromeMotion(look.motion, ChromeEdge.BOTTOM) })
                                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                                        .fillMaxWidth()
                                ) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp, bottom = 4.dp)
                                    ) {
                                        seekRow()
                                        if (wide) {
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                PlayerCenterControls(
                                                    state = status.state,
                                                    scale = controlsScale,
                                                    seekStepMs = config.seekStepMs,
                                                    showSeekButtons = config.seekButtons,
                                                    onToggle = onToggle,
                                                    onRewind = onRewind,
                                                    onForward = onForward,
                                                    onPrevious = onPrevious,
                                                    onNext = onNext,
                                                    onStepBack = if (stepsAvailable) stepBack else null,
                                                    onStepForward = if (stepsAvailable) stepForward else null,
                                                    docked = true
                                                )
                                                quickRow(Modifier.weight(1f), Alignment.CenterEnd)
                                            }
                                        } else {
                                            PlayerCenterControls(
                                                state = status.state,
                                                scale = controlsScale,
                                                seekStepMs = config.seekStepMs,
                                                showSeekButtons = config.seekButtons,
                                                onToggle = onToggle,
                                                onRewind = onRewind,
                                                onForward = onForward,
                                                onPrevious = onPrevious,
                                                onNext = onNext,
                                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                                compact = compact,
                                                docked = true
                                            )
                                            quickRow(Modifier.fillMaxWidth(), Alignment.Center)
                                        }
                                    }
                                }
                            } else if (chromeReady) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(with(visibility) { chromeMotion(look.motion, ChromeEdge.BOTTOM) })
                                ) {
                                    seekRow()
                                    quickRow(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        Alignment.CenterEnd
                                    )
                                }
                            }
                        }
                        if (chromeReady && QuickButton.LOCK.key in config.quickButtons) {
                            PlayerSideLock(
                                onLock = {
                                    ui.locked = true
                                    controlsVisible = false
                                    lockedHintVisible = true
                                    interaction++
                                    flash(GestureHint.Message(lockedText, Icons.Rounded.Lock))
                                },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        if (!inPip && !ui.locked && errorMessage == null && player != null) {
            ResumeNotice(
                resumedFromMs = resumeNotice.takeIf { statusKey == uriKey },
                raised = controlsVisible,
                onStartOver = {
                    currentPlayer?.seekTo(0L)
                    resumeNotice = null
                    bump()
                },
                modifier = Modifier.align(Alignment.BottomStart)
            )
            SkipSegmentButton(
                segment = skipSegmentAt(status.chapters, status.positionMs, status.durationMs),
                raised = controlsVisible,
                hasNext = goNext != null,
                onSkip = { segment ->
                    val target = segment.endMs
                    if (target != null) {
                        currentPlayer?.seekTo(target)
                    } else {
                        goNext?.invoke()
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
        if (errorMessage != null && !inPip) {
            ErrorPanel(
                message = errorMessage,
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
    }

    if (showSubtitles && player != null && !inPip) {
        SubtitlesSheet(
            tracks = status.subtitleTracks,
            selected = status.selectedSubtitle,
            onSelect = { index ->
                currentPlayer?.selectSubtitle(index)
                rememberSubtitle(index)
            },
            onAddFile = if (status.hasVideo) ({ subtitlePicker.launch(arrayOf("*/*")) }) else null,
            controls = subtitleControls,
            onDismiss = { showSubtitles = false }
        )
    }
    if (showAudio && player != null && !inPip) {
        AudioSheet(
            tracks = status.audioTracks,
            selected = status.selectedAudio,
            allowOff = status.hasVideo,
            onSelect = { index ->
                currentPlayer?.selectAudio(index)
                rememberAudio(index)
            },
            controls = audioControls,
            onDismiss = { showAudio = false }
        )
    }
    if (showQuick && !inPip) {
        QuickSettingsPanel(
            ui = ui,
            panLimits = panLimitsFor(containerSize.width.toFloat(), containerSize.height.toFloat(), ui.videoShape, ui.transform),
            hdrSupported = caps.hdr10,
            actions = quickActions,
            tab = quickTab,
            onTab = { quickTab = it },
            scrollState = quickScrollState,
            onDismiss = { showQuick = false }
        )
    }
    if (showEqualizer && !inPip) {
        EqualizerSheet(
            equalizer = config.equalizer,
            onChange = { saveEqualizer(prefs, it) },
            onDismiss = { showEqualizer = false }
        )
    }
    if (showChapters && !inPip) {
        ChaptersSheet(
            chapters = status.chapters,
            positionMs = status.positionMs,
            onSelect = { chapter ->
                currentPlayer?.seekTo(chapter.startMs)
                showChapters = false
                bump()
            },
            onDismiss = { showChapters = false }
        )
    }
    if (showSpeed && !inPip) {
        SpeedSheet(
            speed = ui.speed,
            onSpeed = { ui.speed = it.coerceIn(0.25f, 4f) },
            onDismiss = { showSpeed = false }
        )
    }
}
