package com.example.privatenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.privatenotes.ui.navigation.AppNavigation
import com.example.privatenotes.ui.theme.PrivateNotesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        container = (application as PrivateNotesApp).container

        setContent {
            PrivateNotesTheme {
                AppNavigation(container = container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground is the reliable moment to catch up; Android owes the
        // app no execution time at exit (§9.4).
        container.applicationScope.launch {
            runCatching { container.backupScheduler.onAppForeground() }
        }
    }
}
