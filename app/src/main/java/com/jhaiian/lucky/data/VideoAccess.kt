package com.jhaiian.lucky.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class LibrarySource(val key: String) {
    AUTO("auto"),
    FOLDERS("folders");

    companion object {
        fun fromKey(key: String?): LibrarySource? = entries.firstOrNull { it.key == key }
    }
}

enum class VideoAccessLevel { NONE, PARTIAL, FULL }

object VideoAccess {
    val permissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun level(context: Context): VideoAccessLevel {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
                granted(Manifest.permission.READ_MEDIA_VIDEO) -> VideoAccessLevel.FULL
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> VideoAccessLevel.PARTIAL
                else -> VideoAccessLevel.NONE
            }
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> VideoAccessLevel.FULL
            else -> VideoAccessLevel.NONE
        }
    }
}
