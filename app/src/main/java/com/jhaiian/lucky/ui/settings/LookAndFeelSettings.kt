package com.jhaiian.lucky.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.customtheme.customAccentLabel
import com.jhaiian.lucky.customtheme.isCustomAccent
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.ui.ThemeSwatchUtils
import com.jhaiian.lucky.ui.components.ChoiceRow
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.RowArrow
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import com.jhaiian.lucky.ui.theme.ThemeMode
import com.jhaiian.lucky.ui.theme.resolveLuckyTheme
import com.jhaiian.lucky.util.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LabeledOption(val key: String, @StringRes val label: Int)

internal class IntensityOption(
    val key: String,
    @StringRes val label: Int,
    @StringRes val lightDescription: Int,
    @StringRes val darkDescription: Int
)

internal const val MATERIAL_YOU_ACCENT = "material_you"
internal const val DEFAULT_INTENSITY = "soft_tint"

internal val ThemeOptions = listOf(
    LabeledOption(ThemeMode.SYSTEM, R.string.theme_system),
    LabeledOption(ThemeMode.LIGHT, R.string.theme_light),
    LabeledOption(ThemeMode.DARK, R.string.theme_dark)
)

internal val AccentOptions = listOf(
    LabeledOption(MATERIAL_YOU_ACCENT, R.string.accent_material_you),
    LabeledOption("purple", R.string.accent_purple),
    LabeledOption("deep_purple", R.string.accent_deep_purple),
    LabeledOption("royal_purple", R.string.accent_royal_purple),
    LabeledOption("amethyst", R.string.accent_amethyst),
    LabeledOption("lavender", R.string.accent_lavender),
    LabeledOption("plum", R.string.accent_plum),
    LabeledOption("violet", R.string.accent_violet),
    LabeledOption("default", R.string.accent_default),
    LabeledOption("charcoal", R.string.accent_charcoal),
    LabeledOption("slate", R.string.accent_slate),
    LabeledOption("graphite", R.string.accent_graphite),
    LabeledOption("obsidian", R.string.accent_obsidian),
    LabeledOption("onyx", R.string.accent_onyx),
    LabeledOption("titanium", R.string.accent_titanium),
    LabeledOption("indigo", R.string.accent_indigo),
    LabeledOption("blue", R.string.accent_blue),
    LabeledOption("midnight", R.string.accent_midnight),
    LabeledOption("cyan", R.string.accent_cyan),
    LabeledOption("sky", R.string.accent_sky),
    LabeledOption("teal", R.string.accent_teal),
    LabeledOption("azure", R.string.accent_azure),
    LabeledOption("yellow", R.string.accent_yellow),
    LabeledOption("lemon", R.string.accent_lemon),
    LabeledOption("gold", R.string.accent_gold),
    LabeledOption("amber", R.string.accent_amber),
    LabeledOption("sand", R.string.accent_sand),
    LabeledOption("sepia", R.string.accent_sepia),
    LabeledOption("mustard", R.string.accent_mustard),
    LabeledOption("red", R.string.accent_red),
    LabeledOption("crimson", R.string.accent_crimson),
    LabeledOption("ruby", R.string.accent_ruby),
    LabeledOption("pink", R.string.accent_pink),
    LabeledOption("coral", R.string.accent_coral),
    LabeledOption("burgundy", R.string.accent_burgundy),
    LabeledOption("scarlet", R.string.accent_scarlet),
    LabeledOption("orange", R.string.accent_orange),
    LabeledOption("deep_orange", R.string.accent_deep_orange),
    LabeledOption("tangerine", R.string.accent_tangerine),
    LabeledOption("apricot", R.string.accent_apricot),
    LabeledOption("copper", R.string.accent_copper),
    LabeledOption("peach", R.string.accent_peach),
    LabeledOption("terracotta", R.string.accent_terracotta),
    LabeledOption("green", R.string.accent_green),
    LabeledOption("emerald", R.string.accent_emerald),
    LabeledOption("mint", R.string.accent_mint),
    LabeledOption("forest", R.string.accent_forest),
    LabeledOption("sage", R.string.accent_sage),
    LabeledOption("lime", R.string.accent_lime),
    LabeledOption("olive", R.string.accent_olive)
)

internal val IntensityOptions = listOf(
    IntensityOption("no_tint", R.string.surface_intensity_no_tint, R.string.surface_intensity_no_tint_desc, R.string.surface_intensity_no_tint_desc),
    IntensityOption("soft_tint", R.string.surface_intensity_soft, R.string.surface_intensity_soft_desc, R.string.surface_intensity_soft_desc),
    IntensityOption("strong_tint", R.string.surface_intensity_strong, R.string.surface_intensity_strong_desc, R.string.surface_intensity_strong_desc),
    IntensityOption("pure_mode", R.string.surface_intensity_pure, R.string.surface_intensity_pure_light_desc, R.string.surface_intensity_pure_dark_desc),
    IntensityOption(
        "amoled_no_tint",
        R.string.surface_intensity_amoled_no_tint,
        R.string.surface_intensity_amoled_no_tint_light_desc,
        R.string.surface_intensity_amoled_no_tint_dark_desc
    )
)

internal val LanguageOptions = listOf(
    LabeledOption(LocaleHelper.LANGUAGE_SYSTEM, R.string.settings_language_system),
    LabeledOption(LocaleHelper.LANGUAGE_ENGLISH, R.string.language_name_english),
    LabeledOption(LocaleHelper.LANGUAGE_CHINESE_SIMPLIFIED, R.string.language_name_chinese_simplified),
    LabeledOption(LocaleHelper.LANGUAGE_INDONESIAN, R.string.language_name_indonesian),
    LabeledOption(LocaleHelper.LANGUAGE_SPANISH, R.string.language_name_spanish),
    LabeledOption(LocaleHelper.LANGUAGE_FILIPINO, R.string.language_name_filipino),
    LabeledOption(LocaleHelper.LANGUAGE_PORTUGUESE_BRAZIL, R.string.language_name_portuguese_brazil),
    LabeledOption(LocaleHelper.LANGUAGE_RUSSIAN, R.string.language_name_russian),
    LabeledOption(LocaleHelper.LANGUAGE_HINDI, R.string.language_name_hindi)
)

private enum class LookDialog { ACCENT, SURFACE, LANGUAGE }

internal val materialYouSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal fun LookAndFeelSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val activity = rememberLuckyActivity()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val theme = rememberStringPref(LuckyPrefs.THEME, ThemeMode.SYSTEM)
    val accent = rememberStringPref(LuckyPrefs.ACCENT, MATERIAL_YOU_ACCENT)
    val intensity = rememberStringPref(LuckyPrefs.SURFACE_INTENSITY, DEFAULT_INTENSITY)
    val language = rememberStringPref(LocaleHelper.PREF_APP_LANGUAGE, LocaleHelper.LANGUAGE_SYSTEM)
    val hideStatusBar = rememberBooleanPref(LuckyPrefs.HIDE_STATUS_BAR, true)
    val hideNavigationBar = rememberBooleanPref(LuckyPrefs.HIDE_NAVIGATION_BAR, false)
    var openDialog by remember { mutableStateOf<LookDialog?>(null) }

    val intensitySupported = ThemeSwatchUtils.isSurfaceIntensityEnabled(theme.value, accent.value)
    val accentName = if (isCustomAccent(accent.value)) {
        remember(accent.value) { customAccentLabel(context, accent.value) }
    } else {
        stringResource(AccentOptions.firstOrNull { it.key == accent.value }?.label ?: R.string.accent_material_you)
    }
    val intensityName = stringResource((IntensityOptions.firstOrNull { it.key == intensity.value } ?: IntensityOptions[1]).label)
    val languageName = stringResource(LanguageOptions.firstOrNull { it.key == language.value }?.label ?: R.string.settings_language_system)

    val pageTitle = stringResource(R.string.settings_look_and_feel)
    val themeTitle = stringResource(R.string.settings_section_theme)
    val colorsTitle = stringResource(R.string.settings_section_colors)
    val colorsNote = if (intensitySupported) null else stringResource(R.string.settings_surface_unavailable)
    val languageTitle = stringResource(R.string.settings_section_language)
    val barsTitle = stringResource(R.string.settings_section_system_bars)

    val applyAfterDialog: (() -> Unit) -> Unit = { action ->
        openDialog = null
        scope.launch {
            delay(DialogExitDelayMillis)
            action()
        }
    }

    Box(modifier.fillMaxSize()) {
        SubPage(title = pageTitle, onBack = onBack) {
            item(key = "look_preview") { LookPreviewCard() }
            section(key = "look_theme", title = themeTitle) {
                ChoiceTiles(
                    options = ThemeOptions.map { stringResource(it.label) },
                    selected = ThemeOptions.indexOfFirst { it.key == theme.value }.coerceAtLeast(0),
                    onSelect = { index ->
                        val key = ThemeOptions[index].key
                        if (key != theme.value) {
                            haptics.tick()
                            activity?.captureAndRecreate(key)
                        }
                    },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            section(key = "look_colors", title = colorsTitle, note = colorsNote) {
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_accent),
                    subtitle = accentName,
                    onClick = { openDialog = LookDialog.ACCENT },
                    trailing = {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(colors.primary)
                                .border(BorderStroke(1.dp, colors.divider), CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        RowArrow()
                    }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Gradient,
                    title = stringResource(R.string.settings_surface),
                    subtitle = intensityName,
                    enabled = intensitySupported,
                    onClick = { openDialog = LookDialog.SURFACE }
                )
            }
            section(key = "look_language", title = languageTitle) {
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.settings_language),
                    subtitle = languageName,
                    onClick = { openDialog = LookDialog.LANGUAGE }
                )
            }
            section(key = "look_bars", title = barsTitle) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.settings_hide_status_bar),
                    subtitle = stringResource(R.string.settings_hide_status_bar_summary),
                    checked = hideStatusBar.value,
                    onCheckedChange = {
                        hideStatusBar.update(it)
                        activity?.applyStatusBarVisibility()
                    }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.settings_hide_navigation_bar),
                    subtitle = stringResource(R.string.settings_hide_navigation_bar_summary),
                    checked = hideNavigationBar.value,
                    onCheckedChange = {
                        hideNavigationBar.update(it)
                        activity?.applyStatusBarVisibility()
                    }
                )
            }
        }

        AccentDialog(
            visible = openDialog == LookDialog.ACCENT,
            current = accent.value,
            intensity = intensity.value,
            onSelect = { key ->
                haptics.tick()
                if (key == accent.value) {
                    openDialog = null
                } else {
                    applyAfterDialog { activity?.captureAndApplyAccentColor(key) }
                }
            },
            onDismiss = { openDialog = null }
        )

        SettingsDialog(
            visible = openDialog == LookDialog.SURFACE,
            icon = Icons.Rounded.Gradient,
            title = stringResource(R.string.settings_surface),
            onDismiss = { openDialog = null }
        ) {
            IntensityOptions.forEach { option ->
                ChoiceRow(
                    label = stringResource(option.label),
                    detail = stringResource(if (colors.isLight) option.lightDescription else option.darkDescription),
                    selected = option.key == intensity.value,
                    onClick = {
                        haptics.tick()
                        if (option.key == intensity.value) {
                            openDialog = null
                        } else {
                            applyAfterDialog { activity?.captureAndApplySurfaceIntensity(option.key) }
                        }
                    }
                )
            }
        }

        SettingsDialog(
            visible = openDialog == LookDialog.LANGUAGE,
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.settings_language),
            onDismiss = { openDialog = null }
        ) {
            LanguageOptions.forEach { option ->
                ChoiceRow(
                    label = stringResource(option.label),
                    selected = option.key == language.value,
                    onClick = {
                        haptics.tick()
                        if (option.key == language.value) {
                            openDialog = null
                        } else {
                            applyAfterDialog { activity?.captureAndApplyLanguage(option.key) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LookPreviewCard() {
    val colors = LocalLuckyColors.current
    val corners = rememberStringPref(LuckyPrefs.LIBRARY_CORNERS, "rounded")
    val showDuration = rememberBooleanPref(LuckyPrefs.LIBRARY_SHOW_DURATION, true)
    val showBadges = rememberBooleanPref(LuckyPrefs.LIBRARY_SHOW_BADGES, true)
    val radius = (10f * LuckyPrefs.cornerScale(corners.value)).dp
    val badgeRadius = (5f * LuckyPrefs.cornerScale(corners.value)).coerceAtMost(8f).dp
    Column(
        Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.cardBackground)
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.settings_preview_label).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colors.secondaryText
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Box(
                Modifier
                    .width(116.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(radius))
                    .background(colors.surfaceVariant)
            ) {
                Icon(
                    Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = colors.primary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(26.dp)
                )
                if (showBadges.value) {
                    Text(
                        stringResource(R.string.settings_preview_resolution),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(badgeRadius))
                            .background(colors.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (showDuration.value) {
                    Text(
                        stringResource(R.string.settings_preview_duration),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(badgeRadius))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(colors.primary.copy(alpha = 0.25f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.42f)
                            .fillMaxHeight()
                            .background(colors.primary)
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    stringResource(R.string.settings_preview_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.settings_preview_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun AccentDialog(
    visible: Boolean,
    current: String,
    intensity: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val mode = if (colors.isLight) ThemeMode.LIGHT else ThemeMode.DARK
    val swatches = remember(mode, intensity) {
        AccentOptions.associate { option ->
            val resolved = resolveLuckyTheme(context, mode, option.key, intensity)
            option.key to (resolved.primary to resolved.onPrimary)
        }
    }
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard(
            icon = Icons.Rounded.Palette,
            title = stringResource(R.string.settings_accent_pick),
            onClose = onDismiss,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(vertical = 16.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(AccentOptions, key = { it.key }) { option ->
                    val enabled = option.key != MATERIAL_YOU_ACCENT || materialYouSupported
                    val (swatch, onSwatch) = swatches[option.key] ?: (colors.primary to colors.onPrimary)
                    AccentSwatch(
                        label = stringResource(option.label),
                        color = swatch,
                        checkColor = onSwatch,
                        selected = option.key == current,
                        enabled = enabled,
                        onClick = { onSelect(option.key) }
                    )
                }
            }
            if (!materialYouSupported) {
                Text(
                    stringResource(R.string.settings_accent_material_you_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    label: String,
    color: Color,
    checkColor: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalLuckyColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .border(BorderStroke(2.dp, if (selected) colors.primary else Color.Transparent), CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = checkColor, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.primary else colors.secondaryText,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
        )
    }
}
