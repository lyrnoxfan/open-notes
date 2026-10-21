package com.example.privatenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.privatenotes.feature.notes.NoteListScreen
import com.example.privatenotes.feature.notes.NoteListViewModel
import com.example.privatenotes.ui.theme.PrivateNotesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as PrivateNotesApp).container

        setContent {
            PrivateNotesTheme {
                val noteListViewModel: NoteListViewModel = viewModel(
                    factory = NoteListViewModel.factory { container.noteRepository },
                )
                NoteListScreen(viewModel = noteListViewModel)
            }
        }
    }
}
