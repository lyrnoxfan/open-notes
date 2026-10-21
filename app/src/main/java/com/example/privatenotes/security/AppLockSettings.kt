package com.example.privatenotes.security

import android.content.Context

/**
 * Persistence for the app-lock switch.
 *
 * Only a boolean lives here. Nothing about the user's credential is stored: the system owns it,
 * and this flag never makes a locked database readable on its own.
 */
interface AppLockSettings {
    var isEnabled: Boolean
}

class SharedPreferencesAppLockSettings(context: Context) : AppLockSettings {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        const val FILE_NAME = "app_lock"
        private const val KEY_ENABLED = "enabled"
    }
}
