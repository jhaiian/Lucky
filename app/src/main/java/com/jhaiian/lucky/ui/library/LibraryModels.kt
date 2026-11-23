package com.jhaiian.lucky.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.VideoItem
import com.jhaiian.lucky.data.WatchEntry

enum class LibraryCategory(val key: String, val titleRes: Int, val icon: ImageVector, val browse: Boolean, val sortable: Boolean) {
    VIDEOS("videos", R.string.library_category_videos, Icons.Rounded.VideoLibrary, true, true),
    FOLDERS("folders", R.string.library_category_folders, Icons.Rounded.Folder, true, false),
    RECENTLY_ADDED("recently_added", R.string.library_category_recently_added, Icons.Rounded.NewReleases, false, false),
    CONTINUE("continue", R.string.library_category_continue, Icons.Rounded.PlayCircle, false, false),
    HISTORY("history", R.string.library_category_history, Icons.Rounded.History, false, false);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class ViewMode(val key: String, val titleRes: Int, val grid: Boolean, val level: Int) {
    GRID("grid", R.string.view_mode_grid, true, 0),
    GRID_SMALL("grid_small", R.string.view_mode_grid_small, true, 1),
    GRID_EXTRA_SMALL("grid_extra_small", R.string.view_mode_grid_extra_small, true, 2),
    LIST_BIG("list_big", R.string.view_mode_list_big, false, 6),
    LIST_LARGE("list_large", R.string.view_mode_list_large, false, 5),
    LIST("list", R.string.view_mode_list, false, 4),
    LIST_SMALL("list_small", R.string.view_mode_list_small, false, 3),
    LIST_EXTRA_SMALL("list_extra_small", R.string.view_mode_list_extra_small, false, 2),
    LIST_COMPACT("list_compact", R.string.view_mode_list_compact, false, 1),
    LIST_NO_IMAGE("list_no_image", R.string.view_mode_list_no_image, false, 0);

    companion object {
        fun fromKey(key: String?, fallback: ViewMode) = entries.firstOrNull { it.key == key } ?: fallback
    }
}

enum class SortOrder(val key: String, val titleRes: Int) {
    TITLE("title", R.string.sort_by_title),
    FILENAME("filename", R.string.sort_by_filename),
    FOLDER("folder", R.string.sort_by_folder),
    DATE_ADDED("date_added", R.string.sort_by_date_added),
    DURATION("duration", R.string.sort_by_duration),
    SIZE("size", R.string.sort_by_size),
    LAST_WATCHED("last_watched", R.string.sort_by_last_watched);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: TITLE
    }
}

private val naturalCollator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }

fun List<VideoItem>.sortedBy(order: SortOrder, descending: Boolean, history: Map<String, WatchEntry>): List<VideoItem> {
    val comparator: Comparator<VideoItem> = when (order) {
        SortOrder.TITLE -> Comparator { a, b -> naturalCollator.compare(a.title, b.title) }
        SortOrder.FILENAME -> Comparator { a, b -> naturalCollator.compare(a.name, b.name) }
        SortOrder.FOLDER -> Comparator<VideoItem> { a, b -> naturalCollator.compare(a.folderPath, b.folderPath) }
            .thenComparator { a, b -> naturalCollator.compare(a.title, b.title) }
        SortOrder.DATE_ADDED -> compareByDescending { it.modified }
        SortOrder.DURATION -> compareBy { it.durationMs }
        SortOrder.SIZE -> compareBy { it.size }
        SortOrder.LAST_WATCHED -> compareByDescending { history[it.uri]?.updatedAt ?: 0L }
    }
    return sortedWith(if (descending) comparator.reversed() else comparator)
}

data class FolderGroup(val id: String, val name: String, val path: String, val videos: List<VideoItem>) {
    val cover: VideoItem? get() = videos.maxByOrNull { it.modified }
}

fun List<VideoItem>.folderGroups(): List<FolderGroup> = groupBy { it.folderId }
    .map { (id, items) -> FolderGroup(id, items.first().folderName, items.first().folderPath, items.sortedWith { a, b -> naturalCollator.compare(a.title, b.title) }) }
    .sortedWith { a, b -> naturalCollator.compare(a.path, b.path) }
