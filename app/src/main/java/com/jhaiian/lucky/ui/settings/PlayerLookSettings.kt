package com.jhaiian.lucky.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.engine.PlaybackState
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.library.rememberPreferences
import com.jhaiian.lucky.ui.player.ButtonStyle
import com.jhaiian.lucky.ui.player.ControlsLayout
import com.jhaiian.lucky.ui.player.ControlsMotion
import com.jhaiian.lucky.ui.player.ControlsSize
import com.jhaiian.lucky.ui.player.LocalPlayerChromeStyle
import com.jhaiian.lucky.ui.player.LocalPlayerPalette
import com.jhaiian.lucky.ui.player.PlayPauseButton
import com.jhaiian.lucky.ui.player.PlayShape
import com.jhaiian.lucky.ui.player.PlayerButton
import com.jhaiian.lucky.ui.player.PlayerButtonGroup
import com.jhaiian.lucky.ui.player.PlayerChromeStyle
import com.jhaiian.lucky.ui.player.PlayerLook
import com.jhaiian.lucky.ui.player.PlayerLookPreview
import com.jhaiian.lucky.ui.player.PlayerPalette
import com.jhaiian.lucky.ui.player.PlayerSeekBar
import com.jhaiian.lucky.ui.player.PlayerTint
import com.jhaiian.lucky.ui.player.PreviewBackdrop
import com.jhaiian.lucky.ui.player.QuickButton
import com.jhaiian.lucky.ui.player.ScrimLevel
import com.jhaiian.lucky.ui.player.SeekStyle
import com.jhaiian.lucky.ui.player.TimeLayout
import com.jhaiian.lucky.ui.player.rememberPlayerPalette
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

@Composable
private fun OptionLabel(title: String, value: String? = null) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    val colors = LocalLuckyColors.current
    val fill by animateColorAsState(if (selected) colors.primary else Color.Transparent, tween(220), label = "mark")
    val edge by animateColorAsState(if (selected) colors.primary else colors.divider, tween(220), label = "markEdge")
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(fill)
            .border(BorderStroke(1.6.dp, edge), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) + fadeIn(tween(120)),
            exit = scaleOut(tween(140)) + fadeOut(tween(140))
        ) {
            Icon(Icons.Rounded.Check, null, tint = colors.onPrimary, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun <T> LookTiles(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    sample: @Composable BoxScope.(T) -> Unit
) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            val text = label(option)
            val edge by animateColorAsState(if (active) colors.primary else Color.Transparent, tween(220), label = "tileEdge")
            val fill by animateColorAsState(
                if (active) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant,
                tween(220),
                label = "tileFill"
            )
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(fill)
                    .border(2.dp, edge, shape)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(PreviewBackdrop),
                        contentAlignment = Alignment.Center
                    ) {
                        sample(option)
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) colors.primary else colors.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp, bottom = 3.dp)
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .semantics { contentDescription = text }
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                )
            }
        }
    }
}

@Composable
private fun LayoutSketch(layout: ControlsLayout, palette: PlayerPalette) {
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        val w = size.width
        val h = size.height
        val line = 2.5.dp.toPx()
        val faint = Color.White.copy(alpha = 0.35f)
        drawLine(faint, Offset(w * 0.06f, h * 0.08f), Offset(w * 0.46f, h * 0.08f), line, StrokeCap.Round)
        drawCircle(faint, line * 1.1f, Offset(w * 0.86f, h * 0.08f))
        drawCircle(faint, line * 1.1f, Offset(w * 0.94f, h * 0.08f))
        when (layout) {
            ControlsLayout.CENTER -> {
                drawCircle(palette.accent, h * 0.17f, Offset(w / 2f, h * 0.45f))
                drawCircle(faint, h * 0.08f, Offset(w / 2f - h * 0.42f, h * 0.45f))
                drawCircle(faint, h * 0.08f, Offset(w / 2f + h * 0.42f, h * 0.45f))
                drawLine(faint, Offset(w * 0.06f, h * 0.82f), Offset(w * 0.94f, h * 0.82f), line, StrokeCap.Round)
                drawLine(palette.accent, Offset(w * 0.06f, h * 0.82f), Offset(w * 0.45f, h * 0.82f), line, StrokeCap.Round)
                drawCircle(faint, line * 1.1f, Offset(w * 0.78f, h * 0.96f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.86f, h * 0.96f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.94f, h * 0.96f))
            }
            ControlsLayout.DOCKED -> {
                val top = h * 0.58f
                drawRoundRect(
                    Color.White.copy(alpha = 0.14f),
                    Offset(0f, top),
                    Size(w, h - top),
                    CornerRadius(8.dp.toPx())
                )
                drawLine(faint, Offset(w * 0.08f, top + h * 0.1f), Offset(w * 0.92f, top + h * 0.1f), line, StrokeCap.Round)
                drawLine(palette.accent, Offset(w * 0.08f, top + h * 0.1f), Offset(w * 0.45f, top + h * 0.1f), line, StrokeCap.Round)
                drawCircle(palette.accent, h * 0.075f, Offset(w * 0.2f, top + h * 0.27f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.08f, top + h * 0.27f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.32f, top + h * 0.27f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.76f, top + h * 0.27f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.84f, top + h * 0.27f))
                drawCircle(faint, line * 1.1f, Offset(w * 0.92f, top + h * 0.27f))
            }
        }
    }
}

@Composable
private fun SeekStyleRow(style: SeekStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    val label = stringResource(style.labelRes)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectedMark(selected)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(0.42f)
                    .padding(start = 12.dp, end = 8.dp)
            )
            Box(
                Modifier
                    .weight(0.58f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(PreviewBackdrop)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                PlayerSeekBar(
                    style = style,
                    value = 0.42f,
                    enabled = true,
                    playing = true,
                    markers = emptyList(),
                    onValueChange = {},
                    onValueChangeFinished = {}
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = label }
                .clickable(role = Role.RadioButton, onClick = onClick)
        )
    }
}

@Composable
private fun SwatchRow(options: List<PlayerTint>, selected: PlayerTint, onSelect: (PlayerTint) -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { tint ->
            val active = tint == selected
            val color = tint.resolve(colors.primary)
            val ring by animateColorAsState(if (active) colors.primary else Color.Transparent, tween(220), label = "ring")
            val name = stringResource(tint.labelRes)
            val check by animateFloatAsState(
                if (active) 1f else 0f,
                spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
                label = "check"
            )
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .border(2.dp, ring, CircleShape)
                    .semantics { contentDescription = name }
                    .clickable(role = Role.RadioButton) { onSelect(tint) }
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, colors.divider, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            scaleX = check
                            scaleY = check
                            alpha = check.coerceIn(0f, 1f)
                        }
                )
            }
        }
    }
}

@Composable
internal fun PlayerLookSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val prefs = rememberPreferences()
    val store = remember(context) { LuckyPrefs.get(context) }
    val look = PlayerLook.from(prefs)
    val quickButtons = QuickButton.read(prefs)
    val quickOrder = QuickButton.order(prefs)
    val palette = rememberPlayerPalette(look.iconTint, look.accentTint)
    val putString: (String, String) -> Unit = { key, value ->
        haptics.tick()
        store.edit().putString(key, value).apply()
    }

    val pageTitle = stringResource(R.string.settings_player_look_page)
    val hint = stringResource(R.string.settings_player_preview_hint)
    val layoutTitle = stringResource(R.string.settings_player_layout_section)
    val buttonsTitle = stringResource(R.string.settings_player_buttons_section)
    val seekTitle = stringResource(R.string.settings_player_seek_style)
    val colorsTitle = stringResource(R.string.settings_player_colors_section)
    val colorsNote = stringResource(R.string.settings_player_accent_color_hint)
    val shadeTitle = stringResource(R.string.settings_player_shade_section)
    val shadeNote = stringResource(R.string.settings_player_shade_note)
    val sizeLabels = ControlsSize.entries.map { stringResource(it.labelRes) }
    val motionLabels = ControlsMotion.entries.map { stringResource(it.labelRes) }
    val timeLabels = TimeLayout.entries.map { stringResource(it.labelRes) }
    val shadeLabels = ScrimLevel.entries.map { stringResource(it.labelRes) }

    SubPage(title = pageTitle, onBack = onBack, modifier = modifier) {
        item(key = "look_preview") {
            Column(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlayerLookPreview(
                    look = look,
                    quickButtons = quickButtons,
                    quickOrder = quickOrder,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLuckyColors.current.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
        section(key = "look_layout", title = layoutTitle) {
            OptionLabel(stringResource(R.string.settings_player_layout), stringResource(look.layout.labelRes))
            LookTiles(
                options = ControlsLayout.entries,
                selected = look.layout,
                label = { stringResource(it.labelRes) },
                onSelect = { putString(LuckyPrefs.PLAYER_LAYOUT, it.key) }
            ) { option -> LayoutSketch(option, palette) }
            RowDivider(startInset = 20.dp)
            OptionLabel(stringResource(R.string.settings_player_controls_size))
            ChoiceTiles(
                options = sizeLabels,
                selected = ControlsSize.entries.indexOf(look.controlsSize),
                onSelect = { putString(LuckyPrefs.PLAYER_CONTROLS_SIZE, ControlsSize.entries[it].key) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
            RowDivider(startInset = 20.dp)
            OptionLabel(stringResource(R.string.settings_player_controls_motion))
            ChoiceTiles(
                options = motionLabels,
                selected = ControlsMotion.entries.indexOf(look.motion),
                onSelect = { putString(LuckyPrefs.PLAYER_CONTROLS_MOTION, ControlsMotion.entries[it].key) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        section(key = "look_buttons", title = buttonsTitle) {
            OptionLabel(stringResource(R.string.settings_player_button_style), stringResource(look.buttonStyle.labelRes))
            LookTiles(
                options = ButtonStyle.entries,
                selected = look.buttonStyle,
                label = { stringResource(it.labelRes) },
                onSelect = { putString(LuckyPrefs.PLAYER_BUTTON_STYLE, it.key) }
            ) { option ->
                CompositionLocalProvider(
                    LocalPlayerPalette provides palette,
                    LocalPlayerChromeStyle provides PlayerChromeStyle(option, look.playShape, look.glow)
                ) {
                    PlayerButtonGroup {
                        PlayerButton(Icons.Rounded.ClosedCaption, "", onClick = {}, active = true, size = 0.78f)
                        PlayerButton(Icons.Rounded.Tune, "", onClick = {}, size = 0.78f)
                    }
                }
            }
            RowDivider(startInset = 20.dp)
            OptionLabel(stringResource(R.string.settings_player_play_shape), stringResource(look.playShape.labelRes))
            LookTiles(
                options = PlayShape.entries,
                selected = look.playShape,
                label = { stringResource(it.labelRes) },
                onSelect = { putString(LuckyPrefs.PLAYER_PLAY_SHAPE, it.key) }
            ) { option ->
                CompositionLocalProvider(
                    LocalPlayerPalette provides palette,
                    LocalPlayerChromeStyle provides PlayerChromeStyle(look.buttonStyle, option, look.glow)
                ) {
                    PlayPauseButton(state = PlaybackState.PAUSED, onClick = {}, diameter = 38.dp)
                }
            }
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Rounded.WbSunny,
                title = stringResource(R.string.settings_player_accent_glow),
                subtitle = stringResource(R.string.settings_player_accent_glow_summary),
                checked = look.glow,
                onCheckedChange = { store.edit().putBoolean(LuckyPrefs.PLAYER_ACCENT_GLOW, it).apply() },
                enabled = look.playShape != PlayShape.BARE
            )
        }
        section(key = "look_seek", title = seekTitle) {
            Spacer(Modifier.height(6.dp))
            SeekStyle.entries.forEach { style ->
                SeekStyleRow(style, style == look.seekStyle) { putString(LuckyPrefs.PLAYER_SEEK_STYLE, style.key) }
            }
            Spacer(Modifier.height(6.dp))
            RowDivider(startInset = 20.dp)
            OptionLabel(stringResource(R.string.settings_player_time_layout))
            ChoiceTiles(
                options = timeLabels,
                selected = TimeLayout.entries.indexOf(look.timeLayout),
                onSelect = { putString(LuckyPrefs.PLAYER_TIME_LAYOUT, TimeLayout.entries[it].key) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        section(key = "look_colors", title = colorsTitle, note = colorsNote) {
            OptionLabel(stringResource(R.string.settings_player_icon_color), stringResource(look.iconTint.labelRes))
            SwatchRow(PlayerTint.entries, look.iconTint) { putString(LuckyPrefs.PLAYER_ICON_TINT, it.key) }
            RowDivider(startInset = 20.dp)
            OptionLabel(stringResource(R.string.settings_player_accent_color), stringResource(look.accentTint.labelRes))
            SwatchRow(PlayerTint.accents, look.accentTint) { putString(LuckyPrefs.PLAYER_ACCENT_TINT, it.key) }
        }
        section(key = "look_shade", title = shadeTitle, note = shadeNote) {
            ChoiceTiles(
                options = shadeLabels,
                selected = ScrimLevel.entries.indexOf(look.scrim),
                onSelect = { putString(LuckyPrefs.PLAYER_SCRIM, ScrimLevel.entries[it].key) },
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        section(key = "look_reset", title = null) {
            SettingsRow(
                icon = Icons.Rounded.RestartAlt,
                title = stringResource(R.string.settings_player_look_reset),
                subtitle = stringResource(R.string.settings_player_look_reset_summary),
                onClick = {
                    haptics.longPress()
                    store.edit().apply { PlayerLook.lookKeys.forEach { remove(it) } }.apply()
                },
                trailing = null
            )
        }
    }
}
