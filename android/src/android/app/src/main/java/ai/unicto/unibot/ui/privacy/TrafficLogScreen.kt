package ai.unicto.unibot.ui.privacy

import ai.unicto.unibot.privacy.PrivacyNetworkGate
import ai.unicto.unibot.ui.components.EmptyState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                            "Only the destination host is logged; paths, queries, " +
                            "headers, and bodies are never recorded.",
                    )
                }
                itemsIndexed(entries.reversed()) { index, entry ->
                    TrafficRow(entry, index)
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
                Text(
                    text = entry.host,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = timeAgo(entry.timestampMs),
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
        PrivacyNetworkGate.Category.OAUTH -> androidx.compose.ui.graphics.Color(0xFF34C759)
        PrivacyNetworkGate.Category.UPDATE -> androidx.compose.ui.graphics.Color(0xFFFF9F0A)
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
