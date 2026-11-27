package com.jhaiian.lucky.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract

data class VideoFolder(
    val uri: Uri,
    val displayName: String,
    val locationLabel: String,
    val enabled: Boolean = true
)

data class DetectedVolume(
    val id: String,
    val label: String,
    val intent: Intent?
)

object LibraryFolders {
    private const val ENTRY_SEPARATOR = '\u001F'
    private const val FIELD_SEPARATOR = '\u001E'

    fun load(context: Context): List<VideoFolder> {
        val raw = LuckyPrefs.get(context).getString(LuckyPrefs.LIBRARY_FOLDERS, null) ?: return emptyList()
        return raw.split(ENTRY_SEPARATOR).mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size < 4 || fields[0].isBlank()) return@mapNotNull null
            VideoFolder(Uri.parse(fields[0]), fields[1], fields[2], fields[3] != "0")
        }
    }

    fun save(context: Context, folders: List<VideoFolder>) {
        val raw = folders.joinToString(ENTRY_SEPARATOR.toString()) { folder ->
            listOf(folder.uri.toString(), folder.displayName, folder.locationLabel, if (folder.enabled) "1" else "0")
                .joinToString(FIELD_SEPARATOR.toString())
        }
        LuckyPrefs.get(context).edit().putString(LuckyPrefs.LIBRARY_FOLDERS, raw).apply()
    }

    fun describe(context: Context, treeUri: Uri, internalLabel: String, externalLabel: String): VideoFolder {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val name = documentId?.let { id ->
            runCatching {
                context.contentResolver.query(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        } ?: documentId?.substringAfterLast(':')?.ifBlank { null } ?: treeUri.lastPathSegment.orEmpty()
        val label = if (documentId?.startsWith("primary") == true) internalLabel else externalLabel
        return VideoFolder(treeUri, name, label, true)
    }

    fun release(context: Context, folder: VideoFolder) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                folder.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(folder.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun detectVolumes(context: Context, internalLabel: String, externalLabel: String): List<DetectedVolume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return runCatching {
            manager.storageVolumes.mapIndexed { index, volume ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) volume.createOpenDocumentTreeIntent() else null
                val label = volume.getDescription(context)?.takeIf { it.isNotBlank() }
                    ?: if (volume.isPrimary) internalLabel else externalLabel
                DetectedVolume(volume.uuid ?: if (volume.isPrimary) "primary" else "volume$index", label, intent)
            }
        }.getOrDefault(emptyList())
    }
}
