package com.jhaiian.lucky.ui.library

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.VideoAccess
import com.jhaiian.lucky.data.VideoAccessLevel
import com.jhaiian.lucky.ui.findActivity

class VideoAccessRequest(val blocked: Boolean, private val onRequest: () -> Unit) {
    fun request() = onRequest()
}

@Composable
fun rememberVideoAccessRequest(onResult: (VideoAccessLevel) -> Unit = {}): VideoAccessRequest {
    val context = LocalContext.current
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val level = LibraryStore.refreshAccess(context, rescanPartial = true)
        val activity = context.findActivity()
        blocked = level == VideoAccessLevel.NONE && activity != null &&
            VideoAccess.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        onResult(level)
    }
    val currentlyBlocked = blocked && LibraryStore.access == VideoAccessLevel.NONE
    return VideoAccessRequest(currentlyBlocked) {
        if (currentlyBlocked) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            runCatching { launcher.launch(VideoAccess.permissions) }
        }
    }
}
