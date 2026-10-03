package ai.unicto.unibot.ui.privacy

import android.widget.Toast
import ai.unicto.unibot.R
import ai.unicto.unibot.guard.AppLock
import ai.unicto.unibot.share.ExportPasswordStore
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [v12-D] Batch D (Privacy) settings entry rows for v1.2.
 *
 * The coordinator wires these into Settings later — each is a
 * self-contained row in the Muse settings language ([MuseRow]) with its
 * own dialogs. No navigation routes are needed: the dialogs live here.
 */

/**
 * Entry row into the existing App lock settings. Shows the live state —
 * "Off", or "On · After 5 minutes" / "On · Never" ([AppLock.timeoutLabel]).
 * [onAppLockClick] should navigate to the app-lock settings screen.
 */
@Composable
fun V12AutoLockRow(onAppLockClick: () -> Unit) {
    val haptics = rememberHaptic()
    val enabled by AppLock.enabled.collectAsState()
    val timeoutMinutes by AppLock.timeoutMinutes.collectAsState()

    MuseRow(
        title = stringResource(R.string.v12_privacy_autolock_title),
        icon = Icons.Outlined.Lock,
        value = if (enabled) {
            stringResource(R.string.v12_privacy_autolock_on) +
                " · " + AppLock.timeoutLabel(timeoutMinutes)
        } else {
            stringResource(R.string.v12_privacy_autolock_off)
        },
        onClick = {
            haptics.tap()
            onAppLockClick()
        },
    )
}

/**
 * "Chat export password" row. Tapping opens a dialog to set / change /
 * remove the password. While a password is set, [ai.unicto.unibot.share.ChatExporter]
 * writes every chat export as a WinZip-AES-256 encrypted zip that needs
 * the password to open. The password lives in the AndroidKeystore-backed
 * encrypted prefs ([ExportPasswordStore]) — on this phone only.
 */
@Composable
fun V12ExportPasswordRow() {
    val context = LocalContext.current
    val haptics = rememberHaptic()
    val scope = rememberCoroutineScope()

    var hasPassword by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    // True = the manage/remove dialog; false = the set/change dialog.
    var manageMode by remember { mutableStateOf(false) }

    // Keystore IO stays off the main thread.
    LaunchedEffect(Unit) {
        hasPassword = withContext(Dispatchers.IO) {
            ExportPasswordStore.hasPassword(context)
        }
    }

    MuseRow(
        title = stringResource(R.string.v12_privacy_export_password_title),
        icon = Icons.Outlined.Key,
        value = stringResource(
            if (hasPassword) R.string.v12_privacy_export_password_set
            else R.string.v12_privacy_export_password_not_set,
        ),
        onClick = {
            haptics.tap()
            manageMode = hasPassword
            showDialog = true
        },
    )

    if (showDialog) {
        if (manageMode) {
            ExportPasswordManageDialog(
                onChange = { manageMode = false },
                onRemove = {
                    showDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ExportPasswordStore.clear(context)
                        }
                        hasPassword = false
                        haptics.toggle()
                        Toast.makeText(
                            context,
                            context.getString(R.string.v12_privacy_export_password_cleared),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onDismiss = { showDialog = false },
            )
        } else {
            ExportPasswordSetDialog(
                onSaved = { password ->
                    showDialog = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            ExportPasswordStore.setPassword(context, password)
                        }
                        if (ok) {
                            hasPassword = true
                            haptics.success()
                            Toast.makeText(
                                context,
                                context.getString(R.string.v12_privacy_export_password_saved),
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            haptics.error()
                        }
                    }
                },
                onDismiss = { showDialog = false },
            )
        }
    }
}

@Composable
private fun ExportPasswordSetDialog(
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.v12_privacy_export_password_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.v12_privacy_export_password_dialog_body),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.v12_privacy_export_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text(stringResource(R.string.v12_privacy_export_password_confirm_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error.orEmpty(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        password.length < 4 ->
                            context.getString(R.string.v12_privacy_export_password_too_short)
                        password != confirm ->
                            context.getString(R.string.v12_privacy_export_password_mismatch)
                        else -> null
                    }
                    if (error == null) onSaved(password)
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ExportPasswordManageDialog(
    onChange: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.v12_privacy_export_password_remove_title)) },
        text = { Text(stringResource(R.string.v12_privacy_export_password_remove_body)) },
        confirmButton = {
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(R.string.v12_privacy_export_password_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onChange) {
                    Text(stringResource(R.string.v12_privacy_export_password_change))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
