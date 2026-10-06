package ai.unicto.unibot.ui.chat

// [P2-branching] Sibling-variant navigation + regenerate/fork actions under
// assistant messages. Rendered as FlatChatItem.BranchActions (see
// ChatFlatItems.kt) — one row per assistant turn, below its content.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R
import ai.unicto.unibot.data.db.MessageVariantEntity
import ai.unicto.unibot.ui.theme.ChatColors

/**
 * Per-anchor branch state: archived [variants] (index ASC) plus which sibling
 * is currently shown. [selectedIndex] ranges over
 * `0..variants.size` where `variants.size` means the LIVE row content.
 */
data class BranchAnchorState(
    val variants: List<MessageVariantEntity>,
    val selectedIndex: Int,
)

/**
 * The action row under an assistant message: sibling pager ("‹ 1/3 ›") when
 * archived variants exist, plus regenerate + fork-from-here ghost buttons.
 *
 * Deliberately quiet — 30dp tall, tertiary tint — so turns without variants
 * gain only two small affordances and the chat rhythm stays intact.
 */
@Composable
internal fun BranchActionsRow(
    viewModel: ChatViewModel,
    anchorUserMessageId: String,
    assistantMessageId: String,
    // v1.4.0 item 16 — tap the token count for the per-message token sheet.
    onTokenClick: (messageId: String) -> Unit = {},
) {
    val variantsByAnchor by viewModel.branchVariants.collectAsState()
    val selectionByAnchor by viewModel.branchSelection.collectAsState()
    val haptics = LocalHapticFeedback.current

    val variants = variantsByAnchor[anchorUserMessageId].orEmpty()
    // selectedIndex == variants.size → live content.
    val selected = selectionByAnchor[anchorUserMessageId]?.coerceIn(0, variants.size)
        ?: variants.size
    val total = variants.size + 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (variants.isNotEmpty()) {
            // Sibling pager pill: ‹ k / n ›
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChatColors.toolCapsuleBg)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PagerChevron(
                    enabled = selected > 0,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.selectBranchVariant(anchorUserMessageId, selected - 1)
                    },
                    icon = Icons.Filled.ChevronLeft,
                    desc = stringResource(R.string.ub_branch_prev_variant),
                )
                Text(
                    text = "${selected + 1} / $total",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                PagerChevron(
                    enabled = selected < variants.size,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.selectBranchVariant(anchorUserMessageId, selected + 1)
                    },
                    icon = Icons.Filled.ChevronRight,
                    desc = stringResource(R.string.ub_branch_next_variant),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (selected < variants.size) {
                // Which model produced the archived sibling (when known).
                val modelName = variants[selected].modelDisplayName
                if (!modelName.isNullOrBlank()) {
                    Text(
                        text = modelName,
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alpha(0.8f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // Regenerate → archive current as a sibling, re-run the turn.
        GhostIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.regenerateBranch(assistantMessageId)
            },
            icon = Icons.Filled.Refresh,
            desc = stringResource(R.string.ub_branch_regenerate),
        )
        // Fork from here → new chat with history up to this turn.
        GhostIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.forkFromMessage(assistantMessageId)
            },
            icon = Icons.Filled.CallSplit,
            desc = stringResource(R.string.ub_branch_fork),
        )
        // v1.4.0 item 16 — per-message token affordance: a muted count that
        // opens the token sheet. Loads async; hidden when the turn recorded
        // no usage (e.g. restored legacy rows).
        TokenCountChip(
            viewModel = viewModel,
            messageId = assistantMessageId,
            onClick = { onTokenClick(assistantMessageId) },
        )
    }
}

/**
 * v1.4.0 item 16 — muted per-message token count ("1.2k tok"). Tapping opens
 * the per-message token sheet. Deliberately quiet — same tertiary tint as the
 * sibling ghost buttons — so turns without recorded usage gain nothing.
 */
@Composable
private fun TokenCountChip(
    viewModel: ChatViewModel,
    messageId: String,
    onClick: () -> Unit,
) {
    var label by remember(messageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(messageId) {
        val stats = viewModel.messageTokenStats(messageId)
        label = stats?.let { "${formatTokenCount(it.total)} tok" }
    }
    label?.let { text ->
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            color = ChatColors.secondaryText,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

private fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun PagerChevron(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = ChatColors.secondaryText,
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(2.dp),
    )
}

@Composable
private fun GhostIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = ChatColors.secondaryText,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .alpha(0.65f)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}
