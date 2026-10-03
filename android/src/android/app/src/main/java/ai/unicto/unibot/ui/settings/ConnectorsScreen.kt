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
import androidx.compose.animation.AnimatedVisibility // [v1.0-wave5-privacy]
import androidx.compose.animation.expandVertically // [v1.0-wave5-privacy]
import androidx.compose.animation.fadeIn // [v1.0-wave5-privacy]
import androidx.compose.animation.fadeOut // [v1.0-wave5-privacy]
import androidx.compose.animation.shrinkVertically // [v1.0-wave5-privacy]
import androidx.compose.foundation.clickable // [v1.0-wave5-privacy]
import androidx.compose.material.icons.filled.KeyboardArrowDown // [v1.0-wave5-privacy]
import androidx.compose.material.icons.filled.KeyboardArrowUp // [v1.0-wave5-privacy]
import ai.unicto.unibot.ui.util.rememberHaptic // [v1.0-wave5-privacy]
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
import ai.unicto.unibot.connectors.currency.CurrencyConnector
import ai.unicto.unibot.connectors.dictionary.DictionaryConnector
import ai.unicto.unibot.connectors.drive.DriveConnector
import ai.unicto.unibot.connectors.dropbox.DropboxConnector
import ai.unicto.unibot.connectors.dropbox.DropboxOAuth
import ai.unicto.unibot.connectors.github.GitHubConnector
import ai.unicto.unibot.connectors.gitlab.GitLabConnector
import ai.unicto.unibot.connectors.gmail.GmailOAuth
import ai.unicto.unibot.connectors.gmail.GmailStore
import ai.unicto.unibot.connectors.gnews.GNewsConnector
import ai.unicto.unibot.connectors.google.GoogleOAuth
import ai.unicto.unibot.connectors.gtasks.GTasksConnector
import ai.unicto.unibot.connectors.hn.HnConnector
import ai.unicto.unibot.connectors.notion.NotionConnector
import ai.unicto.unibot.connectors.onedrive.OneDriveConnector
import ai.unicto.unibot.connectors.onedrive.OneDriveOAuth
import ai.unicto.unibot.connectors.outlook.OutlookConnector
import ai.unicto.unibot.connectors.outlook.OutlookOAuth
import ai.unicto.unibot.connectors.photos.PhotosConnector
import ai.unicto.unibot.connectors.qr.QrConnector
import ai.unicto.unibot.connectors.reddit.RedditConnector
import ai.unicto.unibot.connectors.rss.RssConnector
import ai.unicto.unibot.connectors.spotify.SpotifyConnector
import ai.unicto.unibot.connectors.spotify.SpotifyOAuth
import ai.unicto.unibot.connectors.stackoverflow.StackOverflowConnector
import ai.unicto.unibot.connectors.telegram.TelegramConnector
import ai.unicto.unibot.connectors.tmdb.TmdbConnector
import ai.unicto.unibot.connectors.todoist.TodoistConnector
import ai.unicto.unibot.connectors.translate.TranslateConnector
import ai.unicto.unibot.connectors.trello.TrelloConnector
import ai.unicto.unibot.connectors.weather.WeatherConnector
import ai.unicto.unibot.connectors.whatsapp.WhatsAppConnector
import ai.unicto.unibot.connectors.wiki.WikiConnector
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
import ai.unicto.unibot.ui.theme.staggeredEntrance // [Wave 9b] entrance choreography
import ai.unicto.unibot.ui.theme.successPop // [Wave 9b] connect celebration
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
            // [Wave 9b] Section (header + card) enters first; rows cascade
            // after it via the stagger cursor reset below.
            modifier = Modifier.staggeredEntrance(0),
        ) {
            // [Wave 9b] Reset the entrance cascade — rows claim indices 1..N
            // in composition order through their default staggerIndex.
            connectorStaggerCursor = 1
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
                dataAccess = "Read, send, and search your emails",
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
                dataAccess = "List and read your files",
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
                dataAccess = "Read and create events",
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
                dataAccess = "Read repos you grant, via your token",
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
                dataAccess = "Read channel stats, list uploads",
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
                dataAccess = "Read your library (read-only)",
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
                dataAccess = "Post messages via your bot token",
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
                dataAccess = "Post messages via your bot token",
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
                dataAccess = "Send messages via your bot",
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
                dataAccess = "Playback state and control",
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
                dataAccess = "Read and send mail",
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
                dataAccess = "Pages you share with the integration",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_reddit,
                name = stringResource(R.string.ub_connectors_reddit),
                description = stringResource(R.string.ub_connectors_reddit_desc),
                isEnabled = { ctx -> RedditConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> RedditConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public posts only — no account",
            )
            // [v0.7.0-wave3] WhatsApp: share (no auth) + opt-in notification reader.
            WhatsAppConnectorRow()
            RssConnectorRow()
            // [v1.2] Batch C: 8 new connectors (IMAP/SMTP, Nextcloud, Jellyfin,
            // Home Assistant, Health Connect, Matrix, Steam, Podcasts).
            ai.unicto.unibot.ui.settings.ConnectorsV12BatchC()
            // [v1.0-wave7] Connector mega-batch: token rows.
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_trello,
                name = stringResource(R.string.ub_connectors_trello),
                description = stringResource(R.string.ub_connectors_trello_desc),
                hint = stringResource(R.string.ub_connectors_trello_hint),
                isConnected = { ctx -> TrelloConnector.isConnected(ctx) },
                label = { ctx -> TrelloConnector.store.label(ctx) },
                onSave = { ctx, combined ->
                    val i = combined.indexOf(':')
                    if (i <= 0) null
                    else TrelloConnector.connect(ctx, combined.substring(0, i), combined.substring(i + 1))
                },
                onDisconnect = { ctx -> TrelloConnector.disconnect(ctx) },
                dataAccess = "Boards, lists and cards you can see",
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_todoist,
                name = stringResource(R.string.ub_connectors_todoist),
                description = stringResource(R.string.ub_connectors_todoist_desc),
                hint = stringResource(R.string.ub_connectors_todoist_hint),
                isConnected = { ctx -> TodoistConnector.isConnected(ctx) },
                label = { ctx -> TodoistConnector.store.label(ctx) },
                onSave = { ctx, token -> TodoistConnector.connect(ctx, token) },
                onDisconnect = { ctx -> TodoistConnector.disconnect(ctx) },
                dataAccess = "Read, add and complete tasks",
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_gitlab,
                name = stringResource(R.string.ub_connectors_gitlab),
                description = stringResource(R.string.ub_connectors_gitlab_desc),
                hint = stringResource(R.string.ub_connectors_gitlab_hint),
                isConnected = { ctx -> GitLabConnector.isConnected(ctx) },
                label = { ctx -> GitLabConnector.store.label(ctx) },
                onSave = { ctx, token -> GitLabConnector.connect(ctx, token) },
                onDisconnect = { ctx -> GitLabConnector.disconnect(ctx) },
                dataAccess = "Projects and issues you can see",
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_tmdb,
                name = stringResource(R.string.ub_connectors_tmdb),
                description = stringResource(R.string.ub_connectors_tmdb_desc),
                hint = stringResource(R.string.ub_connectors_tmdb_hint),
                isConnected = { ctx -> TmdbConnector.isConnected(ctx) },
                label = { ctx -> TmdbConnector.store.label(ctx) },
                onSave = { ctx, token -> TmdbConnector.connect(ctx, token) },
                onDisconnect = { ctx -> TmdbConnector.disconnect(ctx) },
                dataAccess = "Public movie/TV catalog",
            )
            TokenConnectorRow(
                logoRes = R.drawable.ic_connector_gnews,
                name = stringResource(R.string.ub_connectors_gnews),
                description = stringResource(R.string.ub_connectors_gnews_desc),
                hint = stringResource(R.string.ub_connectors_gnews_hint),
                isConnected = { ctx -> GNewsConnector.isConnected(ctx) },
                label = { ctx -> GNewsConnector.store.label(ctx) },
                onSave = { ctx, token -> GNewsConnector.connect(ctx, token) },
                onDisconnect = { ctx -> GNewsConnector.disconnect(ctx) },
                dataAccess = "Headlines via your free key",
            )
            // [v1.0-wave7] OAuth rows.
            ConnectorRow(
                logoRes = R.drawable.ic_connector_dropbox,
                name = stringResource(R.string.ub_connectors_dropbox),
                description = stringResource(R.string.ub_connectors_dropbox_desc),
                isConnected = { ctx -> DropboxConnector.isConnected(ctx) },
                accountEmail = { ctx -> DropboxConnector.store.accountEmail(ctx) },
                isConfigured = { DropboxConnector.isConfigured() },
                onConnect = { ctx ->
                    when (val r = DropboxConnector.authorize(ctx)) {
                        is DropboxOAuth.Result.Success -> ConnectOutcome.Ok
                        is DropboxOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is DropboxOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> DropboxConnector.disconnect(ctx) },
                dataAccess = "Read your files (read-only)",
            )
            ConnectorRow(
                logoRes = R.drawable.ic_connector_onedrive,
                name = stringResource(R.string.ub_connectors_onedrive),
                description = stringResource(R.string.ub_connectors_onedrive_desc),
                isConnected = { ctx -> OneDriveConnector.isConnected(ctx) },
                accountEmail = { ctx -> OneDriveConnector.store.accountEmail(ctx) },
                isConfigured = { OneDriveConnector.isConfigured() },
                onConnect = { ctx ->
                    when (val r = OneDriveConnector.authorize(ctx)) {
                        is OneDriveOAuth.Result.Success -> ConnectOutcome.Ok
                        is OneDriveOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is OneDriveOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> OneDriveConnector.disconnect(ctx) },
                dataAccess = "Read your files (read-only)",
            )
            ConnectorRow(
                logoRes = R.drawable.ic_connector_gtasks,
                name = stringResource(R.string.ub_connectors_gtasks),
                description = stringResource(R.string.ub_connectors_gtasks_desc),
                isConnected = { ctx -> GTasksConnector.isConnected(ctx) },
                accountEmail = { ctx -> GTasksConnector.store.accountEmail(ctx) },
                isConfigured = { true },
                onConnect = { ctx ->
                    when (val r = GTasksConnector.authorize(ctx)) {
                        is GoogleOAuth.Result.Success -> ConnectOutcome.Ok
                        is GoogleOAuth.Result.Cancelled -> ConnectOutcome.Cancelled
                        is GoogleOAuth.Result.Failed -> ConnectOutcome.Failed(r.message)
                    }
                },
                onDisconnect = { ctx -> GTasksConnector.disconnect(ctx) },
                dataAccess = "Read and add tasks",
            )
            // [v1.0-wave7] No-account toggle rows.
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_weather,
                name = stringResource(R.string.ub_connectors_weather),
                description = stringResource(R.string.ub_connectors_weather_desc),
                isEnabled = { ctx -> WeatherConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> WeatherConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public weather data — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_currency,
                name = stringResource(R.string.ub_connectors_currency),
                description = stringResource(R.string.ub_connectors_currency_desc),
                isEnabled = { ctx -> CurrencyConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> CurrencyConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public exchange rates — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_wiki,
                name = stringResource(R.string.ub_connectors_wiki),
                description = stringResource(R.string.ub_connectors_wiki_desc),
                isEnabled = { ctx -> WikiConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> WikiConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public articles — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_hn,
                name = stringResource(R.string.ub_connectors_hn),
                description = stringResource(R.string.ub_connectors_hn_desc),
                isEnabled = { ctx -> HnConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> HnConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public stories — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_dictionary,
                name = stringResource(R.string.ub_connectors_dictionary),
                description = stringResource(R.string.ub_connectors_dictionary_desc),
                isEnabled = { ctx -> DictionaryConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> DictionaryConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public definitions — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_translate,
                name = stringResource(R.string.ub_connectors_translate),
                description = stringResource(R.string.ub_connectors_translate_desc),
                isEnabled = { ctx -> TranslateConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> TranslateConnector.setEnabled(ctx, enabled) },
                dataAccess = "Free translation tier — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_stackoverflow,
                name = stringResource(R.string.ub_connectors_stackoverflow),
                description = stringResource(R.string.ub_connectors_stackoverflow_desc),
                isEnabled = { ctx -> StackOverflowConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> StackOverflowConnector.setEnabled(ctx, enabled) },
                dataAccess = "Public Q&A — no account",
            )
            ToggleConnectorRow(
                logoRes = R.drawable.ic_connector_qr,
                name = stringResource(R.string.ub_connectors_qr),
                description = stringResource(R.string.ub_connectors_qr_desc),
                isEnabled = { ctx -> QrConnector.isEnabled(ctx) },
                onToggle = { ctx, enabled -> QrConnector.setEnabled(ctx, enabled) },
                dataAccess = "On-device only — no network",
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

/**
 * [v1.0-wave5-privacy] Expandable "What it can access" line under a
 * connector row. The [dataAccess] string is a static, honest one-liner per
 * connector describing what the agent can do with the connection — it is
 * not derived from scopes at runtime, so keep it in sync with the
 * connector's actual tools.
 */
@Composable
private fun DataAccessDisclosure(dataAccess: String) {
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
                "What it can access",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(Motion.Standard)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(Motion.Quick)) + fadeOut(),
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

/**
 * [Wave 9b] Stagger-index cursor for the connector rows below. Every row on
 * this screen is a direct child of the single SettingsSection, composed in
 * call order, so each row claims the next entrance index through its default
 * [staggerIndex] argument — no call-site changes needed. The cursor is reset
 * at the top of the section content on every composition, keeping indices
 * stable across recompositions (each row's entrance animation is remembered
 * per call site regardless).
 */
private var connectorStaggerCursor = 0

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
    // [v1.0-wave5-privacy] Honest one-liner for the "What it can access" disclosure.
    dataAccess: String,
    // [Wave 9b] Entrance cascade index; defaults to the next cursor value.
    staggerIndex: Int = connectorStaggerCursor++,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // [Wave 9b] Haptic audit: tap on press, success on connect, error on
    // failures and destructive disconnects.
    val haptics = rememberHaptic()
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
            // [Wave 9b] Rows cascade in by index (claimed in composition
            // order via the default staggerIndex argument).
            .staggeredEntrance(staggerIndex)
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
                    // [Wave 9b] The row's connected indicator — pops when a
                    // connect lands (email flips null → value); the visual
                    // half of the haptics.success() fired above.
                    modifier = Modifier.successPop(email),
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (connected) {
                UnibotTextButton(onClick = { haptics.tap(); showDisconnectConfirm = true }) {
                    Text(stringResource(R.string.ub_connectors_disconnect))
                }
            } else {
                UnibotTextButton(onClick = {
                    haptics.tap()
                    if (!isConfigured()) {
                        error = context.getString(R.string.ub_connectors_not_configured)
                        haptics.error()
                        return@UnibotTextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = onConnect(context)) {
                            is ConnectOutcome.Ok -> {
                                refresh()
                                pulseTrigger++
                                // [Wave 9b] Celebration — the visual half is the
                                // successPop on the "Connected as" line below.
                                haptics.success()
                            }
                            is ConnectOutcome.Cancelled ->
                                error = context.getString(R.string.ub_connectors_cancelled)
                            is ConnectOutcome.Failed -> {
                                error = context.getString(
                                    R.string.ub_connectors_connect_failed,
                                    r.message,
                                )
                                haptics.error()
                            }
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
        // [v1.0-wave5-privacy] Honest data-access disclosure.
        DataAccessDisclosure(dataAccess)
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.ub_connectors_disconnect_title)) },
            text = { Text(stringResource(R.string.ub_connectors_confirm_disconnect_named, name)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.error()
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
    // [v1.0-wave5-privacy] Honest one-liner for the "What it can access" disclosure.
    dataAccess: String,
    // [Wave 9b] Entrance cascade index; defaults to the next cursor value.
    staggerIndex: Int = connectorStaggerCursor++,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // [Wave 9b] Haptic audit: tap on press, success on token save, error on
    // invalid tokens and destructive disconnects.
    val haptics = rememberHaptic()
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
            // [Wave 9b] Rows cascade in by index (claimed in composition
            // order via the default staggerIndex argument).
            .staggeredEntrance(staggerIndex)
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
                    // [Wave 9b] The row's connected indicator — pops when a
                    // token save lands (label flips null → value); the visual
                    // half of the haptics.success() fired above.
                    modifier = Modifier.successPop(connectedLabel),
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (connected) {
                UnibotTextButton(onClick = { haptics.tap(); showDisconnectConfirm = true }) {
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
                        haptics.tap()
                        busy = true
                        error = null
                        scope.launch {
                            val who = onSave(context, token)
                            busy = false
                            if (who != null) {
                                token = ""
                                refresh()
                                pulseTrigger++
                                // [Wave 9b] Celebration — the visual half is the
                                // successPop on the "Connected as" line below.
                                haptics.success()
                            } else {
                                error = context.getString(R.string.ub_connectors_token_invalid)
                                haptics.error()
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
        // [v1.0-wave5-privacy] Honest data-access disclosure.
        DataAccessDisclosure(dataAccess)
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
                        haptics.error()
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
    // [v1.0-wave5-privacy] Honest one-liner for the "What it can access" disclosure.
    dataAccess: String,
    // [Wave 9b] Entrance cascade index; defaults to the next cursor value.
    staggerIndex: Int = connectorStaggerCursor++,
) {
    val context = LocalContext.current
    // [Wave 9b] Haptic audit: the switch gets a toggle tick.
    val haptics = rememberHaptic()
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
            // [Wave 9b] Rows cascade in by index (claimed in composition
            // order via the default staggerIndex argument).
            .staggeredEntrance(staggerIndex)
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
                    haptics.toggle()
                    enabled = it
                    onToggle(context, it)
                    pulseTrigger++
                },
            )
        }
        // [v1.0-wave5-privacy] Honest data-access disclosure.
        DataAccessDisclosure(dataAccess)
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
private fun WhatsAppConnectorRow(
    // [Wave 9b] Entrance cascade index; defaults to the next cursor value.
    staggerIndex: Int = connectorStaggerCursor++,
) {
    val context = LocalContext.current
    // [Wave 9b] Haptic audit: the reader switch gets a toggle tick.
    val haptics = rememberHaptic()
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
            // [Wave 9b] Rows cascade in by index (claimed in composition
            // order via the default staggerIndex argument).
            .staggeredEntrance(staggerIndex)
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
                        // [Wave 9b] Connected badge — pops the moment the
                        // reader row enters its enabled state (the badge only
                        // exists in composition while readerOn, so a constant
                        // trigger fires exactly on appearance).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.successPop(Unit),
                        ) {
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
                    haptics.toggle()
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
        // [v1.0-wave5-privacy] Honest data-access disclosure.
        DataAccessDisclosure("Share sheet + notifications you allow")
    }
}

/**
 * RSS feeds connector row: manage the on-device feed list (add/remove URLs).
 * Feeds stay on this phone; nothing is uploaded anywhere.
 */
@Composable
private fun RssConnectorRow(
    // [Wave 9b] Entrance cascade index; defaults to the next cursor value.
    staggerIndex: Int = connectorStaggerCursor++,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // [Wave 9b] Haptic audit: taps on buttons, success on feed add, error on
    // failures and feed removal.
    val haptics = rememberHaptic()
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
            // [Wave 9b] Rows cascade in by index (claimed in composition
            // order via the default staggerIndex argument).
            .staggeredEntrance(staggerIndex)
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
            UnibotTextButton(onClick = { haptics.tap(); expanded = !expanded }) {
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
                        haptics.tap()
                        scope.launch {
                            val ok = RssConnector.addFeed(context, newUrl)
                            if (ok) {
                                newUrl = ""
                                refresh()
                                pulseTrigger++
                                haptics.success()
                            } else {
                                error = context.getString(R.string.ub_connectors_token_invalid)
                                haptics.error()
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
                                haptics.error()
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
        // [v1.0-wave5-privacy] Honest data-access disclosure.
        DataAccessDisclosure("Fetches feed URLs you add")
    }
}
