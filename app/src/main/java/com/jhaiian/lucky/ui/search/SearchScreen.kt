package com.jhaiian.lucky.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.PlayQueue
import com.jhaiian.lucky.data.QueueItem
import com.jhaiian.lucky.data.VideoItem
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.ui.components.EmptyState
import com.jhaiian.lucky.ui.components.LocalBottomContentPadding
import com.jhaiian.lucky.ui.components.LuckyIconButton
import com.jhaiian.lucky.ui.components.SectionHeader
import com.jhaiian.lucky.ui.components.VerticalScrollbar
import com.jhaiian.lucky.ui.library.LibraryCategory
import com.jhaiian.lucky.ui.library.LibraryTileView
import com.jhaiian.lucky.ui.library.StaticFraction
import com.jhaiian.lucky.ui.library.TileFrame
import com.jhaiian.lucky.ui.library.ViewMode
import com.jhaiian.lucky.ui.library.fixedHeight
import com.jhaiian.lucky.ui.library.folderGroups
import com.jhaiian.lucky.ui.library.folderTiles
import com.jhaiian.lucky.ui.library.listRowHeight
import com.jhaiian.lucky.ui.library.tileStyleFor
import com.jhaiian.lucky.ui.library.videoTiles
import com.jhaiian.lucky.ui.library.rememberPreferences
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.text.Normalizer
import java.util.Locale

private val diacritics = Regex("\\p{Mn}+")

private fun String.normalized(): String =
    diacritics.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

private fun matches(item: VideoItem, terms: List<String>): Boolean {
    val haystack = "${item.title} ${item.name} ${item.folderName}".normalized()
    return terms.all { haystack.contains(it) }
}

@Composable
fun SearchScreen(
    onPlay: (String, String?) -> Unit,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val prefs = rememberPreferences()
    val history by WatchHistory.entries(context).collectAsState()
    LaunchedEffect(Unit) { LibraryStore.ensureLoaded(context) }
    var query by rememberSaveable { mutableStateOf("") }
    val videos = LibraryStore.videos
    val terms = remember(query) { query.normalized().split(' ').filter { it.isNotBlank() } }
    val results = remember(terms, videos) {
        if (terms.isEmpty()) emptyList() else videos.filter { matches(it, terms) }.sortedBy { it.title.normalized() }
    }
    val folderResults = remember(terms, videos) {
        if (terms.isEmpty()) emptyList() else videos.folderGroups().filter { group -> terms.all { group.name.normalized().contains(it) } }
    }
    val cornerScale = LuckyPrefs.cornerScale(prefs.getString(LuckyPrefs.LIBRARY_CORNERS, "rounded") ?: "rounded")
    val showDuration = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_DURATION, true)
    val showBadges = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_BADGES, true)
    val showScrollbar = prefs.getBoolean(LuckyPrefs.LIBRARY_SHOW_SCROLLBAR, true)
    val density = LocalDensity.current
    val videoStyle = tileStyleFor(ViewMode.LIST, cornerScale)
    val folderStyle = tileStyleFor(ViewMode.LIST_SMALL, cornerScale)
    val videoFrame = remember(videoStyle) { TileFrame(videoStyle, 0, 0) }
    val folderFrame = remember(folderStyle) { TileFrame(folderStyle, 0, 0) }
    val videoRowHeight = with(density) { listRowHeight(videoStyle) }
    val folderRowHeight = with(density) { listRowHeight(folderStyle) }
    val videoResultTiles = remember(results, history, showDuration, showBadges) {
        videoTiles(context, results, history, LibraryCategory.VIDEOS, false, showDuration, showBadges)
    }
    val folderResultTiles = remember(folderResults) { folderTiles(context.resources, folderResults) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalBottomContentPadding.current
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp),
            modifier = Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                        Text(
                            stringResource(R.string.nav_search),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(colors.cardBackground)
                            .padding(start = 18.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(22.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.secondaryText
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                cursorBrush = SolidColor(colors.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                            )
                        }
                        AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                            LuckyIconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear), tint = colors.secondaryText)
                            }
                        }
                    }
                    if (terms.isNotEmpty()) {
                        val total = results.size
                        Text(
                            pluralStringResource(R.plurals.search_result_count, total, total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)
                        )
                    }
                }
            }
            when {
                terms.isEmpty() -> item(key = "idle") {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.search_idle_title),
                        message = if (videos.isEmpty()) stringResource(R.string.search_idle_empty_library) else pluralStringResource(R.plurals.search_idle_message, videos.size, videos.size)
                    )
                }
                results.isEmpty() && folderResults.isEmpty() -> item(key = "none") {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.search_no_results_title),
                        message = stringResource(R.string.search_no_results_message, query.trim())
                    )
                }
                else -> {
                    if (folderResults.isNotEmpty()) {
                        item(key = "folders-header") { SectionHeader(stringResource(R.string.library_category_folders), Modifier.padding(top = 20.dp)) }
                        items(folderResultTiles, key = { "f-${it.id}" }) { tile ->
                            LibraryTileView(
                                tile = tile,
                                from = folderFrame,
                                to = null,
                                fraction = StaticFraction,
                                modifier = Modifier
                                    .fixedHeight(folderRowHeight)
                                    .clickable {
                                        focusManager.clearFocus()
                                        onOpenFolder(tile.id)
                                    }
                            )
                        }
                    }
                    if (results.isNotEmpty()) {
                        item(key = "videos-header") { SectionHeader(stringResource(R.string.library_category_videos), Modifier.padding(top = 20.dp)) }
                        items(videoResultTiles, key = { "v-${it.id}" }) { tile ->
                            LibraryTileView(
                                tile = tile,
                                from = videoFrame,
                                to = null,
                                fraction = StaticFraction,
                                modifier = Modifier
                                    .fixedHeight(videoRowHeight)
                                    .clickable {
                                        focusManager.clearFocus()
                                        PlayQueue.set(videoResultTiles.map { item -> QueueItem(item.id, item.title) })
                                        onPlay(tile.id, tile.title)
                                    }
                            )
                        }
                    }
                }
            }
        }
        if (showScrollbar) VerticalScrollbar(listState, colors.primary, topPadding = top, bottomPadding = bottom)
    }
}
