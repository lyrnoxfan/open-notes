package com.example.privatenotes.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Real system authentication (§6.2).
 *
 * Two routes, because they are not interchangeable across API levels:
 *
 * - biometrics enrolled and API 30+ → `BiometricPrompt` allowing
 *   `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, so the prompt itself offers the device PIN;
 * - biometrics enrolled but API 26–29 → `BiometricPrompt` limited to `BIOMETRIC_STRONG`, because
 *   combining the two authenticators in one prompt is only supported from API 30. The credential
 *   route stays reachable through the separate action on the lock screen;
 * - PIN/pattern/password but no enrolled biometrics (possible on API 26–29) → the Keyguard
 *   confirmation screen, which is the only thing that can verify a credential there.
 *
 * Nothing here stores or sees the credential: the system owns it.
 */
class AndroidAppLockAuthenticator(context: Context) : AppLockAuthenticator {

    private val appContext = context.applicationContext

    override fun availability(): LockAvailability {
        val biometricsEnrolled = BiometricManager.from(appContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

        val deviceSecure = appContext.getSystemService(KeyguardManager::class.java)
            ?.isDeviceSecure == true

        return when {
            biometricsEnrolled -> LockAvailability.Ready(CredentialMode.BIOMETRIC)
            deviceSecure -> LockAvailability.Ready(CredentialMode.DEVICE_CREDENTIAL)
            else -> LockAvailability.NoDeviceCredential
        }
    }

    override fun authenticateWithBiometrics(
        activity: FragmentActivity,
        prompt: AuthPrompt,
        onResult: (AuthResult) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(AuthResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(errorCode.toAuthResult())
            }

            // Not terminal: the prompt stays open and the user may try again.
            override fun onAuthenticationFailed() = Unit
        }

        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        )

        biometricPrompt.authenticate(buildPromptInfo(prompt, Build.VERSION.SDK_INT))
    }

    /**
     * Builds the prompt for the given platform level.
     *
     * `DEVICE_CREDENTIAL` may only be combined with a biometric authenticator from API 30; on
     * 26–29 that combination is rejected outright, so those versions get biometrics only and the
     * lock screen offers the Keyguard route separately. Parameterised by `sdkInt` so both
     * branches are covered by tests on one machine.
     */
    internal fun buildPromptInfo(prompt: AuthPrompt, sdkInt: Int): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(prompt.title)
            .setSubtitle(prompt.subtitle)
            .setDescription(prompt.description)

        if (sdkInt >= Build.VERSION_CODES.R) {
            // No negative button: with the device credential allowed the system supplies the
            // dismissal affordance, and setting both is rejected.
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            // Mandatory on these levels: a prompt without the device credential must offer a way
            // to dismiss itself, and build() throws if this is missing or blank.
            builder.setNegativeButtonText(prompt.negativeButtonText)
        }

        return builder.build()
    }

    override fun deviceCredentialIntent(prompt: AuthPrompt): Intent? {
        val keyguard = appContext.getSystemService(KeyguardManager::class.java) ?: return null
        if (!keyguard.isDeviceSecure) return null
        return keyguard.createConfirmDeviceCredentialIntent(prompt.title, prompt.subtitle)
    }

    private fun Int.toAuthResult(): AuthResult = when (this) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_TIMEOUT,
        -> AuthResult.Cancelled

        // The credential was removed between opening the prompt and using it.
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthResult.Unavailable

        BiometricPrompt.ERROR_LOCKOUT -> AuthResult.Failure(AuthFailure.LOCKOUT)
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthResult.Failure(AuthFailure.PERMANENT_LOCKOUT)
        BiometricPrompt.ERROR_NO_BIOMETRICS -> AuthResult.Failure(AuthFailure.NO_BIOMETRICS)
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        -> AuthResult.Failure(AuthFailure.HARDWARE_UNAVAILABLE)

        else -> AuthResult.Failure(AuthFailure.UNKNOWN)
    }
}
