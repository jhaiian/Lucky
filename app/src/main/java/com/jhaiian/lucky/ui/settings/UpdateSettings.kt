package com.jhaiian.lucky.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.components.DialogButtonRole
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.LuckyTextButton
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import com.jhaiian.lucky.update.UpdateChecker

@Composable
internal fun UpdateSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    UpdateChecker.ensureLoaded(context)
    var showBetaConfirm by remember { mutableStateOf(false) }

    val pageTitle = stringResource(R.string.update_settings_title)
    val updatesTitle = stringResource(R.string.update_section_updates)
    val channelTitle = stringResource(R.string.update_section_channel)

    Box(modifier.fillMaxSize()) {
        SubPage(title = pageTitle, onBack = onBack) {
            section(key = "update_main", title = updatesTitle) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.update_check_on_launch_title),
                    subtitle = stringResource(R.string.update_check_on_launch_summary),
                    checked = UpdateChecker.checkOnLaunch,
                    onCheckedChange = { UpdateChecker.setCheckOnLaunch(context, it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.NetworkCell,
                    title = stringResource(R.string.update_skip_metered_title),
                    subtitle = stringResource(R.string.update_skip_metered_summary),
                    checked = UpdateChecker.skipOnMetered,
                    onCheckedChange = { UpdateChecker.setSkipOnMetered(context, it) },
                    enabled = UpdateChecker.checkOnLaunch
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.SystemUpdate,
                    title = stringResource(R.string.update_check_now_title),
                    subtitle = stringResource(R.string.update_check_now_summary),
                    onClick = { UpdateChecker.check(context, silent = false) }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.update_changelog_title),
                    subtitle = stringResource(R.string.update_changelog_summary),
                    onClick = { openExternalLink(context, UpdateChecker.CHANGELOG_URL) },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
            section(key = "update_channel", title = channelTitle) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.Science,
                    title = stringResource(R.string.update_beta_title),
                    subtitle = stringResource(R.string.update_beta_summary),
                    checked = UpdateChecker.betaChannel,
                    onCheckedChange = { on ->
                        if (on) showBetaConfirm = true else UpdateChecker.setBetaChannel(context, false)
                    }
                )
            }
        }

        LuckyDialog(visible = showBetaConfirm, onDismiss = { showBetaConfirm = false }) {
            DialogCard(
                icon = Icons.Rounded.Science,
                title = stringResource(R.string.update_beta_title),
                onClose = { showBetaConfirm = false },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 16.dp)
            ) {
                Text(
                    stringResource(R.string.update_beta_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
                )
                DialogActions {
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_cancel),
                        onClick = { showBetaConfirm = false },
                        role = DialogButtonRole.SECONDARY
                    )
                    LuckyTextButton(
                        label = stringResource(R.string.update_beta_confirm),
                        onClick = {
                            showBetaConfirm = false
                            UpdateChecker.setBetaChannel(context, true)
                        }
                    )
                }
            }
        }
    }
}
