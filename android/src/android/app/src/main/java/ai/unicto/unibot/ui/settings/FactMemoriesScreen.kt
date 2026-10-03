package ai.unicto.unibot.ui.settings

import ai.unicto.unibot.local.FactMemory
import ai.unicto.unibot.local.FactMemoryStore
import ai.unicto.unibot.ui.components.EmptyState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val ROUTE_FACT_MEMORIES = "unibot/fact_memories"

/**
 * Settings → Saved memories. Lists every "remember that …" fact with its
 * date and a delete button.
 *
 * Facts live in [FactMemoryStore] — encrypted on-device storage, never
 * uploaded. Deleting here removes them permanently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactMemoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { FactMemoryStore(context) }
    var facts by remember { mutableStateOf<List<FactMemory>>(emptyList()) }
    val haptics = rememberHaptic()

    LaunchedEffect(Unit) { facts = store.getAll() }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text("Saved memories") },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (facts.isEmpty()) {
            // [v1.0-wave9a] Shared branded empty state replaces the
            // hand-rolled icon+text column.
            EmptyState(
                icon = Icons.Default.Psychology,
                title = "Nothing memorized yet",
                hint = "In any chat, say \"remember that …\" and I'll keep it here — encrypted, only on this phone.",
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
                    Text(
                        text = "${facts.size} memor${if (facts.size == 1) "y" else "ies"} · stored encrypted on this device only",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                itemsIndexed(facts, key = { _, f -> f.id }) { index, fact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggeredEntrance(index)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fact.text,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = dateLabel(fact.createdAt),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                // Destructive: "Forget" removes the fact permanently.
                                haptics.error()
                                if (store.delete(fact.id)) {
                                    facts = store.getAll()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Forget",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dateLabel(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
