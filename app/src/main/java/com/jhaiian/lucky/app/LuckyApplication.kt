package com.jhaiian.lucky.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.jhaiian.lucky.crash.CrashHandler
import com.jhaiian.lucky.ui.theme.SystemDarkState
import com.jhaiian.lucky.util.LocaleHelper

class LuckyApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        applyNightMode()
        SystemDarkState.install(this)
    }

    fun applyNightMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = prefs.getString("app_theme", "system") ?: "system"
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
