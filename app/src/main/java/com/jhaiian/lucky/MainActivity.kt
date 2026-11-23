package com.jhaiian.lucky

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.SetupState
import com.jhaiian.lucky.ui.LuckyApp
import com.jhaiian.lucky.ui.theme.LuckyComposeTheme

class MainActivity : LuckyActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !SetupState.isDone(this)) {
            val options = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
            startActivity(Intent(this, SetupActivity::class.java), options.toBundle())
            finish()
            return
        }
        val prefs = LuckyPrefs.get(this)
        LibraryStore.ensureLoaded(this)
        setContent {
            LuckyComposeTheme(theme = prefs.getString(LuckyPrefs.THEME, "system") ?: "system") {
                LuckyApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LibraryStore.refreshAccess(this, rescanPartial = true)
    }
}
