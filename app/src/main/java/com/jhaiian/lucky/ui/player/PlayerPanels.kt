package com.jhaiian.lucky.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.AudioChannels
import com.jhaiian.lucky.engine.EqPreset
import com.jhaiian.lucky.engine.TrackInfo
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.util.Locale
import kotlin.math.roundToInt

internal data class SubtitleControls(
    val delayMs: Long,
    val scale: Float,
    val look: SubtitleLook,
    val onDelay: (Long) -> Unit,
    val onScale: (Float) -> Unit,
    val onLook: (SubtitleLook) -> Unit
)

internal data class AudioControlsState(
    val delayMs: Int,
    val boost: Float,
    val night: Boolean,
    val dialogue: Boolean,
    val channels: AudioChannels,
    val balance: Float,
    val normalize: Boolean,
    val preset: EqPreset,
    val onDelay: (Int) -> Unit,
    val onBoost: (Float) -> Unit,
    val onNight: (Boolean) -> Unit,
    val onDialogue: (Boolean) -> Unit,
    val onNormalize: (Boolean) -> Unit,
    val onChannels: (AudioChannels) -> Unit,
    val onBalance: (Float) -> Unit,
    val onEqualizer: () -> Unit
)

@Composable
private fun PanelSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    PlayerSidePanel(title = title, onDismiss = onDismiss) {
        content()
    }
}

@Composable
private fun TrackList(
    tracks: List<TrackInfo>,
    selected: Int?,
    allowOff: Boolean,
    onSelect: (Int?) -> Unit
) {
    if (allowOff) {
        TrackRow(stringResource(R.string.tracks_off), null, selected == null) { onSelect(null) }
    }
    tracks.forEachIndexed { position, track ->
        TrackRow(trackLabel(track, position), trackDetail(track), selected == track.index) { onSelect(track.index) }
    }
}

@Composable
private fun ColorDot(color: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
internal fun SubtitlesSheet(
    tracks: List<TrackInfo>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onAddFile: (() -> Unit)?,
    controls: SubtitleControls,
    onDismiss: () -> Unit
) {
    val colors = LocalLuckyColors.current
    val look = controls.look
    PanelSheet(stringResource(R.string.track_subtitle), onDismiss) {
        TrackSectionTitle(stringResource(R.string.player_section_track))
        TrackList(tracks, selected, allowOff = true, onSelect = onSelect)
        if (onAddFile != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddFile)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.tracks_load_file),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
            }
        }
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
        SheetSection(stringResource(R.string.player_section_timing)) {
            StepperRow(
                label = stringResource(R.string.player_subtitle_delay),
                value = String.format(Locale.getDefault(), "%+.1f s", controls.delayMs / 1000f),
                onMinus = { controls.onDelay(controls.delayMs - 100) },
                onPlus = { controls.onDelay(controls.delayMs + 100) },
                onReset = if (controls.delayMs != 0L) ({ controls.onDelay(0L) }) else null
            )
            StepperRow(
                label = stringResource(R.string.player_subtitle_size),
                value = "${(controls.scale * 100).roundToInt()}%",
                onMinus = { controls.onScale((controls.scale - 0.1f).coerceAtLeast(0.5f)) },
                onPlus = { controls.onScale((controls.scale + 0.1f).coerceAtMost(3f)) },
                onReset = if (controls.scale != 1f) ({ controls.onScale(1f) }) else null
            )
        }
        SheetSection(stringResource(R.string.player_section_style)) {
            ChipFlow {
                PlayerChip(stringResource(R.string.settings_player_subtitle_system), look.followSystem) {
                    controls.onLook(look.copy(followSystem = !look.followSystem))
                }
                PlayerChip(stringResource(R.string.settings_player_subtitle_bold), look.bold) {
                    controls.onLook(look.copy(bold = !look.bold))
                }
            }
            if (!look.followSystem) {
                Text(
                    stringResource(R.string.settings_player_subtitle_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                ChipFlow {
                    SubtitleColor.entries.forEach { option ->
                        ColorDot(option.color, look.color == option) { controls.onLook(look.copy(color = option)) }
                    }
                }
                Text(
                    stringResource(R.string.settings_player_subtitle_edge),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                ChipFlow {
                    SubtitleEdge.entries.forEach { option ->
                        PlayerChip(stringResource(option.labelRes), look.edge == option) { controls.onLook(look.copy(edge = option)) }
                    }
                }
            }
            Text(
                stringResource(R.string.settings_player_subtitle_size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            ChipFlow {
                SubtitleSizes.forEach { size ->
                    PlayerChip("$size%", look.sizePercent == size) { controls.onLook(look.copy(sizePercent = size)) }
                }
            }
            Text(
                stringResource(R.string.settings_player_subtitle_position),
                style = MaterialTheme.typography.labelLarge,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            ChipFlow {
                SubtitlePosition.entries.forEach { option ->
                    PlayerChip(stringResource(option.labelRes), look.position == option) { controls.onLook(look.copy(position = option)) }
                }
            }
        }
    }
}

@Composable
internal fun AudioSheet(
    tracks: List<TrackInfo>,
    selected: Int?,
    allowOff: Boolean,
    onSelect: (Int) -> Unit,
    controls: AudioControlsState,
    onDismiss: () -> Unit
) {
    val colors = LocalLuckyColors.current
    PanelSheet(stringResource(R.string.track_audio), onDismiss) {
        if (tracks.isNotEmpty()) {
            TrackSectionTitle(stringResource(R.string.player_section_track))
            TrackList(tracks, selected, allowOff = allowOff) { onSelect(it ?: -1) }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
        }
        SheetSection(stringResource(R.string.player_section_timing)) {
            StepperRow(
                label = stringResource(R.string.audio_delay),
                value = stringResource(R.string.audio_delay_value, controls.delayMs),
                onMinus = { controls.onDelay((controls.delayMs - 50).coerceAtLeast(-5000)) },
                onPlus = { controls.onDelay((controls.delayMs + 50).coerceAtMost(5000)) },
                onReset = if (controls.delayMs != 0) ({ controls.onDelay(0) }) else null
            )
        }
        SheetSection(stringResource(R.string.settings_section_sound)) {
            LabeledSlider(
                label = stringResource(R.string.volume_boost),
                value = "${(controls.boost * 100).roundToInt()}%",
                current = controls.boost,
                range = 1f..3f,
                onChange = { controls.onBoost((it * 20).roundToInt() / 20f) },
                onReset = if (controls.boost != 1f) ({ controls.onBoost(1f) }) else null
            )
            ChipFlow {
                PlayerChip(stringResource(R.string.audio_night), controls.night) { controls.onNight(!controls.night) }
                PlayerChip(stringResource(R.string.audio_dialogue), controls.dialogue) { controls.onDialogue(!controls.dialogue) }
                PlayerChip(stringResource(R.string.audio_normalize), controls.normalize) { controls.onNormalize(!controls.normalize) }
                PlayerChip(
                    stringResource(R.string.equalizer_with_preset, stringResource(eqPresetLabel(controls.preset))),
                    controls.preset != EqPreset.FLAT
                ) { controls.onEqualizer() }
            }
        }
        SheetSection(stringResource(R.string.audio_channel_mode)) {
            ChipFlow {
                AudioChannels.entries.forEach { option ->
                    PlayerChip(stringResource(channelsLabel(option)), controls.channels == option) { controls.onChannels(option) }
                }
            }
            LabeledSlider(
                label = stringResource(R.string.audio_balance),
                value = balanceLabel(controls.balance),
                current = controls.balance,
                range = -1f..1f,
                onChange = { controls.onBalance(((it * 20).roundToInt() / 20f).coerceIn(-1f, 1f)) },
                onReset = if (controls.balance != 0f) ({ controls.onBalance(0f) }) else null
            )
        }
    }
}
