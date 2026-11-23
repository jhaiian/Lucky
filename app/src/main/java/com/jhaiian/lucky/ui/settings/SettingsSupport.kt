package com.jhaiian.lucky.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.findActivity
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

internal const val DialogExitDelayMillis = 220L

internal class PrefState<T>(
    private val prefs: SharedPreferences,
    private val read: (SharedPreferences) -> T,
    private val write: (SharedPreferences.Editor, T) -> Unit
) {
    var value by mutableStateOf(read(prefs))
        private set

    fun update(newValue: T) {
        value = newValue
        prefs.edit().also { write(it, newValue) }.apply()
    }

    fun reload() {
        value = read(prefs)
    }
}

@Composable
internal fun <T> rememberPref(
    key: String,
    fallback: T,
    read: SharedPreferences.() -> T,
    write: SharedPreferences.Editor.(T) -> Unit
): PrefState<T> {
    val context = LocalContext.current
    val prefs = remember(context) { LuckyPrefs.get(context) }
    val state = remember(prefs, key) {
        PrefState(
            prefs,
            { source -> runCatching { source.read() }.getOrDefault(fallback) },
            { editor, value -> editor.write(value) }
        )
    }
    DisposableEffect(prefs, key, state) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key) state.reload()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        state.reload()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
internal fun rememberBooleanPref(key: String, default: Boolean): PrefState<Boolean> =
    rememberPref(key, default, { getBoolean(key, default) }, { putBoolean(key, it) })

@Composable
internal fun rememberStringPref(key: String, default: String): PrefState<String> =
    rememberPref(key, default, { getString(key, default) ?: default }, { putString(key, it) })

@Composable
internal fun rememberIntPref(key: String, default: Int): PrefState<Int> =
    rememberPref(key, default, { getInt(key, default) }, { putInt(key, it) })

@Composable
internal fun rememberStringSetPref(key: String): PrefState<Set<String>> =
    rememberPref(key, emptySet(), { getStringSet(key, null)?.toSet() ?: emptySet() }, { putStringSet(key, HashSet(it)) })

@Composable
internal fun rememberLuckyActivity(): LuckyActivity? {
    val context = LocalContext.current
    return remember(context) { context.findActivity() as? LuckyActivity }
}

internal fun openExternalLink(context: Context, url: String) {
    val opened = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
    if (!opened) {
        runCatching { Toast.makeText(context, R.string.settings_link_failed, Toast.LENGTH_SHORT).show() }
    }
}

@Composable
internal fun SettingsDialog(
    visible: Boolean,
    icon: ImageVector,
    title: String,
    onDismiss: () -> Unit,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalLuckyColors.current
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard(
            icon = icon,
            title = title,
            onClose = onDismiss,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(vertical = 16.dp)
        ) {
            if (header != null) {
                Text(
                    header,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp)
                )
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                content = content
            )
            if (footer != null) {
                Text(
                    footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun DialogActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun DestructiveRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(30))
                .background(colors.colorError.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.colorError, modifier = Modifier.size(20.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.colorError, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
internal fun InfoLine(label: String, value: String) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryText,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
