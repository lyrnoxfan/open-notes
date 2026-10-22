package com.example.privatenotes.feature.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privatenotes.R
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import com.example.privatenotes.security.LockAvailability

/**
 * The app-lock switch (§6.2), as a settings row.
 *
 * Turning it on is refused when the device has no secure lock screen: a lock that cannot be
 * satisfied would shut the user out of their own notes. In that case the row offers the system
 * settings instead. Availability is re-checked on every attempt, so a PIN set while the app is
 * open is picked up without a restart.
 */
@Composable
fun AppLockSection(
    lock: AppLock,
    authenticator: AppLockAuthenticator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    var availability by remember { mutableStateOf(authenticator.availability()) }
    val noCredential = availability is LockAvailability.NoDeviceCredential

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_lock_settings_switch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { wanted ->
                    val current = authenticator.availability()
                    availability = current
                    if (!wanted || current is LockAvailability.Ready) {
                        lock.setEnabled(wanted)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        Text(
            text = stringResource(R.string.app_lock_settings_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (noCredential) {
            Text(
                text = stringResource(R.string.app_lock_settings_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.lock_open_security_settings))
            }
        }
    }
}
