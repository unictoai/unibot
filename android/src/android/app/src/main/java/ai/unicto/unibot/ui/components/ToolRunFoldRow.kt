package ai.unicto.unibot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.data.model.ToolRunFoldSummary

/**
 * v1.4.0 item 80 — folded tool-call row for agent runs.
 *
 * Renders [summary]'s one-line label ("Ran 3 tools · 9s", muted) with an
 * expand chevron; tapping reveals [expandedContent] (the per-tool rows)
 * with an alpha crossfade. Collapsed by default — an agent run reads as
 * one quiet line until the user asks for detail.
 *
 * Professional-UI bar: theme tokens only (no hardcoded colors / sizes),
 * 48dp touch target, expand/collapse is alpha-only (≤300ms) — never
 * layout-size animation — and the row carries no bubble, card, or
 * decoration. The chat renderer (ui/chat) maps its tool blocks onto
 * [ai.unicto.unibot.data.model.ToolCallSummary] and passes the detail
 * rows as [expandedContent].
 */
@Composable
fun ToolRunFoldRow(
    summary: ToolRunFoldSummary,
    modifier: Modifier = Modifier,
    expandedContent: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Collapse tool calls" else "Expand tool calls",
                ) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            expandedContent()
        }
    }
}
