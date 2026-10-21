package com.lyrnox.opennotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyrnox.opennotes.ui.notes.NotesScreen
import com.lyrnox.opennotes.ui.notes.NotesViewModel
import com.lyrnox.opennotes.ui.theme.OpenNotesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as OpenNotesApp).container

        setContent {
            OpenNotesTheme {
                val notesViewModel: NotesViewModel = viewModel(
                    factory = NotesViewModel.factory { container.noteRepository },
                )
                NotesScreen(viewModel = notesViewModel)
            }
        }
    }
}
