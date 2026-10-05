package ai.unicto.unibot.ui.swarm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.swarm.SwarmAgentState
import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmLifecycle
import ai.unicto.unibot.swarm.SwarmUiState
import ai.unicto.unibot.swarm.SwarmViewModel
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance

/**
 * [v1.3.0-swarm] The dedicated swarm space — separate from normal chat.
 *
 * Information architecture (from the Kimi swarm research): the
 * orchestrator's task list FIRST, then agents spawning / per-agent
 * progress + results, tool calls and sources inline, and the stitched
 * deliverable at the end.
 *
 * Every agent row reaches an EXPLICIT terminal visual — done / failed /
 * incomplete-flagged (the engine prefixes `[incomplete]` onto a
 * best-effort result) — never silently "done".
 */
@Composable
fun SwarmScreen(
    viewModel: SwarmViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Agent Swarm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            // Result view: the stitched deliverable, per-agent cost, total.
            state.lifecycle == SwarmLifecycle.DONE && state.stitchedResult.isNotBlank() ->
                SwarmResultView(
                    state = state,
                    onNewMission = viewModel::dismissResult,
                    modifier = contentModifier,
                )
            // Active run: plan -> agent ID cards -> controls. CANCELLED lands
            // here too (read-only): the engine keeps partial worker results
            // visible, with a "New mission" exit instead of run controls.
            state.lifecycle == SwarmLifecycle.PLANNING ||
                state.lifecycle == SwarmLifecycle.RUNNING ||
                state.lifecycle == SwarmLifecycle.PAUSED ||
                state.lifecycle == SwarmLifecycle.CANCELLED ->
                SwarmActiveView(
                    state = state,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onCancel = viewModel::cancel,
                    onDismiss = viewModel::dismissResult,
                    modifier = contentModifier,
                )
            // Idle: greeting + composer + crew presets (+ resume/error banners).
            else ->
                SwarmIdleView(
                    state = state,
                    onLaunch = viewModel::launch,
                    onResume = viewModel::resume,
                    // Discard needs the PAUSED-legal path: dismissResult()
                    // rejects interrupted (PAUSED) runs, so it would no-op
                    // here. discardCheckpoint() clears the checkpoint and
                    // returns to IDLE.
                    onDiscard = viewModel::discardCheckpoint,
                    modifier = contentModifier,
                )
        }
    }
}

// ─── Idle: greeting + composer + presets ─────────────────────────────────────

@Composable
private fun SwarmIdleView(
    state: SwarmUiState,
    onLaunch: (String, ai.unicto.unibot.swarm.SwarmCrewPreset) -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mission by remember { mutableStateOf("") }
    var presetId by remember { mutableStateOf("research") }
    val preset = swarmPresetById(presetId) ?: SWARM_PRESETS.first()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.canResume) {
            item {
                ResumeBanner(onResume = onResume, onDiscard = onDiscard)
            }
        }
        if (state.error != null) {
            item {
                ErrorCard(message = state.error)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Put a crew of agents to work",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Describe the mission once. A crew of specialized agents " +
                        "researches, writes and verifies — then stitches everything " +
                        "into one result, with each agent's token cost on the bill.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MissionComposer(
                mission = mission,
                onMissionChange = { mission = it },
                onLaunch = { onLaunch(mission.trim(), preset) },
                launchEnabled = mission.isNotBlank(),
            )
        }
        item {
            Text(
                text = "Crew",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(SWARM_PRESETS, key = { _, p -> p.id }) { index, itemPreset ->
            PresetCard(
                preset = itemPreset,
                selected = itemPreset.id == presetId,
                onSelect = { presetId = itemPreset.id },
                modifier = Modifier.staggeredEntrance(index),
            )
        }
    }
}

@Composable
private fun ResumeBanner(
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "A swarm was interrupted",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Pick up where it left off, or discard it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDiscard) {
                    Text("Discard")
                }
                Button(onClick = onResume) {
                    Text("Resume")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MissionComposer(
    mission: String,
    onMissionChange: (String) -> Unit,
    onLaunch: () -> Unit,
    launchEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = mission,
            onValueChange = onMissionChange,
            label = { Text("What should the swarm do?") },
            placeholder = { Text("e.g. Research the best budget ANC headphones and compare the top 5") },
            minLines = 3,
            maxLines = 6,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onLaunch,
            enabled = launchEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Launch swarm")
        }
    }
}

@Composable
private fun PresetCard(
    preset: ai.unicto.unibot.swarm.SwarmCrewPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.large,
        border = border,
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${preset.roles.size} agents · " +
                        preset.roles.distinct().joinToString(", ") { roleDisplayName(it) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
    }
}

// ─── Active run: plan -> agent ID cards -> controls ──────────────────────────

@Composable
private fun SwarmActiveView(
    state: SwarmUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Mission header: what the swarm is doing, which crew, live cost.
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.mission.ifBlank { "Mission" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LifecyclePill(lifecycle = state.lifecycle)
                Text(
                    text = "${state.crew?.name ?: "Crew"} · ${formatTokens(state.totalTokens)} tokens",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Restored from a checkpoint (the engine lands these at PAUSED):
            // say plainly where the run stands.
            if (state.canResume) {
                Text(
                    text = "Interrupted — resume to continue from the last checkpoint.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // (a) The orchestrator's task list — visible FIRST, before agents.
            item {
                PlanSection(state = state)
            }
            // (b–e) Agent ID cards: codename + role + task + defined output,
            // per-agent progress, tool calls / sources inline.
            itemsIndexed(state.agents, key = { _, agent -> agent.id }) { index, agent ->
                AgentCard(
                    agent = agent,
                    modifier = Modifier.staggeredEntrance(index),
                )
            }
        }
        ControlsRow(
            lifecycle = state.lifecycle,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun LifecyclePill(
    lifecycle: SwarmLifecycle,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (lifecycle) {
        SwarmLifecycle.PLANNING ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SwarmLifecycle.RUNNING ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SwarmLifecycle.PAUSED ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Text(
            text = lifecycle.label(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The decomposition, surfaced prominently during/after PLANNING — the
 * "plan first, approve, then swarm" cautious path. A true approval gate
 * (engine pausing for approval) is a v1.3.x follow-up; for now the plan is
 * highly visible before results stream.
 */
@Composable
private fun PlanSection(
    state: SwarmUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Plan",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.agents.isEmpty()) {
            // Still decomposing — nothing spawned yet.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkingDots(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Decomposing mission into tasks…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.agents.forEach { agent ->
                    TaskChecklistRow(
                        agent = agent,
                        incompleteNote = extractIncompleteNote(agent.result, agent.detail),
                    )
                }
            }
        }
        if (state.lifecycle == SwarmLifecycle.PLANNING) {
            Text(
                text = "The crew works from this plan. Step-by-step approval is on the roadmap.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskChecklistRow(
    agent: SwarmAgentState,
    incompleteNote: String?,
    modifier: Modifier = Modifier,
) {
    val task = agent.displayName.ifBlank { roleDisplayName(agent.role) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            MiniStatusIndicator(status = agent.status, incompleteNote = incompleteNote)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = agentCodename(agent.id),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = task,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniStatusIndicator(
    status: SwarmAgentStatus,
    incompleteNote: String?,
    iconSize: Dp = 16.dp,
) {
    val failed = status == SwarmAgentStatus.FAILED
    val incomplete = !failed && incompleteNote != null && status.isTerminal()
    val sizeModifier = Modifier.size(iconSize)
    when {
        failed -> Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = sizeModifier,
        )
        incomplete -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Incomplete",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = sizeModifier,
        )
        status == SwarmAgentStatus.DONE -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.primary,
            modifier = sizeModifier,
        )
        status == SwarmAgentStatus.WORKING || status == SwarmAgentStatus.VERIFYING ->
            WorkingDots(
                color = MaterialTheme.colorScheme.primary,
                dotSize = 5.dp,
            )
        else -> Icon(
            imageVector = Icons.Filled.RadioButtonUnchecked,
            contentDescription = "Queued",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = sizeModifier,
        )
    }
}

/**
 * Agent ID card: codename + role + assigned task + defined output up top,
 * the streaming current action below, tool calls / sources inline, token
 * count and an explicit terminal state in the footer. Tap to expand for the
 * full activity log and result.
 */
@Composable
private fun AgentCard(
    agent: SwarmAgentState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(agent.id) { mutableStateOf(false) }
    val incompleteNote = extractIncompleteNote(agent.result, agent.detail)
    val failed = agent.status == SwarmAgentStatus.FAILED
    val incomplete = !failed && incompleteNote != null && agent.status.isTerminal()

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ID card header.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MiniStatusIndicator(
                        status = agent.status,
                        incompleteNote = incompleteNote,
                        iconSize = 20.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = agentCodename(agent.id),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        RoleChip(role = agent.role)
                    }
                    Text(
                        text = "Task: ${agent.displayName.ifBlank { roleDisplayName(agent.role) }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Output: ${expectedOutput(agent.role)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }

            // The streaming current action.
            if (agent.currentStep.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (agent.status == SwarmAgentStatus.WORKING ||
                        agent.status == SwarmAgentStatus.VERIFYING
                    ) {
                        WorkingDots(color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = agent.currentStep,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Tool calls / sources, inline (first two lines; the rest lives
            // in the expandable section).
            withoutIncompleteMarker(agent.detail)
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(2)
                .forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

            // Footer: live cost + explicit terminal state.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatTokens(agent.tokensUsed)} tokens",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                AgentStatusText(
                    status = agent.status,
                    incomplete = incomplete,
                )
            }

            // Expandable: full activity + result. Fade only (cheap alpha) —
            // no layout-size animation, per the motion rules.
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(durationMillis = Motion.Quick)),
                exit = fadeOut(animationSpec = tween(durationMillis = Motion.Quick)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (agent.detail.isNotBlank()) {
                        Text(
                            text = "Activity",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = withoutIncompleteMarker(agent.detail),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val cleanResult = withoutIncompleteMarker(agent.result)
                    if (cleanResult.isNotBlank()) {
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = cleanResult,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (incompleteNote != null) {
                        IncompleteBadge(note = incompleteNote)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleChip(
    role: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text = roleDisplayName(role),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AgentStatusText(
    status: SwarmAgentStatus,
    incomplete: Boolean,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when {
        status == SwarmAgentStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        incomplete -> "Incomplete" to MaterialTheme.colorScheme.tertiary
        status == SwarmAgentStatus.DONE -> "Done" to MaterialTheme.colorScheme.primary
        else -> status.label() to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun IncompleteBadge(
    note: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Incomplete result",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Subtle 3-dot pulse for working states — the skill's thinking language.
 * Alpha only (cheap), LinearEasing for indeterminate progress.
 */
@Composable
private fun WorkingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition(label = "swarm_working_dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    // Ambient loop on the Slow token; phase offset keeps the
                    // three dots chasing each other.
                    animation = tween(
                        durationMillis = Motion.Slow,
                        delayMillis = i * (Motion.Slow / 4),
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "swarm_dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape),
            )
        }
    }
}

@Composable
private fun ControlsRow(
    lifecycle: SwarmLifecycle,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A cancelled run is read-only: the timeline stays visible with its
    // partial results, and the only way out is a fresh mission.
    if (lifecycle == SwarmLifecycle.CANCELLED) {
        Button(
            onClick = onDismiss,
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text("New mission")
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (lifecycle) {
            SwarmLifecycle.PLANNING, SwarmLifecycle.RUNNING -> {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Pause")
                }
            }
            SwarmLifecycle.PAUSED -> {
                Button(
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Resume")
                }
            }
            else -> {}
        }
        if (lifecycle == SwarmLifecycle.PLANNING ||
            lifecycle == SwarmLifecycle.RUNNING ||
            lifecycle == SwarmLifecycle.PAUSED
        ) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Cancel")
            }
        }
    }
}

// ─── Result: the stitched deliverable + cost ─────────────────────────────────

@Composable
private fun SwarmResultView(
    state: SwarmUiState,
    onNewMission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Agents whose output stands with an incomplete note — surfaced as a
    // group so partial failure is never silent.
    val flagged = state.agents.mapNotNull { agent ->
        extractIncompleteNote(agent.result, agent.detail)?.let { note -> agent to note }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.error != null) {
            item {
                ErrorCard(message = state.error)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Mission complete",
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (state.mission.isNotBlank()) {
                    Text(
                        text = state.mission,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // (f) The stitched deliverable — document style, no bubble.
        item {
            Text(
                text = state.stitchedResult,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (flagged.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Needs attention",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    flagged.forEach { (agent, note) ->
                        IncompleteBadge(note = "${agentCodename(agent.id)}: $note")
                    }
                }
            }
        }
        // Per-agent cost + total, framed against Kimi's token-burn
        // criticism: on unibot the spend is $0 — your keys, your free tier.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Cost",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.agents.forEach { agent ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = agentCodename(agent.id),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${formatTokens(agent.tokensUsed)} tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Total",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${formatTokens(state.totalTokens)} tokens",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = "$0 spent — your keys, your free tier.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Button(
                onClick = onNewMission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New mission")
            }
        }
    }
}

// ─── No provider: the space explains itself ─────────────────────────────────

/**
 * Shown by the NavHost destination when [resolveSwarmLlmProvider] finds no
 * usable provider. The engine's ViewModel factory requires a provider, so
 * the screen cannot mount — this explains why and routes to setup instead.
 */
@Composable
internal fun SwarmNoProviderScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Agent Swarm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Swarms need a model",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Add a provider first — the swarm runs on your own key, " +
                    "so it costs nothing extra.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = onOpenProviders) {
                Text("Set up a provider")
            }
        }
    }
}
