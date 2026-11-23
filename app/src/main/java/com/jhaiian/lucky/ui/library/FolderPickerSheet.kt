package com.jhaiian.lucky.ui.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LibraryFolders
import com.jhaiian.lucky.data.LibrarySource
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.VideoAccessLevel
import com.jhaiian.lucky.data.VideoFolder
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.IconTileIcon
import com.jhaiian.lucky.ui.components.LuckyActionButton
import com.jhaiian.lucky.ui.components.LuckyCard
import com.jhaiian.lucky.ui.components.LuckyIconButton
import com.jhaiian.lucky.ui.components.LuckySheetWindowInsets
import com.jhaiian.lucky.ui.components.LuckySwitch
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SectionHeader
import com.jhaiian.lucky.ui.components.rememberSheetFlingConnection
import com.jhaiian.lucky.ui.components.rememberSheetMaxHeight
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val internalLabel = stringResource(R.string.storage_internal)
    val externalLabel = stringResource(R.string.storage_external)
    var folders by remember { mutableStateOf(LibraryStore.folders) }
    var source by remember { mutableStateOf(LibraryStore.source) }
    val access = LibraryStore.access
    val volumes = remember { LibraryFolders.detectVolumes(context, internalLabel, externalLabel) }

    fun saveAndScan() {
        LibraryStore.updateFolders(context, folders)
        LibraryStore.applySource(context, source)
        LibraryStore.startScan(context, restart = true)
        onDismiss()
    }

    val accessRequest = rememberVideoAccessRequest { level ->
        if (level != VideoAccessLevel.NONE && source == LibrarySource.AUTO) saveAndScan()
    }

    fun addTree(uri: Uri?) {
        if (uri == null) return
        if (folders.any { it.uri == uri }) return
        folders = folders + LibraryFolders.describe(context, uri, internalLabel, externalLabel)
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { addTree(it) }
    val volumeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) addTree(result.data?.data)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardBackground,
        contentColor = colors.onSurface,
        contentWindowInsets = { LuckySheetWindowInsets }
    ) {
        Column(Modifier.height(rememberSheetMaxHeight())) {
            Row(
                Modifier.padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.folder_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                LuckyIconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.folder_picker_close), tint = colors.secondaryText)
                }
            }
            LazyColumn(Modifier.weight(1f).nestedScroll(rememberSheetFlingConnection(sheetState, consumeFling = false))) {
                item(key = "source") {
                    SectionHeader(stringResource(R.string.library_source_header), Modifier.padding(top = 8.dp))
                    LuckyCard(horizontalPadding = 16.dp) {
                        ChoiceTiles(
                            options = listOf(stringResource(R.string.library_source_auto), stringResource(R.string.library_source_folders)),
                            selected = if (source == LibrarySource.AUTO) 0 else 1,
                            onSelect = { index -> source = if (index == 0) LibrarySource.AUTO else LibrarySource.FOLDERS }
                        )
                        Text(
                            stringResource(if (source == LibrarySource.AUTO) R.string.library_source_auto_note else R.string.library_source_folders_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                if (source == LibrarySource.AUTO) {
                    item(key = "access") {
                        SectionHeader(stringResource(R.string.library_access_header))
                        LuckyCard(horizontalPadding = 16.dp) {
                            AccessRow(
                                access = access,
                                blocked = accessRequest.blocked,
                                onChangeSelection = { accessRequest.request() }
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (source == LibrarySource.FOLDERS && volumes.isNotEmpty()) {
                    item(key = "volumes") {
                        SectionHeader(stringResource(R.string.folder_picker_detected_storage), Modifier.padding(top = 8.dp))
                        LuckyCard(horizontalPadding = 16.dp) {
                            volumes.forEachIndexed { index, volume ->
                                if (index > 0) RowDivider()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconTileIcon(Icons.Rounded.SdCard)
                                    Text(
                                        volume.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 14.dp)
                                    )
                                    Text(
                                        stringResource(R.string.folder_picker_enable),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primary,
                                        modifier = Modifier
                                            .clickable {
                                                val intent = volume.intent
                                                if (intent != null) {
                                                    runCatching { volumeLauncher.launch(intent) }.onFailure { treeLauncher.launch(null) }
                                                } else {
                                                    treeLauncher.launch(null)
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
                if (source == LibrarySource.FOLDERS) item(key = "selected") {
                    SectionHeader(stringResource(R.string.folder_picker_selected_folders))
                    LuckyCard(horizontalPadding = 16.dp) {
                        if (folders.isEmpty()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = colors.primary)
                                Text(
                                    stringResource(R.string.folder_picker_empty_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                                Text(
                                    stringResource(R.string.folder_picker_empty_subtext),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.secondaryText,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        folders.forEachIndexed { index, folder ->
                            if (index > 0) RowDivider()
                            FolderEntryRow(
                                folder = folder,
                                onToggle = { enabled ->
                                    folders = folders.map { if (it.uri == folder.uri) it.copy(enabled = enabled) else it }
                                },
                                onRemove = { folders = folders.filterNot { it.uri == folder.uri } }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (source == LibrarySource.FOLDERS) {
                    LuckyActionButton(
                        label = stringResource(R.string.folder_picker_browse_button),
                        icon = Icons.Rounded.FolderOpen,
                        primary = false,
                        minHeight = 52.dp,
                        onClick = { runCatching { treeLauncher.launch(null) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (source == LibrarySource.AUTO && access == VideoAccessLevel.NONE) {
                    LuckyActionButton(
                        label = stringResource(if (accessRequest.blocked) R.string.library_access_open_settings else R.string.library_access_allow),
                        icon = Icons.Rounded.LockOpen,
                        minHeight = 52.dp,
                        onClick = { accessRequest.request() },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LuckyActionButton(
                        label = stringResource(R.string.folder_picker_scan_button),
                        icon = Icons.Rounded.Refresh,
                        minHeight = 52.dp,
                        enabled = source == LibrarySource.AUTO || folders.any { it.enabled } || LibraryStore.folders.isNotEmpty(),
                        onClick = { saveAndScan() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessRow(access: VideoAccessLevel, blocked: Boolean, onChangeSelection: () -> Unit) {
    val colors = LocalLuckyColors.current
    val (icon, title, summary) = when (access) {
        VideoAccessLevel.FULL -> Triple(Icons.Rounded.VideoLibrary, R.string.library_access_full_title, R.string.library_access_full_summary)
        VideoAccessLevel.PARTIAL -> Triple(Icons.Rounded.AutoAwesome, R.string.library_access_partial_title, R.string.library_access_partial_summary)
        VideoAccessLevel.NONE -> Triple(
            Icons.Rounded.Lock,
            R.string.library_access_none_title,
            if (blocked) R.string.library_access_blocked_summary else R.string.library_access_none_summary
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTileIcon(icon)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
        if (access == VideoAccessLevel.PARTIAL) {
            Text(
                stringResource(R.string.library_access_change_selection),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onChangeSelection)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun FolderEntryRow(folder: VideoFolder, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTileIcon(Icons.Rounded.Folder)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp)
                .alpha(if (folder.enabled) 1f else 0.6f)
        ) {
            Text(
                folder.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(folder.locationLabel, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
        LuckySwitch(checked = folder.enabled, onCheckedChange = onToggle)
        LuckyIconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.folder_picker_remove), tint = colors.secondaryText)
        }
    }
}
