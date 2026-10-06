package ai.unicto.unibot.ui.automation

import ai.unicto.unibot.automation.BackgroundAgent
import ai.unicto.unibot.automation.BackgroundAgentRun
import ai.unicto.unibot.automation.BackgroundAgentSchedule
import ai.unicto.unibot.automation.BackgroundAgentScheduler
import ai.unicto.unibot.automation.BackgroundAgentStore
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Item 91 — the background agents manager: every agent with its schedule,
 * an enable switch (gated on explicit consent), a kill switch, run-once,
 * and the callback cards of recent runs.
 *
 * Nothing here runs without the per-agent consent granted in the editor —
 * the enable switch stays disabled until then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundAgentsScreen(
    onBack: () -> Unit,
    onEditAgent: (agentId: String?) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { BackgroundAgentStore(context) }
    val scheduler = remember { BackgroundAgentScheduler(context) }
    val agents by store.agents.collectAsState()
    val runs by store.runs.collectAsState()
    val haptics = rememberHaptic()

    var pendingDelete by remember { mutableStateOf<BackgroundAgent?>(null) }
    var consentPrompt by remember { mutableStateOf<BackgroundAgent?>(null) }

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = { Text("Background agents", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { haptics.tap(); onEditAgent(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "New agent")
            }
        },
    ) { padding ->
        if (agents.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No background agents yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Define an agent with a pinned model and a schedule — it runs on its own, " +
                        "even with the app closed, and reports back here. Battery-friendly: " +
                        "runs wait for a good moment unless you say otherwise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(agents, key = { it.id }) { agent ->
                val agentRuns = runs.filter { it.agentId == agent.id }.take(3)
                MuseCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { haptics.tap(); onEditAgent(agent.id) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(agent.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                scheduleSummary(agent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!agent.consentGranted) {
                                Text(
                                    "Needs your permission to run",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        // Kill switch — always available, consent or not.
                        IconButton(onClick = {
                            haptics.tap()
                            scheduler.kill(agent.id)
                        }) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "Stop agent",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            haptics.tap()
                            scheduler.runOnce(agent.id)
                        }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Run once now",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { haptics.error(); pendingDelete = agent }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            if (agent.enabled) "On" else "Off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = agent.enabled,
                            // The switch is dead until consent is granted in the editor.
                            enabled = agent.consentGranted,
                            onCheckedChange = { checked ->
                                haptics.toggle()
                                if (checked && !agent.consentGranted) {
                                    consentPrompt = agent
                                    return@Switch
                                }
                                val updated = store.setEnabled(agent.id, checked)
                                if (updated != null) {
                                    if (checked) scheduler.schedule(updated)
                                    else scheduler.kill(agent.id)
                                }
                            },
                        )
                    }
                    // Callback cards — the latest runs.
                    agentRuns.forEach { run ->
                        AgentRunCard(
                            run = run,
                            onOpenSession = { run.sessionId?.let(onOpenSession) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${agent.name}\"?") },
            text = { Text("Its schedule is cancelled. Past run records stay visible.") },
            confirmButton = {
                UnibotTextButton(onClick = {
                    haptics.error()
                    scheduler.kill(agent.id)
                    store.delete(agent.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                UnibotTextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    consentPrompt?.let { agent ->
        AlertDialog(
            onDismissRequest = { consentPrompt = null },
            title = { Text("Permission needed") },
            text = {
                Text(
                    "Background agents run on their own and use your API key. " +
                        "Open \"${agent.name}\" and tick the permission checkbox to allow it.",
                )
            },
            confirmButton = {
                UnibotTextButton(onClick = {
                    consentPrompt = null
                    onEditAgent(agent.id)
                }) { Text("Open settings") }
            },
            dismissButton = {
                UnibotTextButton(onClick = { consentPrompt = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AgentRunCard(
    run: BackgroundAgentRun,
    onOpenSession: () -> Unit,
) {
    val time = remember(run.startedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(run.startedAt))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .let {
                if (run.sessionId != null) it.clickable(onClick = onOpenSession) else it
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    run.finishedAt == null -> "Running…"
                    run.ok -> "Finished"
                    else -> "Failed"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    run.finishedAt == null -> MaterialTheme.colorScheme.primary
                    run.ok -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val detail = run.error ?: run.preview
        if (detail != null) {
            Text(
                detail.take(160),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private fun scheduleSummary(agent: BackgroundAgent): String {
    val whenText = when (agent.schedule) {
        BackgroundAgentSchedule.MANUAL -> "Manual"
        BackgroundAgentSchedule.INTERVAL -> "Every ${agent.intervalMinutes} min"
        BackgroundAgentSchedule.DAILY ->
            "Daily at %02d:%02d".format(agent.dailyHour, agent.dailyMinute)
    }
    val guards = buildList {
        if (agent.batteryNotLow) add("battery OK")
        if (agent.requireCharging) add("charging")
        if (agent.requireUnmetered) add("unmetered")
    }
    return if (guards.isEmpty()) whenText else "$whenText · ${guards.joinToString(", ")}"
}
