package com.example.privatenotes.security

import com.example.privatenotes.data.repository.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.2 acceptance at the state-machine level: authentication gates the app, the 30-second window
 * behaves exactly as specified, and system windows never trigger a lock.
 *
 * Time is fake, so these tests neither sleep nor depend on a device clock.
 */
class AppLockManagerTest {

    private class FakeSettings(override var isEnabled: Boolean) : AppLockSettings

    private class FakeClock(var now: Long = 1_000L) : TimeSource {
        override fun nowMillis(): Long = now
    }

    private val clock = FakeClock()
    private var lockCallbacks = 0

    private fun manager(enabled: Boolean = true): AppLockManager {
        val settings = FakeSettings(enabled)
        return AppLockManager(settings, clock, onLocked = { lockCallbacks++ })
    }

    /** An enabled lock that the user has already passed. */
    private fun unlockedManager(): AppLockManager = manager().apply { unlock() }

    @Test
    fun `starts locked when the lock is enabled`() {
        assertEquals(AppLockState.Locked, manager(enabled = true).state.value)
    }

    @Test
    fun `starts unlocked when the lock is disabled`() {
        assertEquals(AppLockState.Unlocked, manager(enabled = false).state.value)
    }

    @Test
    fun `returning within the grace window does not require authentication`() {
        val lock = unlockedManager()

        lock.onBackground()
        clock.now += 30_000
        lock.onForeground()

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, lockCallbacks)
    }

    @Test
    fun `returning after the grace window requires authentication`() {
        val lock = unlockedManager()

        lock.onBackground()
        clock.now += 30_001
        lock.onForeground()

        assertEquals(AppLockState.Locked, lock.state.value)
        assertEquals(1, lockCallbacks)
    }

    @Test
    fun `a trip caused by a system window never locks`() {
        val lock = unlockedManager()

        lock.beginSystemUi()
        lock.onBackground()
        clock.now += 10 * 60_000
        lock.onForeground()
        lock.endSystemUi()

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, lockCallbacks)
    }

    @Test
    fun `a system window marker cannot disable locking for good`() {
        val lock = unlockedManager()

        // A prompt that never reports back: the foreground clears the marker.
        lock.beginSystemUi()
        lock.onBackground()
        lock.onForeground()

        lock.onBackground()
        clock.now += 60_000
        lock.onForeground()

        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `the gate never opens by itself`() {
        val lock = manager(enabled = true)

        repeat(3) {
            lock.onBackground()
            clock.now += 5 * 60_000
            lock.onForeground()
            assertEquals(AppLockState.Locked, lock.state.value)
        }

        lock.unlock()

        assertEquals(AppLockState.Unlocked, lock.state.value)
    }

    @Test
    fun `foreground without a recorded trip does not lock`() {
        val lock = unlockedManager()

        clock.now += 10 * 60_000
        lock.onForeground()

        assertEquals(AppLockState.Unlocked, lock.state.value)
    }

    @Test
    fun `turning the lock on does not lock the current session`() {
        val settings = FakeSettings(false)
        val lock = AppLockManager(settings, clock, onLocked = { lockCallbacks++ })

        lock.setEnabled(true)

        assertTrue(settings.isEnabled)
        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, lockCallbacks)
    }

    @Test
    fun `turning the lock off opens the gate immediately`() {
        val settings = FakeSettings(true)
        val lock = AppLockManager(settings, clock)
        assertEquals(AppLockState.Locked, lock.state.value)

        lock.setEnabled(false)

        assertFalse(settings.isEnabled)
        assertEquals(AppLockState.Unlocked, lock.state.value)
    }

    @Test
    fun `one lock transition reports one callback`() {
        val lock = unlockedManager()

        lock.onBackground()
        clock.now += 60_000
        lock.onForeground()

        lock.onBackground()
        clock.now += 60_000
        lock.onForeground()

        assertEquals(1, lockCallbacks)
    }

    @Test
    fun `a disabled lock ignores time away`() {
        val lock = manager(enabled = false)

        lock.onBackground()
        clock.now += 60 * 60_000
        lock.onForeground()

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, lockCallbacks)
    }
}
