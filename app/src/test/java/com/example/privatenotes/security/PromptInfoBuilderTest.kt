package com.example.privatenotes.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The biometric prompt differs by platform level, and getting it wrong is not a cosmetic problem:
 * combining `BIOMETRIC_STRONG` with `DEVICE_CREDENTIAL` is rejected below API 30, so a device with
 * a fingerprint on Android 8–10 would fail to show any prompt at all.
 */
@RunWith(RobolectricTestRunner::class)
class PromptInfoBuilderTest {

    private val prompt = AuthPrompt(
        title = "解锁",
        subtitle = "验证身份",
        description = "以打开笔记",
        negativeButtonText = "取消",
    )
    private val authenticator = AndroidAppLockAuthenticator(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    private val biometricStrong = BiometricManager.Authenticators.BIOMETRIC_STRONG
    private val deviceCredential = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    @Test
    @Config(sdk = [34])
    fun `api 30 and above allow the device credential inside the prompt`() {
        val info: BiometricPrompt.PromptInfo = authenticator.buildPromptInfo(prompt, 34)

        assertEquals(biometricStrong or deviceCredential, info.allowedAuthenticators)
        assertEquals("解锁", info.title)
    }

    @Test
    @Config(sdk = [29])
    fun `api 26 to 29 never asks for an unsupported combination`() {
        val info: BiometricPrompt.PromptInfo = authenticator.buildPromptInfo(prompt, 29)

        assertEquals(
            "only biometrics may be requested below API 30",
            biometricStrong,
            info.allowedAuthenticators,
        )
        assertFalse(info.allowedAuthenticators == (biometricStrong or deviceCredential))
        assertEquals("a dismiss action is mandatory on these levels", "取消", info.negativeButtonText)
    }

    @Test
    @Config(sdk = [26])
    fun `the minimum supported level still builds a prompt`() {
        val info: BiometricPrompt.PromptInfo = authenticator.buildPromptInfo(prompt, 26)

        assertEquals(biometricStrong, info.allowedAuthenticators)
        assertTrue(info.title.isNotEmpty())
    }
}
