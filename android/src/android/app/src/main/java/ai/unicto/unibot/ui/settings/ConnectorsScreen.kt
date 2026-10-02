package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import ai.unicto.unibot.connectors.notion.NotionConnector
import ai.unicto.unibot.connectors.outlook.OutlookConnector
import ai.unicto.unibot.connectors.outlook.OutlookOAuth
import ai.unicto.unibot.connectors.photos.PhotosConnector
import ai.unicto.unibot.connectors.reddit.RedditConnector
import ai.unicto.unibot.connectors.rss.RssConnector
import ai.unicto.unibot.connectors.spotify.SpotifyConnector
import ai.unicto.unibot.connectors.spotify.SpotifyOAuth
import ai.unicto.unibot.connectors.telegram.TelegramConnector
import ai.unicto.unibot.connectors.whatsapp.WhatsAppConnector
import ai.unicto.unibot.connectors.youtube.YouTubeConnector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ai.unicto.unibot.connectors.discord.DiscordConnector
import ai.unicto.unibot.connectors.slack.SlackConnector
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.theme.Motion
import kotlinx.coroutines.delay
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
            ConnectorRow(
                logoRes = R.drawable.ic_connector_youtube,
                name = stringResource(R.string.ub_connectors_youtube),
                description = stringResource(R.string.ub_connectors_youtube_desc),
                isConnected = { ctx -> YouTubeConnector.isConnected(ctx) },
                accountEmail = { ctx -> YouTubeConnector.store.accountEmail(ctx) },
                isConfigured = { true },
                onConnect = { ctx ->
                    when (val r = YouTubeConnector.authorize(ctx)) {
                        is GoogleOAuth.Result.Success -> ConnectOutcome.Ok
                        is GoogleOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is GoogleOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> YouTubeConnector.disconnect(ctx) },
            )
            // [v0.7.0-wave3] Google Photos (Google OAuth, readonly).
            ConnectorRow(
                logoRes = R.drawable.ic_connector_photos,
                name = stringResource(R.string.ub_connectors_photos),
                description = stringResource(R.string.ub_connectors_photos_desc),
                isConnected = { ctx -> PhotosConnector.isConnected(ctx) },
                accountEmail = { ctx -> PhotosConnector.store.accountEmail(ctx) },
                isConfigured = { true },
                onConnect = { ctx ->
                    when (val r = PhotosConnector.authorize(ctx)) {
                        is GoogleOAuth.Result.Success -> ConnectOutcome.Ok
                        is GoogleOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is GoogleOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> PhotosConnector.disconnect(ctx) },
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_discord,
                name = stringResource(R.string.ub_connectors_discord),
                description = stringResource(R.string.ub_connectors_discord_desc),
                hint = stringResource(R.string.ub_connectors_discord_hint),
                isConnected = { ctx -> DiscordConnector.isConnected(ctx) },
                label = { ctx -> DiscordConnector.store.label(ctx) },
                onSave = { ctx, token -> DiscordConnector.connect(ctx, token) },
                onDisconnect = { ctx -> DiscordConnector.disconnect(ctx) },
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_slack,
                name = stringResource(R.string.ub_connectors_slack),
                description = stringResource(R.string.ub_connectors_slack_desc),
                hint = stringResource(R.string.ub_connectors_slack_hint),
                isConnected = { ctx -> SlackConnector.isConnected(ctx) },
                label = { ctx -> SlackConnector.store.label(ctx) },
                onSave = { ctx, token -> SlackConnector.connect(ctx, token) },
                onDisconnect = { ctx -> SlackConnector.disconnect(ctx) },
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
            // [v0.6.0-wave2] Spotify (OAuth), Notion (token), Reddit + RSS (no-auth).
            ConnectorRow(
                logoRes = R.drawable.ic_connector_spotify,
                name = stringResource(R.string.ub_connectors_spotify),
                description = stringResource(R.string.ub_connectors_spotify_desc),
                isConnected = { ctx -> SpotifyConnector.isConnected(ctx) },
                accountEmail = { ctx -> SpotifyConnector.store.accountEmail(ctx) },
                isConfigured = { SpotifyConnector.isConfigured() },
                onConnect = { ctx ->
                    when (val r = SpotifyConnector.authorize(ctx)) {
                        is SpotifyOAuth.Result.Success -> ConnectOutcome.Ok
                        is SpotifyOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is SpotifyOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> SpotifyConnector.disconnect(ctx) },
            )
            // [v0.7.0-wave3] Outlook (Microsoft Graph OAuth).
            ConnectorRow(
                logoRes = R.drawable.ic_connector_outlook,
                name = stringResource(R.string.ub_connectors_outlook),
                description = stringResource(R.string.ub_connectors_outlook_desc),
                isConnected = { ctx -> OutlookConnector.isConnected(ctx) },
                accountEmail = { ctx -> OutlookConnector.store.accountEmail(ctx) },
                isConfigured = { OutlookConnector.isConfigured() },
                onConnect = { ctx ->
                    when (val r = OutlookConnector.authorize(ctx)) {
                        is OutlookOAuth.Result.Success -> ConnectOutcome.Ok
                        is OutlookOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is OutlookOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> OutlookConnector.disconnect(ctx) },
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_notion,
                name = stringResource(R.string.ub_connectors_notion),
                description = stringResource(R.string.ub_connectors_notion_desc),
                hint = stringResource(R.string.ub_connectors_notion_hint),
                isConnected = { ctx -> NotionConnector.isConnected(ctx) },
                label = { ctx -> NotionConnector.store.label(ctx) },
                onSave = { ctx, token -> NotionConnector.connect(ctx, token) },
                onDisconnect = { ctx -> NotionConnector.disconnect(ctx) },
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_reddit,
                name = stringResource(R.string.ub_connectors_reddit),
                description = stringResource(R.string.ub_connectors_reddit_desc),
                isEnabled = { ctx -> RedditConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> RedditConnector.setEnabled(ctx, enabled) },
            )
            // [v0.7.0-wave3] WhatsApp: share (no auth) + opt-in notification reader.
            WhatsAppConnectorRow()
            RssConnectorRow()
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
    // [v0.6.0-wave2] Success pulse: brief scale-up when a connect succeeds.
    val pulseScale = remember { Animatable(1f) }
    var pulseTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger == 0) return@LaunchedEffect
        pulseScale.animateTo(1.035f, tween(160, easing = Motion.FastOutSlowIn))
        pulseScale.animateTo(1f, tween(280, easing = Motion.FastOutSlowIn))
    }

    fun refresh() {
        connected = isConnected(context)
        email = accountEmail(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
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
                            is ConnectOutcome.Ok -> {
                                refresh()
                                pulseTrigger++
                            }
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
    // [v0.6.0-wave2] Success pulse on token save.
    val pulseScale = remember { Animatable(1f) }
    var pulseTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger == 0) return@LaunchedEffect
        pulseScale.animateTo(1.035f, tween(160, easing = Motion.FastOutSlowIn))
        pulseScale.animateTo(1f, tween(280, easing = Motion.FastOutSlowIn))
    }

    fun refresh() {
        connected = isConnected(context)
        connectedLabel = label(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
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
                                pulseTrigger++
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

/**
 * Row for no-account connectors (Reddit): a simple enable/disable toggle.
 * Nothing is stored except the on/off flag — public reads need no login.
 */
@Composable
private fun ToggleConnectorRow(
    logoRes: Int,
    name: String,
    description: String,
    isEnabled: (android.content.Context) -> Boolean,
    onToggle: (android.content.Context, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isEnabled(context)) }
    // [v0.6.0-wave2] Success pulse on toggle.
    val pulseScale = remember { Animatable(1f) }
    var pulseTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger == 0) return@LaunchedEffect
        pulseScale.animateTo(1.035f, tween(160, easing = Motion.FastOutSlowIn))
        pulseScale.animateTo(1f, tween(280, easing = Motion.FastOutSlowIn))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
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
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    onToggle(context, it)
                    pulseTrigger++
                },
            )
        }
    }
}

/**
 * WhatsApp connector row: share needs no setup; the notification reader is
 * an opt-in switch. Toggling it ON opens the system Notification Access
 * settings (the only place Android allows that grant). State reflects the
 * effective reader state (in-app opt-in AND system grant) and refreshes on
 * resume so a grant made in system settings shows immediately.
 *
 * Privacy: the reader sees only WhatsApp notifications, buffers them
 * in memory on this phone only, and never uploads anything.
 */
@Composable
private fun WhatsAppConnectorRow() {
    val context = LocalContext.current
    var readerOn by remember { mutableStateOf(WhatsAppConnector.isListenerEnabled(context)) }
    val pulseScale = remember { Animatable(1f) }
    var pulseTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger == 0) return@LaunchedEffect
        pulseScale.animateTo(1.035f, tween(160, easing = Motion.FastOutSlowIn))
        pulseScale.animateTo(1f, tween(280, easing = Motion.FastOutSlowIn))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                readerOn = WhatsAppConnector.isListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_connector_whatsapp),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.ub_connectors_whatsapp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (readerOn) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.ub_connectors_whatsapp_private),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    stringResource(R.string.ub_connectors_whatsapp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = readerOn,
                onCheckedChange = { on ->
                    if (on) {
                        WhatsAppConnector.setListenerEnabled(context, true)
                        WhatsAppConnector.requestAccess(context)
                    } else {
                        WhatsAppConnector.setListenerEnabled(context, false)
                    }
                    readerOn = WhatsAppConnector.isListenerEnabled(context)
                    pulseTrigger++
                },
                modifier = Modifier.semantics {
                    contentDescription = context.getString(R.string.ub_connectors_whatsapp_read)
                },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.ub_connectors_whatsapp_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * RSS feeds connector row: manage the on-device feed list (add/remove URLs).
 * Feeds stay on this phone; nothing is uploaded anywhere.
 */
@Composable
private fun RssConnectorRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(RssConnector.feeds(context)) }
    var newUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    // [v0.6.0-wave2] Success pulse on feed add.
    val pulseScale = remember { Animatable(1f) }
    var pulseTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger == 0) return@LaunchedEffect
        pulseScale.animateTo(1.035f, tween(160, easing = Motion.FastOutSlowIn))
        pulseScale.animateTo(1f, tween(280, easing = Motion.FastOutSlowIn))
    }

    fun refresh() {
        feeds = RssConnector.feeds(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_connector_rss),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ub_connectors_rss),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.ub_connectors_rss_desc) +
                        " (${feeds.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            UnibotTextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) stringResource(R.string.ub_connectors_cancel)
                    else stringResource(R.string.ub_connectors_rss_add),
                )
            }
        }
        if (expanded) {
            Text(
                stringResource(R.string.ub_connectors_rss_hint),
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
                    value = newUrl,
                    onValueChange = { newUrl = it; error = null },
                    label = { Text(stringResource(R.string.ub_connectors_rss_url_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(),
                )
                UnibotTextButton(
                    onClick = {
                        if (newUrl.isBlank()) return@UnibotTextButton
                        scope.launch {
                            val ok = RssConnector.addFeed(context, newUrl)
                            if (ok) {
                                newUrl = ""
                                refresh()
                                pulseTrigger++
                            } else {
                                error = context.getString(R.string.ub_connectors_token_invalid)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.ub_connectors_save))
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
            if (feeds.isEmpty()) {
                Text(
                    stringResource(R.string.ub_connectors_rss_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                feeds.forEach { feed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            feed,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        UnibotTextButton(
                            onClick = {
                                scope.launch {
                                    RssConnector.removeFeed(context, feed)
                                    delay(50)
                                    refresh()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.ub_connectors_rss_remove))
                        }
                    }
                }
            }
        }
    }
}
