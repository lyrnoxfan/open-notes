package com.example.privatenotes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

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
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.appLock.onBackground()
                }
            },
        )
    }
}
