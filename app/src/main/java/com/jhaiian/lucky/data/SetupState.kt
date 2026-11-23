package com.jhaiian.lucky.data

import android.content.Context

object SetupState {
    private const val PREFS_NAME = "first_run"
    private const val KEY_DONE = "done"
    private const val KEY_STARTED = "started"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDone(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_DONE, false)) return true
        if (!prefs.getBoolean(KEY_STARTED, false) && LuckyPrefs.get(context).contains(LuckyPrefs.LIBRARY_SOURCE)) {
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            return true
        }
        return false
    }

    fun markStarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_STARTED, true).apply()
    }

    fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }
}
