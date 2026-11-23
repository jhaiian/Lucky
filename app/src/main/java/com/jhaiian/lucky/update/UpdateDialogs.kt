package com.jhaiian.lucky.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.components.DialogButtonRole
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.LuckyTextButton
import com.jhaiian.lucky.ui.settings.DialogActions
import com.jhaiian.lucky.ui.settings.openExternalLink
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

@Composable
fun UpdateFlowHost() {
    if (!UpdateChecker.supported) return
    val context = LocalContext.current
    val step = UpdateChecker.step
    var shown by remember { mutableStateOf(step) }
    if (step != UpdateStep.None) shown = step
    val onDismiss: () -> Unit = {
        if (step == UpdateStep.Downloading) UpdateChecker.cancelDownload() else UpdateChecker.dismiss()
    }

    LuckyDialog(visible = step != UpdateStep.None, onDismiss = onDismiss) {
        when (val current = shown) {
            UpdateStep.Checking -> UpdateCard(Icons.Rounded.SystemUpdate, stringResource(R.string.update_checking_title), onDismiss) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
                ) {
                    CircularProgressIndicator(color = LocalLuckyColors.current.primary, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Text(
                        text = stringResource(R.string.update_checking_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalLuckyColors.current.secondaryText,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                }
                DialogActions {
                    LuckyTextButton(stringResource(R.string.settings_action_cancel), UpdateChecker::dismiss, DialogButtonRole.SECONDARY)
                }
            }
            UpdateStep.UpToDate -> MessageCard(Icons.Rounded.CheckCircle, stringResource(R.string.update_up_to_date_title), stringResource(R.string.update_up_to_date_message))
            UpdateStep.CheckFailed -> MessageCard(Icons.Rounded.ErrorOutline, stringResource(R.string.update_check_failed_title), stringResource(R.string.update_check_failed_message))
            UpdateStep.DownloadFailed -> MessageCard(Icons.Rounded.ErrorOutline, stringResource(R.string.update_download_failed_title), stringResource(R.string.update_download_failed_message))
            is UpdateStep.Available -> {
                val title = if (current.isBeta) {
                    stringResource(R.string.update_available_beta_title, current.version)
                } else {
                    stringResource(R.string.update_available_title, current.version)
                }
                UpdateCard(Icons.Rounded.NewReleases, title, UpdateChecker::dismiss) {
                    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                    Column(
                        modifier = Modifier
                            .heightIn(max = screenHeight * 0.5f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 22.dp, end = 22.dp, top = 14.dp)
                    ) {
                        ChangelogText(current.changelog)
                    }
                    DialogActions {
                        LuckyTextButton(stringResource(R.string.update_skip_version), { UpdateChecker.skip(context, current.versionCode) }, DialogButtonRole.SECONDARY)
                        LuckyTextButton(stringResource(R.string.update_later), UpdateChecker::dismiss, DialogButtonRole.SECONDARY)
                        if (current.downloadUrl != null) {
                            LuckyTextButton(stringResource(R.string.update_download), { UpdateChecker.download(context, current) })
                        } else {
                            LuckyTextButton(stringResource(R.string.update_view_github), {
                                UpdateChecker.dismiss()
                                openExternalLink(context, UpdateChecker.RELEASES_URL)
                            })
                        }
                    }
                }
            }
            UpdateStep.Downloading -> UpdateCard(Icons.Rounded.CloudDownload, stringResource(R.string.update_downloading_title), UpdateChecker::cancelDownload) {
                val colors = LocalLuckyColors.current
                val total = UpdateChecker.totalBytes
                val done = Formatter.formatShortFileSize(context, UpdateChecker.downloadedBytes)
                Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { UpdateChecker.progress },
                            color = colors.primary,
                            trackColor = colors.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(color = colors.primary, trackColor = colors.surfaceVariant, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text(
                            text = if (total > 0) {
                                stringResource(
                                    R.string.update_download_size_known,
                                    done,
                                    Formatter.formatShortFileSize(context, total),
                                    (UpdateChecker.progress * 100).toInt()
                                )
                            } else {
                                stringResource(R.string.update_download_size_unknown, done)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondaryText
                        )
                        if (UpdateChecker.bytesPerSecond > 0) {
                            Text(
                                text = stringResource(R.string.update_download_speed, Formatter.formatShortFileSize(context, UpdateChecker.bytesPerSecond)),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.secondaryText
                            )
                        }
                    }
                }
                DialogActions {
                    LuckyTextButton(stringResource(R.string.settings_action_cancel), UpdateChecker::cancelDownload, DialogButtonRole.SECONDARY)
                }
            }
            UpdateStep.None -> Unit
        }
    }
}

@Composable
private fun UpdateCard(icon: ImageVector, title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    DialogCard(
        icon = icon,
        title = title,
        onClose = onClose,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(vertical = 16.dp)
    ) {
        content()
    }
}

@Composable
private fun MessageCard(icon: ImageVector, title: String, message: String) {
    UpdateCard(icon, title, UpdateChecker::dismiss) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalLuckyColors.current.secondaryText,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
        )
        DialogActions {
            LuckyTextButton(stringResource(R.string.update_ok), UpdateChecker::dismiss)
        }
    }
}

@Composable
private fun ChangelogText(changelog: String) {
    val colors = LocalLuckyColors.current
    changelog.lines().map { it.trimEnd() }.forEach { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.isEmpty() -> Text("", style = MaterialTheme.typography.bodySmall)
            trimmed.startsWith("#") -> Text(
                text = trimmed.trimStart('#').trim(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = colors.primary, modifier = Modifier.padding(end = 8.dp))
                Text(trimmed.drop(2).replace("**", ""), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
            else -> Text(trimmed.replace("**", ""), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
    }
}
