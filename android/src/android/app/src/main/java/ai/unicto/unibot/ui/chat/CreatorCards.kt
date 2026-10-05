package ai.unicto.unibot.ui.chat
import ai.unicto.unibot.ui.theme.UbColors

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic

/**
 * Animated result cards for the creator tools (Wave 4 / v1.0).
 *
 * Each tool's `execute()` emits a tiny line-based wire format (see
 * tools/CreatorTools.kt KDoc); [CreatorCard] parses it and renders the
 * matching card. Every item staggers in, copy buttons buzz via haptics,
 * and the title-optimizer score ring sweeps to its value. Unknown or
 * malformed payloads fall back to [ToolCallPill]-style plain text so a
 * bad parse never blanks the result.
 */

private data class CreatorPayload(
    val kind: String,
    val title: String,
    val score: Int? = null,
    val issues: List<String> = emptyList(),
    val tags: String? = null,
    val items: List<Pair<String?, String>> = emptyList(), // (label, body)
)

private fun parseCreatorPayload(content: String): CreatorPayload? {
    val lines = content.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    if (lines.isEmpty() || !lines[0].startsWith("CREATOR_CARD:")) return null
    val kind = lines[0].substringAfter("CREATOR_CARD:")
    var title = kind
    var score: Int? = null
    val issues = mutableListOf<String>()
    var tags: String? = null
    val items = mutableListOf<Pair<String?, String>>()
    for (line in lines.drop(1)) {
        when {
            line.startsWith("TITLE:") -> title = line.substringAfter("TITLE:")
            line.startsWith("SCORE:") -> score = line.substringAfter("SCORE:").toIntOrNull()
            line.startsWith("ISSUE:") -> issues += line.substringAfter("ISSUE:")
            line.startsWith("TAGS:") -> tags = line.substringAfter("TAGS:")
            line.startsWith("ITEM:") -> {
                val body = line.substringAfter("ITEM:")
                val split = body.split(" — ", limit = 2)
                if (split.size == 2) items += split[0] to split[1]
                else items += null to body
            }
        }
    }
    return CreatorPayload(kind, title, score, issues, tags, items)
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "clip_captions" -> Icons.Filled.Movie
    "clip_hooks" -> Icons.Outlined.Bolt
    "draft_reply" -> Icons.Outlined.Forum
    "optimize_title" -> Icons.Filled.Title
    "hashtags" -> Icons.Filled.Tag
    else -> Icons.Filled.TextFields
}

/** Dispatcher — renders the matching card, or plain text if parsing fails. */
@Composable
internal fun CreatorCard(
    block: AssistantBlock,
    modifier: Modifier = Modifier,
) {
    val payload = parseCreatorPayload(block.content)
    if (payload == null) {
        Text(
            text = block.content,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.padding(vertical = 4.dp),
        )
    } else {
        CreatorCardShell(payload = payload, modifier = modifier)
    }
}

/** True for the six creator tool names — used by the chat renderers. */
internal fun isCreatorToolName(name: String): Boolean = name == "clip_captions" ||
    name == "clip_hooks" ||
    name == "draft_reply" ||
    name == "optimize_title" ||
    name == "hashtags" ||
    name == "write_script"

@Composable
private fun CreatorCardShell(payload: CreatorPayload, modifier: Modifier = Modifier) {
    val haptics = rememberHaptic()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = kindIcon(payload.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = payload.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        payload.score?.let { ScoreRing(score = it) }

        if (payload.issues.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            payload.issues.forEachIndexed { i, issue ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(i)
                        .padding(vertical = 3.dp),
                ) {
                    Text("• ", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(issue, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        payload.tags?.let { tags ->
            Spacer(Modifier.height(10.dp))
            HashtagChips(tags = tags)
        }

        if (payload.items.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            payload.items.forEachIndexed { i, (label, body) ->
                CreatorItemRow(
                    index = i,
                    label = label,
                    body = body,
                    onCopy = {
                        haptics.success()
                        clipboard.setText(AnnotatedString(body))
                    },
                )
            }
        }
    }
}

@Composable
private fun CreatorItemRow(
    index: Int,
    label: String?,
    body: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = "Copy",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onCopy)
                .padding(2.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Animated score ring for the title optimizer — sweeps from 0 to [score]
 * on [Motion.Emphasis]. Green ≥ 75, amber ≥ 50, red below.
 */
@Composable
private fun ScoreRing(score: Int) {
    val sweep by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = Motion.Emphasis,
            easing = Motion.FastOutSlowIn,
        ),
        label = "score_ring",
    )
    val ringColor = when {
        score >= 75 -> UbColors.success
        score >= 50 -> UbColors.warningDark
        else -> UbColors.errorDark
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            Canvas(Modifier.size(64.dp)) {
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Text(
                text = "$score",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = when {
                score >= 75 -> "Strong title — ship it."
                score >= 50 -> "Decent — the rewrites below lift it."
                else -> "Needs work — try a rewrite."
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HashtagChips(tags: String) {
    val haptics = rememberHaptic()
    val clipboard = LocalClipboardManager.current
    val list = tags.split(" ").filter { it.isNotBlank() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.forEachIndexed { i, tag ->
            Box(
                modifier = Modifier
                    .staggeredEntrance(i)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable {
                        haptics.tap()
                        clipboard.setText(AnnotatedString(tag))
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = tag,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptics.success()
                clipboard.setText(AnnotatedString(tags))
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Copy all",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
