package com.jhaiian.lucky.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.ui.components.ChoiceRow
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.IconTileIcon
import com.jhaiian.lucky.ui.components.LuckySwitch
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.library.rememberPreferences
import com.jhaiian.lucky.ui.player.ControlsTimeouts
import com.jhaiian.lucky.ui.player.DoubleTapAction
import com.jhaiian.lucky.ui.player.FitModeLabels
import com.jhaiian.lucky.ui.player.HoldSpeeds
import com.jhaiian.lucky.ui.player.OrientationMode
import com.jhaiian.lucky.ui.player.PlayerLook
import com.jhaiian.lucky.ui.player.PlayerLookPreview
import com.jhaiian.lucky.ui.player.QuickButton
import com.jhaiian.lucky.ui.player.RepeatMode
import com.jhaiian.lucky.ui.player.SubtitleColor
import com.jhaiian.lucky.ui.player.SubtitleEdge
import com.jhaiian.lucky.ui.player.SubtitlePosition
import com.jhaiian.lucky.ui.player.SubtitleSizes
import com.jhaiian.lucky.ui.player.TopButton
import com.jhaiian.lucky.ui.player.formatSpeed
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

private enum class PlayerDialog { FIT, ORIENTATION, DOUBLE_TAP, HOLD, TIMEOUT, COLOR, EDGE, REPEAT }

@Composable
internal fun rememberFloatPref(key: String, default: Float): PrefState<Float> =
    rememberPref(key, default, { getFloat(key, default) }, { putFloat(key, it) })

private val QuickButtonIcons: Map<QuickButton, ImageVector> = mapOf(
    QuickButton.LOCK to Icons.Rounded.Lock,
    QuickButton.ASPECT to Icons.Rounded.AspectRatio,
    QuickButton.ROTATE to Icons.Rounded.ScreenRotation,
    QuickButton.SPEED to Icons.Rounded.Speed,
    QuickButton.REPEAT to Icons.Rounded.Repeat,
    QuickButton.MIRROR to Icons.Rounded.Flip,
    QuickButton.PIP to Icons.Rounded.PictureInPictureAlt,
    QuickButton.BACKGROUND to Icons.Rounded.Headphones,
    QuickButton.MOTION to Icons.Rounded.AutoAwesomeMotion,
    QuickButton.LOOP to Icons.Rounded.Loop,
    QuickButton.SCREENSHOT to Icons.Rounded.PhotoCamera
)

private val TopButtonIcons: Map<TopButton, ImageVector> = mapOf(
    TopButton.CHAPTERS to Icons.Rounded.Bookmarks,
    TopButton.SUBTITLES to Icons.Rounded.ClosedCaption,
    TopButton.AUDIO to Icons.Rounded.Audiotrack,
    TopButton.STATS to Icons.Rounded.Info
)

@Composable
private fun OrderArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = colors.secondaryText, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QuickButtonRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    val colors = LocalLuckyColors.current
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptics.toggle(!checked)
                onCheckedChange(!checked)
            }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTileIcon(icon)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 4.dp)
        )
        if (onMoveUp != null && onMoveDown != null) {
            OrderArrow(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.settings_move_up), canMoveUp, onMoveUp)
            OrderArrow(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.settings_move_down), canMoveDown, onMoveDown)
            Spacer(Modifier.width(6.dp))
        }
        LuckySwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun timeoutLabel(seconds: Int): String =
    if (seconds <= 0) stringResource(R.string.player_timeout_never) else pluralStringResource(R.plurals.player_timeout_seconds, seconds, seconds)

@Composable
private fun holdLabel(speed: Float): String =
    if (speed <= 0f) stringResource(R.string.player_option_off) else formatSpeed(speed)

@Composable
internal fun PlayerSettings(onBack: () -> Unit, onOpenLook: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val defaultFit = rememberStringPref(LuckyPrefs.PLAYER_DEFAULT_FIT, FitMode.FIT.name)
    val orientation = rememberStringPref(LuckyPrefs.PLAYER_ORIENTATION, OrientationMode.VIDEO.key)
    val doubleTap = rememberStringPref(LuckyPrefs.PLAYER_DOUBLE_TAP, DoubleTapAction.SEEK.key)
    val swipeSeek = rememberBooleanPref(LuckyPrefs.PLAYER_SWIPE_SEEK, true)
    val swipeBrightness = rememberBooleanPref(LuckyPrefs.PLAYER_SWIPE_BRIGHTNESS, true)
    val swipeVolume = rememberBooleanPref(LuckyPrefs.PLAYER_SWIPE_VOLUME, true)
    val swapSides = rememberBooleanPref(LuckyPrefs.PLAYER_SWAP_SIDES, false)
    val pinchZoom = rememberBooleanPref(LuckyPrefs.PLAYER_PINCH_ZOOM, true)
    val holdSpeed = rememberFloatPref(LuckyPrefs.PLAYER_HOLD_SPEED, 2f)
    val timeout = rememberIntPref(LuckyPrefs.PLAYER_CONTROLS_TIMEOUT, 4)
    val seekButtons = rememberBooleanPref(LuckyPrefs.PLAYER_SEEK_BUTTONS, true)
    val showRemaining = rememberBooleanPref(LuckyPrefs.PLAYER_SHOW_REMAINING, false)
    val showClock = rememberBooleanPref(LuckyPrefs.PLAYER_SHOW_CLOCK, false)
    val quickButtons = rememberPref(
        LuckyPrefs.PLAYER_QUICK_BUTTONS,
        QuickButton.defaults,
        { QuickButton.read(this) },
        { putStringSet(LuckyPrefs.PLAYER_QUICK_BUTTONS, HashSet(it)).putBoolean(LuckyPrefs.PLAYER_QUICK_MOTION, true).putBoolean(LuckyPrefs.PLAYER_QUICK_BACKGROUND, true) }
    )
    val quickOrder = rememberPref(
        LuckyPrefs.PLAYER_QUICK_ORDER,
        QuickButton.entries.toList(),
        { QuickButton.order(this) },
        { putString(LuckyPrefs.PLAYER_QUICK_ORDER, QuickButton.encodeOrder(it)) }
    )
    val topButtons = rememberPref(
        LuckyPrefs.PLAYER_TOP_BUTTONS,
        TopButton.defaults,
        { TopButton.read(this) },
        { putStringSet(LuckyPrefs.PLAYER_TOP_BUTTONS, HashSet(it)) }
    )
    val look = PlayerLook.from(rememberPreferences())
    val autoplayNext = rememberBooleanPref(LuckyPrefs.PLAYER_AUTOPLAY_NEXT, true)
    val repeat = rememberStringPref(LuckyPrefs.PLAYER_REPEAT, RepeatMode.OFF.key)
    val autoPip = rememberBooleanPref(LuckyPrefs.PLAYER_AUTO_PIP, true)
    val backgroundPlay = rememberBooleanPref(LuckyPrefs.PLAYER_BACKGROUND_PLAY, false)
    val rememberBrightness = rememberBooleanPref(LuckyPrefs.PLAYER_REMEMBER_BRIGHTNESS, false)
    val rememberSpeed = rememberBooleanPref(LuckyPrefs.PLAYER_REMEMBER_SPEED, false)
    val rememberVolume = rememberBooleanPref(LuckyPrefs.PLAYER_REMEMBER_VOLUME, true)
    val subtitleSize = rememberIntPref(LuckyPrefs.SUBTITLE_SIZE, 100)
    val subtitleColor = rememberStringPref(LuckyPrefs.SUBTITLE_COLOR, SubtitleColor.WHITE.key)
    val subtitleEdge = rememberStringPref(LuckyPrefs.SUBTITLE_EDGE, SubtitleEdge.OUTLINE.key)
    val subtitleBold = rememberBooleanPref(LuckyPrefs.SUBTITLE_BOLD, false)
    val subtitlePosition = rememberStringPref(LuckyPrefs.SUBTITLE_POSITION, SubtitlePosition.NORMAL.key)
    val subtitleSystem = rememberBooleanPref(LuckyPrefs.SUBTITLE_SYSTEM_STYLE, false)
    val subtitleLibass = rememberBooleanPref(LuckyPrefs.SUBTITLE_LIBASS, true)
    val pipAvailable = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }

    val fit = FitMode.parse(defaultFit.value)
    val orientationMode = OrientationMode.parse(orientation.value)
    val doubleTapAction = DoubleTapAction.parse(doubleTap.value)
    val repeatMode = RepeatMode.parse(repeat.value)
    val color = SubtitleColor.parse(subtitleColor.value)
    val edge = SubtitleEdge.parse(subtitleEdge.value)
    val position = SubtitlePosition.parse(subtitlePosition.value)
    val movable = quickOrder.value.filter { it != QuickButton.LOCK && (it != QuickButton.PIP || pipAvailable) }
    val moveQuick: (QuickButton, Int) -> Unit = { button, delta ->
        val from = movable.indexOf(button)
        val to = from + delta
        if (from >= 0 && to in movable.indices) {
            haptics.tick()
            val list = quickOrder.value.toMutableList()
            val other = movable[to]
            val a = list.indexOf(button)
            val b = list.indexOf(other)
            list[a] = other
            list[b] = button
            quickOrder.update(list)
        }
    }

    val pageTitle = stringResource(R.string.settings_player)
    val displayTitle = stringResource(R.string.settings_player_display)
    val gesturesTitle = stringResource(R.string.settings_player_gestures)
    val gesturesNote = stringResource(R.string.settings_player_gestures_note)
    val controlsTitle = stringResource(R.string.settings_player_controls)
    val lookTitle = stringResource(R.string.settings_player_look)
    val buttonsTitle = stringResource(R.string.settings_player_quick_buttons)
    val buttonsNote = stringResource(R.string.settings_player_quick_buttons_order_note)
    val topTitle = stringResource(R.string.settings_player_top_buttons)
    val topNote = stringResource(R.string.settings_player_top_buttons_note)
    val lookSummary = listOf(
        stringResource(look.layout.labelRes),
        stringResource(look.buttonStyle.labelRes),
        stringResource(look.seekStyle.labelRes)
    ).joinToString(" · ")
    val subtitlesTitle = stringResource(R.string.settings_player_subtitles)
    val behaviorTitle = stringResource(R.string.settings_player_behavior)
    val subtitleSizeLabels = SubtitleSizes.map { "$it%" }
    val positionLabels = SubtitlePosition.entries.map { stringResource(it.labelRes) }

    Box(modifier.fillMaxSize()) {
        SubPage(title = pageTitle, onBack = onBack) {
            section(key = "player_display", title = displayTitle) {
                SettingsRow(
                    icon = Icons.Rounded.AspectRatio,
                    title = stringResource(R.string.settings_player_default_fit),
                    subtitle = stringResource(FitModeLabels.getValue(fit)),
                    onClick = { dialog = PlayerDialog.FIT }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.ScreenRotation,
                    title = stringResource(R.string.settings_player_orientation),
                    subtitle = stringResource(orientationMode.labelRes),
                    onClick = { dialog = PlayerDialog.ORIENTATION }
                )
            }
            section(key = "player_gestures", title = gesturesTitle, note = gesturesNote) {
                SettingsRow(
                    icon = Icons.Rounded.TouchApp,
                    title = stringResource(R.string.settings_player_double_tap_action),
                    subtitle = stringResource(doubleTapAction.labelRes),
                    onClick = { dialog = PlayerDialog.DOUBLE_TAP }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.SwipeLeft,
                    title = stringResource(R.string.settings_player_swipe_seek),
                    subtitle = stringResource(R.string.settings_player_swipe_seek_summary),
                    checked = swipeSeek.value,
                    onCheckedChange = { swipeSeek.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Brightness6,
                    title = stringResource(R.string.settings_player_swipe_brightness),
                    subtitle = stringResource(if (swapSides.value) R.string.settings_player_right_side else R.string.settings_player_left_side),
                    checked = swipeBrightness.value,
                    onCheckedChange = { swipeBrightness.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.settings_player_swipe_volume),
                    subtitle = stringResource(if (swapSides.value) R.string.settings_player_left_side else R.string.settings_player_right_side),
                    checked = swipeVolume.value,
                    onCheckedChange = { swipeVolume.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.SwapHoriz,
                    title = stringResource(R.string.settings_player_swap_sides),
                    subtitle = stringResource(R.string.settings_player_swap_sides_summary),
                    checked = swapSides.value,
                    onCheckedChange = { swapSides.update(it) },
                    enabled = swipeBrightness.value || swipeVolume.value
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.ZoomIn,
                    title = stringResource(R.string.settings_player_pinch_zoom),
                    subtitle = stringResource(R.string.settings_player_pinch_zoom_summary),
                    checked = pinchZoom.value,
                    onCheckedChange = { pinchZoom.update(it) }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.FastForward,
                    title = stringResource(R.string.settings_player_hold_speed),
                    subtitle = holdLabel(holdSpeed.value),
                    onClick = { dialog = PlayerDialog.HOLD }
                )
            }
            section(key = "player_controls", title = controlsTitle) {
                SettingsRow(
                    icon = Icons.Rounded.Timer,
                    title = stringResource(R.string.settings_player_controls_timeout),
                    subtitle = timeoutLabel(timeout.value),
                    onClick = { dialog = PlayerDialog.TIMEOUT }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Forward10,
                    title = stringResource(R.string.settings_player_seek_buttons),
                    subtitle = stringResource(R.string.settings_player_seek_buttons_summary),
                    checked = seekButtons.value,
                    onCheckedChange = { seekButtons.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.TimerOff,
                    title = stringResource(R.string.settings_player_show_remaining),
                    subtitle = stringResource(R.string.settings_player_show_remaining_summary),
                    checked = showRemaining.value,
                    onCheckedChange = { showRemaining.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.settings_player_show_clock),
                    subtitle = stringResource(R.string.settings_player_show_clock_summary),
                    checked = showClock.value,
                    onCheckedChange = { showClock.update(it) }
                )
            }
            section(key = "player_look", title = lookTitle) {
                PlayerLookPreview(
                    look = look,
                    quickButtons = quickButtons.value,
                    quickOrder = quickOrder.value,
                    onClick = onOpenLook,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                )
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_player_look_page),
                    subtitle = lookSummary,
                    onClick = onOpenLook
                )
            }
            section(key = "player_buttons", title = buttonsTitle, note = buttonsNote) {
                QuickButtonRow(
                    icon = QuickButtonIcons.getValue(QuickButton.LOCK),
                    title = stringResource(QuickButton.LOCK.labelRes),
                    checked = QuickButton.LOCK.key in quickButtons.value,
                    onCheckedChange = { checked ->
                        quickButtons.update(if (checked) quickButtons.value + QuickButton.LOCK.key else quickButtons.value - QuickButton.LOCK.key)
                    },
                    canMoveUp = false,
                    canMoveDown = false,
                    onMoveUp = null,
                    onMoveDown = null
                )
                movable.forEachIndexed { index, button ->
                    RowDivider()
                    QuickButtonRow(
                        icon = QuickButtonIcons.getValue(button),
                        title = stringResource(button.labelRes),
                        checked = button.key in quickButtons.value,
                        onCheckedChange = { checked ->
                            quickButtons.update(if (checked) quickButtons.value + button.key else quickButtons.value - button.key)
                        },
                        canMoveUp = index > 0,
                        canMoveDown = index < movable.lastIndex,
                        onMoveUp = { moveQuick(button, -1) },
                        onMoveDown = { moveQuick(button, 1) }
                    )
                }
            }
            section(key = "player_top_buttons", title = topTitle, note = topNote) {
                TopButton.entries.forEachIndexed { index, button ->
                    if (index > 0) RowDivider()
                    SettingsSwitchRow(
                        icon = TopButtonIcons.getValue(button),
                        title = stringResource(button.labelRes),
                        checked = button.key in topButtons.value,
                        onCheckedChange = { checked ->
                            topButtons.update(if (checked) topButtons.value + button.key else topButtons.value - button.key)
                        }
                    )
                }
            }
            section(key = "player_subtitles", title = subtitlesTitle) {
                SubtitlePreview(
                    color = color,
                    edge = edge,
                    bold = subtitleBold.value,
                    sizePercent = subtitleSize.value,
                    followSystem = subtitleSystem.value
                )
                SettingsSwitchRow(
                    icon = Icons.Rounded.ClosedCaption,
                    title = stringResource(R.string.settings_player_subtitle_system),
                    subtitle = stringResource(R.string.settings_player_subtitle_system_summary),
                    checked = subtitleSystem.value,
                    onCheckedChange = { subtitleSystem.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Subtitles,
                    title = stringResource(R.string.settings_player_subtitle_libass),
                    subtitle = stringResource(R.string.settings_player_subtitle_libass_summary),
                    checked = subtitleLibass.value,
                    onCheckedChange = { subtitleLibass.update(it) }
                )
                RowDivider()
                Text(
                    stringResource(R.string.settings_player_subtitle_size),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = LocalLuckyColors.current.onSurface,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
                )
                ChoiceTiles(
                    options = subtitleSizeLabels,
                    selected = SubtitleSizes.indexOf(subtitleSize.value).coerceAtLeast(0),
                    onSelect = { index ->
                        haptics.tick()
                        subtitleSize.update(SubtitleSizes[index])
                    },
                    modifier = Modifier.padding(12.dp)
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.FormatColorText,
                    title = stringResource(R.string.settings_player_subtitle_color),
                    subtitle = stringResource(color.labelRes),
                    onClick = { dialog = PlayerDialog.COLOR },
                    enabled = !subtitleSystem.value
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.BorderStyle,
                    title = stringResource(R.string.settings_player_subtitle_edge),
                    subtitle = stringResource(edge.labelRes),
                    onClick = { dialog = PlayerDialog.EDGE },
                    enabled = !subtitleSystem.value
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.FormatBold,
                    title = stringResource(R.string.settings_player_subtitle_bold),
                    checked = subtitleBold.value,
                    onCheckedChange = { subtitleBold.update(it) }
                )
                RowDivider()
                Text(
                    stringResource(R.string.settings_player_subtitle_position),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = LocalLuckyColors.current.onSurface,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
                )
                ChoiceTiles(
                    options = positionLabels,
                    selected = SubtitlePosition.entries.indexOf(position),
                    onSelect = { index ->
                        haptics.tick()
                        subtitlePosition.update(SubtitlePosition.entries[index].key)
                    },
                    modifier = Modifier.padding(12.dp)
                )
            }
            section(key = "player_behavior", title = behaviorTitle) {
                SettingsSwitchRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    title = stringResource(R.string.settings_player_autoplay_next),
                    subtitle = stringResource(R.string.settings_player_autoplay_next_summary),
                    checked = autoplayNext.value,
                    onCheckedChange = { autoplayNext.update(it) }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Repeat,
                    title = stringResource(R.string.player_button_repeat),
                    subtitle = stringResource(repeatMode.labelRes),
                    onClick = { dialog = PlayerDialog.REPEAT }
                )
                if (pipAvailable) {
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.PictureInPictureAlt,
                        title = stringResource(R.string.settings_player_auto_pip),
                        subtitle = stringResource(R.string.settings_player_auto_pip_summary),
                        checked = autoPip.value,
                        onCheckedChange = { autoPip.update(it) }
                    )
                }
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Headphones,
                    title = stringResource(R.string.settings_player_background_play),
                    subtitle = stringResource(R.string.settings_player_background_play_summary),
                    checked = backgroundPlay.value,
                    onCheckedChange = { backgroundPlay.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Brightness6,
                    title = stringResource(R.string.settings_player_remember_brightness),
                    subtitle = stringResource(R.string.settings_player_remember_brightness_summary),
                    checked = rememberBrightness.value,
                    onCheckedChange = { rememberBrightness.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.settings_player_remember_speed),
                    subtitle = stringResource(R.string.settings_player_remember_speed_summary),
                    checked = rememberSpeed.value,
                    onCheckedChange = { rememberSpeed.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.settings_player_remember_volume),
                    subtitle = stringResource(R.string.settings_player_remember_volume_summary),
                    checked = rememberVolume.value,
                    onCheckedChange = { rememberVolume.update(it) }
                )
            }
        }

        val choose: (() -> Unit) -> Unit = { action ->
            haptics.tick()
            action()
            dialog = null
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.FIT,
            icon = Icons.Rounded.AspectRatio,
            title = stringResource(R.string.settings_player_default_fit),
            onDismiss = { dialog = null }
        ) {
            FitMode.entries.forEach { mode ->
                ChoiceRow(stringResource(FitModeLabels.getValue(mode)), mode == fit, onClick = { choose { defaultFit.update(mode.name) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.ORIENTATION,
            icon = Icons.Rounded.ScreenRotation,
            title = stringResource(R.string.settings_player_orientation),
            header = stringResource(R.string.settings_player_orientation_hint),
            onDismiss = { dialog = null }
        ) {
            OrientationMode.entries.forEach { mode ->
                ChoiceRow(stringResource(mode.labelRes), mode == orientationMode, onClick = { choose { orientation.update(mode.key) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.DOUBLE_TAP,
            icon = Icons.Rounded.TouchApp,
            title = stringResource(R.string.settings_player_double_tap_action),
            header = stringResource(R.string.settings_player_double_tap_hint),
            onDismiss = { dialog = null }
        ) {
            DoubleTapAction.entries.forEach { action ->
                ChoiceRow(stringResource(action.labelRes), action == doubleTapAction, onClick = { choose { doubleTap.update(action.key) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.HOLD,
            icon = Icons.Rounded.FastForward,
            title = stringResource(R.string.settings_player_hold_speed),
            header = stringResource(R.string.settings_player_hold_speed_hint),
            onDismiss = { dialog = null }
        ) {
            HoldSpeeds.forEach { speed ->
                ChoiceRow(holdLabel(speed), speed == holdSpeed.value, onClick = { choose { holdSpeed.update(speed) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.TIMEOUT,
            icon = Icons.Rounded.Timer,
            title = stringResource(R.string.settings_player_controls_timeout),
            onDismiss = { dialog = null }
        ) {
            ControlsTimeouts.forEach { seconds ->
                ChoiceRow(timeoutLabel(seconds), seconds == timeout.value, onClick = { choose { timeout.update(seconds) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.COLOR,
            icon = Icons.Rounded.FormatColorText,
            title = stringResource(R.string.settings_player_subtitle_color),
            onDismiss = { dialog = null }
        ) {
            SubtitleColor.entries.forEach { option ->
                ChoiceRow(stringResource(option.labelRes), option == color, onClick = { choose { subtitleColor.update(option.key) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.EDGE,
            icon = Icons.Rounded.BorderStyle,
            title = stringResource(R.string.settings_player_subtitle_edge),
            onDismiss = { dialog = null }
        ) {
            SubtitleEdge.entries.forEach { option ->
                ChoiceRow(stringResource(option.labelRes), option == edge, onClick = { choose { subtitleEdge.update(option.key) } })
            }
        }
        SettingsDialog(
            visible = dialog == PlayerDialog.REPEAT,
            icon = Icons.Rounded.Repeat,
            title = stringResource(R.string.player_button_repeat),
            onDismiss = { dialog = null }
        ) {
            RepeatMode.entries.forEach { mode ->
                ChoiceRow(stringResource(mode.labelRes), mode == repeatMode, onClick = { choose { repeat.update(mode.key) } })
            }
        }
    }
}

@Composable
private fun SubtitlePreview(color: SubtitleColor, edge: SubtitleEdge, bold: Boolean, sizePercent: Int, followSystem: Boolean) {
    val context = LocalContext.current
    val systemStyle = remember(followSystem) {
        if (followSystem) context.getSystemService(CaptioningManager::class.java)?.takeIf { it.isEnabled }?.userStyle else null
    }
    val foreground = if (followSystem) {
        systemStyle?.takeIf { it.hasForegroundColor() }?.let { Color(it.foregroundColor) } ?: Color.White
    } else {
        color.color
    }
    val effectiveEdge = if (followSystem) SubtitleEdge.OUTLINE else edge
    val fontSize = (16f * sizePercent / 100f).sp
    val base = TextStyle(
        fontSize = fontSize,
        lineHeight = fontSize * 1.2f,
        textAlign = TextAlign.Center,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
    )
    val sample = stringResource(R.string.settings_player_subtitle_sample)
    Box(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF6B4E71), Color(0xFFB0735A)))),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            Modifier
                .padding(bottom = 14.dp, start = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (effectiveEdge == SubtitleEdge.BOX) Color.Black.copy(alpha = 0.62f) else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            when (effectiveEdge) {
                SubtitleEdge.OUTLINE -> {
                    Text(sample, style = base.copy(color = Color.Black, drawStyle = Stroke(width = fontSize.value * 0.3f)))
                    Text(sample, style = base.copy(color = foreground))
                }
                SubtitleEdge.SHADOW -> Text(
                    sample,
                    style = base.copy(color = foreground, shadow = Shadow(Color.Black, Offset(2f, 2f), 4f))
                )
                else -> Text(sample, style = base.copy(color = foreground))
            }
        }
    }
}
