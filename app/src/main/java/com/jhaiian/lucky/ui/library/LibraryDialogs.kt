package com.jhaiian.lucky.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.components.ChoiceRow
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.SegmentedChoice
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

@Composable
fun SortDialog(
    visible: Boolean,
    order: SortOrder,
    descending: Boolean,
    onChange: (SortOrder, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard(icon = Icons.Rounded.Tune, title = stringResource(R.string.sort_options_title), onClose = onDismiss) {
            SegmentedChoice(
                options = listOf(
                    Icons.Rounded.ArrowUpward to stringResource(R.string.sort_direction_ascending),
                    Icons.Rounded.ArrowDownward to stringResource(R.string.sort_direction_descending)
                ),
                selected = if (descending) 1 else 0,
                onSelect = { onChange(order, it == 1) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp)
            ) {
                SortOrder.entries.forEach { option ->
                    ChoiceRow(stringResource(option.titleRes), option == order, onClick = { onChange(option, descending) })
                }
            }
        }
    }
}

@Composable
fun ViewModeDialog(visible: Boolean, mode: ViewMode, onSelect: (ViewMode) -> Unit, onDismiss: () -> Unit) {
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard(icon = Icons.Outlined.ViewAgenda, title = stringResource(R.string.view_mode_options_title), onClose = onDismiss) {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                ViewMode.entries.forEach { option ->
                    ChoiceRow(stringResource(option.titleRes), option == mode, onClick = {
                        onSelect(option)
                        onDismiss()
                    })
                }
            }
        }
    }
}

@Composable
fun CategorySwitcherDialog(
    visible: Boolean,
    current: LibraryCategory,
    counts: (LibraryCategory) -> String,
    onSelect: (LibraryCategory) -> Unit,
    onDismiss: () -> Unit
) {
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard(icon = Icons.Rounded.VideoLibrary, title = stringResource(R.string.library_switch_title), onClose = onDismiss) {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                LibraryCategory.entries.forEach { category ->
                    ChoiceRow(
                        label = stringResource(category.titleRes),
                        detail = counts(category),
                        selected = category == current,
                        onClick = {
                            onSelect(category)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItemRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
fun LibraryMenuDialog(
    visible: Boolean,
    category: LibraryCategory,
    onManageFolders: () -> Unit,
    onRescan: () -> Unit,
    onSort: (() -> Unit)?,
    onViewMode: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuckyColors.current
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.cardBackground)
                .border(1.dp, colors.divider.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                .padding(vertical = 8.dp)
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(category.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                Text(
                    stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.surfaceVariant)
            )
            MenuItemRow(Icons.Rounded.CreateNewFolder, stringResource(R.string.library_menu_select_folders)) {
                onDismiss()
                onManageFolders()
            }
            MenuItemRow(Icons.Rounded.Refresh, stringResource(R.string.library_menu_rescan)) {
                onDismiss()
                onRescan()
            }
            if (onSort != null) {
                MenuItemRow(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.library_menu_sort)) {
                    onDismiss()
                    onSort()
                }
            }
            MenuItemRow(Icons.Rounded.GridView, stringResource(R.string.library_menu_view_mode)) {
                onDismiss()
                onViewMode()
            }
        }
    }
}
