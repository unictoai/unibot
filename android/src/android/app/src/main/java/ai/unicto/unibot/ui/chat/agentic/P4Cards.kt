package ai.unicto.unibot.ui.chat.agentic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.terminal.UnibotOpenUrlBroker
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.pressableRow
import ai.unicto.unibot.ui.theme.ChatColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * P4 card renderer (v0.2.0).
 *
 * [P4ArtifactHost] is called from the MarkdownBlock intercept
 * (StreamingMarkdownText.kt) whenever a complete ```unibot-* fence is
 * detected. Cards follow the Muse design language (MuseChrome/MuseTones):
 * 16dp radius, hairline border, outline glyphs — and are dark-mode
 * first-class via theme tokens (no hardcoded colors).
 */
@Composable
fun P4ArtifactHost(artifact: P4Artifacts.Artifact, isStreaming: Boolean) {
    when (artifact) {
        is P4Artifacts.Artifact.Card -> ArtifactCard(
            html = artifact.html,
            params = artifact.params,
        )
        is P4Artifacts.Artifact.Todo -> P4TodoCard(artifact)
        is P4Artifacts.Artifact.Research -> P4ResearchCard(artifact, isStreaming)
        is P4Artifacts.Artifact.Checkpoint -> P4CheckpointCard(artifact)
    }
}

/**
 * Supersede registry: to-do / research fences are RE-EMITTED as the run
 * progresses. Only the latest emission per runId renders expanded; older
 * ones collapse to a one-line summary so the chat doesn't fill with stale
 * checklists. StateFlow-backed so a new emission recomposes the old cards.
 */
internal object P4Supersede {
    private val latest = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun note(runId: String, contentHash: Int) {
        latest.update { cur ->
            if (cur[runId] == contentHash) cur else cur + (runId to contentHash)
        }
    }

    @Composable
    fun isLatest(runId: String, contentHash: Int): Boolean {
        val map by latest.collectAsState()
        return map[runId] == null || map[runId] == contentHash
    }
}

@Composable
private fun P4CardFrame(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MuseTones.surface)
            .border(0.6.dp, MuseTones.hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun P4CardHeader(icon: ImageVector, title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = ChatColors.secondaryText,
            )
        }
    }
}

// ── To-do list ───────────────────────────────────────────────────────────

@Composable
private fun P4TodoCard(todo: P4Artifacts.Artifact.Todo) {
    val contentHash = remember(todo) { todo.title.hashCode() * 31 + todo.items.hashCode() }
    SideEffect { P4Supersede.note(todo.runId, contentHash) }
    val latest = P4Supersede.isLatest(todo.runId, contentHash)
    val done = todo.items.count { it.done }
    if (!latest) {
        // Superseded by a newer emission — one quiet line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.TaskAlt,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${todo.title} · $done/${todo.items.size} — updated below",
                fontSize = 13.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    P4CardFrame {
        P4CardHeader(
            icon = Icons.Outlined.TaskAlt,
            title = todo.title,
            trailing = "$done/${todo.items.size}",
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (todo.items.isEmpty()) 0f else done.toFloat() / todo.items.size },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MuseTones.fill,
        )
        Spacer(Modifier.height(8.dp))
        todo.items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
            ) {
                Icon(
                    imageVector = if (item.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (item.done) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.done) ChatColors.secondaryText else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── Research progress ────────────────────────────────────────────────────

@Composable
private fun P4ResearchCard(research: P4Artifacts.Artifact.Research, isStreaming: Boolean) {
    val contentHash = remember(research) {
        research.question.hashCode() * 31 + research.sources.size * 7 + research.step.hashCode()
    }
    SideEffect { P4Supersede.note(research.runId, contentHash) }
    val latest = P4Supersede.isLatest(research.runId, contentHash)
    var expanded by remember { mutableStateOf(false) }
    if (!latest) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Research · ${research.sources.size} sources — updated below",
                fontSize = 13.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    P4CardFrame {
        P4CardHeader(
            icon = Icons.Outlined.Search,
            title = "Deep research",
            trailing = "${research.sources.size} sources",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = research.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (research.step.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isStreaming) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = research.step,
                    fontSize = 13.sp,
                    color = ChatColors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (research.sources.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressableRow { expanded = !expanded }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = if (expanded) "Hide sources" else "Show sources",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    research.sources.forEachIndexed { i, source ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressableRow { UnibotOpenUrlBroker.offer(source.url) }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = "${i + 1}.",
                                fontSize = 13.sp,
                                color = ChatColors.secondaryText,
                                modifier = Modifier.width(22.dp),
                            )
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                tint = ChatColors.secondaryText,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = source.title,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Checkpoint (pause + resume) ──────────────────────────────────────────
// Approval gates are NOT replaced by this card: RiskPolicy's approval cards
// still fire per tool call. This card is for pauses only the USER can clear
// (login, paywall, captcha, a decision) — Resume continues the agent loop,
// it never approves anything.

@Composable
private fun P4CheckpointCard(checkpoint: P4Artifacts.Artifact.Checkpoint) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    val kindLabel = when (checkpoint.kind) {
        "login" -> "Login needed"
        "paywall" -> "Paywall"
        "approval" -> "Your call"
        else -> "Paused"
    }
    P4CardFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = checkpoint.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (checkpoint.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = checkpoint.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = ChatColors.secondaryText,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { P4Actions.resumeFromCheckpoint() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Resume")
            }
            TextButton(onClick = { dismissed = true }) {
                Text("Dismiss", color = ChatColors.secondaryText)
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.HourglassTop,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Resume continues the task — it never approves a payment or login for you.",
                fontSize = 12.sp,
                color = ChatColors.secondaryText,
            )
        }
    }
}
