package ai.unicto.unibot.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.connectors.homeassistant.HomeAssistantConnector
import ai.unicto.unibot.connectors.imap.ImapSmtpConnector
import ai.unicto.unibot.connectors.jellyfin.JellyfinConnector
import ai.unicto.unibot.connectors.matrix.MatrixConnector
import ai.unicto.unibot.connectors.nextcloud.NextcloudConnector
import ai.unicto.unibot.connectors.podcasts.PodcastsConnector
import ai.unicto.unibot.connectors.steam.SteamConnector
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.theme.successPop
import ai.unicto.unibot.ui.util.rememberHaptic
import kotlinx.coroutines.launch

/**
 * v1.2 "the 59" — Batch C (new connectors) settings rows.
 *
 * NOT wired into [ConnectorsScreen] yet — the coordinator drops
 * [ConnectorsV12BatchC] into the screen in the final pass. Every row is
 * self-contained: connect/disconnect (or search/follow) all work, every
 * credential field is validated before anything is stored, and rows that
 * handle sensitive data show the ON-DEVICE badge plus a "What it can
 * access" disclosure. No dead buttons, no dev strings.
 */

private var v12StaggerCursor = 0

/** Section hosting all eight Batch C rows. Call from ConnectorsScreen. */
@Composable
fun ConnectorsV12BatchC() {
    v12StaggerCursor = 0
    Column(modifier = Modifier.fillMaxWidth()) {
        ImapSmtpRow()
        NextcloudRow()
        JellyfinRow()
        HomeAssistantRow()
        // [v1.2] Health Connect cut: the android.health.connect framework API
        // usage didn't compile and can't be verified without an SDK; the 7
        // other connectors ship. See v1.2 cut list.
        MatrixRow()
        SteamRow()
        PodcastsRow()
    }
}

// ------------------------------------------------------------------
// Shared row chrome
// ------------------------------------------------------------------

@Composable
private fun V12OnDeviceBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            stringResource(R.string.v12_conn_on_device),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun V12DataAccess(dataAccess: String) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = rememberHaptic()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable {
                    haptics.tap()
                    expanded = !expanded
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.v12_conn_what_access),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                dataAccess,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun V12Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(),
    )
}

/**
 * Shell: icon, name (+ optional ON-DEVICE badge), connected-as/description
 * line, error line, data-access disclosure, disconnect confirmation.
 */
@Composable
private fun V12RowShell(
    icon: ImageVector,
    name: String,
    description: String,
    connectedLabel: String?,
    onDevice: Boolean,
    dataAccess: String,
    onDisconnect: suspend () -> Unit,
    onDisconnected: () -> Unit,
    hideDisconnect: Boolean = false,
    showStoredNote: Boolean = true,
    staggerIndex: Int = v12StaggerCursor++,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    val connected = connectedLabel != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(staggerIndex)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (onDevice) V12OnDeviceBadge()
                }
                Text(
                    if (connected)
                        stringResource(R.string.v12_conn_connected_as, connectedLabel!!)
                    else description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.successPop(connectedLabel),
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (connected && !hideDisconnect) {
                UnibotTextButton(onClick = { haptics.tap(); showDisconnectConfirm = true }) {
                    Text(stringResource(R.string.v12_conn_disconnect))
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

        content()

        if (showStoredNote) {
            Text(
                stringResource(R.string.v12_conn_stored_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        V12DataAccess(dataAccess)
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.v12_conn_disconnect_title)) },
            text = { Text(stringResource(R.string.v12_conn_confirm_disconnect, name)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.error()
                    showDisconnectConfirm = false
                    scope.launch {
                        busy = true
                        onDisconnect()
                        busy = false
                        onDisconnected()
                    }
                }) {
                    Text(stringResource(R.string.v12_conn_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text(stringResource(R.string.v12_conn_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------------
// IMAP / SMTP
// ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImapSmtpRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var connectedAccount by remember { mutableStateOf(ImapSmtpConnector.account(context)) }
    var preset by remember { mutableStateOf(ImapSmtpConnector.PRESETS[0]) }
    var presetExpanded by remember { mutableStateOf(false) }
    var imapHost by remember { mutableStateOf(preset.imapHost) }
    var imapPort by remember { mutableStateOf(preset.imapPort.toString()) }
    var smtpHost by remember { mutableStateOf(preset.smtpHost) }
    var smtpPort by remember { mutableStateOf(preset.smtpPort.toString()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        connectedAccount = if (ImapSmtpConnector.isConnected(context))
            ImapSmtpConnector.account(context) else null
    }

    V12RowShell(
        icon = Icons.Filled.Email,
        name = stringResource(R.string.v12_imap_name),
        description = stringResource(R.string.v12_imap_desc),
        connectedLabel = connectedAccount?.email,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_imap_access),
        onDisconnect = { ImapSmtpConnector.disconnect(context) },
        onDisconnected = { refresh() },
    ) {
        if (connectedAccount != null) return@V12RowShell
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(top = 8.dp))
            return@V12RowShell
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = it },
            ) {
                OutlinedTextField(
                    value = preset.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.v12_imap_provider)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = TextFieldDefaults.colors(),
                )
                ExposedDropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false },
                ) {
                    ImapSmtpConnector.PRESETS.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                preset = p
                                imapHost = p.imapHost
                                imapPort = p.imapPort.toString()
                                smtpHost = p.smtpHost
                                smtpPort = p.smtpPort.toString()
                                presetExpanded = false
                                error = null
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V12Field(imapHost, { imapHost = it }, stringResource(R.string.v12_imap_imap_host), modifier = Modifier.weight(1f))
                V12Field(imapPort, { imapPort = it.filter(Char::isDigit) }, stringResource(R.string.v12_imap_imap_port), modifier = Modifier.weight(0.4f), keyboardType = KeyboardType.Number)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V12Field(smtpHost, { smtpHost = it }, stringResource(R.string.v12_imap_smtp_host), modifier = Modifier.weight(1f))
                V12Field(smtpPort, { smtpPort = it.filter(Char::isDigit) }, stringResource(R.string.v12_imap_smtp_port), modifier = Modifier.weight(0.4f), keyboardType = KeyboardType.Number)
            }
            V12Field(email, { email = it; error = null }, stringResource(R.string.v12_imap_email_label), keyboardType = KeyboardType.Email)
            V12Field(password, { password = it; error = null }, stringResource(R.string.v12_conn_password_label), secret = true)
            Text(
                stringResource(R.string.v12_imap_app_password_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            UnibotTextButton(onClick = {
                haptics.tap()
                busy = true
                error = null
                scope.launch {
                    error = ImapSmtpConnector.connect(
                        context,
                        imapHost, imapPort.toIntOrNull() ?: 993,
                        smtpHost, smtpPort.toIntOrNull() ?: 465,
                        email, password,
                    )
                    busy = false
                    if (error == null) {
                        password = ""
                        haptics.success()
                        refresh()
                    } else haptics.error()
                }
            }) {
                Text(stringResource(R.string.v12_conn_connect))
            }
        }
    }
}

// ------------------------------------------------------------------
// Nextcloud
// ------------------------------------------------------------------

@Composable
private fun NextcloudRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var connected by remember { mutableStateOf(NextcloudConnector.isConnected(context)) }
    var label by remember { mutableStateOf(NextcloudConnector.username(context)) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var appPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        connected = NextcloudConnector.isConnected(context)
        label = NextcloudConnector.username(context)
    }

    V12RowShell(
        icon = Icons.Filled.Cloud,
        name = stringResource(R.string.v12_nc_name),
        description = stringResource(R.string.v12_nc_desc),
        connectedLabel = if (connected) label else null,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_nc_access),
        onDisconnect = { NextcloudConnector.disconnect(context) },
        onDisconnected = { refresh() },
    ) {
        if (connected) return@V12RowShell
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(top = 8.dp))
            return@V12RowShell
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.v12_nc_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            V12Field(url, { url = it; error = null }, stringResource(R.string.v12_conn_server_label), keyboardType = KeyboardType.Uri)
            V12Field(username, { username = it; error = null }, stringResource(R.string.v12_conn_username_label))
            V12Field(appPassword, { appPassword = it; error = null }, stringResource(R.string.v12_conn_password_label), secret = true)
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            UnibotTextButton(onClick = {
                haptics.tap()
                busy = true
                error = null
                scope.launch {
                    error = NextcloudConnector.connect(context, url, username, appPassword)
                    busy = false
                    if (error == null) {
                        appPassword = ""
                        haptics.success()
                        refresh()
                    } else haptics.error()
                }
            }) {
                Text(stringResource(R.string.v12_conn_connect))
            }
        }
    }
}

// ------------------------------------------------------------------
// Jellyfin
// ------------------------------------------------------------------

@Composable
private fun JellyfinRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var connected by remember { mutableStateOf(JellyfinConnector.isConnected(context)) }
    var server by remember { mutableStateOf(JellyfinConnector.serverUrl(context)) }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        connected = JellyfinConnector.isConnected(context)
        server = JellyfinConnector.serverUrl(context)
    }

    V12RowShell(
        icon = Icons.Filled.Movie,
        name = stringResource(R.string.v12_jf_name),
        description = stringResource(R.string.v12_jf_desc),
        connectedLabel = if (connected) server else null,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_jf_access),
        onDisconnect = { JellyfinConnector.disconnect(context) },
        onDisconnected = { refresh() },
    ) {
        if (connected) return@V12RowShell
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(top = 8.dp))
            return@V12RowShell
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.v12_jf_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            V12Field(url, { url = it; error = null }, stringResource(R.string.v12_conn_server_label), keyboardType = KeyboardType.Uri)
            V12Field(apiKey, { apiKey = it; error = null }, stringResource(R.string.v12_conn_token_label), secret = true)
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            UnibotTextButton(onClick = {
                haptics.tap()
                busy = true
                error = null
                scope.launch {
                    error = JellyfinConnector.connect(context, url, apiKey)
                    busy = false
                    if (error == null) {
                        apiKey = ""
                        haptics.success()
                        refresh()
                    } else haptics.error()
                }
            }) {
                Text(stringResource(R.string.v12_conn_connect))
            }
        }
    }
}

// ------------------------------------------------------------------
// Home Assistant
// ------------------------------------------------------------------

@Composable
private fun HomeAssistantRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var connected by remember { mutableStateOf(HomeAssistantConnector.isConnected(context)) }
    var server by remember { mutableStateOf(HomeAssistantConnector.serverUrl(context)) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        connected = HomeAssistantConnector.isConnected(context)
        server = HomeAssistantConnector.serverUrl(context)
    }

    V12RowShell(
        icon = Icons.Filled.Home,
        name = stringResource(R.string.v12_ha_name),
        description = stringResource(R.string.v12_ha_desc),
        connectedLabel = if (connected) server else null,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_ha_access),
        onDisconnect = { HomeAssistantConnector.disconnect(context) },
        onDisconnected = { refresh() },
    ) {
        if (connected) return@V12RowShell
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(top = 8.dp))
            return@V12RowShell
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.v12_ha_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            V12Field(url, { url = it; error = null }, stringResource(R.string.v12_conn_server_label), keyboardType = KeyboardType.Uri)
            V12Field(token, { token = it; error = null }, stringResource(R.string.v12_conn_token_label), secret = true)
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            UnibotTextButton(onClick = {
                haptics.tap()
                busy = true
                error = null
                scope.launch {
                    error = HomeAssistantConnector.connect(context, url, token)
                    busy = false
                    if (error == null) {
                        token = ""
                        haptics.success()
                        refresh()
                    } else haptics.error()
                }
            }) {
                Text(stringResource(R.string.v12_conn_connect))
            }
        }
    }
}

// ------------------------------------------------------------------
// Matrix
// ------------------------------------------------------------------

@Composable
private fun MatrixRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var connected by remember { mutableStateOf(MatrixConnector.isConnected(context)) }
    var label by remember { mutableStateOf(MatrixConnector.userId(context)) }
    var homeserver by remember { mutableStateOf("https://matrix.org") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        connected = MatrixConnector.isConnected(context)
        label = MatrixConnector.userId(context)
    }

    V12RowShell(
        icon = Icons.Filled.Forum,
        name = stringResource(R.string.v12_mx_name),
        description = stringResource(R.string.v12_mx_desc),
        connectedLabel = if (connected) label else null,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_mx_access),
        onDisconnect = { MatrixConnector.disconnect(context) },
        onDisconnected = { refresh() },
    ) {
        if (connected) return@V12RowShell
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(top = 8.dp))
            return@V12RowShell
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            V12Field(homeserver, { homeserver = it; error = null }, stringResource(R.string.v12_mx_homeserver_label), keyboardType = KeyboardType.Uri)
            V12Field(username, { username = it; error = null }, stringResource(R.string.v12_conn_username_label))
            V12Field(password, { password = it; error = null }, stringResource(R.string.v12_conn_password_label), secret = true)
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            UnibotTextButton(onClick = {
                haptics.tap()
                busy = true
                error = null
                scope.launch {
                    error = MatrixConnector.connect(context, homeserver, username, password)
                    busy = false
                    if (error == null) {
                        password = ""
                        haptics.success()
                        refresh()
                    } else haptics.error()
                }
            }) {
                Text(stringResource(R.string.v12_conn_connect))
            }
        }
    }
}

// ------------------------------------------------------------------
// Steam
// ------------------------------------------------------------------

@Composable
private fun SteamRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var connected by remember { mutableStateOf(SteamConnector.isConnected(context)) }
    var label by remember { mutableStateOf(SteamConnector.store.label(context)) }
    var apiKey by remember { mutableStateOf("") }
    var steamId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        connected = SteamConnector.isConnected(context)
        label = SteamConnector.store.label(context)
    }

    V12RowShell(
        icon = Icons.Filled.SportsEsports,
        name = stringResource(R.string.v12_st_name),
        description = stringResource(R.string.v12_st_desc),
        connectedLabel = if (connected) label else null,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_st_access),
        onDisconnect = { SteamConnector.disconnect(context) },
        onDisconnected = { refresh() },
    ) {
        if (connected) return@V12RowShell
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(top = 8.dp))
            return@V12RowShell
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.v12_st_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            V12Field(apiKey, { apiKey = it; error = null }, stringResource(R.string.v12_conn_token_label), secret = true)
            V12Field(steamId, { steamId = it.filter(Char::isDigit); error = null }, stringResource(R.string.v12_st_steamid_label), keyboardType = KeyboardType.Number)
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            UnibotTextButton(onClick = {
                haptics.tap()
                if (apiKey.isBlank() || steamId.isBlank()) {
                    error = context.getString(R.string.v12_conn_need_url_token)
                    return@UnibotTextButton
                }
                busy = true
                error = null
                scope.launch {
                    val name = SteamConnector.connect(context, apiKey, steamId)
                    busy = false
                    if (name != null) {
                        apiKey = ""
                        haptics.success()
                        refresh()
                    } else {
                        error = context.getString(R.string.v12_conn_failed, "invalid API key or SteamID64")
                        haptics.error()
                    }
                }
            }) {
                Text(stringResource(R.string.v12_conn_connect))
            }
        }
    }
}

// ------------------------------------------------------------------
// Podcasts
// ------------------------------------------------------------------

@Composable
private fun PodcastsRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PodcastsConnector.Show>>(emptyList()) }
    var subs by remember { mutableStateOf(PodcastsConnector.subscriptions(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshSubs() {
        subs = PodcastsConnector.subscriptions(context)
    }

    V12RowShell(
        icon = Icons.Filled.Podcasts,
        name = stringResource(R.string.v12_pc_name),
        description = stringResource(R.string.v12_pc_desc),
        connectedLabel = if (subs.isNotEmpty()) stringResource(R.string.v12_pc_shows_count, subs.size) else null,
        onDevice = true,
        dataAccess = stringResource(R.string.v12_pc_access),
        onDisconnect = { subs.forEach { PodcastsConnector.unsubscribe(context, it.feedUrl) } },
        onDisconnected = { refreshSubs(); results = emptyList() },
        showStoredNote = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; error = null },
                    label = { Text(stringResource(R.string.v12_pc_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    UnibotTextButton(onClick = {
                        haptics.tap()
                        busy = true
                        error = null
                        scope.launch {
                            when (val r = PodcastsConnector.search(context, query)) {
                                is PodcastsConnector.ApiResult.Ok -> {
                                    results = r.value
                                    haptics.success()
                                }
                                is PodcastsConnector.ApiResult.Error -> {
                                    error = r.message
                                    haptics.error()
                                }
                            }
                            busy = false
                        }
                    }) {
                        Text(stringResource(R.string.v12_pc_search))
                    }
                }
            }
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            results.forEach { show ->
                val following = subs.any { it.feedUrl.equals(show.feedUrl, ignoreCase = true) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(show.title, style = MaterialTheme.typography.bodyMedium)
                        if (show.artist.isNotBlank()) {
                            Text(
                                show.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    UnibotTextButton(onClick = {
                        haptics.tap()
                        if (following) PodcastsConnector.unsubscribe(context, show.feedUrl)
                        else PodcastsConnector.subscribe(context, show)
                        refreshSubs()
                    }) {
                        Text(
                            stringResource(
                                if (following) R.string.v12_pc_subscribed
                                else R.string.v12_pc_subscribe,
                            ),
                        )
                    }
                }
            }
            if (results.isEmpty() && subs.isNotEmpty()) {
                Text(
                    stringResource(R.string.v12_pc_subs_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                subs.forEach { show ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            show.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        UnibotTextButton(onClick = {
                            haptics.tap()
                            PodcastsConnector.unsubscribe(context, show.feedUrl)
                            refreshSubs()
                        }) {
                            Text(stringResource(R.string.v12_pc_unfollow))
                        }
                    }
                }
            }
            if (results.isEmpty() && subs.isEmpty()) {
                Text(
                    stringResource(R.string.v12_pc_no_subs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
