package com.jhaiian.lucky.ui.player

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.Chapter
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.HdrOutput
import com.jhaiian.lucky.engine.Downscaler
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.SmoothMotion
import com.jhaiian.lucky.engine.Upscaler
import com.jhaiian.lucky.ui.components.rememberSheetFlingConnection
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.settings.downscalerLabel
import com.jhaiian.lucky.ui.settings.hdrOutputDetail
import com.jhaiian.lucky.ui.settings.hdrOutputLabel
import com.jhaiian.lucky.ui.settings.motionQualityLabel
import com.jhaiian.lucky.ui.settings.smoothMotionLabel
import com.jhaiian.lucky.ui.settings.upscalerLabel
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    val onScreenshot: () -> Unit
)

@Composable
internal fun SheetSection(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = LocalLuckyColors.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        content()
    }
}

@Composable
private fun SpeedSection(speed: Float, onSpeed: (Float) -> Unit) {
    val colors = LocalLuckyColors.current
    SheetSection(
        title = stringResource(R.string.player_button_speed),
        trailing = {
            Text(formatSpeed(speed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
        }
    ) {
        Slider(
            value = speed,
            onValueChange = { onSpeed(((it * 20).roundToInt() / 20f).coerceIn(0.25f, 4f)) },
            valueRange = 0.25f..4f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.primary.copy(alpha = 0.2f)
            )
        )
        ChipFlow {
            SpeedPresets.forEach { preset ->
                PlayerChip(formatSpeed(preset), speed == preset) { onSpeed(preset) }
            }
        }
    }
}

@Composable
private fun ZoomAxisSection(title: String, value: Float, onChange: (Float) -> Unit) {
    val colors = LocalLuckyColors.current
    SheetSection(
        title = title,
        trailing = {
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
        }
    ) {
        Slider(
            value = value,
            onValueChange = { onChange((it * 100).roundToInt() / 100f) },
            valueRange = 0.5f..4f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.primary.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
private fun PanSection(title: String, value: Float, limit: Float, onChange: (Float) -> Unit) {
    val colors = LocalLuckyColors.current
    val enabled = limit > 0.001f
    val bound = if (enabled) limit else 1f
    SheetSection(
        title = title,
        trailing = {
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) colors.onSurface else colors.secondaryText
            )
        }
    ) {
        Slider(
            value = value.coerceIn(-bound, bound),
            onValueChange = { onChange(((it * 1000).roundToInt() / 1000f).coerceIn(-bound, bound)) },
            valueRange = -bound..bound,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.primary.copy(alpha = 0.2f),
                disabledThumbColor = colors.primary.copy(alpha = 0.38f),
                disabledActiveTrackColor = colors.primary.copy(alpha = 0.38f),
                disabledInactiveTrackColor = colors.primary.copy(alpha = 0.12f)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqualizerSheet(equalizer: Equalizer, onChange: (Equalizer) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalLuckyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardBackground,
        contentColor = colors.onSurface
    ) {
        ImmersiveDialogWindow()
        Column(
            Modifier
                .nestedScroll(rememberSheetFlingConnection(sheetState, consumeFling = false))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            SheetSection(stringResource(R.string.equalizer)) {
                EqualizerEditor(equalizer, onChange)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalLuckyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardBackground,
        contentColor = colors.onSurface
    ) {
        ImmersiveDialogWindow()
        Column(
            Modifier
                .nestedScroll(rememberSheetFlingConnection(sheetState, consumeFling = false))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            SpeedSection(speed, onSpeed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickSettingsSheet(
    ui: PlayerUiState,
    panLimits: PanLimits,
    hdrSupported: Boolean,
    actions: QuickSettingsActions,
    scrollState: ScrollState,
    onDismiss: () -> Unit
) {
    val colors = LocalLuckyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardBackground,
        contentColor = colors.onSurface
    ) {
        ImmersiveDialogWindow()
        Column(
            Modifier
                .nestedScroll(rememberSheetFlingConnection(sheetState, consumeFling = false))
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                stringResource(R.string.player_quick_settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            SheetSection(stringResource(R.string.player_section_video_size)) {
                ChipFlow {
                    FitMode.entries.forEach { mode ->
                        PlayerChip(stringResource(FitModeLabels.getValue(mode)), ui.fit == mode) { actions.onFit(mode) }
                    }
                }
            }
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
            SheetSection(stringResource(R.string.player_section_aspect)) {
                ChipFlow {
                    PlayerChip(stringResource(R.string.player_aspect_auto), ui.aspect <= 0f) { actions.onAspect(0f) }
                    AspectPresets.forEach { preset ->
                        PlayerChip(preset.label, ui.aspect == preset.ratio) { actions.onAspect(preset.ratio) }
                    }
                }
            }
            if (ui.hasVideo) {
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
            SpeedSection(ui.speed, actions.onSpeed)
            SheetSection(stringResource(R.string.player_section_orientation)) {
                ChipFlow {
                    OrientationMode.entries.forEach { mode ->
                        PlayerChip(stringResource(mode.labelRes), ui.manualOrientation == null && ui.orientation == mode) {
                            actions.onOrientation(mode)
                        }
                    }
                }
            }
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
                    PlayerChip(stringResource(R.string.player_ab_clear), false) { actions.onLoopClear() }
                }
            }
            if (ui.hasVideo) {
                SheetSection(stringResource(R.string.player_button_screenshot)) {
                    ChipFlow {
                        PlayerChip(stringResource(R.string.player_screenshot_take), false) { actions.onScreenshot() }
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
        }
    }
}

@Composable
internal fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onReset: (() -> Unit)?) {
    val colors = LocalLuckyColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) {
            Icon(Icons.Rounded.Remove, contentDescription = null, tint = colors.primary)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            modifier = Modifier.widthIn(min = 64.dp),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onPlus) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.primary)
        }
        if (onReset != null) {
            IconButton(onClick = onReset) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.player_zoom_reset), tint = colors.secondaryText)
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(chapters: List<Chapter>, positionMs: Long, onSelect: (Chapter) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalLuckyColors.current
    val current = currentChapterIndex(chapters, positionMs)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardBackground,
        contentColor = colors.onSurface
    ) {
        ImmersiveDialogWindow()
        Column(
            Modifier
                .nestedScroll(rememberSheetFlingConnection(sheetState, consumeFling = false))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                stringResource(R.string.player_chapters),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
            chapters.forEachIndexed { index, chapter ->
                val selected = index == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(chapter) }
                        .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) colors.primary else colors.secondaryText,
                        modifier = Modifier.widthIn(min = 32.dp)
                    )
                    Text(
                        chapter.title ?: stringResource(R.string.player_chapter_number, index + 1),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.primary else colors.onSurface,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatDuration(chapter.startMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}

fun currentChapterIndex(chapters: List<Chapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs + 250 }
