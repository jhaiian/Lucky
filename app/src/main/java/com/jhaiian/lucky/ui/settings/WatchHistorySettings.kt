package com.jhaiian.lucky.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.ui.components.DialogButtonRole
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.LuckyTextButton
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WatchHistorySettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val haptics = rememberHaptics()
    val enabled = rememberBooleanPref(LuckyPrefs.WATCH_HISTORY_ENABLED, true)
    val savedCount by produceState<Int?>(initialValue = null, context) {
        val flow = withContext(Dispatchers.IO) { WatchHistory.entries(context) }
        flow.collect { value = it.size }
    }
    var confirmClear by remember { mutableStateOf(false) }

    val pageTitle = stringResource(R.string.settings_watch_history)
    val note = stringResource(R.string.settings_history_note)
    val clearedMessage = stringResource(R.string.settings_clear_history_done)

    Box(modifier.fillMaxSize()) {
        SubPage(title = pageTitle, onBack = onBack) {
            section(key = "history_main", title = null, note = note) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.settings_remember_progress),
                    subtitle = stringResource(R.string.settings_remember_progress_summary),
                    checked = enabled.value,
                    onCheckedChange = { enabled.update(it) }
                )
                RowDivider()
                val count = savedCount
                DestructiveRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.settings_clear_history),
                    subtitle = when (count) {
                        null -> null
                        0 -> stringResource(R.string.settings_clear_history_empty)
                        else -> pluralStringResource(R.plurals.settings_clear_history_count, count, count)
                    },
                    enabled = count != 0,
                    onClick = { confirmClear = true }
                )
            }
        }

        LuckyDialog(visible = confirmClear, onDismiss = { confirmClear = false }) {
            DialogCard(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.settings_clear_history_title),
                onClose = { confirmClear = false },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 16.dp)
            ) {
                Text(
                    stringResource(R.string.settings_clear_history_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
                )
                DialogActions {
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_cancel),
                        onClick = { confirmClear = false },
                        role = DialogButtonRole.SECONDARY
                    )
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_clear),
                        onClick = {
                            confirmClear = false
                            haptics.longPress()
                            WatchHistory.clear(context)
                            Toast.makeText(context, clearedMessage, Toast.LENGTH_SHORT).show()
                        },
                        role = DialogButtonRole.DESTRUCTIVE
                    )
                }
            }
        }
    }
}
