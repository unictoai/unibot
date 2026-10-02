package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.connectors.gmail.GmailOAuth
import ai.unicto.unibot.connectors.gmail.GmailStore
import ai.unicto.unibot.ui.components.UnibotTextButton
import kotlinx.coroutines.launch

/**
 * Settings → Connectors. Service connections (OAuth) that give the agent
 * tools like gmail_search / gmail_read / gmail_send.
 *
 * Each user connects their OWN account via Google's sign-in page — the app
 * never sees passwords. Tokens are kept in EncryptedSharedPreferences.
 */
@Composable
fun ConnectorsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(GmailStore.isConnected(context)) }
    var accountEmail by remember { mutableStateOf(GmailStore.accountEmail(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        connected = GmailStore.isConnected(context)
        accountEmail = GmailStore.accountEmail(context)
    }

    SettingsScaffold(
        title = stringResource(R.string.ub_connectors_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.ub_connectors_section),
            footer = stringResource(R.string.ub_connectors_footer),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Email,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.ub_connectors_gmail),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (connected && accountEmail != null)
                                stringResource(R.string.ub_connectors_connected_as, accountEmail!!)
                            else
                                stringResource(R.string.ub_connectors_gmail_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (connected) {
                        UnibotTextButton(
                            onClick = { showDisconnectConfirm = true },
                        ) {
                            Text(stringResource(R.string.ub_connectors_disconnect))
                        }
                    } else {
                        UnibotTextButton(
                            onClick = {
                                if (!GmailOAuth.isConfigured()) {
                                    error = context.getString(R.string.ub_connectors_not_configured)
                                    return@UnibotTextButton
                                }
                                busy = true
                                error = null
                                scope.launch {
                                    when (val r = GmailOAuth.authorize(context)) {
                                        is GmailOAuth.Result.Success -> refresh()
                                        is GmailOAuth.Result.Cancelled ->
                                            error = context.getString(R.string.ub_connectors_cancelled)
                                        is GmailOAuth.Result.Failed ->
                                            error = context.getString(
                                                R.string.ub_connectors_connect_failed,
                                                r.message,
                                            )
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.ub_connectors_connect))
                        }
                    }
                }
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.ub_connectors_disconnect_title)) },
            text = { Text(stringResource(R.string.ub_connectors_confirm_disconnect)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    scope.launch {
                        GmailOAuth.disconnect(context)
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.ub_connectors_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text(stringResource(R.string.ub_connectors_cancel))
                }
            },
        )
    }
}
