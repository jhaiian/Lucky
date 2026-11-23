package com.jhaiian.lucky.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section

internal val CornerOptions = listOf(
    LabeledOption("sharp", R.string.settings_corners_sharp),
    LabeledOption("rounded", R.string.settings_corners_rounded),
    LabeledOption("round", R.string.settings_corners_round)
)

@Composable
internal fun LibrarySettings(onBack: () -> Unit, onManageFolders: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val corners = rememberStringPref(LuckyPrefs.LIBRARY_CORNERS, "rounded")
    val showDuration = rememberBooleanPref(LuckyPrefs.LIBRARY_SHOW_DURATION, true)
    val showBadges = rememberBooleanPref(LuckyPrefs.LIBRARY_SHOW_BADGES, true)
    val hiddenSections = rememberStringSetPref(LuckyPrefs.HOME_HIDDEN_SECTIONS)
    val scrollbar = rememberBooleanPref(LuckyPrefs.LIBRARY_SHOW_SCROLLBAR, true)
    val alphabetIndex = rememberBooleanPref(LuckyPrefs.LIBRARY_ALPHABET_INDEX, true)

    val pageTitle = stringResource(R.string.settings_library)
    val foldersTitle = stringResource(R.string.settings_section_folders)
    val thumbnailsTitle = stringResource(R.string.settings_section_thumbnails)
    val cornersNote = stringResource(R.string.settings_corners_note)
    val detailsTitle = stringResource(R.string.settings_section_details)
    val homeTitle = stringResource(R.string.settings_section_home)
    val scrollingTitle = stringResource(R.string.settings_section_scrolling)

    val setSectionVisible: (String, Boolean) -> Unit = { key, visible ->
        hiddenSections.update(if (visible) hiddenSections.value - key else hiddenSections.value + key)
    }

    SubPage(title = pageTitle, onBack = onBack, modifier = modifier) {
        section(key = "library_folders", title = foldersTitle) {
            SettingsRow(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.settings_manage_folders),
                subtitle = stringResource(R.string.settings_manage_folders_summary),
                onClick = onManageFolders
            )
        }
        section(key = "library_thumbnails", title = thumbnailsTitle, note = cornersNote) {
            ChoiceTiles(
                options = CornerOptions.map { stringResource(it.label) },
                selected = CornerOptions.indexOfFirst { it.key == corners.value }.let { if (it < 0) 1 else it },
                onSelect = { index ->
                    val key = CornerOptions[index].key
                    if (key != corners.value) {
                        haptics.tick()
                        corners.update(key)
                    }
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        section(key = "library_details", title = detailsTitle) {
            SettingsSwitchRow(
                icon = Icons.Rounded.Timer,
                title = stringResource(R.string.settings_show_duration),
                subtitle = stringResource(R.string.settings_show_duration_summary),
                checked = showDuration.value,
                onCheckedChange = { showDuration.update(it) }
            )
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Rounded.HighQuality,
                title = stringResource(R.string.settings_show_badges),
                subtitle = stringResource(R.string.settings_show_badges_summary),
                checked = showBadges.value,
                onCheckedChange = { showBadges.update(it) }
            )
        }
        section(key = "library_home", title = homeTitle) {
            SettingsSwitchRow(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.settings_home_continue),
                subtitle = stringResource(R.string.settings_home_continue_summary),
                checked = LuckyPrefs.HOME_SECTION_CONTINUE !in hiddenSections.value,
                onCheckedChange = { setSectionVisible(LuckyPrefs.HOME_SECTION_CONTINUE, it) }
            )
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Rounded.FiberNew,
                title = stringResource(R.string.settings_home_recent),
                subtitle = stringResource(R.string.settings_home_recent_summary),
                checked = LuckyPrefs.HOME_SECTION_RECENT !in hiddenSections.value,
                onCheckedChange = { setSectionVisible(LuckyPrefs.HOME_SECTION_RECENT, it) }
            )
        }
        section(key = "library_scrolling", title = scrollingTitle) {
            SettingsSwitchRow(
                icon = Icons.Rounded.UnfoldMore,
                title = stringResource(R.string.settings_scrollbar),
                subtitle = stringResource(R.string.settings_scrollbar_summary),
                checked = scrollbar.value,
                onCheckedChange = { scrollbar.update(it) }
            )
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Rounded.SortByAlpha,
                title = stringResource(R.string.settings_alphabet_index),
                subtitle = stringResource(R.string.settings_alphabet_index_summary),
                checked = alphabetIndex.value,
                onCheckedChange = { alphabetIndex.update(it) }
            )
        }
    }
}
