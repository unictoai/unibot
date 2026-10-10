package ai.unicto.unibot.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.planner.AgentPlan
import ai.unicto.unibot.planner.PlanLifecycle
import ai.unicto.unibot.planner.PlanStep
import ai.unicto.unibot.planner.PlanStepState

/**
 * Live plan checklist. Visual pattern follows the swarm's PlanApprovalCard
 * (Surface + title + steps), but this card tracks execution: steps move
 * pending → doing → done, with failed and skipped states.
 *
 * Every action is optional — a producer only passes the callbacks its engine
 * can actually honor, so no dead controls ever render.
 */
@Composable
fun PlanChecklistCard(
    plan: AgentPlan,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onEditStep: ((stepId: String, newText: String) -> Unit)? = null,
    onSkipStep: ((stepId: String) -> Unit)? = null,
    onRetryStep: ((stepId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var editingStep by remember(plan.id) { mutableStateOf<PlanStep?>(null) }

    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = plan.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = planProgressLabel(plan),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (plan.lifecycle) {
                    PlanLifecycle.RUNNING -> if (onPause != null) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause plan")
                        }
                    }
                    PlanLifecycle.PAUSED -> if (onResume != null) {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume plan")
                        }
                    }
                    else -> Unit
                }
            }
            plan.steps.forEach { step ->
                PlanStepRow(
                    step = step,
                    onEdit = if (onEditStep != null && !plan.isTerminal) {
                        { editingStep = step }
                    } else {
                        null
                    },
                    onSkip = if (onSkipStep != null && !plan.isTerminal &&
                        (step.state == PlanStepState.PENDING || step.state == PlanStepState.DOING)
                    ) {
                        { onSkipStep(step.id) }
                    } else {
                        null
                    },
                    onRetry = if (onRetryStep != null && !plan.isTerminal &&
                        step.state == PlanStepState.FAILED
                    ) {
                        { onRetryStep(step.id) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    editingStep?.let { step ->
        var draft by remember(step.id) { mutableStateOf(step.text) }
        AlertDialog(
            onDismissRequest = { editingStep = null },
            title = { Text("Edit step", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditStep?.invoke(step.id, draft)
                        editingStep = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingStep = null }) { Text("Cancel") }
            },
        )
    }
}

private fun planProgressLabel(plan: AgentPlan): String {
    val base = "${plan.finishedCount} of ${plan.steps.size} done"
    return when (plan.lifecycle) {
        PlanLifecycle.PAUSED -> "$base · paused"
        PlanLifecycle.DONE -> "Complete"
        PlanLifecycle.FAILED -> "$base · failed"
        PlanLifecycle.CANCELLED -> "$base · cancelled"
        PlanLifecycle.RUNNING -> base
    }
}

@Composable
private fun PlanStepRow(
    step: PlanStep,
    onEdit: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            PlanStepIcon(state = step.state)
        }
        val textModifier = if (onEdit != null) {
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onEdit)
        } else {
            Modifier.weight(1f)
        }
        Text(
            text = step.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.state == PlanStepState.SKIPPED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry") }
        } else if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun PlanStepIcon(state: PlanStepState) {
    when (state) {
        PlanStepState.PENDING -> Icon(
            imageVector = Icons.Filled.RadioButtonUnchecked,
            contentDescription = "Pending",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        PlanStepState.DOING -> Icon(
            imageVector = Icons.Filled.RadioButtonUnchecked,
            contentDescription = "In progress",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        PlanStepState.DONE -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        PlanStepState.FAILED -> Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        PlanStepState.SKIPPED -> Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "Skipped",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
