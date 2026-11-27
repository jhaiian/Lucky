package com.jhaiian.lucky.ui.player

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.Chapter
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.util.Locale
import kotlin.math.roundToInt

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
internal fun SpeedSection(speed: Float, onSpeed: (Float) -> Unit) {
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
internal fun ZoomAxisSection(title: String, value: Float, onChange: (Float) -> Unit) {
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
internal fun PanSection(title: String, value: Float, limit: Float, onChange: (Float) -> Unit) {
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

@Composable
internal fun EqualizerSheet(equalizer: Equalizer, onChange: (Equalizer) -> Unit, onDismiss: () -> Unit) {
    PlayerSidePanel(title = stringResource(R.string.equalizer), onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            EqualizerEditor(equalizer, onChange)
        }
    }
}

@Composable
fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    PlayerSidePanel(title = stringResource(R.string.player_button_speed), onDismiss = onDismiss) {
        SpeedSection(speed, onSpeed)
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

@Composable
fun ChaptersSheet(chapters: List<Chapter>, positionMs: Long, onSelect: (Chapter) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalLuckyColors.current
    val current = currentChapterIndex(chapters, positionMs)
    PlayerSidePanel(title = stringResource(R.string.player_chapters), onDismiss = onDismiss) {
        run {
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
