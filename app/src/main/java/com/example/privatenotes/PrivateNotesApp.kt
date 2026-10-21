package com.example.privatenotes

import android.app.Application
import kotlinx.coroutines.launch
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.privatenotes.security.AppLockState

class PrivateNotesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // §6.2: the 30-second grace window is measured at the process level, not per Activity.
        // ProcessLifecycleOwner ignores configuration changes, so rotating the device or opening
        // a dialog never counts as leaving the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.appLock.onForeground()
                    // §9.4: a launch is also a chance to notice that yesterday's backup never
                    // ran. Idempotent, and does nothing until a destination is configured.
                    container.applicationScope.launch {
                        // Enqueueing is free, but a worker would open the database; only ask for
                        // the catch-up when the lock is satisfied.
                        if (container.appLock.state.value == AppLockState.Unlocked) {
                            runCatching { container.backupScheduler.onAppForeground() }
                        }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.appLock.onBackground()
                }
            },
        )
    }
}
