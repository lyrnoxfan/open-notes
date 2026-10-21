package com.example.privatenotes.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch has to survive a process restart: a lock that forgets it was enabled on cold start
 * is not a lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences(
            SharedPreferencesAppLockSettings.FILE_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun `defaults to disabled`() {
        assertFalse(SharedPreferencesAppLockSettings(context).isEnabled)
    }

    @Test
    fun `the switch is visible to a new instance`() {
        SharedPreferencesAppLockSettings(context).isEnabled = true

        assertTrue(SharedPreferencesAppLockSettings(context).isEnabled)
    }

    @Test
    fun `turning the lock off is persisted too`() {
        SharedPreferencesAppLockSettings(context).apply { isEnabled = true }
        SharedPreferencesAppLockSettings(context).isEnabled = false

        assertFalse(SharedPreferencesAppLockSettings(context).isEnabled)
    }
}
