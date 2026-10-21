package com.example.privatenotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.example.privatenotes.AppContainer
import com.example.privatenotes.feature.lock.LockScreen
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import com.example.privatenotes.security.AppLockState
import com.example.privatenotes.ui.navigation.AppNavigation
import kotlinx.coroutines.launch

/**
 * The gate in front of everything sensitive (§6.2).
 *
 * While locked, the content is not composed at all: note text, editors and their in-memory state
 * are dropped rather than hidden behind an overlay. The database is closed by the container at
 * the same moment.
 */
@Composable
fun AppRoot(container: AppContainer) {
    AppLockGate(
        lock = container.appLock,
        authenticator = container.appLockAuthenticator,
    ) {
        // Moved here from Application.onCreate: opening the database before authentication would
        // defeat the point of closing it on lock.
        LaunchedEffect(Unit) {
            container.applicationScope.launch {
                runCatching {
                    container.noteRepository.purgeExpiredTrash(container.timeSource.nowMillis())
                }
            }
        }
        AppNavigation(container = container)
    }
}

/**
 * Shows [content] only while the lock is satisfied.
 *
 * The unlocked content gets its own ViewModel store, cleared when the lock closes again. Without
 * that, navigation's ViewModels would live in the Activity store and keep note titles in memory
 * behind the lock screen — visible to nobody, but still there. Clearing the store calls
 * `onCleared()` on every screen ViewModel, so the text really is dropped.
 *
 * Kept separate from [AppRoot] so the lock screen can be exercised against a fake [AppLock] and
 * [AppLockAuthenticator]: the authentication path itself is a system surface that no test can
 * drive reliably.
 */
@Composable
fun AppLockGate(
    lock: AppLock,
    authenticator: AppLockAuthenticator,
    content: @Composable () -> Unit,
) {
    val state by lock.state.collectAsStateWithLifecycle()

    when (state) {
        AppLockState.Locked -> LockScreen(lock = lock, authenticator = authenticator)

        AppLockState.Unlocked -> {
            val owner = remember { LockSessionViewModelStoreOwner() }
            DisposableEffect(owner) {
                onDispose { owner.viewModelStore.clear() }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                content()
            }
        }
    }
}

/**
 * A ViewModel store that belongs to one unlocked session.
 *
 * Navigation looks its ViewModels up through `LocalViewModelStoreOwner`, so providing this for the
 * duration of an unlocked session is enough to make locking able to flush them.
 */
private class LockSessionViewModelStoreOwner :
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    /** Screens pass explicit factories; this is only the fallback navigation asks for. */
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.NewInstanceFactory()
}
