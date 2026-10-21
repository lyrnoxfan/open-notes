package com.example.privatenotes.data.backup

import android.content.Context

/** Version stamped into every backup; purely informational for the user and for support. */
class AppVersionProvider(context: Context) {

    private val packageName = context.packageName
    private val packageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
