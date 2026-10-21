package com.example.privatenotes

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.feature.lock.LockScreen
import com.example.privatenotes.feature.lock.LockTags
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import com.example.privatenotes.security.AppLockState
import com.example.privatenotes.security.AuthFailure
import com.example.privatenotes.security.AuthPrompt
import com.example.privatenotes.security.AuthResult
import com.example.privatenotes.security.CredentialMode
import com.example.privatenotes.security.LockAvailability
import com.example.privatenotes.ui.AppLockGate
import com.example.privatenotes.ui.theme.PrivateNotesTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §6.2 acceptance around the gate and the lock screen.
 *
 * Nothing behind the gate is composed until the lock is satisfied, a cancellation keeps it closed,
 * and a device without a credential is pointed at the system settings instead of being shown an
 * unlock button that cannot work.
 *
 * The system prompt is replaced by [FakeAuthenticator]; the real one is a system surface that no
 * test can drive reliably.
 */
@RunWith(AndroidJUnit4::class)
class AppLockGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val noteContent = "只有解锁后才能看到的笔记正文"
    private val lockTitle = context.getString(R.string.lock_title)
    private val unlockLabel = context.getString(R.string.lock_unlock)
    private val cancelledMessage = context.getString(R.string.lock_cancelled)
    private val lockoutMessage = context.getString(R.string.lock_lockout)
    private val noCredentialTitle = context.getString(R.string.lock_no_credential_title)
    private val openSettingsLabel = context.getString(R.string.lock_open_security_settings)

    /** The gate must never compose the notes before the lock is satisfied. */
    @Test
    fun `the locked gate shows the lock screen and does not compose the notes`() {
        val lock = FakeAppLock()
        composeRule.setContent {
            PrivateNotesTheme {
                AppLockGate(lock = lock, authenticator = FakeAuthenticator()) {
                    Text(noteContent)
                }
            }
        }

        composeRule.onNodeWithTag(LockTags.SCREEN).assertExists()
        composeRule.onNodeWithText(lockTitle).assertExists()
        composeRule.onNodeWithText(noteContent).assertDoesNotExist()
    }

    @Test
    fun `the notes appear once the lock is satisfied`() {
        val lock = FakeAppLock()
        composeRule.setContent {
            PrivateNotesTheme {
                AppLockGate(lock = lock, authenticator = FakeAuthenticator()) {
                    Text(noteContent)
                }
            }
        }

        composeRule.onNodeWithText(noteContent).assertDoesNotExist()

        composeRule.runOnIdle { lock.unlock() }

        composeRule.onNodeWithText(noteContent).assertExists()
        composeRule.onNodeWithTag(LockTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `a successful authentication unlocks the gate`() {
        val lock = FakeAppLock()
        val authenticator = FakeAuthenticator()
        renderLockScreen(lock, authenticator)

        composeRule.runOnIdle { authenticator.succeed() }

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, lock.systemUiDepth)
    }

    @Test
    fun `cancelling keeps the gate closed and says so`() {
        val lock = FakeAppLock()
        val authenticator = FakeAuthenticator()
        renderLockScreen(lock, authenticator)

        composeRule.runOnIdle { authenticator.cancel() }

        assertEquals(AppLockState.Locked, lock.state.value)
        composeRule.onNodeWithText(cancelledMessage).assertExists()
        composeRule.onNodeWithTag(LockTags.SCREEN).assertExists()
    }

    @Test
    fun `a failed attempt reports the reason and stays locked`() {
        val lock = FakeAppLock()
        val authenticator = FakeAuthenticator()
        renderLockScreen(lock, authenticator)

        composeRule.runOnIdle { authenticator.fail(AuthFailure.LOCKOUT) }

        assertEquals(AppLockState.Locked, lock.state.value)
        composeRule.onNodeWithText(lockoutMessage).assertExists()
    }

    @Test
    fun `retrying after a cancellation can unlock`() {
        val lock = FakeAppLock()
        val authenticator = FakeAuthenticator()
        renderLockScreen(lock, authenticator)

        composeRule.runOnIdle { authenticator.cancel() }
        composeRule.onNodeWithText(unlockLabel).performClick()

        composeRule.runOnIdle { authenticator.succeed() }

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(2, authenticator.requests)
    }

    @Test
    fun `a device without a credential is pointed at the system settings`() {
        renderLockScreen(
            FakeAppLock(),
            FakeAuthenticator(current = LockAvailability.NoDeviceCredential),
        )

        composeRule.onNodeWithText(noCredentialTitle).assertExists()
        composeRule.onNodeWithText(openSettingsLabel).assertExists()
        composeRule.onNodeWithText(unlockLabel).assertDoesNotExist()
    }

    private fun renderLockScreen(lock: FakeAppLock, authenticator: FakeAuthenticator) {
        composeRule.setContent {
            PrivateNotesTheme {
                LockScreen(
                    lock = lock,
                    authenticator = authenticator,
                    // The Compose test host is not a FragmentActivity; the prompt is faked anyway.
                    hostActivity = FragmentActivity(),
                )
            }
        }
    }
}

/** Minimal [AppLock] for UI tests: only the state and transitions the gate observes. */
private class FakeAppLock(initial: AppLockState = AppLockState.Locked) : AppLock {

    private val enabledFlow = MutableStateFlow(true)
    private val stateFlow = MutableStateFlow(initial)

    override val enabled: StateFlow<Boolean> = enabledFlow
    override val state: StateFlow<AppLockState> = stateFlow

    var systemUiDepth = 0
        private set

    override fun onForeground() = Unit
    override fun onBackground() = Unit

    override fun unlock() {
        stateFlow.value = AppLockState.Unlocked
    }

    override fun beginSystemUi() {
        systemUiDepth++
    }

    override fun endSystemUi() {
        if (systemUiDepth > 0) systemUiDepth--
    }

    override fun setEnabled(enabled: Boolean) {
        enabledFlow.value = enabled
        if (!enabled) stateFlow.value = AppLockState.Unlocked
    }
}

/** Stands in for `BiometricPrompt`/Keyguard; the test decides when and how it answers. */
private class FakeAuthenticator(
    var current: LockAvailability = LockAvailability.Ready(CredentialMode.BIOMETRIC),
) : AppLockAuthenticator {

    var pending: ((AuthResult) -> Unit)? = null
        private set

    var requests = 0
        private set

    override fun availability(): LockAvailability = current

    override fun authenticateWithBiometrics(
        activity: FragmentActivity,
        prompt: AuthPrompt,
        onResult: (AuthResult) -> Unit,
    ) {
        requests++
        pending = onResult
    }

    override fun deviceCredentialIntent(prompt: AuthPrompt): Intent? = null

    fun succeed() {
        pending?.invoke(AuthResult.Success)
    }

    fun cancel() {
        pending?.invoke(AuthResult.Cancelled)
    }

    fun fail(reason: AuthFailure) {
        pending?.invoke(AuthResult.Failure(reason))
    }
}
