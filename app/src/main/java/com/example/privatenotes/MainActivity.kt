package com.example.privatenotes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.example.privatenotes.security.AppLockState
import com.example.privatenotes.security.SecureWindow
import kotlinx.coroutines.launch
import com.example.privatenotes.ui.AppRoot
import com.example.privatenotes.ui.theme.PrivateNotesTheme

/**
 * FragmentActivity rather than ComponentActivity: `BiometricPrompt` is constructed against a
 * FragmentActivity, and the app lock is not optional plumbing (§6.2).
 */
class MainActivity : FragmentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // §6.2: screenshots and the recents preview never show note content. Applied before the
        // first frame so no window is ever unsecure, including the lock screen itself.
        SecureWindow.apply(this)

        container = (application as PrivateNotesApp).container

        setContent {
            PrivateNotesTheme {
                // With adjustResize the keyboard arrives as an animated inset instead of the
                // system panning the whole window - panning is what exposed the bottom of the
                // document whenever the IME opened. Consuming the inset here keeps the focused
                // cursor on screen on every screen with a text field.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    AppRoot(container = container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground is the reliable moment to catch up; Android owes the app
        // no execution time at exit (§9.4).
        container.applicationScope.launch {
            // The lock may be showing; a backup run must not reopen the database behind it.
            if (container.appLock.state.value == AppLockState.Unlocked) {
                runCatching { container.backupScheduler.onAppForeground() }
            }
        }
    }
}
