package com.jhaiian.lucky.ui.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

private val SubtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "smi", "sup", "ttml", "lrc")

data class SidecarSubtitle(val uri: Uri, val name: String)

fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

private fun stem(name: String): String = name.substringBeforeLast('.', name).lowercase(Locale.ROOT)

fun findSidecarSubtitles(context: Context, video: Uri): List<SidecarSubtitle> = when (video.authority) {
    EXTERNAL_STORAGE_AUTHORITY -> treeSidecars(context, video)
    MediaStore.AUTHORITY -> mediaStoreSidecars(context, video)
    else -> emptyList()
}

private fun matchSidecars(videoName: String, siblings: List<SidecarSubtitle>): List<SidecarSubtitle> {
    val videoStem = stem(videoName)
    if (videoStem.isEmpty()) return emptyList()
    return siblings.filter { sibling ->
        val extension = sibling.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in SubtitleExtensions) return@filter false
        val subtitleStem = stem(sibling.name)
        subtitleStem == videoStem || subtitleStem.startsWith("$videoStem.") || subtitleStem.startsWith("${videoStem}_")
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
}

private fun treeSidecars(context: Context, video: Uri): List<SidecarSubtitle> {
    val documentId = runCatching { DocumentsContract.getDocumentId(video) }.getOrNull() ?: return emptyList()
    runCatching { DocumentsContract.getTreeDocumentId(video) }.getOrNull() ?: return emptyList()
    val parentId = when {
        documentId.contains('/') -> documentId.substringBeforeLast('/')
        documentId.contains(':') -> documentId.substringBefore(':') + ":"
        else -> return emptyList()
    }
    val videoName = displayName(context, video) ?: documentId.substringAfterLast('/').substringAfterLast(':')
    val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(video, parentId) }.getOrNull() ?: return emptyList()
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    val siblings = mutableListOf<SidecarSubtitle>()
    runCatching {
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                siblings += SidecarSubtitle(DocumentsContract.buildDocumentUriUsingTree(video, id), name)
            }
        }
    }
    return matchSidecars(videoName, siblings)
}

@Suppress("DEPRECATION")
private fun mediaStoreSidecars(context: Context, video: Uri): List<SidecarSubtitle> {
    val resolver = context.contentResolver
    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.Video.Media.BUCKET_ID)
    val row = runCatching {
        resolver.query(video, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)) else null
        }
    }.getOrNull() ?: return emptyList()
    val (name, data, bucket) = row
    val videoName = name ?: data?.substringAfterLast('/') ?: return emptyList()
    val fromDisk = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && data != null) {
        runCatching {
            File(data).parentFile?.listFiles()?.filter { it.isFile }?.map { SidecarSubtitle(Uri.fromFile(it), it.name) }
        }.getOrNull().orEmpty()
    } else {
        emptyList()
    }
    val siblings = fromDisk.ifEmpty {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || bucket == null) return@ifEmpty emptyList()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val found = mutableListOf<SidecarSubtitle>()
        runCatching {
            resolver.query(
                files,
                arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME),
                "${MediaStore.Files.FileColumns.BUCKET_ID} = ?",
                arrayOf(bucket),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val sibling = cursor.getString(1) ?: continue
                    found += SidecarSubtitle(ContentUris.withAppendedId(files, cursor.getLong(0)), sibling)
                }
            }
        }
        found
    }
    return matchSidecars(videoName, siblings)
}

private val NotLanguages = setOf("sdh", "cc", "mkv", "mp4", "avi", "mov", "wmv", "m4v", "ts", "flv")

fun sidecarLanguage(name: String): String? =
    stem(name).split('.').drop(1).reversed().firstOrNull { part ->
        (part.length == 2 || part.length == 3) && part.all { it.isLetter() } && part !in NotLanguages
    }
