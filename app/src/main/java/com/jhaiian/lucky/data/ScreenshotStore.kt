package com.jhaiian.lucky.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotStore {

    const val FOLDER = "Lucky"
    const val DISPLAY_PATH = "Pictures/$FOLDER"
    private const val MIME_TYPE = "image/png"

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun save(context: Context, bitmap: Bitmap): Uri? {
        val name = "Lucky_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".png"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(context, bitmap, name) else saveLegacy(context, bitmap, name)
        }.getOrNull()
    }

    fun openIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, bitmap: Bitmap, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        val written = try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        } catch (error: Exception) {
            false
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, name: String): Uri? {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, name)
        val written = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!written) {
            file.delete()
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) file.delete()
        return uri
    }
}
