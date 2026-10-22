package com.example.privatenotes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.privatenotes.AppContainer
import com.example.privatenotes.feature.backup.BackupSettingsScreen
import com.example.privatenotes.feature.backup.BackupSettingsViewModel
import com.example.privatenotes.feature.notes.NoteEditorScreen
import com.example.privatenotes.feature.notes.NoteEditorViewModel
import com.example.privatenotes.feature.notes.NoteListScreen
import com.example.privatenotes.feature.notes.NoteListViewModel
import com.example.privatenotes.feature.trash.TrashScreen
import com.example.privatenotes.feature.trash.TrashViewModel

object Routes {
    const val NOTES = "notes"
    const val TRASH = "trash"
    const val BACKUP = "backup"
    const val EDITOR = "editor"
    const val ARG_NOTE_ID = "noteId"

    const val EDITOR_PATTERN = "$EDITOR?$ARG_NOTE_ID={$ARG_NOTE_ID}"

    fun editor(noteId: String? = null): String = if (noteId == null) EDITOR else "$EDITOR?$ARG_NOTE_ID=$noteId"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.NOTES) {
        composable(Routes.NOTES) {
            val listViewModel: NoteListViewModel = viewModel(
                factory = NoteListViewModel.factory(
                    repositoryProvider = { container.noteRepository },
                    tagRepositoryProvider = { container.tagRepository },
                ),
            )
            NoteListScreen(
                viewModel = listViewModel,
                onCreateNote = { navController.navigate(Routes.editor()) },
                onOpenNote = { noteId -> navController.navigate(Routes.editor(noteId)) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenSettings = { navController.navigate(Routes.BACKUP) },
            )
        }

        composable(
            route = Routes.EDITOR_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_NOTE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString(Routes.ARG_NOTE_ID)
            val editorViewModel: NoteEditorViewModel = viewModel(
                factory = NoteEditorViewModel.factory(
                    repository = container.noteRepository,
                    tagRepository = container.tagRepository,
                    noteId = noteId,
                    timeSource = container.timeSource,
                    externalScope = container.applicationScope,
                ),
            )
            NoteEditorScreen(
                viewModel = editorViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BACKUP) {
            val backupViewModel: BackupSettingsViewModel = viewModel(
                factory = BackupSettingsViewModel.factory(container),
            )
            BackupSettingsScreen(
                viewModel = backupViewModel,
                appLock = container.appLock,
                authenticator = container.appLockAuthenticator,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.TRASH) {
            val trashViewModel: TrashViewModel = viewModel(
                factory = TrashViewModel.factory(container.noteRepository, container.timeSource),
            )
            TrashScreen(
                viewModel = trashViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
