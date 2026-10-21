package com.example.privatenotes

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.feature.lock.LockTags
import com.example.privatenotes.feature.notes.NoteListTags
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-4 acceptance against the real Activity, the real `ProcessLifecycleOwner` wiring and the
 * real encrypted database.
 *
 * The biometric prompt itself is a system surface; what these tests pin down is everything around
 * it: the window is secure, the 30-second window is measured by the process lifecycle, a lock
 * hides the notes and drops the database connection, and the data survives that close.
 */
@RunWith(AndroidJUnit4::class)
class AppLockIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    private val lock: AppLock
        get() = app.container.appLock

    @Before
    fun armTheLock() {
        // The container builds the lock during Application.onCreate, before a test can touch the
        // persisted flag; enabling here is the same call the settings dialog makes.
        lock.setEnabled(true)
        lock.unlock()
    }

    @After
    fun disarmTheLock() {
        lock.setEnabled(false)
        lock.unlock()
    }

    @Test
    fun `screenshots and the recents preview are blocked`() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertTrue(
                "FLAG_SECURE is what blanks the screenshot and the recents thumbnail",
                flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    @Test
    fun `returning after more than 30 seconds requires authentication`() {
        composeRule.onNodeWithTag(NoteListTags.CREATE).assertExists()
        assertEquals(AppLockState.Unlocked, lock.state.value)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(GRACE_MILLIS + SETTLE_MILLIS)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        awaitState(AppLockState.Locked)
        composeRule.onNodeWithTag(LockTags.SCREEN).assertExists()
        composeRule.onNodeWithTag(NoteListTags.CREATE).assertDoesNotExist()

        // §6.2: a locked app also drops the database connection.
        assertFalse(app.container.isDatabaseOpen)
    }

    @Test
    fun `returning within the grace window does not require authentication`() {
        composeRule.onNodeWithTag(NoteListTags.CREATE).assertExists()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(SHORT_ABSENCE_MILLIS)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // Give the process lifecycle callbacks a moment before judging.
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(AppLockState.Unlocked, lock.state.value)
        composeRule.onNodeWithTag(NoteListTags.CREATE).assertExists()
        composeRule.onNodeWithTag(LockTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `closing the database for the lock does not cost a saved note`() = runBlocking {
        val repository = app.container.noteRepository
        val id = repository.createNote("锁定前", "写下的内容")
        assertTrue(app.container.isDatabaseOpen)

        app.container.closeDatabase()
        assertFalse(app.container.isDatabaseOpen)

        val reopened = app.container.noteRepository.findById(id)
        assertNotNull("the key still opens the file after the lock closed it", reopened)
        assertEquals("写下的内容", reopened!!.content)
    }

    private fun awaitState(expected: AppLockState, timeoutMillis: Long = 5_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (lock.state.value == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, lock.state.value)
    }

    companion object {
        private const val GRACE_MILLIS = 30_000L
        private const val SETTLE_MILLIS = 2_000L
        private const val SHORT_ABSENCE_MILLIS = 3_000L

        /**
         * The Activity rule launches MainActivity before any `@Before` runs. If a previous run
         * left the lock enabled, the app would start on the lock screen — and, on a device with a
         * credential, with a system prompt over it. Start each class from a known unlocked state.
         */
        @JvmStatic
        @BeforeClass
        fun startUnlocked() {
            val application = ApplicationProvider.getApplicationContext<PrivateNotesApp>()
            application.container.appLock.setEnabled(false)
            application.container.appLock.unlock()
        }
    }
}
