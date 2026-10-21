package com.example.privatenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.privatenotes.ui.navigation.AppNavigation
import com.example.privatenotes.ui.theme.PrivateNotesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as PrivateNotesApp).container

        setContent {
            PrivateNotesTheme {
                AppNavigation(container = container)
            }
        }
    }
}
