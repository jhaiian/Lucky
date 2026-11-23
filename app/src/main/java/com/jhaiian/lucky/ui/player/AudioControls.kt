package com.jhaiian.lucky.ui.player

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.engine.AudioChannels
import com.jhaiian.lucky.engine.AudioOutputKind
import com.jhaiian.lucky.engine.AudioOutputMode
import com.jhaiian.lucky.engine.AudioOutputOptions
import com.jhaiian.lucky.engine.EqPreset
import com.jhaiian.lucky.engine.PassthroughCodec
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class Equalizer(val preset: EqPreset, val bands: List<Float>)

internal fun readEqualizer(prefs: SharedPreferences): Equalizer {
    val preset = EqPreset.parse(prefs.getString(LuckyPrefs.AUDIO_EQ_PRESET, null))
    if (preset != EqPreset.CUSTOM) return Equalizer(preset, preset.gains)
    val stored = prefs.getString(LuckyPrefs.AUDIO_EQ_BANDS, null)
        ?.split(',')
        ?.mapNotNull { it.toFloatOrNull()?.coerceIn(-EqPreset.MAX_GAIN, EqPreset.MAX_GAIN) }
        ?.takeIf { it.size == EqPreset.bandFrequencies.size }
    return Equalizer(preset, stored ?: EqPreset.FLAT.gains)
}

internal fun saveEqualizer(prefs: SharedPreferences, equalizer: Equalizer) {
    prefs.edit()
        .putString(LuckyPrefs.AUDIO_EQ_PRESET, equalizer.preset.name)
        .putString(LuckyPrefs.AUDIO_EQ_BANDS, equalizer.bands.joinToString(",") { it.toString() })
        .apply()
}

internal fun readAudioOutput(prefs: SharedPreferences): AudioOutputOptions = AudioOutputOptions(
    mode = AudioOutputMode.parse(prefs.getString(LuckyPrefs.AUDIO_OUTPUT, null)),
    passthrough = prefs.getBoolean(LuckyPrefs.AUDIO_PASSTHROUGH, false),
    codecs = PassthroughCodec.entries
        .filter { prefs.getBoolean(LuckyPrefs.AUDIO_PASSTHROUGH_CODEC_PREFIX + it.key, true) }
        .toSet()
)

internal fun audioOutputModeLabel(mode: AudioOutputMode): Int = when (mode) {
    AudioOutputMode.STEREO -> R.string.audio_output_stereo
    AudioOutputMode.AUTO -> R.string.audio_output_auto
}

internal fun passthroughLabel(codec: PassthroughCodec): Int = when (codec) {
    PassthroughCodec.AC3 -> R.string.passthrough_ac3
    PassthroughCodec.EAC3 -> R.string.passthrough_eac3
    PassthroughCodec.DTS -> R.string.passthrough_dts
    PassthroughCodec.DTS_HD -> R.string.passthrough_dtshd
    PassthroughCodec.TRUEHD -> R.string.passthrough_truehd
}

@Composable
internal fun audioOutputText(kind: AudioOutputKind, channels: Int?, codec: PassthroughCodec?): String {
    val resources = LocalContext.current.resources
    return when (kind) {
        AudioOutputKind.STEREO -> stringResource(R.string.audio_channels_stereo)
        AudioOutputKind.MONO -> stringResource(R.string.audio_channels_mono)
        AudioOutputKind.MULTICHANNEL -> {
            val count = channels ?: 6
            stringResource(R.string.stats_output_multichannel, resources.getQuantityString(R.plurals.audio_channels, count, count))
        }
        AudioOutputKind.PASSTHROUGH ->
            stringResource(R.string.stats_output_passthrough, codec?.let { stringResource(passthroughLabel(it)) } ?: "")
    }
}

internal fun eqPresetLabel(preset: EqPreset): Int = when (preset) {
    EqPreset.FLAT -> R.string.eq_flat
    EqPreset.BASS -> R.string.eq_bass
    EqPreset.TREBLE -> R.string.eq_treble
    EqPreset.VOCAL -> R.string.eq_vocal
    EqPreset.CINEMA -> R.string.eq_cinema
    EqPreset.ROCK -> R.string.eq_rock
    EqPreset.POP -> R.string.eq_pop
    EqPreset.JAZZ -> R.string.eq_jazz
    EqPreset.CLASSICAL -> R.string.eq_classical
    EqPreset.CUSTOM -> R.string.eq_custom
}

internal fun channelsLabel(channels: AudioChannels): Int = when (channels) {
    AudioChannels.STEREO -> R.string.audio_channels_stereo
    AudioChannels.MONO -> R.string.audio_channels_mono
    AudioChannels.SWAPPED -> R.string.audio_channels_swapped
}

@Composable
internal fun balanceLabel(balance: Float): String {
    val percent = (abs(balance) * 100).roundToInt()
    return when {
        percent == 0 -> stringResource(R.string.audio_balance_center)
        balance < 0 -> stringResource(R.string.audio_balance_left, percent)
        else -> stringResource(R.string.audio_balance_right, percent)
    }
}

@Composable
private fun frequencyLabel(hz: Int): String =
    if (hz >= 1000) stringResource(R.string.eq_khz, hz / 1000) else stringResource(R.string.eq_hz, hz)

@Composable
internal fun ThemedSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Slider(
        value = value.coerceIn(range),
        onValueChange = onValueChange,
        valueRange = range,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = colors.primary,
            activeTrackColor = colors.primary,
            inactiveTrackColor = colors.primary.copy(alpha = 0.2f)
        )
    )
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onReset: (() -> Unit)? = null
) {
    val colors = LocalLuckyColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
            if (onReset != null) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.eq_reset), color = colors.primary)
                }
            }
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.onSurface)
        }
        ThemedSlider(current, onChange, range)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EqualizerEditor(equalizer: Equalizer, onChange: (Equalizer) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqPreset.entries.forEach { preset ->
                PlayerChip(stringResource(eqPresetLabel(preset)), equalizer.preset == preset) {
                    onChange(Equalizer(preset, if (preset == EqPreset.CUSTOM) equalizer.bands else preset.gains))
                }
            }
        }
        EqPreset.bandFrequencies.forEachIndexed { index, hz ->
            val gain = equalizer.bands.getOrElse(index) { 0f }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    frequencyLabel(hz),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.secondaryText,
                    modifier = Modifier.widthIn(min = 64.dp)
                )
                ThemedSlider(
                    value = gain,
                    onValueChange = { raw ->
                        val snapped = (raw * 2).roundToInt() / 2f
                        val bands = equalizer.bands.toMutableList().also { it[index] = snapped }
                        onChange(Equalizer(EqPreset.CUSTOM, bands))
                    },
                    range = -EqPreset.MAX_GAIN..EqPreset.MAX_GAIN,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.eq_db, String.format(Locale.getDefault(), "%+.1f", gain)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 64.dp)
                )
            }
        }
    }
}
