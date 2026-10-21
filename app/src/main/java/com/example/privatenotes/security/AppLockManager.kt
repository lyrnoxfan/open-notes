package com.example.privatenotes.security

import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app-lock state machine (§6.2).
 *
 * Rules, in the order they matter:
 *
 * 1. **Authentication is the only way in.** The gate starts [AppLockState.Locked] whenever the
 *    lock is enabled — including on a cold start — and only [unlock] clears it.
 * 2. **30-second grace.** A trip to the background only locks if the app stayed away for
 *    *more* than [graceMillis]; coming back at 30 seconds or less does not re-prompt.
 * 3. **System flows do not count as leaving.** The biometric prompt, the device-credential
 *    screen and file pickers push the app off screen for reasons the user did not choose. A trip
 *    bracketed by [beginSystemUi]/[endSystemUi] never triggers the grace window. If a system
 *    window never reports back, the next foreground clears the marker instead of leaving the app
 *    exempt forever.
 *
 * The clock and the persisted switch are injected, so every rule above is testable without
 * waiting 30 seconds or owning a fingerprint.
 */
class AppLockManager(
    private val settings: AppLockSettings,
    private val timeSource: TimeSource,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    /** Called once per transition into the locked state; the container closes the database here. */
    private val onLocked: () -> Unit = {},
) : AppLock {

    private val _enabled = MutableStateFlow(settings.isEnabled)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _state = MutableStateFlow(
        if (settings.isEnabled) AppLockState.Locked else AppLockState.Unlocked,
    )
    override val state: StateFlow<AppLockState> = _state.asStateFlow()

    /** When the process left the foreground, if that trip counts toward the grace window. */
    private var leftAtMillis: Long? = null

    /** Non-zero while a system window (prompt, credential screen, picker) owns the screen. */
    private var systemUiDepth = 0

    @Synchronized
    override fun onBackground() {
        if (!_enabled.value) return
        if (_state.value == AppLockState.Locked) return
        if (systemUiDepth > 0) return
        leftAtMillis = timeSource.nowMillis()
    }

    @Synchronized
    override fun onForeground() {
        val fromSystemUi = systemUiDepth > 0
        // Self-healing: a system window that never reports back must not disable locking.
        systemUiDepth = 0
        val leftAt = leftAtMillis
        leftAtMillis = null

        if (fromSystemUi) return

        if (!_enabled.value) {
            _state.value = AppLockState.Unlocked
            return
        }
        if (_state.value == AppLockState.Locked) return
        if (leftAt != null && timeSource.nowMillis() - leftAt > graceMillis) lock()
    }

    @Synchronized
    override fun unlock() {
        leftAtMillis = null
        _state.value = AppLockState.Unlocked
    }

    @Synchronized
    override fun beginSystemUi() {
        systemUiDepth += 1
    }

    @Synchronized
    override fun endSystemUi() {
        if (systemUiDepth > 0) systemUiDepth -= 1
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
        val wasEnabled = _enabled.value
        settings.isEnabled = enabled
        _enabled.value = enabled
        leftAtMillis = null
        // Turning the lock on is not a reason to throw the user out of the screen they are
        // looking at; the next trip away is what starts requiring authentication.
        if (!enabled || !wasEnabled) _state.value = AppLockState.Unlocked
    }

    private fun lock() {
        _state.value = AppLockState.Locked
        onLocked()
    }

    companion object {
        /** §6.2: coming back within 30 seconds must not re-prompt. */
        const val DEFAULT_GRACE_MILLIS = 30_000L
    }
}
