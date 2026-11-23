package com.jhaiian.lucky.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jhaiian.lucky.R
import com.jhaiian.lucky.crash.CrashHandler
import com.jhaiian.lucky.crash.CrashReportItem
import com.jhaiian.lucky.crash.CrashUiState
import com.jhaiian.lucky.crash.MAX_CRASH_CLIP_CHARS
import com.jhaiian.lucky.ui.components.DialogButtonRole
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.IconTileIcon
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.LuckyIconButton
import com.jhaiian.lucky.ui.components.LuckyTextButton
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private const val CRASH_FILENAME_DATE_FORMAT = "yyyyMMdd_HHmmss"
private const val CRASH_DISPLAY_DATE_FORMAT = "MMM d, yyyy  HH:mm:ss"

private fun readCrashReportsFromDisk(context: Context): List<CrashReportItem> {
    val appContext = context.applicationContext
    val fileDateFmt = SimpleDateFormat(CRASH_FILENAME_DATE_FORMAT, Locale.US)
    val displayFmt = SimpleDateFormat(CRASH_DISPLAY_DATE_FORMAT, Locale.getDefault())
    CrashHandler.deleteOldReports(appContext)
    return CrashHandler.getCrashFiles(appContext).map { file ->
        val nameWithoutExt = file.nameWithoutExtension.removePrefix("crash_")
        val date = runCatching { fileDateFmt.parse(nameWithoutExt) }.getOrNull()
        val title = date?.let { displayFmt.format(it) } ?: file.name
        CrashReportItem(file, title, runCatching { file.readText() }.getOrDefault(""))
    }
}

private class LastCrashReport {
    var item: CrashReportItem? = null
}

@Composable
internal fun CrashLogSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val scope = rememberCoroutineScope()
    val state = remember { CrashUiState() }
    val lastDetail = remember { LastCrashReport() }

    fun copyToClipboard(content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val truncated = content.length > MAX_CRASH_CLIP_CHARS
        val clipped = if (truncated) content.take(MAX_CRASH_CLIP_CHARS) + "\n" + context.getString(R.string.crash_log_truncated) else content
        clipboard.setPrimaryClip(ClipData.newPlainText("Lucky Crash Report", clipped))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val message = if (truncated) R.string.crash_copied_truncated else R.string.crash_copied
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(item: CrashReportItem) {
        state.detailReport = null
        scope.launch {
            withContext(Dispatchers.IO) { item.file.delete() }
            state.reports.remove(item)
        }
    }

    LaunchedEffect(Unit) {
        state.isLoading = true
        val items = withContext(Dispatchers.IO) { readCrashReportsFromDisk(context) }
        state.reports.clear()
        state.reports.addAll(items)
        state.isLoading = false
    }

    val pageTitle = stringResource(R.string.crash_log_title)
    val note = stringResource(R.string.crash_log_note)

    Box(modifier.fillMaxSize()) {
        SubPage(
            title = pageTitle,
            onBack = onBack,
            actions = {
                LuckyTextButton(
                    label = stringResource(R.string.crash_clear_all),
                    onClick = { state.clearAllConfirmOpen = true },
                    role = DialogButtonRole.DESTRUCTIVE,
                    enabled = state.reports.isNotEmpty()
                )
            }
        ) {
            section(key = "crash_reports", title = null, note = note) {
                when {
                    state.isLoading -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.primary)
                    }
                    state.reports.isEmpty() -> Text(
                        stringResource(R.string.crash_no_reports),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp)
                    )
                    else -> state.reports.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        CrashReportRow(
                            item = item,
                            onOpen = { state.detailReport = item },
                            onCopy = { copyToClipboard(item.content) },
                            onDelete = { delete(item) }
                        )
                    }
                }
            }
        }

        LuckyDialog(visible = state.clearAllConfirmOpen, onDismiss = { state.clearAllConfirmOpen = false }) {
            DialogCard(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.crash_clear_title),
                onClose = { state.clearAllConfirmOpen = false },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 16.dp)
            ) {
                Text(
                    stringResource(R.string.crash_clear_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
                )
                DialogActions {
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_cancel),
                        onClick = { state.clearAllConfirmOpen = false },
                        role = DialogButtonRole.SECONDARY
                    )
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_clear),
                        onClick = {
                            state.clearAllConfirmOpen = false
                            scope.launch {
                                withContext(Dispatchers.IO) { CrashHandler.clearAllReports(context.applicationContext) }
                                state.reports.clear()
                            }
                        },
                        role = DialogButtonRole.DESTRUCTIVE
                    )
                }
            }
        }

        state.detailReport?.let { lastDetail.item = it }
        val detail = state.detailReport ?: lastDetail.item
        LuckyDialog(visible = state.detailReport != null, onDismiss = { state.detailReport = null }) {
            if (detail != null) {
                CrashReportDialog(
                    item = detail,
                    onCopy = {
                        copyToClipboard(detail.content)
                        state.detailReport = null
                    },
                    onDelete = { delete(detail) },
                    onDismiss = { state.detailReport = null }
                )
            }
        }
    }
}

@Composable
private fun CrashReportRow(item: CrashReportItem, onOpen: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTileIcon(Icons.Rounded.BugReport)
        Text(
            item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 4.dp)
        )
        LuckyIconButton(onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.crash_copy), tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        LuckyIconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.crash_delete), tint = colors.secondaryText, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CrashReportDialog(item: CrashReportItem, onCopy: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalLuckyColors.current
    val textArgb = colors.onSurface.toArgb()
    val truncatedLabel = stringResource(R.string.crash_log_truncated)
    val displayContent = remember(item, truncatedLabel) {
        if (item.content.length > MAX_CRASH_CLIP_CHARS) item.content.take(MAX_CRASH_CLIP_CHARS) + "\n" + truncatedLabel else item.content
    }
    DialogCard(
        icon = Icons.Rounded.BugReport,
        title = item.title,
        onClose = onDismiss,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(vertical = 16.dp)
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                factory = { ctx ->
                    ScrollView(ctx).apply {
                        addView(
                            TextView(ctx).apply {
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                typeface = Typeface.MONOSPACE
                                setTextIsSelectable(true)
                                val padH = (20 * ctx.resources.displayMetrics.density).toInt()
                                val padV = (12 * ctx.resources.displayMetrics.density).toInt()
                                setPadding(padH, padV, padH, padV)
                            }
                        )
                    }
                },
                update = { scrollView ->
                    val textView = scrollView.getChildAt(0) as TextView
                    textView.setTextColor(textArgb)
                    if (textView.text.toString() != displayContent) textView.text = displayContent
                }
            )
        }
        DialogActions {
            LuckyTextButton(stringResource(R.string.crash_delete), onDelete, DialogButtonRole.DESTRUCTIVE)
            LuckyTextButton(stringResource(R.string.crash_copy), onCopy)
        }
    }
}
