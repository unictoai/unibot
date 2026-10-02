package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.PasswordVisualTransformation
import ai.unicto.unibot.connectors.calendar.CalendarConnector
import ai.unicto.unibot.connectors.drive.DriveConnector
import ai.unicto.unibot.connectors.github.GitHubConnector
import ai.unicto.unibot.connectors.gmail.GmailOAuth
import ai.unicto.unibot.connectors.gmail.GmailStore
import ai.unicto.unibot.connectors.google.GoogleOAuth
import ai.unicto.unibot.connectors.telegram.TelegramConnector
import ai.unicto.unibot.ui.components.UnibotTextButton
import kotlinx.coroutines.launch

/**
 * Settings → Connectors. Service connections (OAuth) that give the agent
 * tools like gmail_search, drive_read, calendar_create.
 *
 * Each user connects their OWN account via Google's sign-in page — the app
 * never sees passwords. Tokens are kept in EncryptedSharedPreferences,
 * one independent entry per service.
 */
@Composable
fun ConnectorsScreen(
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.ub_connectors_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.ub_connectors_section),
            footer = stringResource(R.string.ub_connectors_footer),
        ) {
            ConnectorRow(
                logoRes = R.drawable.ic_connector_gmail,
                name = stringResource(R.string.ub_connectors_gmail),
                description = stringResource(R.string.ub_connectors_gmail_desc),
                isConnected = { ctx -> GmailStore.isConnected(ctx) },
                accountEmail = { ctx -> GmailStore.accountEmail(ctx) },
                isConfigured = { GmailOAuth.isConfigured() },
                onConnect = { ctx ->
                    when (val r = GmailOAuth.authorize(ctx)) {
                        is GmailOAuth.Result.Success -> ConnectOutcome.Ok
                        is GmailOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is GmailOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> GmailOAuth.disconnect(ctx) },
            )
            ConnectorRow(
                logoRes = R.drawable.ic_connector_drive,
                name = stringResource(R.string.ub_connectors_drive),
                description = stringResource(R.string.ub_connectors_drive_desc),
                isConnected = { ctx -> DriveConnector.isConnected(ctx) },
                accountEmail = { ctx -> DriveConnector.store.accountEmail(ctx) },
                isConfigured = { true },
                onConnect = { ctx ->
                    when (val r = DriveConnector.authorize(ctx)) {
                        is GoogleOAuth.Result.Success -> ConnectOutcome.Ok
                        is GoogleOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is GoogleOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> DriveConnector.disconnect(ctx) },
            )
            ConnectorRow(
                logoRes = R.drawable.ic_connector_calendar,
                name = stringResource(R.string.ub_connectors_calendar),
                description = stringResource(R.string.ub_connectors_calendar_desc),
                isConnected = { ctx -> CalendarConnector.isConnected(ctx) },
                accountEmail = { ctx -> CalendarConnector.store.accountEmail(ctx) },
                isConfigured = { true },
                onConnect = { ctx ->
                    when (val r = CalendarConnector.authorize(ctx)) {
                        is GoogleOAuth.Result.Success -> ConnectOutcome.Ok
                        is GoogleOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is GoogleOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> CalendarConnector.disconnect(ctx) },
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_github,
                name = stringResource(R.string.ub_connectors_github),
                description = stringResource(R.string.ub_connectors_github_desc),
                hint = stringResource(R.string.ub_connectors_github_hint),
                isConnected = { ctx -> GitHubConnector.isConnected(ctx) },
                label = { ctx -> GitHubConnector.store.label(ctx) },
                onSave = { ctx, token -> GitHubConnector.connect(ctx, token) },
                onDisconnect = { ctx -> GitHubConnector.disconnect(ctx) },
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_telegram,
                name = stringResource(R.string.ub_connectors_telegram),
                description = stringResource(R.string.ub_connectors_telegram_desc),
                hint = stringResource(R.string.ub_connectors_telegram_hint),
                isConnected = { ctx -> TelegramConnector.isConnected(ctx) },
                label = { ctx -> TelegramConnector.store.label(ctx) },
                onSave = { ctx, token -> TelegramConnector.connect(ctx, token) },
                onDisconnect = { ctx -> TelegramConnector.disconnect(ctx) },
            )
        }
    }
}

/** UI-level outcome of a connect tap, mapping each connector's own Result type. */
sealed interface ConnectOutcome {
    object Ok : ConnectOutcome
    object Cancelled : ConnectOutcome
    data class Failed(val message: String) : ConnectOutcome
}

@Composable
private fun ConnectorRow(
    logoRes: Int,
    name: String,
    description: String,
    isConnected: (android.content.Context) -> Boolean,
    accountEmail: (android.content.Context) -> String?,
    isConfigured: () -> Boolean,
    onConnect: suspend (android.content.Context) -> ConnectOutcome,
    onDisconnect: suspend (android.content.Context) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(isConnected(context)) }
    var email by remember { mutableStateOf(accountEmail(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        connected = isConnected(context)
        email = accountEmail(context)
    }

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
            Image(
                painter = painterResource(logoRes),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (connected && email != null)
                        stringResource(R.string.ub_connectors_connected_as, email!!)
                    else
                        description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (connected) {
                UnibotTextButton(onClick = { showDisconnectConfirm = true }) {
                    Text(stringResource(R.string.ub_connectors_disconnect))
                }
            } else {
                UnibotTextButton(onClick = {
                    if (!isConfigured()) {
                        error = context.getString(R.string.ub_connectors_not_configured)
                        return@UnibotTextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = onConnect(context)) {
                            is ConnectOutcome.Ok -> refresh()
                            is ConnectOutcome.Cancelled ->
                                error = context.getString(R.string.ub_connectors_cancelled)
                            is ConnectOutcome.Failed ->
                                error = context.getString(
                                    R.string.ub_connectors_connect_failed,
                                    r.message,
                                )
                        }
                        busy = false
                    }
                }) {
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

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.ub_connectors_disconnect_title)) },
            text = { Text(stringResource(R.string.ub_connectors_confirm_disconnect_named, name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    scope.launch {
                        onDisconnect(context)
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

/**
 * Row for token-based connectors (GitHub PAT, Telegram bot token). The user
 * pastes their own token; it is validated against the service's API and
 * stored in EncryptedSharedPreferences.
 */
@Composable
private fun TokenConnectorRow(
    logoRes: Int,
    name: String,
    description: String,
    hint: String,
    isConnected: (android.content.Context) -> Boolean,
    label: (android.content.Context) -> String?,
    onSave: suspend (android.content.Context, String) -> String?,
    onDisconnect: suspend (android.content.Context) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(isConnected(context)) }
    var connectedLabel by remember { mutableStateOf(label(context)) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        connected = isConnected(context)
        connectedLabel = label(context)
    }

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
            Image(
                painter = painterResource(logoRes),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (connected && connectedLabel != null)
                        stringResource(R.string.ub_connectors_connected_as, connectedLabel!!)
                    else
                        description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (connected) {
                UnibotTextButton(onClick = { showDisconnectConfirm = true }) {
                    Text(stringResource(R.string.ub_connectors_disconnect))
                }
            }
        }
        if (!connected) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; error = null },
                    label = { Text(stringResource(R.string.ub_connectors_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(),
                )
                UnibotTextButton(
                    onClick = {
                        if (token.isBlank()) return@UnibotTextButton
                        busy = true
                        error = null
                        scope.launch {
                            val who = onSave(context, token)
                            busy = false
                            if (who != null) {
                                token = ""
                                refresh()
                            } else {
                                error = context.getString(R.string.ub_connectors_token_invalid)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.ub_connectors_save))
                }
            }
        }
        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.ub_connectors_disconnect_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ub_connectors_confirm_disconnect_named,
                        name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            onDisconnect(context)
                            showDisconnectConfirm = false
                            refresh()
                        }
                    },
                ) {
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
