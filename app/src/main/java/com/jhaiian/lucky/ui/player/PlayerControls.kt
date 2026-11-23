package com.jhaiian.lucky.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.AudioOutputKind
import com.jhaiian.lucky.engine.PlaybackState
import com.jhaiian.lucky.engine.DisplayOutput
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.PlayerStatus
import com.jhaiian.lucky.engine.ScaleFilter
import com.jhaiian.lucky.engine.RendererPreference
import com.jhaiian.lucky.engine.Downscaler
import com.jhaiian.lucky.engine.Upscaler
import com.jhaiian.lucky.ui.settings.motionQualityLabel
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.home.formatSampleRate
import com.jhaiian.lucky.ui.findActivity
import com.jhaiian.lucky.ui.settings.downscalerLabel
import com.jhaiian.lucky.ui.settings.scaleFilterLabel
import com.jhaiian.lucky.ui.settings.upscalerLabel
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import androidx.compose.material3.Surface as MaterialSurface

internal fun seekIcons(stepMs: Long): Pair<ImageVector, ImageVector> = when (stepMs) {
    5_000L -> Icons.Rounded.Replay5 to Icons.Rounded.Forward5
    10_000L -> Icons.Rounded.Replay10 to Icons.Rounded.Forward10
    30_000L -> Icons.Rounded.Replay30 to Icons.Rounded.Forward30
    else -> Icons.Rounded.FastRewind to Icons.Rounded.FastForward
}

@Composable
private fun PlayerIcon(icon: ImageVector, description: String, active: Boolean = false, size: Float = 1f, onClick: () -> Unit) {
    val palette = LocalPlayerPalette.current
    IconButton(onClick = onClick, modifier = Modifier.size((48 * size).dp)) {
        Icon(icon, contentDescription = description, tint = if (active) palette.active else palette.icon, modifier = Modifier.size((24 * size).dp))
    }
}

@Composable
internal fun PlayerTopBar(
    title: String?,
    subtitle: String?,
    clock: Pair<String, Int?>?,
    onBack: () -> Unit,
    onSubtitles: (() -> Unit)?,
    subtitlesOn: Boolean,
    onAudio: (() -> Unit)?,
    onChapters: (() -> Unit)?,
    onQuickSettings: () -> Unit,
    onStats: () -> Unit
) {
    val iconSize = if (LocalConfiguration.current.screenWidthDp < 420) 0.9f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerIcon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.player_back), onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(
                text = title.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (clock != null) {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(clock.first, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                clock.second?.let { level ->
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Battery5Bar, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("$level%", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (onChapters != null) {
            PlayerIcon(Icons.Rounded.Bookmarks, stringResource(R.string.player_chapters), size = iconSize, onClick = onChapters)
        }
        if (onSubtitles != null) {
            PlayerIcon(Icons.Rounded.ClosedCaption, stringResource(R.string.track_subtitle), size = iconSize, active = subtitlesOn, onClick = onSubtitles)
        }
        if (onAudio != null) {
            PlayerIcon(Icons.Rounded.Audiotrack, stringResource(R.string.track_audio), size = iconSize, onClick = onAudio)
        }
        PlayerIcon(Icons.Rounded.Tune, stringResource(R.string.player_quick_settings), size = iconSize, onClick = onQuickSettings)
        PlayerIcon(Icons.Rounded.Info, stringResource(R.string.player_stats), size = iconSize, onClick = onStats)
    }
}

@Composable
internal fun PlayerCenterControls(
    state: PlaybackState,
    scale: Float,
    seekStepMs: Long,
    showSeekButtons: Boolean,
    onToggle: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onStepBack: (() -> Unit)? = null,
    onStepForward: (() -> Unit)? = null
) {
    val palette = LocalPlayerPalette.current
    val playing = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
    val (icon, label) = when {
        state == PlaybackState.ENDED -> Icons.Rounded.Replay to stringResource(R.string.player_replay)
        playing -> Icons.Rounded.Pause to stringResource(R.string.player_pause)
        else -> Icons.Rounded.PlayArrow to stringResource(R.string.player_play)
    }
    val (rewindIcon, forwardIcon) = seekIcons(seekStepMs)
    val compact = LocalConfiguration.current.screenWidthDp < 360
    val step = scale * if (compact) 0.85f else 1f
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((24 * step).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onPrevious != null || onNext != null) {
            PlayerIcon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), size = step) { onPrevious?.invoke() }
        }
        if (showSeekButtons) {
            PlayerIcon(rewindIcon, stringResource(R.string.player_rewind), size = step * 1.25f, onClick = onRewind)
        }
        if (onStepBack != null) {
            PlayerIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.player_step_back), size = step, onClick = onStepBack)
        }
        MaterialSurface(
            onClick = onToggle,
            shape = CircleShape,
            color = palette.accent,
            contentColor = palette.onAccent,
            modifier = Modifier.size((68 * step).dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size((38 * step).dp))
            }
        }
        if (onStepForward != null) {
            PlayerIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.player_step_forward), size = step, onClick = onStepForward)
        }
        if (showSeekButtons) {
            PlayerIcon(forwardIcon, stringResource(R.string.player_forward), size = step * 1.25f, onClick = onForward)
        }
        if (onPrevious != null || onNext != null) {
            PlayerIcon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), size = step) { onNext?.invoke() }
        }
    }
}

@Composable
internal fun PlayerStepControls(
    scale: Float,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy((16 * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.player_step_back), size = scale, onClick = onStepBack)
        PlayerIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.player_step_forward), size = scale, onClick = onStepForward)
    }
}

@Composable
internal fun PlayerSeekRow(
    status: PlayerStatus,
    scrubFraction: Float?,
    showRemaining: Boolean,
    style: SeekStyle,
    onToggleRemaining: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    previewEnabled: Boolean = false,
    previewBitmap: Bitmap? = null,
    loopStartMs: Long? = null,
    loopEndMs: Long? = null
) {
    val duration = status.durationMs
    val fraction = scrubFraction ?: if (duration != null && duration > 0) {
        (status.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownPosition = if (scrubFraction != null && duration != null) (duration * scrubFraction).toLong() else status.positionMs
    val enabled = duration != null && duration > 0 && status.seekable
    val markers = remember(status.chapters, duration) {
        if (duration == null || duration <= 0) emptyList()
        else status.chapters.map { it.startMs.toFloat() / duration }.filter { it > 0.005f && it < 0.995f }
    }
    val remaining = duration?.let { (it - shownPosition).coerceAtLeast(0L) }
    val total = when {
        duration == null -> null
        showRemaining -> "−" + formatDuration(remaining ?: 0L)
        else -> formatDuration(duration)
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var barStart by remember { mutableFloatStateOf(0f) }
    var barSpan by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (total != null) stringResource(R.string.player_time_pair, formatDuration(shownPosition), total) else formatDuration(shownPosition),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = duration != null, onClick = onToggleRemaining)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(10.dp))
            PlayerSeekBar(
                style = style,
                value = fraction,
                enabled = enabled,
                playing = status.state == PlaybackState.PLAYING,
                markers = markers,
                onValueChange = onScrub,
                onValueChangeFinished = onScrubFinished,
                loopStart = loopStartMs?.let { start -> duration?.takeIf { it > 0 }?.let { (start.toFloat() / it).coerceIn(0f, 1f) } },
                loopEnd = loopEndMs?.let { end -> duration?.takeIf { it > 0 }?.let { (end.toFloat() / it).coerceIn(0f, 1f) } },
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        barStart = coordinates.positionInRoot().x
                        barSpan = coordinates.size.width.toFloat()
                    }
            )
        }
        if (previewEnabled && scrubFraction != null && duration != null && barSpan > 0f) {
            SeekPreviewCard(
                bitmap = previewBitmap,
                positionMs = shownPosition,
                anchor = barStart - origin.x + barSpan * fraction,
                topLimit = origin.y
            )
        }
    }
}

@Composable
internal fun PlayerQuickButtons(
    buttons: Set<String>,
    ui: PlayerUiState,
    pipAvailable: Boolean,
    screenshotAvailable: Boolean,
    interpolating: Boolean,
    onAspect: () -> Unit,
    onRotate: () -> Unit,
    onSpeed: () -> Unit,
    onRepeat: () -> Unit,
    onMirror: () -> Unit,
    onPip: () -> Unit,
    onMotion: () -> Unit,
    onLoop: () -> Unit,
    onScreenshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPlayerPalette.current
    val shown = QuickButton.entries.filter {
        it.key in buttons && it != QuickButton.LOCK && (it != QuickButton.PIP || pipAvailable) && (it != QuickButton.SCREENSHOT || screenshotAvailable)
    }
    if (shown.isEmpty()) return
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shown.forEach { button ->
                val description = stringResource(button.labelRes)
                when (button) {
                    QuickButton.SPEED -> Text(
                        text = formatSpeed(ui.speed),
                        color = if (ui.speed != 1f) palette.active else palette.icon,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onSpeed)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    QuickButton.ASPECT -> PlayerIcon(Icons.Rounded.AspectRatio, description, active = ui.aspect > 0f || ui.zoomed, onClick = onAspect)
                    QuickButton.MOTION -> PlayerIcon(Icons.Rounded.AutoAwesomeMotion, description, active = interpolating, onClick = onMotion)
                    QuickButton.REPEAT -> PlayerIcon(
                        when (ui.repeat) {
                            RepeatMode.OFF -> Icons.Rounded.Repeat
                            RepeatMode.ONE -> Icons.Rounded.RepeatOne
                            RepeatMode.ALL -> Icons.Rounded.Repeat
                        },
                        description,
                        active = ui.repeat != RepeatMode.OFF,
                        onClick = onRepeat
                    )
                    QuickButton.MIRROR -> PlayerIcon(Icons.Rounded.Flip, description, active = ui.mirror, onClick = onMirror)
                    QuickButton.PIP -> PlayerIcon(Icons.Rounded.PictureInPictureAlt, description, onClick = onPip)
                    QuickButton.ROTATE -> PlayerIcon(Icons.Rounded.ScreenRotation, description, active = ui.manualOrientation != null, onClick = onRotate)
                    QuickButton.LOOP -> Text(
                        text = when {
                            ui.loopStartMs == null -> "A–B"
                            ui.loopEndMs == null -> "A–"
                            else -> "A–B"
                        },
                        color = if (ui.loopStartMs != null) palette.active else palette.icon,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .semantics { contentDescription = description }
                            .clickable(onClick = onLoop)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    QuickButton.SCREENSHOT -> PlayerIcon(Icons.Rounded.PhotoCamera, description, onClick = onScreenshot)
                    QuickButton.LOCK -> Unit
                }
            }
        }
    }
}

@Composable
internal fun PlayerSideLock(onLock: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPlayerPalette.current
    IconButton(
        onClick = onLock,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = stringResource(R.string.player_button_lock), tint = palette.icon)
    }
}

@Composable
internal fun LockedOverlay(visible: Boolean, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    if (!visible) return
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onUnlock)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.player_unlock), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = LocalLuckyColors.current
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            color = colors.popupText.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            color = colors.popupText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun PlayerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceVariant,
            labelColor = colors.popupText,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary
        ),
        border = null
    )
}

@Composable
internal fun StatsPanel(
    status: PlayerStatus,
    speed: Float,
    upscaler: Upscaler,
    downscaler: Downscaler,
    preferHardware: Boolean,
    onDecoderChange: (Boolean) -> Unit,
    renderer: RendererPreference,
    onRendererChange: (RendererPreference) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    val context = LocalContext.current
    val decoderKind = when (status.decoderKind) {
        "hardware" -> stringResource(R.string.decoder_hardware)
        "software" -> stringResource(R.string.decoder_software)
        else -> null
    }
    MaterialSurface(
        shape = RoundedCornerShape(16.dp),
        color = colors.popupBackground.copy(alpha = 0.92f),
        modifier = modifier.widthIn(max = 360.dp)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            val decoder = listOfNotNull(status.videoDecoder, decoderKind).joinToString(" · ")
            if (decoder.isNotEmpty()) StatRow(stringResource(R.string.stats_decoder), decoder)
            if (status.hasVideo && status.deinterlaced) {
                StatRow(stringResource(R.string.settings_deinterlace), stringResource(R.string.stats_deinterlace_active))
            }
            status.renderer?.let { StatRow(stringResource(R.string.stats_renderer), it) }
            if (status.videoWidth > 0) {
                StatRow(
                    stringResource(R.string.label_resolution),
                    stringResource(R.string.value_resolution, status.videoWidth, status.videoHeight)
                )
            }
            status.frameRate?.let {
                StatRow(stringResource(R.string.label_frame_rate), String.format(java.util.Locale.getDefault(), "%.3f", it))
            }
            val screenRate = refreshRate(context.findActivity())
            if (status.hasVideo && screenRate > 0f) {
                val hz = String.format(java.util.Locale.getDefault(), "%.0f", screenRate)
                val fits = status.frameRate?.let { refreshFits(screenRate, it) }
                StatRow(
                    stringResource(R.string.stats_screen),
                    when (fits) {
                        true -> stringResource(R.string.screen_fits, hz)
                        false -> stringResource(R.string.screen_uneven, hz)
                        null -> stringResource(R.string.screen_rate, hz)
                    }
                )
            }
            if (status.motionLevel > 0) {
                StatRow(
                    stringResource(R.string.stats_motion),
                    stringResource(
                        R.string.stats_motion_value,
                        stringResource(motionQualityLabel(MotionQuality.fromCode(status.motionLevel))),
                        String.format(java.util.Locale.getDefault(), "%.0f", status.outputFps)
                    )
                )
            } else if (status.smoothBlending) {
                StatRow(stringResource(R.string.settings_smooth_motion), stringResource(R.string.stats_smooth_active))
            }
            status.pixelFormat?.let { StatRow(stringResource(R.string.label_pixel_format), it) }
            if (status.hasVideo) {
                val dolbyVision = DolbyVision.parse(status.hdrFormat)
                val hdrName = when {
                    dolbyVision != null -> stringResource(R.string.hdr_dolby_vision_profile, dolbyVision.label)
                    status.hdrFormat == "hdr10" -> "HDR10"
                    status.hdrFormat == "hlg" -> "HLG"
                    else -> null
                }
                hdrName?.let { StatRow(stringResource(R.string.stats_hdr_format), it) }
                val output = when (status.displayOutput) {
                    DisplayOutput.HDR10 -> stringResource(R.string.output_hdr10)
                    DisplayOutput.DISPLAY_P3 ->
                        if (hdrName != null) stringResource(R.string.output_p3_tone_mapped) else stringResource(R.string.output_p3)
                    DisplayOutput.SRGB ->
                        if (hdrName != null) stringResource(R.string.output_sdr_tone_mapped) else stringResource(R.string.output_sdr)
                }
                StatRow(stringResource(R.string.stats_display_output), output)
            }
            val audioOutput = status.audioOutput
            if (status.audioDecoder != null || audioOutput == AudioOutputKind.PASSTHROUGH) {
                val details = listOfNotNull(
                    status.audioDecoder,
                    status.audioSampleRate?.let { formatSampleRate(context, it) },
                    status.audioChannels?.let { context.resources.getQuantityString(R.plurals.audio_channels, it, it) }
                ).joinToString(" · ")
                StatRow(stringResource(R.string.stats_audio), details)
            }
            if (audioOutput != null) {
                StatRow(
                    stringResource(R.string.stats_audio_output),
                    audioOutputText(audioOutput, status.audioChannels, status.audioPassthrough)
                )
            }
            if (speed != 1f) StatRow(stringResource(R.string.player_button_speed), formatSpeed(speed))
            if (status.hasVideo) {
                StatRow(stringResource(R.string.settings_upscaling), stringResource(upscalerLabel(upscaler)))
                StatRow(stringResource(R.string.settings_downscaling), stringResource(downscalerLabel(downscaler)))
                val active = ScaleFilter.fromKernel(status.activeKernel)
                val chosen = when (status.scalingDirection) {
                    1 -> upscaler.filter
                    2 -> downscaler.filter
                    else -> null
                }
                StatRow(
                    stringResource(R.string.stats_scaling_now),
                    when (status.scalingDirection) {
                        1 -> stringResource(R.string.scaling_now_up, stringResource(scaleFilterLabel(active)))
                        2 -> stringResource(R.string.scaling_now_down, stringResource(scaleFilterLabel(active)))
                        else -> stringResource(R.string.scaling_now_none)
                    }
                )
                if (chosen != null && chosen != active && status.costCap > 0.0) {
                    StatRow(
                        stringResource(R.string.settings_auto_quality),
                        stringResource(R.string.stats_quality_lowered, stringResource(scaleFilterLabel(chosen)))
                    )
                }
                if (status.decoderCatchUp > 0) StatRow(stringResource(R.string.settings_fast_decode), stringResource(R.string.stats_fast_decode_active))
            }
            if (status.renderMs > 0.0) {
                val locale = java.util.Locale.getDefault()
                StatRow(
                    stringResource(R.string.stats_render_time),
                    stringResource(
                        R.string.value_render_time,
                        String.format(locale, "%.1f", status.renderMs),
                        String.format(locale, "%.1f", status.renderPeakMs)
                    )
                )
            }
            StatRow(stringResource(R.string.stats_frames_shown), status.presentedFrames.toString())
            StatRow(stringResource(R.string.stats_frames_dropped), status.droppedFrames.toString())
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.stats_decoding_preference),
                color = colors.popupText.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerChip(stringResource(R.string.decoder_hardware), preferHardware) { onDecoderChange(true) }
                PlayerChip(stringResource(R.string.decoder_software), !preferHardware) { onDecoderChange(false) }
            }
            Text(
                text = stringResource(R.string.stats_renderer_preference),
                color = colors.popupText.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerChip(stringResource(R.string.renderer_opengl), renderer == RendererPreference.OPENGL) {
                    onRendererChange(RendererPreference.OPENGL)
                }
                PlayerChip(stringResource(R.string.renderer_vulkan), renderer == RendererPreference.VULKAN) {
                    onRendererChange(RendererPreference.VULKAN)
                }
            }
        }
    }
}

@Composable
internal fun ErrorPanel(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    MaterialSurface(
        shape = RoundedCornerShape(24.dp),
        color = colors.cardBackground,
        contentColor = colors.onSurface,
        modifier = modifier.widthIn(max = 420.dp)
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message.ifBlank { stringResource(R.string.player_open_failed) },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onClose,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.buttonBackground,
                    contentColor = colors.buttonTextColor
                )
            ) {
                Text(stringResource(R.string.player_back))
            }
        }
    }
}
