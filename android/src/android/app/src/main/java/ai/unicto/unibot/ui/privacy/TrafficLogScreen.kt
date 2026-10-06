package ai.unicto.unibot.ui.privacy
import ai.unicto.unibot.ui.theme.UbColors

import ai.unicto.unibot.privacy.PrivacyNetworkGate
import ai.unicto.unibot.ui.components.EmptyState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import ai.unicto.unibot.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val ROUTE_TRAFFIC_LOG = "unibot/traffic_log"

/**
 * Wave 5 (v1.0) — privacy core. The outbound-connection log: host, a coarse
 * category, and when — nothing else. The log lives in memory only; it is
 * never written to disk and dies with the process. The header says exactly
 * that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficLogScreen(onBack: () -> Unit) {
    val entries by PrivacyNetworkGate.log.collectAsState()
    val haptics = rememberHaptic()

    // [v12-D] Per-connector filter: null = All. Built from connectors
    // actually present in the log, so chips never promise empty sets.
    var selectedConnector by remember { mutableStateOf<String?>(null) }
    val connectors = remember(entries) {
        entries.mapNotNull { it.connector }.distinct().sorted()
    }
    val effectiveSelection = selectedConnector?.takeIf { it in connectors }
    // Privacy item 55 — per-session inspector: null = All sessions. Built
    // from the session ids actually tagged in the log, so chips never
    // promise empty sets. Entries made while no chat was on screen are
    // untagged and only appear under "All sessions".
    var selectedSession by remember { mutableStateOf<String?>(null) }
    val sessions = remember(entries) {
        entries.mapNotNull { it.sessionId }.distinct()
    }
    val effectiveSession = selectedSession?.takeIf { it in sessions }
    val visibleEntries = entries.filter { entry ->
        (effectiveSelection == null || entry.connector == effectiveSelection) &&
            (effectiveSession == null || entry.sessionId == effectiveSession)
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Network traffic") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.tap()
                            PrivacyNetworkGate.clear()
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Clear log",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            // [Wave 9c] Shared EmptyState — the brand medallion + stagger,
            // replacing the hand-rolled icon+text column.
            EmptyState(
                icon = Icons.Outlined.Shield,
                title = "No traffic logged",
                hint = "Every connection this app makes will appear here.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    MuseCaption(
                        text = "Memory only — never saved to disk. " +
                            "Method, host, approximate byte counts, and the chat " +
                            "session are logged; paths, queries, headers, and " +
                            "bodies are never recorded.",
                    )
                }
                // [v12-D] Connector filter chips (All + each connector seen).
                if (connectors.isNotEmpty()) {
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = effectiveSelection == null,
                                    onClick = {
                                        haptics.tap()
                                        selectedConnector = null
                                    },
                                    label = {
                                        Text(stringResource(R.string.v12_privacy_filter_all))
                                    },
                                )
                            }
                            items(connectors) { connector ->
                                FilterChip(
                                    selected = effectiveSelection == connector,
                                    onClick = {
                                        haptics.tap()
                                        selectedConnector = connector
                                    },
                                    label = { Text(connector) },
                                )
                            }
                        }
                    }
                }
                // Privacy item 55 — per-session filter chips (All sessions +
                // each session tagged in the log).
                if (sessions.isNotEmpty()) {
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = effectiveSession == null,
                                    onClick = {
                                        haptics.tap()
                                        selectedSession = null
                                    },
                                    label = { Text("All sessions") },
                                )
                            }
                            items(sessions) { sessionId ->
                                FilterChip(
                                    selected = effectiveSession == sessionId,
                                    onClick = {
                                        haptics.tap()
                                        selectedSession = sessionId
                                    },
                                    label = { Text("Chat ${sessionId.take(8)}") },
                                )
                            }
                        }
                    }
                }
                if (visibleEntries.isEmpty()) {
                    // [v12-D] A filter that matches nothing (e.g. right after
                    // Clear) gets its own honest empty state. Privacy item 55:
                    // names whichever filter(s) are active.
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Shield,
                            title = when {
                                effectiveSelection != null && effectiveSession != null ->
                                    "No traffic from $effectiveSelection in chat ${effectiveSession.take(8)}"
                                effectiveSelection != null -> stringResource(
                                    R.string.v12_privacy_no_traffic_from,
                                    effectiveSelection,
                                )
                                effectiveSession != null ->
                                    "No traffic from chat ${effectiveSession.take(8)}"
                                else -> "No traffic"
                            },
                            hint = stringResource(R.string.v12_privacy_no_traffic_from_hint),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        )
                    }
                } else {
                    itemsIndexed(visibleEntries.reversed()) { index, entry ->
                        TrafficRow(entry, index)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun TrafficRow(
    entry: PrivacyNetworkGate.TrafficEntry,
    index: Int,
) {
    Surface(
        color = MuseTones.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(categoryColor(entry.category)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.host,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Privacy item 55 — a blocked request stays visible in
                    // the log with its badge, so the user can see what a
                    // gate refused.
                    if (entry.blocked) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "Blocked",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                // Privacy item 55 — request inspector line: method, byte
                // counts, connector attribution, and when. Byte counts are
                // approximate (content-length when the server reports one).
                Text(
                    text = buildString {
                        append(entry.method)
                        append(" · ↑").append(formatBytesShort(entry.bytesUp))
                        append(" ↓").append(formatBytesShort(entry.bytesDown))
                        if (entry.connector != null) {
                            append(" · ").append(entry.connector)
                        }
                        append(" · ").append(timeAgo(entry.timestampMs))
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CategoryChip(entry.category)
        }
    }
}

@Composable
private fun CategoryChip(category: PrivacyNetworkGate.Category) {
    val label = when (category) {
        PrivacyNetworkGate.Category.LLM -> "AI"
        PrivacyNetworkGate.Category.OAUTH -> "Login"
        PrivacyNetworkGate.Category.UPDATE -> "Update"
        PrivacyNetworkGate.Category.WEB_SEARCH -> "Search"
        PrivacyNetworkGate.Category.CONNECTOR -> "Connector"
        PrivacyNetworkGate.Category.OTHER -> "Other"
    }
    Surface(
        color = categoryColor(category).copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = categoryColor(category),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun categoryColor(category: PrivacyNetworkGate.Category) =
    when (category) {
        PrivacyNetworkGate.Category.LLM -> MaterialTheme.colorScheme.primary
        PrivacyNetworkGate.Category.OAUTH -> UbColors.success
        PrivacyNetworkGate.Category.UPDATE -> UbColors.warningDark
        PrivacyNetworkGate.Category.WEB_SEARCH -> androidx.compose.ui.graphics.Color(0xFF0A84FF)
        PrivacyNetworkGate.Category.CONNECTOR -> androidx.compose.ui.graphics.Color(0xFFBF5AF2)
        PrivacyNetworkGate.Category.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun timeAgo(timestampMs: Long): String {
    val seconds = ((System.currentTimeMillis() - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "$seconds seconds ago"
        seconds < 3600 -> "${seconds / 60} minutes ago"
        else -> "${seconds / 3600} hours ago"
    }
}

/** Compact byte count for the inspector line ("12.4 KB", "3 B"). */
private fun formatBytesShort(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> String.format("%.1f MB", b / (1024.0 * 1024.0))
    }
}
