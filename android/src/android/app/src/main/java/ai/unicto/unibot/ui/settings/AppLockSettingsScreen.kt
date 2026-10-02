package ai.unicto.unibot.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.guard.AppLock
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseTopAppBar

/**
 * [P1-app-lock] Settings → App lock. Toggle + lock-after picker, in the
 * Muse settings language (disc back button, dark card, outlined rows).
 *
 * The gate itself uses the phone's own screen lock (fingerprint / face /
 * PIN / pattern) — if the phone has no lock set, the toggle is disabled
 * with an explanation, since there would be nothing to confirm with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by AppLock.enabled.collectAsState()
    val timeoutMinutes by AppLock.timeoutMinutes.collectAsState()
    var showTimeoutPicker by remember { mutableStateOf(false) }
    // Disabled when there is no usable lock (no screen lock, or API < 29).
    val deviceSecure = remember { AppLock.canLock(context) }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.settings_app_lock)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            MuseGap()
            MuseCard {
                MuseRow(
                    title = stringResource(R.string.app_lock_row_title),
                    onClick = {
                        if (deviceSecure) AppLock.setEnabled(context, !enabled)
                    },
                    chevron = false,
                    trailing = {
                        Switch(
                            checked = enabled,
                            enabled = deviceSecure,
                            onCheckedChange = { AppLock.setEnabled(context, it) },
                        )
                    },
                )
                if (enabled) {
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.app_lock_timeout_title),
                        value = AppLock.timeoutLabel(timeoutMinutes),
                        onClick = { showTimeoutPicker = true },
                    )
                }
            }
            MuseCaption(
                text = stringResource(
                    if (deviceSecure) R.string.app_lock_footer
                    else R.string.app_lock_no_device_lock,
                ),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTimeoutPicker) {
        var picked by remember(timeoutMinutes) { mutableStateOf(timeoutMinutes) }
        AlertDialog(
            onDismissRequest = { showTimeoutPicker = false },
            title = { Text(stringResource(R.string.app_lock_timeout_title)) },
            text = {
                Column {
                    AppLock.TIMEOUT_OPTIONS.forEach { minutes ->
                        MuseRow(
                            title = AppLock.timeoutLabel(minutes),
                            onClick = { picked = minutes },
                            chevron = false,
                            trailing = {
                                RadioButton(
                                    selected = picked == minutes,
                                    onClick = { picked = minutes },
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppLock.setTimeoutMinutes(context, picked)
                    showTimeoutPicker = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeoutPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * [P1-app-lock] Full-screen gate painted over the app while [AppLock.locked]
 * is true. The system prompt (via [AppLock.authenticate]) is the actual
 * gate; this overlay just blocks the UI behind it and offers the Unlock
 * button. Rendered by MainActivity above the NavHost.
 */
@Composable
fun AppLockOverlay() {
    val context = LocalContext.current
    val activity = context as? Activity
    var failed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MuseTones.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_lock_overlay_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (failed) R.string.app_lock_overlay_retry
                    else R.string.app_lock_overlay_subtitle,
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    failed = false
                    activity?.let {
                        AppLock.authenticate(it) { ok -> failed = !ok }
                    }
                },
            ) {
                Text(stringResource(R.string.app_lock_overlay_unlock))
            }
        }
    }
}
