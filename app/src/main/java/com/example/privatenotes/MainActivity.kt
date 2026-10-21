package com.example.privatenotes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.example.privatenotes.security.SecureWindow
import com.example.privatenotes.ui.AppRoot
import com.example.privatenotes.ui.theme.PrivateNotesTheme

/**
 * FragmentActivity rather than ComponentActivity: `BiometricPrompt` is constructed against a
 * FragmentActivity, and the app lock is not optional plumbing (§6.2).
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // §6.2: screenshots and the recents preview never show note content. Applied before the
        // first frame so no window is ever unsecure, including the lock screen itself.
        SecureWindow.apply(this)

        val container = (application as PrivateNotesApp).container

        setContent {
            PrivateNotesTheme {
                AppRoot(container = container)
            }
        }
    }
}
