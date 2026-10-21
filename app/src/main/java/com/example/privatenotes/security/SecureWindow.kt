package com.example.privatenotes.security

import android.app.Activity
import android.view.WindowManager

/**
 * Screenshot and recents-preview protection (§6.2).
 *
 * `FLAG_SECURE` is set unconditionally on the app window. Every screen in this app — list,
 * editor, trash, lock — either shows private text or is a step away from it, so there is no
 * window where leaving screenshots enabled is correct. The flag makes the system refuse
 * screenshots and screen recordings, and blanks the task preview in recents.
 */
object SecureWindow {

    fun apply(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
