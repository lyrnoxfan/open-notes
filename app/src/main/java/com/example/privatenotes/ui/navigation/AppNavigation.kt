package com.example.privatenotes.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.example.privatenotes.feature.tags.TagManagementScreen
import com.example.privatenotes.feature.tags.TagManagementViewModel
import com.example.privatenotes.feature.trash.TrashScreen
import com.example.privatenotes.feature.trash.TrashViewModel

/** design.md §7: two durations only. */
private const val MOTION_SCREEN_MS = 150

object Routes {
    const val NOTES = "notes"
    const val TRASH = "trash"
    const val BACKUP = "backup"
    const val TAGS = "tags"
    const val EDITOR = "editor"
    const val ARG_NOTE_ID = "noteId"

    const val EDITOR_PATTERN = "$EDITOR?$ARG_NOTE_ID={$ARG_NOTE_ID}"

    fun editor(noteId: String? = null): String = if (noteId == null) EDITOR else "$EDITOR?$ARG_NOTE_ID=$noteId"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val navController = rememberNavController()

    // design.md §7: screens cross-fade in 150ms. The default slide would be a second motion
    // vocabulary on a page that has none.
    NavHost(
        navController = navController,
        startDestination = Routes.NOTES,
        enterTransition = { fadeIn(tween(MOTION_SCREEN_MS)) },
        exitTransition = { fadeOut(tween(MOTION_SCREEN_MS)) },
        popEnterTransition = { fadeIn(tween(MOTION_SCREEN_MS)) },
        popExitTransition = { fadeOut(tween(MOTION_SCREEN_MS)) },
    ) {
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
                    onPersisted = { container.noteSyncCoordinator.onNotePersisted() },
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
                onOpenTags = { navController.navigate(Routes.TAGS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.TAGS) {
            val tagViewModel: TagManagementViewModel = viewModel(
                factory = TagManagementViewModel.factory({ container.tagRepository }),
            )
            TagManagementScreen(
                viewModel = tagViewModel,
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
