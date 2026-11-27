package com.jhaiian.lucky.ui.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.FileChange
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.VideoFiles
import com.jhaiian.lucky.data.VideoItem
import com.jhaiian.lucky.data.VideoViewMemory
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.engine.LuckyEngine
import com.jhaiian.lucky.engine.MediaInfo
import com.jhaiian.lucky.engine.ProbeResult
import com.jhaiian.lucky.engine.StreamInfo
import com.jhaiian.lucky.engine.StreamKind
import com.jhaiian.lucky.ui.components.DialogButtonRole
import com.jhaiian.lucky.ui.components.DialogCard
import com.jhaiian.lucky.ui.components.LuckyDialog
import com.jhaiian.lucky.ui.components.LuckyTextButton
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.findActivity
import com.jhaiian.lucky.ui.home.codecDisplayName
import com.jhaiian.lucky.ui.home.formatBitRate
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.home.formatFrameRate
import com.jhaiian.lucky.ui.home.formatSampleRate
import com.jhaiian.lucky.ui.home.formatSize
import com.jhaiian.lucky.ui.home.hdrLabel
import com.jhaiian.lucky.ui.home.languageDisplayName
import com.jhaiian.lucky.ui.home.streamKindLabel
import com.jhaiian.lucky.ui.settings.DialogActions
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private sealed interface VideoChange {
    val item: VideoItem

    data class Delete(override val item: VideoItem) : VideoChange
    data class Rename(override val item: VideoItem, val newName: String) : VideoChange
}

private class VideoChanges(private val context: Context, private val scope: CoroutineScope) {
    var pending: VideoChange? = null
    var launchConsent: (IntentSender) -> Boolean = { false }
    var onResult: (VideoChange, FileChange) -> Unit = { _, _ -> }

    fun run(change: VideoChange, consented: Boolean) {
        scope.launch {
            val uri = Uri.parse(change.item.uri)
            val result = withContext(Dispatchers.IO) {
                when (change) {
                    is VideoChange.Delete -> VideoFiles.delete(context, uri, consented)
                    is VideoChange.Rename -> VideoFiles.rename(context, uri, change.newName, consented)
                }
            }
            if (result is FileChange.NeedsConsent) {
                pending = change
                if (!launchConsent(result.sender)) {
                    pending = null
                    onResult(change, FileChange.Failed)
                }
            } else {
                onResult(change, result)
            }
        }
    }
}

private enum class ActionStage { MENU, RENAME, DELETE, PROPERTIES }

private data class ActionError(val titleRes: Int, val messageRes: Int)

private sealed interface ProbeState {
    data object Loading : ProbeState
    data object Unavailable : ProbeState
    data class Ready(val media: MediaInfo) : ProbeState
}

private fun applyChange(context: Context, change: VideoChange, newUri: String) {
    val item = change.item
    when (change) {
        is VideoChange.Delete -> {
            LibraryStore.removeVideo(context, item.uri)
            WatchHistory.remove(context, item.uri)
            VideoViewMemory.remove(context, item.uri)
        }
        is VideoChange.Rename -> {
            val title = VideoFiles.baseNameOf(change.newName)
            val documentId = if (newUri != item.uri) {
                runCatching { DocumentsContract.getDocumentId(Uri.parse(newUri)) }.getOrNull() ?: item.documentId
            } else {
                item.documentId
            }
            LibraryStore.replaceVideo(context, item.uri, item.copy(uri = newUri, documentId = documentId, name = change.newName, title = title))
            WatchHistory.rename(context, item.uri, newUri, title)
            VideoViewMemory.rename(context, item.uri, newUri)
        }
    }
}

private fun systemConfirmsDelete(item: VideoItem): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Uri.parse(item.uri).authority == MediaStore.AUTHORITY

private fun probeVideo(context: Context, uri: String): ProbeState {
    if (!LuckyEngine.isAvailable) return ProbeState.Unavailable
    val descriptor = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") }.getOrNull()
        ?: return ProbeState.Unavailable
    return when (val result = LuckyEngine.probe(descriptor.detachFd())) {
        is ProbeResult.Success -> ProbeState.Ready(result.media)
        is ProbeResult.Failure -> ProbeState.Unavailable
    }
}

@Composable
fun VideoActionsHost(target: VideoItem?, onDismiss: () -> Unit, onPlay: (VideoItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val history by WatchHistory.entries(context).collectAsState()
    val historyEnabled = remember { LuckyPrefs.watchHistoryEnabled(context) }
    val retained = remember { arrayOfNulls<VideoItem>(1) }
    if (target != null) retained[0] = target
    val item = retained[0]
    var stage by remember(target?.uri) { mutableStateOf(if (target != null) ActionStage.MENU else null) }
    var error by remember { mutableStateOf<ActionError?>(null) }
    val retainedError = remember { arrayOfNulls<ActionError>(1) }
    error?.let { retainedError[0] = it }
    val changes = remember { VideoChanges(context.applicationContext, scope) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val change = changes.pending
        changes.pending = null
        if (change != null && result.resultCode == Activity.RESULT_OK) changes.run(change, consented = true)
    }
    changes.launchConsent = { sender ->
        runCatching { consent.launch(IntentSenderRequest.Builder(sender).build()) }.isSuccess
    }
    changes.onResult = { change, result ->
        val failureTitle = if (change is VideoChange.Delete) R.string.video_error_delete_title else R.string.video_error_rename_title
        val message = when (result) {
            is FileChange.Done -> {
                applyChange(context.applicationContext, change, result.uri)
                null
            }
            FileChange.NeedsStoragePermission -> {
                val host = context.findActivity() as? LuckyActivity
                host?.requestLegacyStoragePermission { granted ->
                    if (granted) changes.run(change, consented = false)
                    if (!granted) error = ActionError(failureTitle, R.string.video_error_permission)
                }
                if (host == null) R.string.video_error_permission else null
            }
            FileChange.NoFolderAccess -> R.string.video_error_folder_access
            FileChange.NameTaken -> R.string.video_error_name_taken
            else -> R.string.video_error_generic
        }
        if (message != null) error = ActionError(failureTitle, message)
    }

    val entry = item?.let { history[it.uri] }
    val inLibrary = item != null && LibraryStore.videos.any { it.uri == item.uri }
    val shareTitle = stringResource(R.string.video_action_share_title)

    LuckyDialog(visible = stage == ActionStage.MENU, onDismiss = onDismiss) {
        if (item != null) {
            ActionMenu(item = item, progress = entry?.progress ?: 0f) {
                ActionRow(Icons.Rounded.PlayArrow, stringResource(R.string.player_play)) {
                    onDismiss()
                    onPlay(item)
                }
                if (historyEnabled && entry?.finished == true) {
                    ActionRow(Icons.Rounded.RemoveDone, stringResource(R.string.video_action_mark_unwatched)) {
                        onDismiss()
                        haptics.tick()
                        WatchHistory.remove(context, item.uri)
                    }
                } else if (historyEnabled && (item.durationMs > 0L || (entry?.durationMs ?: 0L) > 0L)) {
                    ActionRow(Icons.Rounded.DoneAll, stringResource(R.string.video_action_mark_watched)) {
                        onDismiss()
                        haptics.tick()
                        WatchHistory.markWatched(context, item.uri, item.title, item.durationMs)
                    }
                }
                if (entry != null && !entry.finished) {
                    ActionRow(Icons.Rounded.HistoryToggleOff, stringResource(R.string.video_action_remove_history)) {
                        onDismiss()
                        haptics.tick()
                        WatchHistory.remove(context, item.uri)
                    }
                }
                if (inLibrary) {
                    ActionRow(Icons.Rounded.Share, stringResource(R.string.video_action_share)) {
                        onDismiss()
                        try {
                            context.startActivity(VideoFiles.shareIntent(item, shareTitle))
                        } catch (_: ActivityNotFoundException) {
                            error = ActionError(R.string.video_error_share_title, R.string.video_error_generic)
                        }
                    }
                    ActionRow(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.video_action_rename)) {
                        stage = ActionStage.RENAME
                    }
                    ActionRow(Icons.Rounded.Info, stringResource(R.string.video_action_properties)) {
                        stage = ActionStage.PROPERTIES
                    }
                    ActionRow(Icons.Rounded.Delete, stringResource(R.string.video_action_delete), destructive = true) {
                        if (systemConfirmsDelete(item)) {
                            onDismiss()
                            changes.run(VideoChange.Delete(item), consented = false)
                        } else {
                            stage = ActionStage.DELETE
                        }
                    }
                }
            }
        }
    }

    RenameDialog(
        visible = stage == ActionStage.RENAME,
        item = item,
        onDismiss = onDismiss,
        onConfirm = { newName ->
            val current = item
            onDismiss()
            if (current != null) changes.run(VideoChange.Rename(current, newName), consented = false)
        }
    )

    LuckyDialog(visible = stage == ActionStage.DELETE, onDismiss = onDismiss) {
        if (item != null) {
            val colors = LocalLuckyColors.current
            DialogCard(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.video_delete_title),
                onClose = onDismiss,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 16.dp)
            ) {
                Text(
                    stringResource(R.string.video_delete_message, item.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
                )
                DialogActions {
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_cancel),
                        onClick = onDismiss,
                        role = DialogButtonRole.SECONDARY
                    )
                    LuckyTextButton(
                        label = stringResource(R.string.video_action_delete),
                        onClick = {
                            onDismiss()
                            haptics.longPress()
                            changes.run(VideoChange.Delete(item), consented = false)
                        },
                        role = DialogButtonRole.DESTRUCTIVE
                    )
                }
            }
        }
    }

    PropertiesDialog(visible = stage == ActionStage.PROPERTIES, item = item, onDismiss = onDismiss)

    LuckyDialog(visible = error != null, onDismiss = { error = null }) {
        val shown = retainedError[0]
        if (shown != null) {
            val colors = LocalLuckyColors.current
            DialogCard(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(shown.titleRes),
                onClose = { error = null },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 16.dp)
            ) {
                Text(
                    stringResource(shown.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
                )
                DialogActions {
                    LuckyTextButton(label = stringResource(R.string.update_ok), onClick = { error = null })
                }
            }
        }
    }
}

@Composable
private fun ActionMenu(item: VideoItem, progress: Float, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val shape = RoundedCornerShape(28.dp)
    val meta = listOfNotNull(
        item.durationMs.takeIf { it > 0L }?.let { formatDuration(it) },
        item.resolutionLabel,
        item.size.takeIf { it > 0L }?.let { formatSize(context, it) }
    ).joinToString(" • ")
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 32.dp, vertical = 16.dp)
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(colors.cardBackground)
            .border(1.dp, colors.divider.copy(alpha = 0.6f), shape)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumbnail(
                uri = item.uri,
                corner = 12.dp,
                progress = progress,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(THUMB_ASPECT)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 4.dp)
                .height(1.dp)
                .background(colors.surfaceVariant)
        )
        content()
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    val tint = if (destructive) colors.colorError else colors.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) colors.colorError else colors.onSurface,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun RenameDialog(visible: Boolean, item: VideoItem?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        if (item != null) {
            val colors = LocalLuckyColors.current
            val extension = remember(item.name) { VideoFiles.extensionOf(item.name) }
            val base = remember(item.name) { VideoFiles.baseNameOf(item.name) }
            var field by remember(item.uri) { mutableStateOf(TextFieldValue(base, TextRange(0, base.length))) }
            val cleaned = VideoFiles.cleanName(field.text)
            val valid = cleaned.isNotEmpty() && cleaned != base
            val focus = remember { FocusRequester() }
            LaunchedEffect(item.uri) {
                delay(150)
                runCatching { focus.requestFocus() }
            }
            val submit: () -> Unit = { if (valid) onConfirm(cleaned + extension) }
            DialogCard(
                icon = Icons.Rounded.DriveFileRenameOutline,
                title = stringResource(R.string.video_rename_title),
                onClose = onDismiss,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                    .padding(vertical = 16.dp)
            ) {
                Row(
                    Modifier
                        .padding(start = 18.dp, end = 18.dp, top = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                    )
                    if (extension.isNotEmpty()) {
                        Text(
                            extension,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                DialogActions {
                    LuckyTextButton(
                        label = stringResource(R.string.settings_action_cancel),
                        onClick = onDismiss,
                        role = DialogButtonRole.SECONDARY
                    )
                    LuckyTextButton(
                        label = stringResource(R.string.video_action_rename),
                        onClick = submit,
                        enabled = valid
                    )
                }
            }
        }
    }
}

@Composable
private fun PropertiesDialog(visible: Boolean, item: VideoItem?, onDismiss: () -> Unit) {
    LuckyDialog(visible = visible, onDismiss = onDismiss) {
        if (item != null) {
            val context = LocalContext.current
            val colors = LocalLuckyColors.current
            val probe by produceState<ProbeState>(ProbeState.Loading, item.uri) {
                value = withContext(Dispatchers.IO) { probeVideo(context.applicationContext, item.uri) }
            }
            val locale = remember(context) { context.resources.configuration.locales.get(0) ?: Locale.getDefault() }
            DialogCard(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.video_action_properties),
                onClose = onDismiss,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 16.dp)
            ) {
                Column(
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    InfoRow(stringResource(R.string.video_info_file), item.name)
                    if (item.folderPath.isNotBlank()) InfoRow(stringResource(R.string.video_info_location), item.folderPath)
                    if (item.size > 0L) InfoRow(stringResource(R.string.video_info_size), formatSize(context, item.size))
                    if (item.modified > 0L) {
                        InfoRow(
                            stringResource(R.string.video_info_modified),
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(item.modified))
                        )
                    }
                    val ready = probe as? ProbeState.Ready
                    val duration = ready?.media?.durationMs?.takeIf { it > 0L } ?: item.durationMs
                    if (duration > 0L) InfoRow(stringResource(R.string.video_info_duration), formatDuration(duration))
                    when (val state = probe) {
                        ProbeState.Loading -> Row(
                            Modifier.padding(top = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.video_info_reading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.secondaryText,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                        ProbeState.Unavailable -> Text(
                            stringResource(R.string.video_info_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                        is ProbeState.Ready -> MediaDetails(state.media)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetails(media: MediaInfo) {
    val context = LocalContext.current
    InfoRow(stringResource(R.string.video_info_format), media.containerLong ?: media.container.uppercase(Locale.ROOT))
    media.bitRate?.takeIf { it > 0L }?.let { InfoRow(stringResource(R.string.video_info_bitrate), formatBitRate(context, it)) }
    val visible = media.streams.filter { !it.isCoverArt && it.kind in DetailKinds }
    DetailKinds.forEach { kind ->
        val group = visible.filter { it.kind == kind }
        group.forEachIndexed { index, stream ->
            val heading = streamKindLabel(context, kind, false).let { label ->
                if (group.size > 1) "$label · ${stringResource(R.string.track_number, index + 1)}" else label
            }
            StreamDetails(heading, stream)
        }
    }
}

private val DetailKinds = listOf(StreamKind.VIDEO, StreamKind.AUDIO, StreamKind.SUBTITLE)

@Composable
private fun StreamDetails(heading: String, stream: StreamInfo) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    Text(
        heading,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
    )
    val codec = codecDisplayName(stream.codec)
    InfoRow(stringResource(R.string.video_info_codec), stream.profile?.let { "$codec · $it" } ?: codec)
    stream.title?.let { InfoRow(stringResource(R.string.video_info_track_title), it) }
    stream.video?.let { video ->
        if (video.width > 0 && video.height > 0) {
            InfoRow(stringResource(R.string.label_resolution), stringResource(R.string.value_resolution, video.width, video.height))
        }
        video.frameRate?.takeIf { it > 0.0 }?.let { InfoRow(stringResource(R.string.label_frame_rate), formatFrameRate(context, it)) }
        hdrLabel(context, video)?.let { InfoRow(stringResource(R.string.stats_hdr_format), it) }
        video.bitDepth?.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.video_info_bit_depth), stringResource(R.string.value_bit_depth, it)) }
    }
    stream.audio?.let { audio ->
        if (audio.channels > 0) {
            val channels = pluralStringResource(R.plurals.audio_channels, audio.channels, audio.channels)
            InfoRow(stringResource(R.string.video_info_channels), audio.channelLayout?.let { "$channels · $it" } ?: channels)
        }
        if (audio.sampleRate > 0) InfoRow(stringResource(R.string.video_info_sample_rate), formatSampleRate(context, audio.sampleRate))
    }
    stream.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }?.let {
        InfoRow(stringResource(R.string.video_info_language), languageDisplayName(it))
    }
    stream.bitRate?.takeIf { it > 0L }?.let { InfoRow(stringResource(R.string.video_info_bitrate), formatBitRate(context, it)) }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryText,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier
                .weight(0.58f)
                .padding(start = 12.dp)
        )
    }
}
