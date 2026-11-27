package com.jhaiian.lucky.ui.player

import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jhaiian.lucky.data.LuckyPrefs
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.Downscaler
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.HdrOutput
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.SmoothMotion
import com.jhaiian.lucky.engine.Upscaler
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.settings.downscalerLabel
import com.jhaiian.lucky.ui.settings.hdrOutputDetail
import com.jhaiian.lucky.ui.settings.hdrOutputLabel
import com.jhaiian.lucky.ui.settings.motionQualityLabel
import com.jhaiian.lucky.ui.settings.smoothMotionLabel
import com.jhaiian.lucky.ui.settings.upscalerLabel
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class QuickSettingsActions(
    val onFit: (FitMode) -> Unit,
    val onAspect: (Float) -> Unit,
    val onZoom: (Float) -> Unit,
    val onZoomX: (Float) -> Unit,
    val onZoomY: (Float) -> Unit,
    val onResetZoom: () -> Unit,
    val onPanX: (Float) -> Unit,
    val onPanY: (Float) -> Unit,
    val onRemoveBars: () -> Unit,
    val onRotate: () -> Unit,
    val onMirror: () -> Unit,
    val onSpeed: (Float) -> Unit,
    val onOrientation: (OrientationMode) -> Unit,
    val onRepeat: (RepeatMode) -> Unit,
    val onSleep: (Int?, Boolean) -> Unit,
    val onUpscaler: (Upscaler) -> Unit,
    val onDownscaler: (Downscaler) -> Unit,
    val onHdrOutput: (HdrOutput) -> Unit,
    val onSmoothMotion: (SmoothMotion) -> Unit,
    val onMotionQuality: (MotionQuality) -> Unit,
    val onLoopStart: () -> Unit,
    val onLoopEnd: () -> Unit,
    val onLoopClear: () -> Unit,
    val onScreenshot: () -> Unit,
    val onAudioOnly: () -> Unit
)

enum class QuickTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    PLAYBACK(R.string.player_tab_playback, Icons.Rounded.PlayCircle),
    PICTURE(R.string.player_tab_picture, Icons.Rounded.AspectRatio),
    QUALITY(R.string.player_tab_quality, Icons.Rounded.AutoAwesome)
}

internal object SidePanelPin {
    var pinned by mutableStateOf(false)
}

@Composable
private fun ImmersivePopup() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            view.rootView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        onDispose { }
    }
}

@Composable
private fun ResizeHandle(vertical: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val label = stringResource(R.string.player_panel_resize)
    Box(
        modifier
            .then(if (vertical) Modifier.width(20.dp).fillMaxHeight() else Modifier.fillMaxWidth().height(20.dp))
            .semantics { contentDescription = label }
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = if (vertical) Orientation.Horizontal else Orientation.Vertical,
                onDragStopped = { onDragEnd() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .then(if (vertical) Modifier.width(4.dp).height(40.dp) else Modifier.width(40.dp).height(4.dp))
                .clip(RoundedCornerShape(50))
                .background(colors.secondaryText.copy(alpha = 0.5f))
        )
    }
}

@Composable
internal fun PlayerSidePanel(
    title: String,
    onDismiss: () -> Unit,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalLuckyColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val dismiss by rememberUpdatedState(onDismiss)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val closed = !visible.targetState && !visible.currentState && visible.isIdle
    LaunchedEffect(closed) {
        if (closed) dismiss()
    }
    val close = { visible.targetState = false }
    val pinned = SidePanelPin.pinned
    BackHandler(enabled = pinned, onBack = close)
    val prefs = remember { LuckyPrefs.get(context) }
    val container = LocalWindowInfo.current.containerSize
    val screenWidth = with(density) { container.width.toDp() }
    val screenHeight = with(density) { container.height.toDp() }
    val wide = screenWidth > screenHeight || screenWidth >= 600.dp
    val cutoutEnd = with(density) {
        (if (layoutDirection == LayoutDirection.Ltr) WindowInsets.displayCutout.getRight(this, layoutDirection) else WindowInsets.displayCutout.getLeft(this, layoutDirection)).toDp()
    }
    val minWidth = 280.dp
    val maxWidth = (screenWidth * 0.7f).coerceAtLeast(minWidth).coerceAtMost(screenWidth - 24.dp - cutoutEnd)
    var panelWidth by remember {
        val saved = prefs.getFloat(LuckyPrefs.PLAYER_PANEL_WIDTH, 0f)
        mutableStateOf(if (saved > 0f) saved.dp else (screenWidth * 0.4f).coerceIn(300.dp, 400.dp))
    }
    var heightFraction by remember { mutableFloatStateOf(prefs.getFloat(LuckyPrefs.PLAYER_PANEL_HEIGHT, 0.5f).coerceIn(0.3f, 0.85f)) }
    val width = panelWidth.coerceIn(minWidth.coerceAtMost(maxWidth), maxWidth)
    val shape = if (wide) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Popup(
        alignment = if (wide) Alignment.CenterEnd else Alignment.BottomCenter,
        onDismissRequest = close,
        properties = PopupProperties(
            focusable = !pinned,
            dismissOnBackPress = true,
            dismissOnClickOutside = !pinned,
            clippingEnabled = false
        )
    ) {
        ImmersivePopup()
        val sizeModifier = if (wide) {
            Modifier
                .padding(top = 12.dp, bottom = 12.dp, end = 12.dp + cutoutEnd)
                .width(width)
                .height((screenHeight - 24.dp).coerceAtLeast(120.dp))
        } else {
            Modifier
                .width(screenWidth)
                .height(screenHeight * heightFraction)
        }
        AnimatedVisibility(
            visibleState = visible,
            modifier = sizeModifier,
            enter = if (wide) {
                slideInHorizontally(tween(240)) { it / 2 } + fadeIn(tween(200))
            } else {
                slideInVertically(tween(240)) { it / 2 } + fadeIn(tween(200))
            },
            exit = if (wide) {
                slideOutHorizontally(tween(180)) { it / 2 } + fadeOut(tween(160))
            } else {
                slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(160))
            }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(colors.cardBackground.copy(alpha = 0.97f))
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = if (wide) 12.dp else 0.dp, top = if (wide) 0.dp else 12.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = if (wide) 8.dp else 20.dp, end = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { SidePanelPin.pinned = !pinned }) {
                            Icon(
                                if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                contentDescription = stringResource(if (pinned) R.string.player_panel_unpin else R.string.player_panel_pin),
                                tint = if (pinned) colors.primary else colors.secondaryText
                            )
                        }
                        IconButton(onClick = close) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.player_panel_close), tint = colors.secondaryText)
                        }
                    }
                    header?.invoke(this)
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(top = 8.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .then(if (wide) Modifier else Modifier.navigationBarsPadding())
                            .padding(top = 4.dp, bottom = 12.dp),
                        content = content
                    )
                }
                if (wide) {
                    ResizeHandle(
                        vertical = true,
                        onDrag = { delta ->
                            val change = with(density) { delta.toDp() }
                            panelWidth = (width - if (layoutDirection == LayoutDirection.Ltr) change else -change).coerceIn(minWidth.coerceAtMost(maxWidth), maxWidth)
                        },
                        onDragEnd = { prefs.edit().putFloat(LuckyPrefs.PLAYER_PANEL_WIDTH, panelWidth.value).apply() },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                } else {
                    ResizeHandle(
                        vertical = false,
                        onDrag = { delta ->
                            val total = with(density) { screenHeight.toPx() }
                            if (total > 0f) heightFraction = (heightFraction - delta / total).coerceIn(0.3f, 0.85f)
                        },
                        onDragEnd = { prefs.edit().putFloat(LuckyPrefs.PLAYER_PANEL_HEIGHT, heightFraction).apply() },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickTabRow(tabs: List<QuickTab>, selected: QuickTab, onSelect: (QuickTab) -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            val active = tab == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) colors.primary else colors.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (active) colors.onPrimary else colors.popupText,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) colors.onPrimary else colors.popupText,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun QuickSettingsPanel(
    ui: PlayerUiState,
    panLimits: PanLimits,
    hdrSupported: Boolean,
    actions: QuickSettingsActions,
    tab: QuickTab,
    onTab: (QuickTab) -> Unit,
    scrollState: ScrollState,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val tabs = if (ui.hasVideo) QuickTab.entries else listOf(QuickTab.PLAYBACK)
    val current = if (tab in tabs) tab else QuickTab.PLAYBACK
    PlayerSidePanel(
        title = stringResource(R.string.player_quick_settings),
        onDismiss = onDismiss,
        scrollState = scrollState,
        header = if (tabs.size > 1) {
            {
                QuickTabRow(tabs, current) {
                    if (it != current) {
                        onTab(it)
                        scope.launch { scrollState.scrollTo(0) }
                    }
                }
            }
        } else {
            null
        }
    ) {
        when (current) {
            QuickTab.PLAYBACK -> PlaybackTab(ui, actions)
            QuickTab.PICTURE -> PictureTab(ui, panLimits, actions)
            QuickTab.QUALITY -> QualityTab(ui, hdrSupported, actions)
        }
    }
}

@Composable
private fun PlaybackTab(ui: PlayerUiState, actions: QuickSettingsActions) {
    val colors = LocalLuckyColors.current
    SpeedSection(ui.speed, actions.onSpeed)
    SheetSection(stringResource(R.string.player_button_repeat)) {
        ChipFlow {
            RepeatMode.entries.forEach { mode ->
                PlayerChip(stringResource(mode.labelRes), ui.repeat == mode) { actions.onRepeat(mode) }
            }
        }
    }
    val loopStart = ui.loopStartMs
    val loopEnd = ui.loopEndMs
    SheetSection(
        title = stringResource(R.string.player_button_ab_repeat),
        trailing = {
            if (loopStart != null && loopEnd != null) {
                Text(
                    "${formatDuration(loopStart)} – ${formatDuration(loopEnd)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.secondaryText
                )
            }
        }
    ) {
        ChipFlow {
            PlayerChip(stringResource(R.string.player_ab_set_a), loopStart != null) { actions.onLoopStart() }
            PlayerChip(stringResource(R.string.player_ab_set_b), loopEnd != null) { actions.onLoopEnd() }
            if (loopStart != null || loopEnd != null) {
                PlayerChip(stringResource(R.string.player_ab_clear), false) { actions.onLoopClear() }
            }
        }
    }
    val timer = ui.sleepTimer
    val remaining by produceState<Long?>(null, timer) {
        while (timer?.endsAtElapsed != null) {
            value = (timer.endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(1000)
        }
        value = null
    }
    SheetSection(
        title = stringResource(R.string.player_section_sleep),
        trailing = {
            val text = when {
                timer?.endOfVideo == true -> stringResource(R.string.player_sleep_end_of_video)
                remaining != null -> stringResource(R.string.player_sleep_remaining, formatDuration(remaining ?: 0L))
                else -> null
            }
            if (text != null) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = colors.secondaryText)
            }
        }
    ) {
        ChipFlow {
            PlayerChip(stringResource(R.string.player_option_off), timer == null) { actions.onSleep(null, false) }
            SleepPresets.forEach { minutes ->
                PlayerChip(
                    pluralStringResource(R.plurals.player_sleep_minutes, minutes, minutes),
                    timer != null && !timer.endOfVideo && timer.minutes == minutes
                ) { actions.onSleep(minutes, false) }
            }
            PlayerChip(stringResource(R.string.player_sleep_end_of_video), timer?.endOfVideo == true) { actions.onSleep(null, true) }
        }
    }
    SheetSection(stringResource(R.string.player_section_orientation)) {
        ChipFlow {
            OrientationMode.entries.forEach { mode ->
                PlayerChip(stringResource(mode.labelRes), ui.manualOrientation == null && ui.orientation == mode) {
                    actions.onOrientation(mode)
                }
            }
        }
    }
    if (ui.hasVideo) {
        SheetSection(stringResource(R.string.player_audio_only)) {
            ChipFlow {
                PlayerChip(stringResource(R.string.player_audio_only_toggle), ui.audioOnly) { actions.onAudioOnly() }
            }
            Text(
                stringResource(R.string.player_audio_only_summary),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        SheetSection(stringResource(R.string.player_button_screenshot)) {
            ChipFlow {
                PlayerChip(stringResource(R.string.player_screenshot_take), false) { actions.onScreenshot() }
            }
        }
    }
}

@Composable
private fun PictureTab(ui: PlayerUiState, panLimits: PanLimits, actions: QuickSettingsActions) {
    val colors = LocalLuckyColors.current
    SheetSection(stringResource(R.string.player_section_video_size)) {
        ChipFlow {
            FitMode.entries.forEach { mode ->
                PlayerChip(stringResource(FitModeLabels.getValue(mode)), ui.fit == mode) { actions.onFit(mode) }
            }
        }
    }
    SheetSection(stringResource(R.string.player_section_aspect)) {
        ChipFlow {
            PlayerChip(stringResource(R.string.player_aspect_auto), ui.aspect <= 0f) { actions.onAspect(0f) }
            AspectPresets.forEach { preset ->
                PlayerChip(preset.label, ui.aspect == preset.ratio) { actions.onAspect(preset.ratio) }
            }
        }
    }
    val barStatus = ui.barStatus
    SheetSection(
        title = stringResource(R.string.player_section_black_bars),
        trailing = {
            if (barStatus == BarScanState.SCANNING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                Spacer(Modifier.size(8.dp))
            }
            val label = when (barStatus) {
                BarScanState.SCANNING -> stringResource(R.string.player_black_bars_scanning)
                BarScanState.REMOVED -> stringResource(R.string.player_black_bars_removed)
                BarScanState.NONE -> stringResource(R.string.player_black_bars_none)
                BarScanState.LIMITED -> stringResource(R.string.player_black_bars_limited)
                BarScanState.UNCLEAR -> stringResource(R.string.player_black_bars_unclear)
                BarScanState.FAILED -> stringResource(R.string.player_black_bars_failed)
                BarScanState.IDLE -> null
            }
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (barStatus == BarScanState.REMOVED) colors.primary else colors.secondaryText
                )
            }
        }
    ) {
        ChipFlow {
            PlayerChip(stringResource(R.string.player_black_bars_remove), barStatus == BarScanState.REMOVED) {
                if (barStatus != BarScanState.SCANNING) actions.onRemoveBars()
            }
        }
    }
    SheetSection(
        title = stringResource(R.string.player_section_zoom),
        trailing = {
            Text(
                "${(ui.zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
        }
    ) {
        Slider(
            value = ui.zoom,
            onValueChange = { actions.onZoom((it * 20).roundToInt() / 20f) },
            valueRange = 0.5f..4f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.primary.copy(alpha = 0.2f)
            )
        )
        ChipFlow {
            PlayerChip(stringResource(R.string.player_zoom_reset), false) { actions.onResetZoom() }
            PlayerChip(stringResource(R.string.player_rotate_video), ui.turns != 0) { actions.onRotate() }
            PlayerChip(stringResource(R.string.player_button_mirror), ui.mirror) { actions.onMirror() }
        }
    }
    ZoomAxisSection(stringResource(R.string.player_section_zoom_x), ui.zoomX, actions.onZoomX)
    ZoomAxisSection(stringResource(R.string.player_section_zoom_y), ui.zoomY, actions.onZoomY)
    PanSection(stringResource(R.string.player_section_pan_x), ui.panX, panLimits.x, actions.onPanX)
    PanSection(stringResource(R.string.player_section_pan_y), ui.panY, panLimits.y, actions.onPanY)
}

@Composable
private fun QualityTab(ui: PlayerUiState, hdrSupported: Boolean, actions: QuickSettingsActions) {
    val colors = LocalLuckyColors.current
    SheetSection(stringResource(R.string.settings_smooth_motion)) {
        ChipFlow {
            SmoothMotion.entries.forEach { option ->
                PlayerChip(stringResource(smoothMotionLabel(option)), ui.smoothMotion == option) { actions.onSmoothMotion(option) }
            }
        }
    }
    if (ui.smoothMotion == SmoothMotion.INTERPOLATE) {
        SheetSection(stringResource(R.string.motion_quality)) {
            ChipFlow {
                MotionQuality.entries.forEach { option ->
                    PlayerChip(stringResource(motionQualityLabel(option)), ui.motionQuality == option) { actions.onMotionQuality(option) }
                }
            }
        }
    }
    SheetSection(stringResource(R.string.settings_upscaling)) {
        ChipFlow {
            Upscaler.entries.forEach { option ->
                PlayerChip(stringResource(upscalerLabel(option)), ui.upscaler == option) { actions.onUpscaler(option) }
            }
        }
    }
    SheetSection(stringResource(R.string.settings_downscaling)) {
        ChipFlow {
            Downscaler.entries.forEach { option ->
                PlayerChip(stringResource(downscalerLabel(option)), ui.downscaler == option) { actions.onDownscaler(option) }
            }
        }
    }
    SheetSection(stringResource(R.string.player_section_hdr)) {
        ChipFlow {
            HdrOutput.entries.forEach { option ->
                PlayerChip(stringResource(hdrOutputLabel(option)), ui.hdrOutput == option) { actions.onHdrOutput(option) }
            }
        }
        Text(
            stringResource(hdrOutputDetail(ui.hdrOutput, hdrSupported)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
