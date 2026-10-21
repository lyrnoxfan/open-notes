package com.example.privatenotes.feature.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.privatenotes.R
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import com.example.privatenotes.security.AuthFailure
import com.example.privatenotes.security.AuthPrompt
import com.example.privatenotes.security.AuthResult
import com.example.privatenotes.security.CredentialMode
import com.example.privatenotes.security.LockAvailability

/**
 * What the user is told while locked (§6.2).
 *
 * The screen is deliberately bare: there is no note list behind it, no back stack to peek at and
 * no content that could be shown before authentication. Leaving this composable is only possible
 * through [AppLock.unlock].
 */
@Composable
fun LockScreen(
    lock: AppLock,
    authenticator: AppLockAuthenticator,
    modifier: Modifier = Modifier,
    /**
     * The Activity `BiometricPrompt` has to be built against. Resolved from the composition in
     * the app; injectable so the prompt wiring can be exercised in tests, where the Compose host
     * is not a FragmentActivity.
     */
    hostActivity: FragmentActivity? = LocalContext.current.findFragmentActivity(),
) {
    val context = LocalContext.current
    val activity = hostActivity
    val prompt = AuthPrompt(
        title = stringResource(R.string.lock_prompt_title),
        subtitle = stringResource(R.string.lock_prompt_subtitle),
        description = stringResource(R.string.lock_prompt_description),
    )

    var availability by remember { mutableStateOf(authenticator.availability()) }
    var status by remember { mutableStateOf<LockStatus>(LockStatus.Idle) }
    var authenticating by remember { mutableStateOf(false) }
    var promptedOnce by remember { mutableStateOf(false) }

    // API 26–29 with a PIN but no enrolled fingerprint: only the Keyguard screen can verify it.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        lock.endSystemUi()
        authenticating = false
        if (result.resultCode == Activity.RESULT_OK) {
            lock.unlock()
        } else {
            status = LockStatus.Cancelled
        }
    }

    fun startAuthentication() {
        val current = authenticator.availability()
        availability = current
        when (current) {
            LockAvailability.NoDeviceCredential -> {
                status = LockStatus.NoCredential
                authenticating = false
            }

            is LockAvailability.Ready -> {
                status = LockStatus.Authenticating
                authenticating = true
                lock.beginSystemUi()
                if (current.mode == CredentialMode.BIOMETRIC && activity != null) {
                    authenticator.authenticateWithBiometrics(activity, prompt) { result ->
                        lock.endSystemUi()
                        authenticating = false
                        when (result) {
                            AuthResult.Success -> lock.unlock()
                            AuthResult.Cancelled -> status = LockStatus.Cancelled
                            AuthResult.Unavailable -> {
                                // The credential may have been removed while the prompt was up;
                                // re-read availability so the screen stops offering a dead path.
                                availability = authenticator.availability()
                                status = LockStatus.Failed(R.string.lock_generic_error)
                            }
                            is AuthResult.Failure -> status = result.reason.toStatus()
                        }
                    }
                } else {
                    val intent = authenticator.deviceCredentialIntent(prompt)
                    if (intent == null) {
                        lock.endSystemUi()
                        authenticating = false
                        status = LockStatus.NoCredential
                    } else {
                        credentialLauncher.launch(intent)
                    }
                }
            }
        }
    }

    // Coming back from the system settings (where the user may have just set a PIN) has to be
    // picked up, otherwise the screen would keep claiming the device has no credential.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        availability = authenticator.availability()
    }

    // Ask once on arrival. After a cancellation the user retries deliberately; a loop of system
    // prompts is not a lock screen.
    LaunchedEffect(availability) {
        if (!promptedOnce && availability is LockAvailability.Ready) {
            promptedOnce = true
            startAuthentication()
        }
    }

    val available = availability as? LockAvailability.Ready
    val statusText: Pair<String, Boolean>? = when (val current = status) {
        LockStatus.Idle -> null
        LockStatus.Authenticating -> null
        LockStatus.Cancelled -> stringResource(R.string.lock_cancelled) to false
        // The "no credential" case already replaces the title and body above; repeating it
        // here would print the same sentence twice.
        LockStatus.NoCredential -> null
        is LockStatus.Failed -> stringResource(current.messageRes) to true
    }

    Scaffold(
        modifier = modifier.testTag(LockTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (available == null) {
                    stringResource(R.string.lock_no_credential_title)
                } else {
                    stringResource(R.string.lock_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (available == null) {
                    stringResource(R.string.lock_no_credential_body)
                } else {
                    stringResource(R.string.lock_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (statusText != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = statusText.first,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusText.second) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(32.dp))

            if (available == null) {
                Button(
                    onClick = { context.openSecuritySettings() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.lock_open_security_settings))
                }
            } else {
                Button(
                    onClick = { startAuthentication() },
                    enabled = !authenticating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.lock_unlock))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (available == null) {
                TextButton(onClick = { startAuthentication() }) {
                    Text(stringResource(R.string.lock_retry))
                }
            }
        }
    }
}

/** Stable handle for instrumented UI tests. */
object LockTags {
    const val SCREEN = "lock_screen"
}

private sealed interface LockStatus {
    data object Idle : LockStatus
    data object Authenticating : LockStatus
    data object Cancelled : LockStatus
    data object NoCredential : LockStatus
    data class Failed(@StringRes val messageRes: Int) : LockStatus
}

private fun AuthFailure.toStatus(): LockStatus = LockStatus.Failed(
    when (this) {
        AuthFailure.LOCKOUT -> R.string.lock_lockout
        AuthFailure.PERMANENT_LOCKOUT -> R.string.lock_lockout_permanent
        AuthFailure.NO_BIOMETRICS -> R.string.lock_no_biometrics
        AuthFailure.HARDWARE_UNAVAILABLE -> R.string.lock_hardware_unavailable
        AuthFailure.UNKNOWN -> R.string.lock_generic_error
    },
)

private fun Context.openSecuritySettings() {
    val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
