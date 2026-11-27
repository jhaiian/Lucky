package com.jhaiian.lucky.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.ExternalRequest
import com.jhaiian.lucky.data.ExternalVideo
import com.jhaiian.lucky.data.PlayQueue
import com.jhaiian.lucky.ui.components.LocalBottomContentPadding
import com.jhaiian.lucky.ui.components.drillTransition
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.tabTransition
import com.jhaiian.lucky.ui.library.FolderPickerSheet
import com.jhaiian.lucky.ui.library.LibraryCategory
import com.jhaiian.lucky.ui.library.LibraryRoute
import com.jhaiian.lucky.ui.library.LibraryScreen
import com.jhaiian.lucky.ui.player.MiniPlayerBar
import com.jhaiian.lucky.ui.player.PlayerScreen
import com.jhaiian.lucky.ui.search.SearchScreen
import com.jhaiian.lucky.ui.settings.SettingsPage
import com.jhaiian.lucky.ui.settings.SettingsPageScreen
import com.jhaiian.lucky.ui.settings.settingsDepth
import com.jhaiian.lucky.ui.settings.SettingsScreen
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import com.jhaiian.lucky.update.UpdateChecker
import com.jhaiian.lucky.update.UpdateFlowHost

private enum class AppTab(val labelRes: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    LIBRARY(R.string.nav_library, Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    SEARCH(R.string.nav_search, Icons.Rounded.Search, Icons.Rounded.Search),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings, Icons.Rounded.Settings)
}

private data class PlayRequest(val uri: String, val title: String?, val external: Boolean = false, val transient: Boolean = false)

private val PlayRequestSaver: Saver<PlayRequest?, String> = Saver(
    save = { request ->
        request?.let {
            listOf(it.uri, it.title.orEmpty(), if (it.external) "1" else "0", if (it.transient) "1" else "0").joinToString("\u001F")
        }
    },
    restore = { saved ->
        val parts = saved.split('\u001F', limit = 4)
        PlayRequest(
            uri = parts[0],
            title = parts.getOrNull(1)?.ifEmpty { null },
            external = parts.getOrNull(2) == "1",
            transient = parts.getOrNull(3) == "1"
        )
    }
)

@Composable
fun LuckyApp(external: ExternalRequest? = null, onExternalHandled: (Long) -> Unit = {}) {
    val colors = LocalLuckyColors.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var tab by rememberSaveable { mutableStateOf(AppTab.LIBRARY) }
    var libraryRoute by rememberSaveable(stateSaver = LibraryRoute.Saver) { mutableStateOf(LibraryRoute()) }
    var settingsPageName by rememberSaveable { mutableStateOf<String?>(null) }
    var showFolderPicker by rememberSaveable { mutableStateOf(false) }
    var playRequest by rememberSaveable(stateSaver = PlayRequestSaver) { mutableStateOf<PlayRequest?>(null) }
    var navVisible by remember { mutableStateOf(true) }
    var playerMinimized by rememberSaveable { mutableStateOf(false) }
    val settingsPage = settingsPageName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
    val stateHolder = rememberSaveableStateHolder()

    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f) navVisible = false else if (available.y > 8f) navVisible = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(tab, libraryRoute, settingsPageName) { navVisible = true }
    LaunchedEffect(Unit) { UpdateChecker.checkOnLaunch(context) }
    LaunchedEffect(external?.id) {
        val incoming = external ?: return@LaunchedEffect
        val resolved = ExternalVideo.resolve(context, incoming.uri)
        PlayQueue.set(resolved.queue)
        playerMinimized = false
        playRequest = PlayRequest(resolved.uri, resolved.title, external = true, transient = resolved.transient)
        onExternalHandled(incoming.id)
    }

    val play: (String, String?) -> Unit = { uri, title ->
        playerMinimized = false
        playRequest = PlayRequest(uri, title)
    }
    val manageFolders: () -> Unit = { showFolderPicker = true }
    val selectTab: (AppTab) -> Unit = { target ->
        haptics.tick()
        if (target == tab) {
            when (target) {
                AppTab.LIBRARY -> libraryRoute = LibraryRoute()
                AppTab.SETTINGS -> settingsPageName = null
                AppTab.SEARCH -> Unit
            }
        }
        tab = target
    }

    BackHandler(enabled = tab != AppTab.LIBRARY) { tab = AppTab.LIBRARY }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        val useRail = maxWidth >= 600.dp
        val navInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val navPadding = if (useRail) navInsetBottom else navInsetBottom + 80.dp
        val miniShown = playRequest != null && playerMinimized
        val bottomPadding = if (miniShown) navPadding + 80.dp else navPadding
        val sideInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)

        Row(Modifier.fillMaxSize()) {
            if (useRail) {
                NavigationRail(
                    containerColor = colors.cardBackground,
                    contentColor = colors.onSurface,
                    windowInsets = WindowInsets.statusBars
                        .union(sideInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTab.entries.forEach { item ->
                        val selected = item == tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { selectTab(item) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = colors.primary,
                                selectedTextColor = colors.primary,
                                indicatorColor = colors.primary.copy(alpha = 0.16f),
                                unselectedIconColor = colors.secondaryText,
                                unselectedTextColor = colors.secondaryText
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(sideInsets.only(if (useRail) WindowInsetsSides.End else WindowInsetsSides.Horizontal))
                    .nestedScroll(hideOnScroll)
            ) {
                CompositionLocalProvider(LocalBottomContentPadding provides bottomPadding) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { tabTransition() },
                        label = "tabs"
                    ) { current ->
                        stateHolder.SaveableStateProvider(current.name) {
                            when (current) {
                                AppTab.LIBRARY -> LibraryScreen(
                                    route = libraryRoute,
                                    onRouteChange = { libraryRoute = it },
                                    onPlay = play,
                                    onManageFolders = manageFolders
                                )
                                AppTab.SEARCH -> SearchScreen(
                                    onPlay = play,
                                    onOpenFolder = { id ->
                                        libraryRoute = LibraryRoute(LibraryCategory.FOLDERS, id)
                                        tab = AppTab.LIBRARY
                                    }
                                )
                                AppTab.SETTINGS -> SettingsHost(
                                    page = settingsPage,
                                    onPageChange = { settingsPageName = it?.name },
                                    onManageFolders = manageFolders
                                )
                            }
                        }
                    }
                }
                StatusBarScrim()
                androidx.compose.animation.AnimatedVisibility(
                    visible = miniShown,
                    enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = (if (navVisible || useRail) navPadding else navInsetBottom) + 8.dp)
                ) {
                    MiniPlayerBar(
                        fallbackTitle = playRequest?.title,
                        onExpand = { playerMinimized = false },
                        onClose = {
                            playerMinimized = false
                            playRequest = null
                        }
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(navInsetBottom)
                        .background(colors.background.copy(alpha = 0.92f))
                )
                if (!useRail) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = navVisible,
                        enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                        exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160)),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        NavigationBar(
                            containerColor = colors.cardBackground,
                            contentColor = colors.onSurface,
                            tonalElevation = 0.dp
                        ) {
                            AppTab.entries.forEach { item ->
                                val selected = item == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { selectTab(item) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                                    label = {
                                        Text(
                                            stringResource(item.labelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = colors.primary,
                                        selectedTextColor = colors.primary,
                                        indicatorColor = colors.primary.copy(alpha = 0.16f),
                                        unselectedIconColor = colors.secondaryText,
                                        unselectedTextColor = colors.secondaryText
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showFolderPicker) {
            FolderPickerSheet(onDismiss = { showFolderPicker = false })
        }

        UpdateFlowHost()

        val request = playRequest
        if (request != null) {
            val uri = remember(request.uri) { Uri.parse(request.uri) }
            PlayerScreen(
                uri = uri,
                title = request.title,
                recordHistory = !request.transient,
                minimized = playerMinimized,
                onMinimize = if (request.external) null else ({ playerMinimized = true }),
                onClose = {
                    playerMinimized = false
                    playRequest = null
                    if (request.external) context.findActivity()?.moveTaskToBack(true)
                },
                onOpen = { nextUri, nextTitle ->
                    playRequest = PlayRequest(nextUri, nextTitle, external = request.external)
                }
            )
        }
    }
}

@Composable
private fun SettingsHost(
    page: SettingsPage?,
    onPageChange: (SettingsPage?) -> Unit,
    onManageFolders: () -> Unit
) {
    val colors = LocalLuckyColors.current
    BackHandler(enabled = page != null) { onPageChange(page?.parent) }
    AnimatedContent(
        targetState = page,
        transitionSpec = { drillTransition(settingsDepth(targetState) > settingsDepth(initialState)) },
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        label = "settings"
    ) { current ->
        if (current == null) {
            SettingsScreen(onOpenPage = { onPageChange(it) })
        } else {
            SettingsPageScreen(
                page = current,
                onBack = { onPageChange(current.parent) },
                onOpenPage = { onPageChange(it) },
                onManageFolders = onManageFolders
            )
        }
    }
}

@Composable
private fun StatusBarScrim() {
    val colors = LocalLuckyColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .background(colors.background)
    )
}
