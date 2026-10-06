package ai.unicto.unibot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.repository.ChatRepository
import java.text.DateFormat
import java.util.Date

/**
 * v1.4.0 item 66 — the nav drawer with real hierarchy: three grouped
 * sections (destinations, side chats, actions) separated by hairlines,
 * section labels in a muted label role, 48dp rows, theme typography and
 * shape tokens throughout.
 *
 * Destination order is contractual: Main chat, Devices, then the v1.3.5
 * Swarm entry directly below Devices (do not move it), then Coding when a
 * coding computer is online.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SideChatDrawer(
    agentName: String,
    chatRepository: ChatRepository,
    mainSessionId: String?,
    currentSessionId: String?,
    onOpenMain: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onAllChats: () -> Unit,
    onSettings: () -> Unit,
    onSetMain: (String) -> Unit,
    onSystemFiles: (() -> Unit)? = null,
    onDevices: (() -> Unit)? = null,
    // v1.3.5 Swarm entry, directly below Devices.
    onSwarm: (() -> Unit)? = null,
    // v1.4.0-knowledge item 47: personal knowledge base, below Swarm.
    onKnowledge: (() -> Unit)? = null,
    onCoding: (() -> Unit)? = null,
) {
    val sessions by chatRepository.observeSessions().collectAsState(initial = emptyList())
    // unibot: the account's other devices, for the row under the main chat
    val hubConnected by ai.unicto.unibot.hub.Hub.connected.collectAsState()
    val hubDevices by ai.unicto.unibot.hub.Hub.devices.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val othersOnline = remember(hubDevices) { hubDevices.count { it.online && it.kind != "web" && it.id != ai.unicto.unibot.hub.Hub.deviceId(context) } }
    // unibot: computers whose runtime can show and steer coding agents (Cursor, Codex, Claude Code)
    val codingComputers = remember(hubDevices) {
        hubDevices.count { it.online && it.actions.contains("coding.sessions") && it.id != ai.unicto.unibot.hub.Hub.deviceId(context) }
    }
    var query by remember { mutableStateOf("") }
    val sideChats = remember(sessions, mainSessionId, query) {
        sessions
            .filter { it.id != mainSessionId }
            .filter { query.isBlank() || (it.title ?: "").contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<ChatSessionEntity> { it.pinnedAt ?: 0L }.thenByDescending { it.updatedAt })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MuseTones.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Header ──────────────────────────────────────────────────
        Text(
            text = agentName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp),
        )

        // ── Section: destinations ───────────────────────────────────
        val mainSelected = currentSessionId == null || currentSessionId == mainSessionId
        DrawerRow(
            icon = Icons.Outlined.Home,
            title = stringResource(R.string.ub_drawer_main_chat),
            selected = mainSelected,
            onClick = onOpenMain,
        )
        if (onDevices != null) {
            DrawerRow(
                icon = Icons.Outlined.Devices,
                title = stringResource(R.string.ub_devices_title),
                hint = when {
                    !hubConnected -> stringResource(R.string.ub_devices_off)
                    othersOnline == 0 -> stringResource(R.string.ub_hub_service_alone_short)
                    else -> pluralStringResource(R.plurals.ub_hub_service_devices, othersOnline, othersOnline)
                },
                onClick = onDevices,
            )
        }
        // v1.3.5 Swarm — directly below Devices. Position is contractual.
        if (onSwarm != null) {
            SwarmDrawerRow(onSwarm = onSwarm)
        }
        // v1.4.0-knowledge item 47 — the personal knowledge base, directly
        // below Swarm.
        if (onKnowledge != null) {
            KnowledgeDrawerRow(onKnowledge = onKnowledge)
        }
        if (onCoding != null && codingComputers > 0) {
            DrawerRow(
                icon = Icons.Outlined.Terminal,
                title = stringResource(R.string.ub_coding_title),
                hint = codingComputers.toString(),
                onClick = onCoding,
            )
        }

        HorizontalDivider(
            color = MuseTones.hairline,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        // ── Section: side chats ─────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.ub_drawer_side_chats),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAllChats) {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = stringResource(R.string.ub_drawer_all_chats),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // Search lives with the list it filters, not in the bottom strip.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MuseTones.fill)
                .padding(horizontal = 16.dp),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.ub_drawer_search),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        if (sideChats.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ub_drawer_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ub_drawer_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                items(sideChats, key = { it.id }) { session ->
                    SideChatRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        onClick = { onOpenSession(session.id) },
                        onSetMain = { onSetMain(session.id) },
                    )
                }
            }
        }

        HorizontalDivider(
            color = MuseTones.hairline,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        // ── Section: actions ────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        ) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.ub_drawer_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (onSystemFiles != null) {
                IconButton(onClick = onSystemFiles) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = stringResource(R.string.ub_sysfiles_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.ub_drawer_new_chat),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * One destination row: 48dp minimum, icon + title + optional trailing hint,
 * selected state via the fill tone. Shared by every drawer destination so
 * the section reads as one list.
 */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    selected: Boolean = false,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MuseTones.fill else MuseTones.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = iconTint,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SideChatRow(
    session: ChatSessionEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onSetMain: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) MuseTones.fill else MuseTones.surface)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ub_drawer_untitled),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = relativeDay(session.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ub_drawer_set_main)) },
                onClick = { menu = false; onSetMain() },
            )
        }
    }
}

internal fun relativeDay(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    return when {
        diff < 60_000L -> "now"
        android.text.format.DateUtils.isToday(ms) -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
        diff < 7L * 24 * 3600_000L -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(ms))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}
