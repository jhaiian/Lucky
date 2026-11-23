package com.jhaiian.lucky.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.engine.AudioChannels
import com.jhaiian.lucky.engine.AudioNormalize
import com.jhaiian.lucky.engine.AudioOutputMode
import com.jhaiian.lucky.engine.PassthroughCodec
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.library.rememberPreferences
import com.jhaiian.lucky.ui.player.EqualizerEditor
import com.jhaiian.lucky.ui.player.LabeledSlider
import com.jhaiian.lucky.ui.player.audioOutputModeLabel
import com.jhaiian.lucky.ui.player.balanceLabel
import com.jhaiian.lucky.ui.player.channelsLabel
import com.jhaiian.lucky.ui.player.eqPresetLabel
import com.jhaiian.lucky.ui.player.passthroughLabel
import com.jhaiian.lucky.ui.player.readEqualizer
import com.jhaiian.lucky.ui.player.saveEqualizer
import kotlin.math.roundToInt

@Composable
internal fun AudioSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val prefs = rememberPreferences()
    val night = rememberBooleanPref(LuckyPrefs.AUDIO_NIGHT, false)
    val dialogue = rememberBooleanPref(LuckyPrefs.AUDIO_DIALOGUE, false)
    val channels = rememberStringPref(LuckyPrefs.AUDIO_CHANNELS, AudioChannels.STEREO.name)
    val normalize = rememberStringPref(LuckyPrefs.AUDIO_NORMALIZE, AudioNormalize.OFF.name)
    val output = rememberStringPref(LuckyPrefs.AUDIO_OUTPUT, AudioOutputMode.AUTO.name)
    val passthrough = rememberBooleanPref(LuckyPrefs.AUDIO_PASSTHROUGH, false)
    val codecStates = PassthroughCodec.entries.map {
        it to rememberBooleanPref(LuckyPrefs.AUDIO_PASSTHROUGH_CODEC_PREFIX + it.key, true)
    }
    val equalizer = readEqualizer(prefs)
    val balance = prefs.getFloat(LuckyPrefs.AUDIO_BALANCE, 0f).coerceIn(-1f, 1f)
    var showEqualizer by remember { mutableStateOf(false) }
    val pageTitle = stringResource(R.string.settings_audio)
    val soundTitle = stringResource(R.string.settings_section_sound)
    val decodingNote = stringResource(R.string.settings_audio_note)
    val normalizeTitle = stringResource(R.string.audio_normalize)
    val normalizeNote = stringResource(R.string.audio_normalize_summary)
    val normalizeLabels = listOf(stringResource(R.string.player_option_off), stringResource(R.string.audio_normalize_on))
    val channelsTitle = stringResource(R.string.audio_channel_mode)
    val channelLabels = AudioChannels.entries.map { stringResource(channelsLabel(it)) }
    val outputTitle = stringResource(R.string.audio_output)
    val outputNote = stringResource(R.string.audio_output_note)
    val outputLabels = AudioOutputMode.entries.map { stringResource(audioOutputModeLabel(it)) }
    val passthroughTitle = stringResource(R.string.audio_passthrough_section)
    val passthroughNote = stringResource(R.string.audio_passthrough_note)

    Box(modifier.fillMaxSize()) {
        SubPage(title = pageTitle, onBack = onBack) {
            section(key = "audio_sound", title = soundTitle, note = decodingNote) {
                SettingsRow(
                    icon = Icons.Rounded.Equalizer,
                    title = stringResource(R.string.equalizer),
                    subtitle = stringResource(eqPresetLabel(equalizer.preset)),
                    onClick = { showEqualizer = true }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.NightsStay,
                    title = stringResource(R.string.audio_night),
                    subtitle = stringResource(R.string.audio_night_summary),
                    checked = night.value,
                    onCheckedChange = { night.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = stringResource(R.string.audio_dialogue),
                    subtitle = stringResource(R.string.audio_dialogue_summary),
                    checked = dialogue.value,
                    onCheckedChange = { dialogue.update(it) }
                )
            }
            section(key = "audio_normalize", title = normalizeTitle, note = normalizeNote) {
                ChoiceTiles(
                    options = normalizeLabels,
                    selected = AudioNormalize.parse(normalize.value).ordinal,
                    onSelect = { index ->
                        haptics.tick()
                        normalize.update(AudioNormalize.entries[index].name)
                    },
                    modifier = Modifier.padding(4.dp)
                )
            }
            section(key = "audio_channels", title = channelsTitle) {
                val current = AudioChannels.parse(channels.value)
                ChoiceTiles(
                    options = channelLabels,
                    selected = current.ordinal,
                    onSelect = { index ->
                        haptics.tick()
                        channels.update(AudioChannels.entries[index].name)
                    },
                    modifier = Modifier.padding(4.dp)
                )
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.audio_balance),
                        value = balanceLabel(balance),
                        current = balance,
                        range = -1f..1f,
                        onChange = { prefs.edit().putFloat(LuckyPrefs.AUDIO_BALANCE, ((it * 20).roundToInt() / 20f).coerceIn(-1f, 1f)).apply() },
                        onReset = if (balance != 0f) ({ prefs.edit().putFloat(LuckyPrefs.AUDIO_BALANCE, 0f).apply() }) else null
                    )
                }
            }
            section(key = "audio_output", title = outputTitle, note = outputNote) {
                ChoiceTiles(
                    options = outputLabels,
                    selected = AudioOutputMode.parse(output.value).ordinal,
                    onSelect = { index ->
                        haptics.tick()
                        output.update(AudioOutputMode.entries[index].name)
                    },
                    modifier = Modifier.padding(4.dp)
                )
            }
            section(key = "audio_passthrough", title = passthroughTitle, note = passthroughNote) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.SettingsInputHdmi,
                    title = stringResource(R.string.audio_passthrough),
                    subtitle = stringResource(R.string.audio_passthrough_summary),
                    checked = passthrough.value,
                    onCheckedChange = { passthrough.update(it) }
                )
                codecStates.forEach { (codec, state) ->
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = stringResource(passthroughLabel(codec)),
                        checked = state.value,
                        enabled = passthrough.value,
                        onCheckedChange = { state.update(it) }
                    )
                }
            }
        }

        SettingsDialog(
            visible = showEqualizer,
            icon = Icons.Rounded.Equalizer,
            title = stringResource(R.string.equalizer),
            header = stringResource(R.string.equalizer_hint),
            onDismiss = { showEqualizer = false }
        ) {
            EqualizerEditor(
                equalizer = equalizer,
                onChange = { saveEqualizer(prefs, it) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
