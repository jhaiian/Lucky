package com.jhaiian.lucky.ui.library

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LibraryScanState
import com.jhaiian.lucky.data.LibrarySource
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.PlayQueue
import com.jhaiian.lucky.data.QueueItem
import com.jhaiian.lucky.data.ScanPhase
import com.jhaiian.lucky.data.VideoItem
import com.jhaiian.lucky.data.WatchEntry
import com.jhaiian.lucky.data.UpNext
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.ui.components.AlphabetIndex
import com.jhaiian.lucky.ui.components.EmptyState
import com.jhaiian.lucky.ui.components.IconTileIcon
import com.jhaiian.lucky.ui.components.LocalBottomContentPadding
import com.jhaiian.lucky.ui.components.LuckyActionButton
import com.jhaiian.lucky.ui.components.LuckyIconButton
import com.jhaiian.lucky.ui.components.RowArrow
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SectionHeader
import com.jhaiian.lucky.ui.components.VerticalScrollbar
import com.jhaiian.lucky.ui.components.drillTransition
import com.jhaiian.lucky.ui.components.indexLetter
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.home.formatSize
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun rememberPreferences(): SharedPreferences {
    val context = LocalContext.current
    val prefs = remember { LuckyPrefs.get(context) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(version) { prefs }
}

private fun WatchEntry.asVideo(): VideoItem = VideoItem(
    uri = uri,
    documentId = uri,
    name = title ?: uri.substringAfterLast('/'),
    title = title ?: uri.substringAfterLast('/'),
    folderId = "",
    folderName = "",
    folderPath = "",
    size = 0L,
    modified = updatedAt,
    mime = null,
    durationMs = durationMs,
    width = 0,
    height = 0
)

private fun categoryItems(category: LibraryCategory, videos: List<VideoItem>, history: Map<String, WatchEntry>, order: SortOrder, descending: Boolean): List<VideoItem> {
    val byUri = videos.associateBy { it.uri }
    return when (category) {
        LibraryCategory.VIDEOS -> videos.sortedBy(order, descending, history)
        LibraryCategory.RECENTLY_ADDED -> videos.sortedByDescending { it.modified }
        LibraryCategory.CONTINUE -> history.values.filter { it.resumable }.sortedByDescending { it.updatedAt }.map { byUri[it.uri] ?: it.asVideo() }
        LibraryCategory.HISTORY -> history.values.sortedByDescending { it.updatedAt }.map { byUri[it.uri] ?: it.asVideo() }
        LibraryCategory.FOLDERS -> emptyList()
    }
}

@Composable
private fun scanStatus(): String? {
    val progress = LibraryStore.progress ?: return null
    return when (progress.phase) {
        ScanPhase.FINDING -> pluralStringResource(R.plurals.library_scan_found, progress.found, progress.found)
        ScanPhase.READING -> pluralStringResource(R.plurals.library_scan_reading, progress.total, progress.processed, progress.total)
    }
}

@Composable
private fun ScanProgressBar(modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val colors = LocalLuckyColors.current
    val progress = LibraryStore.progress
    Box(modifier.height(height)) {
        if (progress != null) {
            if (progress.phase == ScanPhase.FINDING || progress.total == 0) {
                LinearProgressIndicator(
                    color = colors.primary,
                    trackColor = colors.primary.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress.processed.toFloat() / progress.total.coerceAtLeast(1) },
                    color = colors.primary,
                    trackColor = colors.primary.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

data class LibraryRoute(val category: LibraryCategory? = null, val folderId: String? = null) {
    val depth: Int get() = when {
        category == null -> 0
        folderId == null -> 1
        else -> 2
    }

    val parent: LibraryRoute get() = if (folderId != null) copy(folderId = null) else LibraryRoute()

    companion object {
        val Saver: Saver<LibraryRoute, String> = Saver(
            save = { "${it.category?.key.orEmpty()}\u001F${it.folderId.orEmpty()}" },
            restore = {
                val parts = it.split('\u001F')
                LibraryRoute(LibraryCategory.fromKey(parts.getOrNull(0)), parts.getOrNull(1)?.ifEmpty { null })
            }
        )
    }
}

@Composable
fun LibraryScreen(
    route: LibraryRoute,
    onRouteChange: (LibraryRoute) -> Unit,
    onPlay: (String, String?) -> Unit,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    LaunchedEffect(Unit) { LibraryStore.ensureLoaded(context) }
    BackHandler(enabled = route.depth > 0) { onRouteChange(route.parent) }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = { drillTransition(targetState.depth >= initialState.depth) },
            label = "library"
        ) { current ->
            val category = current.category
            if (category == null) {
                LibraryHome(
                    onOpenCategory = { onRouteChange(LibraryRoute(it)) },
                    onPlay = onPlay,
                    onManageFolders = onManageFolders
                )
            } else {
                CategoryScreen(
                    category = category,
                    folderId = current.folderId,
                    onBack = { onRouteChange(current.parent) },
                    onSwitch = { onRouteChange(LibraryRoute(it)) },
                    onOpenFolder = { onRouteChange(LibraryRoute(LibraryCategory.FOLDERS, it)) },
                    onPlay = onPlay,
                    onManageFolders = onManageFolders
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshContainer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val state = rememberPullToRefreshState()
    val refreshing = LibraryStore.progress?.phase == ScanPhase.FINDING
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { LibraryStore.startScan(context) },
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                containerColor = colors.cardBackground,
                color = colors.primary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            )
        }
    ) {
        content()
    }
}

@Composable
private fun LibraryHome(
    onOpenCategory: (LibraryCategory) -> Unit,
    onPlay: (String, String?) -> Unit,
    onManageFolders: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val prefs = rememberPreferences()
    val history by WatchHistory.entries(context).collectAsState()
    val videos = LibraryStore.videos
    val hidden = prefs.getStringSet(LuckyPrefs.HOME_HIDDEN_SECTIONS, emptySet()) ?: emptySet()
    val cornerScale = LuckyPrefs.cornerScale(prefs.getString(LuckyPrefs.LIBRARY_CORNERS, "rounded") ?: "rounded")
    val showScrollbar = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_SCROLLBAR, true)
    val listState = rememberLazyListState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalBottomContentPadding.current
    val byUri = remember(videos) { videos.associateBy { it.uri } }
    val continueList = remember(history, videos) { UpNext.build(history, videos) }
    val continueWatching = continueList.entries
    val recentlyAdded = remember(videos) { videos.sortedByDescending { it.modified }.take(20) }
    val folderCount = remember(videos) { videos.map { it.folderId }.distinct().size }
    val scanState = LibraryStore.scanState
    val autoSource = LibraryStore.source == LibrarySource.AUTO
    val accessRequest = rememberVideoAccessRequest()
    val haptics = rememberHaptics()
    var actionTarget by remember { mutableStateOf<VideoItem?>(null) }
    var actionQueue by remember { mutableStateOf<List<QueueItem>>(emptyList()) }

    RefreshContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp)
        ) {
            item(key = "header") {
                Column(
                    Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                        Text(
                            stringResource(R.string.nav_library),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        )
                        LuckyActionButton(
                            label = stringResource(R.string.library_folders_button),
                            icon = Icons.Rounded.CreateNewFolder,
                            primary = false,
                            compact = true,
                            minHeight = 36.dp,
                            onClick = onManageFolders
                        )
                    }
                    Text(
                        scanStatus() ?: pluralStringResource(R.plurals.library_video_count, videos.size, videos.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(start = 64.dp, end = 24.dp)
                    )
                    ScanProgressBar(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                    )
                }
            }
            when {
                videos.isEmpty() && scanState == LibraryScanState.SCANNING -> item(key = "scanning") { ScanningState() }
                videos.isEmpty() && !LibraryStore.hasSource && autoSource -> item(key = "idle") {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = stringResource(R.string.placeholder_library_auto_title),
                        message = stringResource(
                            if (accessRequest.blocked) R.string.library_access_blocked_summary else R.string.placeholder_library_auto_message
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LuckyActionButton(
                                label = stringResource(if (accessRequest.blocked) R.string.library_access_open_settings else R.string.library_access_allow),
                                icon = Icons.Rounded.LockOpen,
                                minHeight = 52.dp,
                                onClick = { accessRequest.request() }
                            )
                            Spacer(Modifier.height(10.dp))
                            LuckyActionButton(
                                label = stringResource(R.string.library_choose_folders_instead),
                                icon = Icons.Rounded.CreateNewFolder,
                                primary = false,
                                minHeight = 48.dp,
                                onClick = onManageFolders
                            )
                        }
                    }
                }
                videos.isEmpty() && !LibraryStore.hasSource -> item(key = "idle") {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = stringResource(R.string.placeholder_library_title),
                        message = stringResource(R.string.placeholder_library_message)
                    ) {
                        LuckyActionButton(
                            label = stringResource(R.string.library_add_folder_button),
                            icon = Icons.Rounded.CreateNewFolder,
                            minHeight = 52.dp,
                            onClick = onManageFolders
                        )
                    }
                }
                videos.isEmpty() && scanState == LibraryScanState.EMPTY -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.VideocamOff,
                        title = stringResource(R.string.library_no_results_title),
                        message = stringResource(if (autoSource) R.string.library_no_results_auto_message else R.string.library_no_results_message)
                    ) {
                        LuckyActionButton(
                            label = stringResource(R.string.library_menu_select_folders),
                            icon = Icons.Rounded.CreateNewFolder,
                            minHeight = 52.dp,
                            onClick = onManageFolders
                        )
                    }
                }
            }
            if (LuckyPrefs.HOME_SECTION_CONTINUE !in hidden && continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    Carousel(stringResource(R.string.library_category_continue)) {
                        items(continueWatching, key = { "c-${it.uri}" }) { entry ->
                            val video = byUri[entry.uri]
                            CarouselVideoCard(
                                item = video,
                                uri = entry.uri,
                                title = video?.title ?: entry.title.orEmpty(),
                                subtitle = if (entry.uri in continueList.upNext) {
                                    stringResource(R.string.library_next_episode)
                                } else {
                                    stringResource(R.string.library_time_left, formatDuration((entry.durationMs - entry.positionMs).coerceAtLeast(0L)))
                                },
                                progress = entry.progress,
                                width = 232.dp,
                                corner = (16 * cornerScale).dp,
                                onLongClick = {
                                    haptics.longPress()
                                    actionQueue = continueWatching.map { item -> QueueItem(item.uri, byUri[item.uri]?.title ?: item.title) }
                                    actionTarget = video ?: entry.asVideo()
                                },
                                onClick = {
                                    val episodes = continueList.showOf(entry.uri, video?.name ?: entry.title)
                                    PlayQueue.set(
                                        episodes?.map { item -> QueueItem(item.uri, item.title) }
                                            ?: continueWatching.map { item -> QueueItem(item.uri, byUri[item.uri]?.title ?: item.title) }
                                    )
                                    onPlay(entry.uri, video?.title ?: entry.title)
                                }
                            )
                        }
                    }
                }
            }
            if (LuckyPrefs.HOME_SECTION_RECENT !in hidden && recentlyAdded.isNotEmpty()) {
                item(key = "recent") {
                    Carousel(stringResource(R.string.library_category_recently_added)) {
                        items(recentlyAdded, key = { "r-${it.uri}" }) { video ->
                            CarouselVideoCard(
                                item = video,
                                uri = video.uri,
                                title = video.title,
                                subtitle = listOfNotNull(
                                    video.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
                                    video.resolutionLabel
                                ).joinToString(" • "),
                                progress = history[video.uri]?.progress ?: 0f,
                                width = 200.dp,
                                corner = (16 * cornerScale).dp,
                                onLongClick = {
                                    haptics.longPress()
                                    actionQueue = recentlyAdded.map { item -> QueueItem(item.uri, item.title) }
                                    actionTarget = video
                                },
                                onClick = {
                                    PlayQueue.set(recentlyAdded.map { item -> QueueItem(item.uri, item.title) })
                                    onPlay(video.uri, video.title)
                                }
                            )
                        }
                    }
                }
            }
            if (videos.isNotEmpty() || history.isNotEmpty()) {
                item(key = "browse") {
                    HomeSection(stringResource(R.string.library_section_browse)) {
                        CategoryRow(LibraryCategory.VIDEOS, pluralStringResource(R.plurals.library_video_count, videos.size, videos.size), onOpenCategory)
                        RowDivider()
                        CategoryRow(LibraryCategory.FOLDERS, pluralStringResource(R.plurals.library_folder_count, folderCount, folderCount), onOpenCategory)
                    }
                }
                item(key = "collections") {
                    val resumable = history.values.count { it.resumable }
                    HomeSection(stringResource(R.string.library_section_collections)) {
                        CategoryRow(LibraryCategory.RECENTLY_ADDED, pluralStringResource(R.plurals.library_video_count, videos.size, videos.size), onOpenCategory)
                        RowDivider()
                        CategoryRow(LibraryCategory.CONTINUE, pluralStringResource(R.plurals.library_video_count, resumable, resumable), onOpenCategory)
                        RowDivider()
                        CategoryRow(LibraryCategory.HISTORY, pluralStringResource(R.plurals.library_video_count, history.size, history.size), onOpenCategory)
                    }
                }
            }
        }
        if (showScrollbar) {
            Box(Modifier.fillMaxSize()) {
                VerticalScrollbar(listState, colors.primary, topPadding = top, bottomPadding = bottom)
            }
        }
        VideoActionsHost(
            target = actionTarget,
            onDismiss = { actionTarget = null },
            onPlay = { video ->
                PlayQueue.set(actionQueue)
                onPlay(video.uri, video.title)
            }
        )
    }
}

@Composable
private fun ScanningState() {
    val colors = LocalLuckyColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = colors.primary, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        Text(
            stringResource(R.string.library_scanning_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            scanStatus() ?: stringResource(R.string.library_scanning_message),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 8.dp)
        )
        ScanProgressBar(
            Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .padding(top = 20.dp),
            height = 4.dp
        )
    }
}

@Composable
private fun Carousel(title: String, content: LazyListScope.() -> Unit) {
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(top = 20.dp)
    ) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun HomeSection(title: String, content: @Composable () -> Unit) {
    val colors = LocalLuckyColors.current
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(top = 24.dp)
    ) {
        SectionHeader(title)
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.cardBackground)
        ) {
            content()
        }
    }
}

@Composable
private fun CategoryRow(category: LibraryCategory, count: String, onOpen: (LibraryCategory) -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(category) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTileIcon(category.icon, iconSize = 22.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text(stringResource(category.titleRes), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Text(count, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
        RowArrow()
    }
}

internal fun videoTiles(
    context: Context,
    items: List<VideoItem>,
    history: Map<String, WatchEntry>,
    category: LibraryCategory,
    inFolder: Boolean,
    showDuration: Boolean,
    showBadges: Boolean
): List<LibraryTile> {
    val now = System.currentTimeMillis()
    val resources = context.resources
    return items.map { item ->
        val entry = history[item.uri]
        val size = item.size.takeIf { it > 0 }?.let { formatSize(context, it) }
        val timeLeft = entry?.let { resources.getString(R.string.library_time_left, formatDuration((it.durationMs - it.positionMs).coerceAtLeast(0L))) }
        val watched = entry?.let { DateUtils.getRelativeTimeSpanString(it.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString() }
        val listSubtitle = when {
            category == LibraryCategory.CONTINUE && !inFolder -> timeLeft.orEmpty()
            category == LibraryCategory.HISTORY && !inFolder -> watched.orEmpty()
            else -> listOfNotNull(item.folderName.ifEmpty { null }.takeIf { !inFolder }, size).joinToString(" • ")
        }
        val gridSubtitle = when {
            category == LibraryCategory.CONTINUE && !inFolder -> timeLeft.orEmpty()
            category == LibraryCategory.HISTORY && !inFolder -> watched.orEmpty()
            inFolder -> listOfNotNull(item.resolutionLabel, size).joinToString(" • ")
            else -> item.folderName
        }
        val duration = item.durationMs.takeIf { showDuration && it > 0 }?.let { formatDuration(it) }
        LibraryTile(
            id = item.uri,
            title = item.title,
            listSubtitle = listSubtitle,
            gridSubtitle = gridSubtitle,
            thumbUri = item.uri,
            icon = Icons.Rounded.Movie,
            progress = entry?.takeIf { !it.finished }?.progress ?: 0f,
            meta = duration,
            chips = if (showBadges) listOfNotNull(item.resolutionLabel) else emptyList(),
            badge = duration
        )
    }
}

internal fun folderTiles(resources: Resources, groups: List<FolderGroup>): List<LibraryTile> = groups.map { group ->
    val count = resources.getQuantityString(R.plurals.library_video_count, group.videos.size, group.videos.size)
    LibraryTile(
        id = group.id,
        title = group.name,
        listSubtitle = listOfNotNull(count, group.path.takeIf { it.isNotEmpty() && it != group.name }).joinToString(" • "),
        gridSubtitle = count,
        thumbUri = group.cover?.uri,
        icon = Icons.Rounded.Folder,
        progress = 0f,
        meta = null,
        chips = emptyList(),
        badge = null
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryScreen(
    category: LibraryCategory,
    folderId: String?,
    onBack: () -> Unit,
    onSwitch: (LibraryCategory) -> Unit,
    onOpenFolder: (String) -> Unit,
    onPlay: (String, String?) -> Unit,
    onManageFolders: () -> Unit
) {
    val context = LocalContext.current
    val resources = context.resources
    val colors = LocalLuckyColors.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val prefs = rememberPreferences()
    val history by WatchHistory.entries(context).collectAsState()
    val videos = LibraryStore.videos
    val viewKey = LuckyPrefs.LIBRARY_VIEW_MODE_PREFIX + (if (folderId != null) "folder" else category.key)
    val viewMode = ViewMode.fromKey(prefs.getString(viewKey, null), if (category == LibraryCategory.FOLDERS && folderId == null) ViewMode.LIST_SMALL else ViewMode.LIST)
    val order = SortOrder.fromKey(prefs.getString(LuckyPrefs.LIBRARY_SORT_ORDER, null))
    val descending = prefs.getString(LuckyPrefs.LIBRARY_SORT_DIRECTION, "ascending") == "descending"
    val cornerScale = LuckyPrefs.cornerScale(prefs.getString(LuckyPrefs.LIBRARY_CORNERS, "rounded") ?: "rounded")
    val showDuration = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_DURATION, true)
    val showBadges = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_BADGES, true)
    val showScrollbar = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_SCROLLBAR, true)
    val groups = remember(videos) { videos.folderGroups() }
    val folder = folderId?.let { id -> groups.firstOrNull { it.id == id } }
    val items = remember(category, folder, videos, history, order, descending) {
        when {
            folder != null -> folder.videos.sortedBy(order, descending, history)
            else -> categoryItems(category, videos, history, order, descending)
        }
    }
    val showingFolders = category == LibraryCategory.FOLDERS && folderId == null
    val tiles = remember(showingFolders, groups, items, history, category, folder, showDuration, showBadges, resources) {
        if (showingFolders) {
            folderTiles(resources, groups)
        } else {
            videoTiles(context, items, history, category, folder != null, showDuration, showBadges)
        }
    }
    val count = tiles.size
    val alphabetSort = (category.sortable || folder != null) && (order == SortOrder.TITLE || order == SortOrder.FILENAME)
    val letterPositions = remember(items, alphabetSort, order, showingFolders) {
        if (!alphabetSort || showingFolders) {
            emptyMap()
        } else {
            val positions = LinkedHashMap<String, Int>()
            items.forEachIndexed { index, item ->
                positions.getOrPut(indexLetter(if (order == SortOrder.FILENAME) item.name else item.title)) { index + 1 }
            }
            positions
        }
    }
    val showAlphabet = prefs.getBoolean(LuckyPrefs.LIBRARY_ALPHABET_INDEX, true) && letterPositions.size > 1
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val morph = remember(scope) { ViewModeMorphState(scope) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showViewMode by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<VideoItem?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalBottomContentPadding.current
    val countText = when {
        showingFolders -> pluralStringResource(R.plurals.library_folder_count, count, count)
        else -> pluralStringResource(R.plurals.library_video_count, count, count)
    }
    val onTileClick: (Int) -> Unit = { index ->
        if (showingFolders) {
            groups.getOrNull(index)?.let { onOpenFolder(it.id) }
        } else {
            items.getOrNull(index)?.let {
                PlayQueue.set(items.map { item -> QueueItem(item.uri, item.title) })
                onPlay(it.uri, it.title)
            }
        }
    }
    val onTileLongClick: (Int) -> Unit = { index ->
        if (!showingFolders) {
            items.getOrNull(index)?.let {
                haptics.longPress()
                actionTarget = it
            }
        }
    }

    val header: @Composable () -> Unit = {
        Column(Modifier.padding(top = top)) {
            CategoryHeader(
                category = category,
                title = folder?.name,
                subtitle = folder?.path,
                count = scanStatus() ?: countText,
                canPlay = !showingFolders && items.isNotEmpty(),
                onBack = onBack,
                onSwitch = { showSwitcher = true },
                onPlay = {
                    items.firstOrNull()?.let {
                        PlayQueue.set(items.map { item -> QueueItem(item.uri, item.title) })
                        onPlay(it.uri, it.title)
                    }
                },
                onMenu = { showMenu = true }
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .viewModePinch(morph)
    ) {
        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val bottomPx = with(density) { bottom.roundToPx() }
        val geometries = remember(viewportWidth, viewportHeight, headerHeightPx, bottomPx, count, density, cornerScale) {
            ViewMode.entries.associateWith { mode ->
                computeModeGeometry(mode, viewportWidth, viewportHeight, headerHeightPx, bottomPx, count, density, cornerScale)
            }
        }
        val geometry = geometries.getValue(viewMode)
        val style = geometry.style
        val staticFrame = remember(style) { TileFrame(style, 0, 0) }
        val tileShape = RoundedCornerShape(style.containerCorner)
        val morphing = morph.session != null

        SideEffect {
            morph.pinchEnabled = !showMenu && !showSort && !showViewMode && !showSwitcher && actionTarget == null && count > 0
            morph.currentMode = { viewMode }
            morph.onStep = { haptics.tick() }
            morph.captureSession = capture@{
                val current = geometries.getValue(viewMode)
                if (headerHeightPx <= 0 || count == 0) return@capture null
                val scroll = if (current.isGrid) {
                    current.scrollFromLazy(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                } else {
                    current.scrollFromLazy(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                }
                scope.launch {
                    if (current.isGrid) gridState.stopScroll() else listState.stopScroll()
                }
                val anchor = current.anchorAt(scroll)
                MorphSession(
                    tiles = tiles,
                    anchorIndex = anchor,
                    anchorViewportY = (current.contentTopOf(anchor) - scroll).toFloat()
                )
            }
            morph.commit = { mode ->
                val target = geometries.getValue(mode)
                val session = morph.session
                val scroll = if (session != null) target.scrollFor(session).roundToInt() else 0
                val (index, offset) = target.lazyPosition(scroll)
                if (target.isGrid) {
                    gridState.requestScrollToItem(index, offset)
                } else {
                    listState.requestScrollToItem(index, offset)
                }
                prefs.edit().putString(viewKey, mode.key).apply()
            }
        }

        RefreshContainer(Modifier.graphicsLayer { alpha = if (morph.session != null) 0f else 1f }) {
            if (geometry.isGrid) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(geometry.columns),
                    contentPadding = PaddingValues(start = GridHorizontalPadding, end = GridHorizontalPadding, bottom = bottom + ListEndSpacer),
                    horizontalArrangement = Arrangement.spacedBy(style.gridSpacing),
                    verticalArrangement = Arrangement.spacedBy(style.gridSpacing),
                    modifier = Modifier
                        .widthIn(max = ListMaxWidth)
                        .align(Alignment.TopCenter)
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.onSizeChanged { headerHeightPx = it.height }) { header() }
                    }
                    items(tiles.size, key = { tiles[it].id }) { index ->
                        LibraryTileView(
                            tile = tiles[index],
                            from = staticFrame,
                            to = null,
                            fraction = StaticFraction,
                            modifier = Modifier
                                .fixedHeight(geometry.rowHeight)
                                .clip(tileShape)
                                .combinedClickable(onLongClick = { onTileLongClick(index) }) { onTileClick(index) }
                        )
                    }
                    if (count == 0) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyCategory() }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottom + ListEndSpacer),
                    modifier = Modifier
                        .widthIn(max = ListMaxWidth)
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    item(key = "header") {
                        Box(Modifier.onSizeChanged { headerHeightPx = it.height }) { header() }
                    }
                    itemsIndexed(tiles, key = { _, tile -> tile.id }) { index, tile ->
                        LibraryTileView(
                            tile = tile,
                            from = staticFrame,
                            to = null,
                            fraction = StaticFraction,
                            modifier = Modifier
                                .fixedHeight(geometry.rowHeight)
                                .combinedClickable(onLongClick = { onTileLongClick(index) }) { onTileClick(index) }
                        )
                    }
                    if (count == 0) item(key = "empty") { EmptyCategory() }
                }
            }
        }

        if (!morphing) {
            if (showAlphabet) {
                AlphabetIndex(
                    letters = letterPositions.keys.toList(),
                    onSelect = { letter ->
                        letterPositions[letter]?.let { position ->
                            scope.launch { if (geometry.isGrid) gridState.scrollToItem(position) else listState.scrollToItem(position) }
                        }
                    },
                    topPadding = top,
                    bottomPadding = bottom
                )
            } else if (showScrollbar) {
                if (geometry.isGrid) {
                    VerticalScrollbar(gridState, colors.primary, topPadding = top, bottomPadding = bottom)
                } else {
                    VerticalScrollbar(listState, colors.primary, topPadding = top, bottomPadding = bottom)
                }
            }
        }

        morph.session?.let { session ->
            ViewModeMorphOverlay(
                morph = morph,
                session = session,
                geometries = geometries,
                header = header
            )
        }
    }

    val categoryCounts = LibraryCategory.entries.associateWith { categoryCount(it, videos, history, groups.size) }
    CategorySwitcherDialog(
        visible = showSwitcher,
        current = category,
        counts = { categoryCounts.getValue(it) },
        onSelect = onSwitch,
        onDismiss = { showSwitcher = false }
    )
    LibraryMenuDialog(
        visible = showMenu,
        category = category,
        onManageFolders = onManageFolders,
        onRescan = { LibraryStore.startScan(context) },
        onSort = if (category.sortable || folderId != null) ({ showSort = true }) else null,
        onViewMode = { showViewMode = true },
        onDismiss = { showMenu = false }
    )
    SortDialog(
        visible = showSort,
        order = order,
        descending = descending,
        onChange = { newOrder, newDescending ->
            prefs.edit()
                .putString(LuckyPrefs.LIBRARY_SORT_ORDER, newOrder.key)
                .putString(LuckyPrefs.LIBRARY_SORT_DIRECTION, if (newDescending) "descending" else "ascending")
                .apply()
        },
        onDismiss = { showSort = false }
    )
    ViewModeDialog(
        visible = showViewMode,
        mode = viewMode,
        onSelect = { mode ->
            if (!morph.morphTo(mode)) prefs.edit().putString(viewKey, mode.key).apply()
        },
        onDismiss = { showViewMode = false }
    )
    VideoActionsHost(
        target = actionTarget,
        onDismiss = { actionTarget = null },
        onPlay = { video ->
            PlayQueue.set(items.map { item -> QueueItem(item.uri, item.title) })
            onPlay(video.uri, video.title)
        }
    )
}

@Composable
private fun categoryCount(category: LibraryCategory, videos: List<VideoItem>, history: Map<String, WatchEntry>, folders: Int): String = when (category) {
    LibraryCategory.FOLDERS -> pluralStringResource(R.plurals.library_folder_count, folders, folders)
    LibraryCategory.CONTINUE -> history.values.count { it.resumable }.let { pluralStringResource(R.plurals.library_video_count, it, it) }
    LibraryCategory.HISTORY -> pluralStringResource(R.plurals.library_video_count, history.size, history.size)
    else -> pluralStringResource(R.plurals.library_video_count, videos.size, videos.size)
}

@Composable
private fun EmptyCategory() {
    val colors = LocalLuckyColors.current
    Text(
        stringResource(R.string.library_category_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.secondaryText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
    )
}

@Composable
private fun CategoryHeader(
    category: LibraryCategory,
    title: String?,
    subtitle: String?,
    count: String,
    canPlay: Boolean,
    onBack: () -> Unit,
    onSwitch: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit
) {
    val colors = LocalLuckyColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LuckyIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.player_back), tint = colors.primary)
            }
            Text(
                if (title != null) stringResource(category.titleRes) else stringResource(R.string.nav_library),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
        }
        Row(
            Modifier
                .padding(start = 16.dp, end = 12.dp, top = 12.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = title == null, onClick = onSwitch)
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (title != null) Icons.Rounded.Folder else category.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                Text(
                    title ?: stringResource(category.titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f, fill = false)
                )
                if (title == null) {
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = stringResource(R.string.library_switch_category),
                        tint = colors.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        if (!subtitle.isNullOrBlank() && subtitle != title) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp)
            )
        }
        Row(
            Modifier.padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                count,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText,
                modifier = Modifier.weight(1f)
            )
            if (canPlay) {
                LuckyActionButton(
                    label = stringResource(R.string.library_play),
                    icon = Icons.Rounded.PlayArrow,
                    compact = true,
                    minHeight = 36.dp,
                    onClick = onPlay
                )
                Spacer(Modifier.width(4.dp))
            }
            LuckyIconButton(onClick = onMenu) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.library_menu), tint = colors.onSurface)
            }
        }
        ScanProgressBar(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(4.dp))
    }
}
