package ai.unicto.unibot.ui.chat
import androidx.compose.foundation.border

import ai.unicto.unibot.ui.theme.Motion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [v0.5.0-agentic-core] Dismissible "I remember" banner.
 *
 * Shown when the current turn matched a saved fact memory ("remember
 * that …"), so the recall is visible — not just silently injected into
 * the model's context. Slides up from the bottom of the message list;
 * tap the × or send the next message to dismiss.
 */
@Composable
internal fun MemoryRecallBanner(
    note: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = note != null,
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = androidx.compose.animation.core.tween(
                Motion.Standard, easing = Motion.FastOutSlowIn,
            ),
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(Motion.Quick)),
        exit = slideOutVertically(
            targetOffsetY = { it / 2 },
            animationSpec = androidx.compose.animation.core.tween(
                Motion.Quick, easing = Motion.LinearOutSlowIn,
            ),
        ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(Motion.Quick)),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = note.orEmpty(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onDismiss)
                    .padding(2.dp),
            )
        }
    }
}
