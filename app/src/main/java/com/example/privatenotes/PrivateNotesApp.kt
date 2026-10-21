package com.example.privatenotes

import android.app.Application
import kotlinx.coroutines.launch

class PrivateNotesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // §8: notes past the 30-day trash window are cleared automatically. Doing it at launch
        // means it happens even if the user never opens the trash screen.
        container.applicationScope.launch {
            runCatching {
                container.noteRepository.purgeExpiredTrash(container.timeSource.nowMillis())
            }
        }

        // §9.4: a launch is also a chance to notice that yesterday's backup never ran. The
        // call is idempotent and does nothing until a destination has been configured.
        container.applicationScope.launch {
            runCatching { container.backupScheduler.onAppForeground() }
        }
    }
}
