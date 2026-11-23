package com.jhaiian.lucky.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LibraryScanState { IDLE, SCANNING, EMPTY, LOADED }

object LibraryStore {
    private const val CACHE_FILE = "library_cache.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private var loaded = false
    private var autoScanned = false
    private var refreshJob: Job? = null
    private var observer: ContentObserver? = null

    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var folders by mutableStateOf<List<VideoFolder>>(emptyList())
        private set
    var scanState by mutableStateOf(LibraryScanState.IDLE)
        private set
    var progress by mutableStateOf<ScanProgress?>(null)
        private set
    var lastScanFinished by mutableStateOf(0L)
        private set
    var source by mutableStateOf(LibrarySource.AUTO)
        private set
    var access by mutableStateOf(VideoAccessLevel.NONE)
        private set

    val scanning: Boolean
        get() = progress != null

    val hasSource: Boolean
        get() = if (source == LibrarySource.AUTO) access != VideoAccessLevel.NONE else folders.any { it.enabled }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val appContext = context.applicationContext
        folders = LibraryFolders.load(appContext)
        val prefs = LuckyPrefs.get(appContext)
        source = LibrarySource.fromKey(prefs.getString(LuckyPrefs.LIBRARY_SOURCE, null))
            ?: (if (folders.isNotEmpty()) LibrarySource.FOLDERS else LibrarySource.AUTO).also {
                prefs.edit().putString(LuckyPrefs.LIBRARY_SOURCE, it.key).apply()
            }
        access = VideoAccess.level(appContext)
        updateObserver(appContext)
        scope.launch {
            val cached = withContext(Dispatchers.IO) { readCache(appContext) }
            if (cached.isNotEmpty() && videos.isEmpty()) {
                videos = cached
                scanState = LibraryScanState.LOADED
            } else if (!hasSource) {
                scanState = LibraryScanState.IDLE
            }
            scanIfNeeded(appContext)
        }
    }

    fun scanIfNeeded(context: Context) {
        if (autoScanned || !hasSource) return
        autoScanned = true
        startScan(context)
    }

    fun refreshAccess(context: Context, rescanPartial: Boolean = false): VideoAccessLevel {
        val appContext = context.applicationContext
        val level = VideoAccess.level(appContext)
        val changed = level != access
        access = level
        if (!loaded || source != LibrarySource.AUTO) return level
        updateObserver(appContext)
        if (changed || (rescanPartial && level == VideoAccessLevel.PARTIAL)) startScan(appContext, restart = true)
        return level
    }

    fun applySource(context: Context, updated: LibrarySource) {
        val appContext = context.applicationContext
        access = VideoAccess.level(appContext)
        if (updated != source) {
            source = updated
            LuckyPrefs.get(appContext).edit().putString(LuckyPrefs.LIBRARY_SOURCE, updated.key).apply()
            scanJob?.cancel()
            videos = emptyList()
            scanState = if (hasSource) LibraryScanState.SCANNING else LibraryScanState.IDLE
        }
        updateObserver(appContext)
    }

    private fun updateObserver(context: Context) {
        val wanted = source == LibrarySource.AUTO && access != VideoAccessLevel.NONE
        val current = observer
        if (wanted && current == null) {
            val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    scheduleRefresh(context)
                }
            }
            runCatching {
                context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, created)
                observer = created
            }
        } else if (!wanted && current != null) {
            runCatching { context.contentResolver.unregisterContentObserver(current) }
            observer = null
            refreshJob?.cancel()
        }
    }

    private fun scheduleRefresh(context: Context) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(1500)
            if (source == LibrarySource.AUTO && access != VideoAccessLevel.NONE) startScan(context, restart = true)
        }
    }

    fun updateFolders(context: Context, updated: List<VideoFolder>) {
        val appContext = context.applicationContext
        folders.filter { old -> updated.none { it.uri == old.uri } }.forEach { LibraryFolders.release(appContext, it) }
        folders = updated
        LibraryFolders.save(appContext, updated)
    }

    fun startScan(context: Context, restart: Boolean = false) {
        val appContext = context.applicationContext
        if (scanJob?.isActive == true) {
            if (!restart) return
            scanJob?.cancel()
        }
        val auto = source == LibrarySource.AUTO
        val enabled = folders.filter { it.enabled }
        if (!hasSource) {
            videos = emptyList()
            scanState = LibraryScanState.IDLE
            progress = null
            scope.launch(Dispatchers.IO) { writeCache(appContext, emptyList()) }
            return
        }
        if (videos.isEmpty()) scanState = LibraryScanState.SCANNING
        val cache = videos.associateBy { it.uri }
        scanJob = scope.launch {
            var lastPublish = 0L
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val onProgress: (ScanProgress) -> Unit = { update ->
                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= 120 || update.processed == update.total) {
                            lastPublish = now
                            scope.launch { progress = update }
                        }
                    }
                    val onPartial: (List<VideoItem>) -> Unit = { partial ->
                        scope.launch {
                            if (videos.isEmpty() || scanState != LibraryScanState.LOADED) {
                                videos = partial
                                if (partial.isNotEmpty()) scanState = LibraryScanState.LOADED
                            }
                        }
                    }
                    if (auto) {
                        VideoScanner.scanMediaStore(appContext, cache, onProgress, onPartial)
                    } else {
                        VideoScanner.scan(appContext, enabled, cache, onProgress, onPartial)
                    }
                }
            }
            result.onSuccess { items ->
                videos = items
                scanState = if (items.isEmpty()) LibraryScanState.EMPTY else LibraryScanState.LOADED
                lastScanFinished = System.currentTimeMillis()
                withContext(Dispatchers.IO) { writeCache(appContext, items) }
            }
            result.onFailure { error ->
                if (error !is CancellationException && videos.isEmpty()) scanState = LibraryScanState.EMPTY
            }
            progress = null
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun readCache(context: Context): List<VideoItem> = runCatching {
        val file = cacheFile(context)
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                add(
                    VideoItem(
                        uri = json.optString("uri"),
                        documentId = json.optString("id"),
                        name = json.optString("name"),
                        title = json.optString("title"),
                        folderId = json.optString("folderId"),
                        folderName = json.optString("folderName"),
                        folderPath = json.optString("folderPath"),
                        size = json.optLong("size"),
                        modified = json.optLong("modified"),
                        mime = json.optString("mime").ifEmpty { null },
                        durationMs = json.optLong("duration"),
                        width = json.optInt("width"),
                        height = json.optInt("height")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeCache(context: Context, items: List<VideoItem>) {
        runCatching {
            val array = JSONArray()
            items.forEach { item ->
                array.put(
                    JSONObject()
                        .put("uri", item.uri)
                        .put("id", item.documentId)
                        .put("name", item.name)
                        .put("title", item.title)
                        .put("folderId", item.folderId)
                        .put("folderName", item.folderName)
                        .put("folderPath", item.folderPath)
                        .put("size", item.size)
                        .put("modified", item.modified)
                        .put("mime", item.mime ?: "")
                        .put("duration", item.durationMs)
                        .put("width", item.width)
                        .put("height", item.height)
                )
            }
            val target = cacheFile(context)
            val temp = File(target.parentFile, "$CACHE_FILE.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        }
    }
}
