package com.jhaiian.lucky.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jhaiian.lucky.BuildConfig
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.engine.RendererPreference
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.update.UpdateChecker

enum class SettingsPage {
    LOOK_AND_FEEL, PLAYBACK, AUDIO, PLAYER, LIBRARY, WATCH_HISTORY, UPDATES, CRASH_LOGS, ABOUT, PLAYER_LOOK;

    val parent: SettingsPage?
        get() = when (this) {
            PLAYER_LOOK -> PLAYER
            else -> null
        }
}

fun settingsDepth(page: SettingsPage?): Int = when {
    page == null -> 0
    page.parent == null -> 1
    else -> 2
}

@Composable
fun SettingsScreen(onOpenPage: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    val hardware = rememberBooleanPref(LuckyPrefs.HARDWARE_DECODING, true)
    val renderer = rememberStringPref(LuckyPrefs.RENDERER, RendererPreference.OPENGL.name)
    val resume = rememberBooleanPref(LuckyPrefs.RESUME_PLAYBACK, true)
    val history = rememberBooleanPref(LuckyPrefs.WATCH_HISTORY_ENABLED, true)
    SubPage(title = stringResource(R.string.settings_title), onBack = null, modifier = modifier) {
        section(key = "settings_main", title = null) {
            SettingsRow(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_look_and_feel),
                subtitle = stringResource(R.string.settings_look_and_feel_summary),
                onClick = { onOpenPage(SettingsPage.LOOK_AND_FEEL) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.settings_playback),
                subtitle = listOf(
                    stringResource(if (hardware.value) R.string.decoder_hardware else R.string.decoder_software),
                    stringResource(rendererLabel(renderer.value)),
                    stringResource(if (resume.value) R.string.settings_playback_resume_on else R.string.settings_playback_resume_off)
                ).joinToString(" · "),
                onClick = { onOpenPage(SettingsPage.PLAYBACK) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_audio),
                subtitle = stringResource(R.string.settings_audio_summary),
                onClick = { onOpenPage(SettingsPage.AUDIO) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SmartDisplay,
                title = stringResource(R.string.settings_player),
                subtitle = stringResource(R.string.settings_player_summary),
                onClick = { onOpenPage(SettingsPage.PLAYER) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VideoLibrary,
                title = stringResource(R.string.settings_library),
                subtitle = stringResource(R.string.settings_library_summary),
                onClick = { onOpenPage(SettingsPage.LIBRARY) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.settings_watch_history),
                subtitle = stringResource(if (history.value) R.string.settings_watch_history_on else R.string.settings_watch_history_off),
                onClick = { onOpenPage(SettingsPage.WATCH_HISTORY) }
            )
        }
        section(key = "settings_more", title = null) {
            if (UpdateChecker.supported) {
                SettingsRow(
                    icon = Icons.Rounded.SystemUpdate,
                    title = stringResource(R.string.update_settings_title),
                    subtitle = stringResource(R.string.update_settings_summary),
                    onClick = { onOpenPage(SettingsPage.UPDATES) }
                )
                RowDivider()
            }
            SettingsRow(
                icon = Icons.Rounded.BugReport,
                title = stringResource(R.string.crash_log_title),
                subtitle = stringResource(R.string.crash_log_summary),
                onClick = { onOpenPage(SettingsPage.CRASH_LOGS) }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                onClick = { onOpenPage(SettingsPage.ABOUT) }
            )
        }
    }
}

@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (page) {
        SettingsPage.LOOK_AND_FEEL -> LookAndFeelSettings(onBack = onBack, modifier = modifier)
        SettingsPage.PLAYBACK -> PlaybackSettings(onBack = onBack, modifier = modifier)
        SettingsPage.AUDIO -> AudioSettings(onBack = onBack, modifier = modifier)
        SettingsPage.PLAYER -> PlayerSettings(onBack = onBack, onOpenLook = { onOpenPage(SettingsPage.PLAYER_LOOK) }, modifier = modifier)
        SettingsPage.PLAYER_LOOK -> PlayerLookSettings(onBack = onBack, modifier = modifier)
        SettingsPage.LIBRARY -> LibrarySettings(onBack = onBack, onManageFolders = onManageFolders, modifier = modifier)
        SettingsPage.WATCH_HISTORY -> WatchHistorySettings(onBack = onBack, modifier = modifier)
        SettingsPage.UPDATES -> UpdateSettings(onBack = onBack, modifier = modifier)
        SettingsPage.CRASH_LOGS -> CrashLogSettings(onBack = onBack, modifier = modifier)
        SettingsPage.ABOUT -> AboutSettings(onBack = onBack, modifier = modifier)
    }
}

internal fun rendererLabel(value: String): Int = when (RendererPreference.parse(value)) {
    RendererPreference.VULKAN -> R.string.renderer_vulkan
    RendererPreference.OPENGL -> R.string.renderer_opengl
}
