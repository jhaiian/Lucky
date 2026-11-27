package com.jhaiian.lucky.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

sealed interface FileChange {
    data class Done(val uri: String) : FileChange
    class NeedsConsent(val sender: IntentSender) : FileChange
    data object NeedsStoragePermission : FileChange
    data object NoFolderAccess : FileChange
    data object NameTaken : FileChange
    data object Failed : FileChange
}

object VideoFiles {
    private val forbidden = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun cleanName(input: String): String = input.replace(forbidden, "").trim().trimEnd('.').trim()

    fun extensionOf(name: String): String {
        val extension = name.substringAfterLast('.', "")
        return if (extension.isEmpty() || extension.length == name.length - 1 && name.startsWith('.')) "" else ".$extension"
    }

    fun baseNameOf(name: String): String = name.removeSuffix(extensionOf(name))

    fun shareIntent(item: VideoItem, chooserTitle: String): Intent {
        val uri = Uri.parse(item.uri)
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime?.takeIf { it.startsWith("video/") } ?: "video/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(item.name, uri)
        return Intent.createChooser(send, chooserTitle)
    }

    private fun isMediaStore(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    private fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun dataPath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    fun delete(context: Context, uri: Uri, consented: Boolean): FileChange {
        val resolver = context.contentResolver
        if (!isMediaStore(uri)) {
            return try {
                if (DocumentsContract.deleteDocument(resolver, uri)) FileChange.Done(uri.toString()) else FileChange.Failed
            } catch (_: SecurityException) {
                FileChange.NoFolderAccess
            } catch (_: Exception) {
                FileChange.Failed
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (consented) return FileChange.Done(uri.toString())
            return runCatching<FileChange> {
                FileChange.NeedsConsent(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender)
            }.getOrDefault(FileChange.Failed)
        }
        if (needsLegacyPermission(context)) return FileChange.NeedsStoragePermission
        return try {
            val path = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) dataPath(context, uri) else null
            resolver.delete(uri, null, null)
            val leftover = path?.let(::File)?.takeIf { it.exists() }
            if (leftover != null && !leftover.delete()) FileChange.Failed else FileChange.Done(uri.toString())
        } catch (error: RecoverableSecurityException) {
            if (consented) FileChange.Failed else FileChange.NeedsConsent(error.userAction.actionIntent.intentSender)
        } catch (_: Exception) {
            FileChange.Failed
        }
    }

    @Suppress("DEPRECATION")
    fun rename(context: Context, uri: Uri, newName: String, consented: Boolean): FileChange {
        val resolver = context.contentResolver
        if (!isMediaStore(uri)) {
            return try {
                val renamed = DocumentsContract.renameDocument(resolver, uri, newName)
                if (renamed != null) FileChange.Done(renamed.toString()) else FileChange.Failed
            } catch (_: SecurityException) {
                FileChange.NoFolderAccess
            } catch (_: IllegalStateException) {
                FileChange.NameTaken
            } catch (_: Exception) {
                FileChange.Failed
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (needsLegacyPermission(context)) return FileChange.NeedsStoragePermission
            val source = dataPath(context, uri)?.let(::File) ?: return FileChange.Failed
            val target = File(source.parentFile, newName)
            if (target.exists()) return FileChange.NameTaken
            if (!source.renameTo(target)) return FileChange.Failed
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                put(MediaStore.MediaColumns.TITLE, baseNameOf(newName))
            }
            runCatching { resolver.update(uri, values, null, null) }
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(source.absolutePath, target.absolutePath), null, null)
            return FileChange.Done(uri.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !consented) {
            return runCatching<FileChange> {
                FileChange.NeedsConsent(MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender)
            }.getOrDefault(FileChange.Failed)
        }
        return try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            if (resolver.update(uri, values, null, null) > 0) FileChange.Done(uri.toString()) else FileChange.Failed
        } catch (error: RecoverableSecurityException) {
            if (consented) FileChange.Failed else FileChange.NeedsConsent(error.userAction.actionIntent.intentSender)
        } catch (_: IllegalArgumentException) {
            FileChange.NameTaken
        } catch (_: Exception) {
            FileChange.Failed
        }
    }
}
