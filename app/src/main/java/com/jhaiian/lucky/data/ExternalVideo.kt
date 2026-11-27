package com.jhaiian.lucky.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator

data class ExternalRequest(val id: Long, val uri: Uri)

data class ExternalPlayback(val uri: String, val title: String?, val queue: List<QueueItem>, val transient: Boolean)

object ExternalVideo {
    private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
    private const val LIBRARY_POLLS = 12
    private const val LIBRARY_POLL_MS = 50L

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    fun uriFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        } ?: return null
        return uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
    }

    suspend fun resolve(context: Context, uri: Uri): ExternalPlayback = withContext(Dispatchers.IO) {
        val persisted = uri.scheme == ContentResolver.SCHEME_CONTENT && runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        val (name, size) = describe(context, uri)
        val library = awaitLibrary()
        val match = mediaStoreMatch(uri, library) ?: nameMatch(library, name, size)
        if (match != null) {
            val siblings = library
                .filter { it.folderId == match.folderId }
                .sortedWith { a, b -> collator.compare(a.name, b.name) }
            ExternalPlayback(match.uri, match.title, siblings.map { QueueItem(it.uri, it.title) }, false)
        } else {
            val title = name?.substringBeforeLast('.', name)?.ifBlank { null }
            ExternalPlayback(uri.toString(), title, listOf(QueueItem(uri.toString(), title)), !persisted && uri.scheme == ContentResolver.SCHEME_CONTENT)
        }
    }

    private suspend fun awaitLibrary(): List<VideoItem> {
        repeat(LIBRARY_POLLS) {
            val current = LibraryStore.videos
            if (current.isNotEmpty()) return current
            delay(LIBRARY_POLL_MS)
        }
        return LibraryStore.videos
    }

    private fun describe(context: Context, uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File)
            return file?.name to file?.takeIf { it.exists() }?.length()
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }.getOrNull() ?: (uri.lastPathSegment?.substringAfterLast('/') to null)
    }

    private fun mediaId(uri: Uri): Long? = when (uri.authority) {
        MediaStore.AUTHORITY -> {
            val segments = uri.pathSegments
            when {
                segments.size == 4 && segments[1] == "video" && segments[2] == "media" -> segments[3].toLongOrNull()
                segments.size == 3 && segments[1] == "file" -> segments[2].toLongOrNull()
                else -> null
            }
        }
        MEDIA_DOCUMENTS -> runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?.takeIf { it.startsWith("video:") }
            ?.substringAfter(':')
            ?.toLongOrNull()
        else -> null
    }

    private fun mediaStoreMatch(uri: Uri, library: List<VideoItem>): VideoItem? {
        val id = mediaId(uri) ?: return null
        val canonical = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
        return library.firstOrNull { it.uri == canonical }
    }

    private fun nameMatch(library: List<VideoItem>, name: String?, size: Long?): VideoItem? {
        if (name.isNullOrBlank()) return null
        val named = library.filter { it.name == name }
        return if (size != null && size > 0L) {
            named.firstOrNull { it.size == size }
        } else {
            named.singleOrNull()
        }
    }
}
