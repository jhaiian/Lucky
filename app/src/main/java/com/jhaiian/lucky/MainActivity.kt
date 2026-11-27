package com.jhaiian.lucky

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.ExternalRequest
import com.jhaiian.lucky.data.ExternalVideo
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.SetupState
import com.jhaiian.lucky.playback.CompositionKeeper
import com.jhaiian.lucky.ui.LuckyApp
import com.jhaiian.lucky.ui.theme.LuckyComposeTheme

class MainActivity : LuckyActivity() {

    private var externalRequest by mutableStateOf<ExternalRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = if (savedInstanceState == null) ExternalVideo.uriFrom(intent) else null
        if (savedInstanceState == null && incoming == null && !SetupState.isDone(this)) {
            openSetup()
            return
        }
        if (incoming != null) externalRequest = ExternalRequest(SystemClock.uptimeMillis(), incoming)
        val prefs = LuckyPrefs.get(this)
        LibraryStore.ensureLoaded(this)
        val keeper = CompositionKeeper(this, this)
        setContent(parent = keeper.recomposer) {
            LuckyComposeTheme(theme = prefs.getString(LuckyPrefs.THEME, "system") ?: "system") {
                LuckyApp(
                    external = externalRequest,
                    onExternalHandled = { handled -> if (externalRequest?.id == handled) externalRequest = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = ExternalVideo.uriFrom(intent)
        if (incoming != null) {
            externalRequest = ExternalRequest(SystemClock.uptimeMillis(), incoming)
        } else if (!SetupState.isDone(this)) {
            openSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        LibraryStore.refreshAccess(this, rescanPartial = true)
    }

    private fun openSetup() {
        val options = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(Intent(this, SetupActivity::class.java), options.toBundle())
        finish()
    }
}
