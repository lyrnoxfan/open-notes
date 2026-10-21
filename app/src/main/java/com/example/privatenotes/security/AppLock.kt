package com.example.privatenotes.security

import kotlinx.coroutines.flow.StateFlow

/** Whether the notes UI may be shown (§6.2). */
enum class AppLockState {
    Unlocked,
    Locked,
}

/**
 * The app lock, as the UI sees it.
 *
 * The state machine lives in [AppLockManager]; this interface exists so the lock screen and its
 * tests can drive the gate without a running Activity or a real biometric sensor.
 */
interface AppLock {

    /** Whether the user turned the lock on. Persisted. */
    val enabled: StateFlow<Boolean>

    val state: StateFlow<AppLockState>

    /** The process came to the foreground; locks if the trip away outlasted the grace window. */
    fun onForeground()

    /** The process went to the background; remembers when, so the grace window can be measured. */
    fun onBackground()

    /** Authentication succeeded. Only this moves the gate to [AppLockState.Unlocked]. */
    fun unlock()

    /**
     * A system window (biometric prompt, device-credential screen, file picker) is taking the
     * app off screen. Trips caused by these must not lock the user out on return (§6.2).
     */
    fun beginSystemUi()

    /** The system window came back. Balances [beginSystemUi]. */
    fun endSystemUi()

    /** Turns the lock on or off; turning it on does not lock the current session. */
    fun setEnabled(enabled: Boolean)
}

/** How the user can prove who they are on this device. */
enum class CredentialMode {
    /** Strong biometrics are enrolled; the prompt can also offer the device PIN. */
    BIOMETRIC,

    /** No usable biometrics, but a PIN, pattern or password is set. */
    DEVICE_CREDENTIAL,
}

sealed interface LockAvailability {
    data class Ready(val mode: CredentialMode) : LockAvailability

    /** No secure lock screen at all: nothing can authenticate, so §6.2 asks the user to set one. */
    data object NoDeviceCredential : LockAvailability
}

/** Text for the system prompt; kept out of the authenticator so it stays testable. */
data class AuthPrompt(
    val title: String,
    val subtitle: String,
    val description: String,
    /**
     * Label for the prompt's cancel action.
     *
     * Required below API 30: `PromptInfo.Builder.build()` rejects a prompt that neither allows
     * the device credential nor carries a negative button. It is unused above API 30, where the
     * system supplies its own dismissal affordance.
     */
    val negativeButtonText: String = "",
)

sealed interface AuthResult {
    data object Success : AuthResult

    /** The user dismissed the prompt. Stays locked. */
    data object Cancelled : AuthResult

    /** No usable authenticator right now (hardware removed, credentials removed). */
    data object Unavailable : AuthResult

    data class Failure(val reason: AuthFailure) : AuthResult
}

enum class AuthFailure {
    /** Too many attempts; the system will accept one again later. */
    LOCKOUT,

    /** Too many attempts; only the device credential works now. */
    PERMANENT_LOCKOUT,
    NO_BIOMETRICS,
    HARDWARE_UNAVAILABLE,
    UNKNOWN,
}

/**
 * System authentication, isolated behind an interface.
 *
 * Two paths exist because the combination differs by API level: API 30+ can offer the device
 * credential inside `BiometricPrompt`, while API 26–29 with a PIN but no enrolled fingerprint has
 * to go through `KeyguardManager`.
 */
interface AppLockAuthenticator {

    fun availability(): LockAvailability

    fun authenticateWithBiometrics(
        activity: androidx.fragment.app.FragmentActivity,
        prompt: AuthPrompt,
        onResult: (AuthResult) -> Unit,
    )

    /** Intent for the device-credential screen, or null when the device has no credential. */
    fun deviceCredentialIntent(prompt: AuthPrompt): android.content.Intent?
}
