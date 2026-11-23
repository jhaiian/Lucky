package com.jhaiian.lucky

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.SetupState
import com.jhaiian.lucky.ui.ThemeRevealHolder
import com.jhaiian.lucky.ui.setup.SetupScreen
import com.jhaiian.lucky.ui.theme.LuckyComposeTheme
import com.jhaiian.lucky.ui.theme.ThemeMode

class SetupActivity : LuckyActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val restoringLook = ThemeRevealHolder.bitmap != null
        super.onCreate(savedInstanceState)
        SetupState.markStarted(this)
        LibraryStore.ensureLoaded(this)
        val prefs = LuckyPrefs.get(this)
        setContent {
            LuckyComposeTheme(theme = prefs.getString(LuckyPrefs.THEME, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM) {
                SetupScreen(skipIntro = restoringLook, onFinish = { completeSetup() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LibraryStore.refreshAccess(this)
    }

    private fun completeSetup() {
        if (isFinishing) return
        SetupState.markDone(this)
        if (LibraryStore.hasSource && !LibraryStore.scanning) LibraryStore.startScan(this, restart = true)
        val options = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(Intent(this, MainActivity::class.java), options.toBundle())
        finish()
    }
}
