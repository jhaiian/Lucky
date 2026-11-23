package com.jhaiian.lucky.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class VideoItem(
    val uri: String,
    val documentId: String,
    val name: String,
    val title: String,
    val folderId: String,
    val folderName: String,
    val folderPath: String,
    val size: Long,
    val modified: Long,
    val mime: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int
) {
    val resolutionLabel: String?
        get() {
            val shortSide = minOf(width, height)
            val longSide = maxOf(width, height)
            if (shortSide <= 0) return null
            return when {
                longSide >= 7000 || shortSide >= 4000 -> "8K"
                longSide >= 3400 || shortSide >= 2000 -> "4K"
                longSide >= 2400 || shortSide >= 1400 -> "1440p"
                longSide >= 1800 || shortSide >= 1000 -> "1080p"
                longSide >= 1200 || shortSide >= 700 -> "720p"
                else -> "${shortSide}p"
            }
        }
}

enum class ScanPhase { FINDING, READING }

data class ScanProgress(val phase: ScanPhase, val found: Int, val processed: Int, val total: Int)

object VideoScanner {
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "3gp", "3g2", "flv", "wmv", "asf",
        "mpg", "mpeg", "vob", "ogv", "rm", "rmvb", "divx", "f4v", "mxf"
    )

    private data class Found(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val mime: String?,
        val size: Long,
        val modified: Long,
        val folderId: String,
        val folderName: String,
        val folderPath: String,
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0
    )

    private val storageRoot = Regex("^/storage/(emulated/\\d+|[^/]+)/")

    private data class Child(val id: String, val name: String, val mime: String?, val size: Long, val modified: Long)

    private fun isVideo(name: String, mime: String?): Boolean {
        if (mime?.startsWith("video/") == true) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in videoExtensions
    }

    private suspend fun walk(
        context: Context,
        tree: Uri,
        documentId: String,
        folderName: String,
        folderPath: String,
        isRoot: Boolean,
        sink: ConcurrentLinkedQueue<Found>,
        found: AtomicInteger,
        permits: Semaphore,
        onFound: (Int) -> Unit
    ) {
        coroutineContext.ensureActive()
        val children = permits.withPermit {
            runCatching {
                context.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            add(
                                Child(
                                    id = id,
                                    name = cursor.getString(1).orEmpty(),
                                    mime = cursor.getString(2),
                                    size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                                    modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                                )
                            )
                        }
                    }
                }
            }.getOrNull().orEmpty()
        }
        if (!isRoot && children.any { it.name == ".nomedia" }) return
        val subfolders = mutableListOf<Pair<String, String>>()
        children.forEach { child ->
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!child.name.startsWith(".")) subfolders.add(child.id to child.name)
            } else if (!child.name.startsWith(".") && isVideo(child.name, child.mime)) {
                sink.add(
                    Found(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id),
                        documentId = child.id,
                        name = child.name,
                        mime = child.mime,
                        size = child.size,
                        modified = child.modified,
                        folderId = documentId,
                        folderName = folderName,
                        folderPath = folderPath
                    )
                )
                onFound(found.incrementAndGet())
            }
        }
        coroutineScope {
            subfolders.map { (id, name) ->
                async {
                    walk(context, tree, id, name, if (folderPath.isEmpty()) name else "$folderPath/$name", false, sink, found, permits, onFound)
                }
            }.awaitAll()
        }
    }

    private fun readMetadata(context: Context, uri: Uri): Triple<Long, Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 == 90) {
                val swap = width
                width = height
                height = swap
            }
            Triple(duration, width, height)
        } catch (_: Exception) {
            Triple(0L, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun scan(
        context: Context,
        folders: List<VideoFolder>,
        cache: Map<String, VideoItem>,
        onProgress: (ScanProgress) -> Unit,
        onPartial: (List<VideoItem>) -> Unit
    ): List<VideoItem> {
        val sink = ConcurrentLinkedQueue<Found>()
        val found = AtomicInteger(0)
        val permits = Semaphore(4)
        onProgress(ScanProgress(ScanPhase.FINDING, 0, 0, 0))
        coroutineScope {
            folders.filter { it.enabled }.map { folder ->
                async {
                    val rootId = runCatching { DocumentsContract.getTreeDocumentId(folder.uri) }.getOrNull() ?: return@async
                    walk(context, folder.uri, rootId, folder.displayName, folder.displayName, true, sink, found, permits) { count ->
                        onProgress(ScanProgress(ScanPhase.FINDING, count, 0, 0))
                    }
                }
            }.awaitAll()
        }
        return resolve(context, sink.toList(), cache, onProgress, onPartial)
    }

    @Suppress("DEPRECATION")
    suspend fun scanMediaStore(
        context: Context,
        cache: Map<String, VideoItem>,
        onProgress: (ScanProgress) -> Unit,
        onPartial: (List<VideoItem>) -> Unit
    ): List<VideoItem> {
        onProgress(ScanProgress(ScanPhase.FINDING, 0, 0, 0))
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.MIME_TYPE)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.DATA)
            if (modern) add(MediaStore.Video.Media.RELATIVE_PATH)
        }.toTypedArray()
        val discovered = mutableListOf<Found>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val id = cursor.getLong(0)
                val data = cursor.getString(10)
                val name = cursor.getString(1)?.ifBlank { null } ?: data?.substringAfterLast('/') ?: continue
                if (name.startsWith(".")) continue
                val parent = data?.substringBeforeLast('/', "")?.ifEmpty { null }
                val relative = (if (modern) cursor.getString(11) else null)?.trim('/')?.ifEmpty { null }
                    ?: parent?.replace(storageRoot, "")?.trim('/')?.ifEmpty { null }
                val folderName = cursor.getString(9)?.ifBlank { null }
                    ?: relative?.substringAfterLast('/')
                    ?: parent?.substringAfterLast('/')
                    ?: ""
                val folderId = cursor.getString(8)?.ifBlank { null } ?: parent ?: relative ?: folderName
                discovered += Found(
                    uri = ContentUris.withAppendedId(collection, id),
                    documentId = id.toString(),
                    name = name,
                    mime = cursor.getString(2),
                    size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    modified = if (cursor.isNull(4)) 0L else cursor.getLong(4) * 1000L,
                    folderId = "bucket:$folderId",
                    folderName = folderName,
                    folderPath = relative ?: folderName,
                    durationMs = if (cursor.isNull(5)) 0L else cursor.getLong(5),
                    width = if (cursor.isNull(6)) 0 else cursor.getInt(6),
                    height = if (cursor.isNull(7)) 0 else cursor.getInt(7)
                )
                if (discovered.size % 50 == 0) onProgress(ScanProgress(ScanPhase.FINDING, discovered.size, 0, 0))
            }
        }
        return resolve(context, discovered, cache, onProgress, onPartial)
    }

    private suspend fun resolve(
        context: Context,
        files: List<Found>,
        cache: Map<String, VideoItem>,
        onProgress: (ScanProgress) -> Unit,
        onPartial: (List<VideoItem>) -> Unit
    ): List<VideoItem> {
        val discovered = files.distinctBy { it.uri.toString() }
        val total = discovered.size
        val processed = AtomicInteger(0)
        val results = ConcurrentLinkedQueue<VideoItem>()
        val metadataPermits = Semaphore(3)
        onProgress(ScanProgress(ScanPhase.READING, total, 0, total))
        coroutineScope {
            discovered.map { file ->
                async {
                    coroutineContext.ensureActive()
                    val key = file.uri.toString()
                    val cached = cache[key]
                    val item = if (cached != null && cached.size == file.size && cached.modified == file.modified) {
                        cached.copy(folderName = file.folderName, folderPath = file.folderPath, folderId = file.folderId)
                    } else {
                        val (duration, width, height) = if (file.durationMs > 0L && file.width > 0 && file.height > 0) {
                            Triple(file.durationMs, file.width, file.height)
                        } else {
                            metadataPermits.withPermit { readMetadata(context, file.uri) }
                        }
                        VideoItem(
                            uri = key,
                            documentId = file.documentId,
                            name = file.name,
                            title = file.name.substringBeforeLast('.', file.name),
                            folderId = file.folderId,
                            folderName = file.folderName,
                            folderPath = file.folderPath,
                            size = file.size,
                            modified = file.modified,
                            mime = file.mime,
                            durationMs = duration,
                            width = width,
                            height = height
                        )
                    }
                    results.add(item)
                    val done = processed.incrementAndGet()
                    onProgress(ScanProgress(ScanPhase.READING, total, done, total))
                    if (done % 25 == 0) onPartial(results.toList())
                }
            }.awaitAll()
        }
        return results.toList()
    }
}
