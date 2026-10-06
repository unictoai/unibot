package ai.unicto.unibot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * v1.4.0 item 76 — context-usage glow in the composer.
 *
 * A 3dp hairline directly above the composer showing how full the active
 * model's context window is: the fill fraction grows as the conversation
 * plus the current draft fill the window. One theme-token color
 * ([MaterialTheme.colorScheme.primary] at restrained alpha), no animation
 * on the value itself — it moves only when the text changes, which is
 * already a recomposition.
 *
 * The estimate is deliberately cheap: (message chars + draft chars) / 4,
 * an O(n) pass over string lengths (no tokenization, no allocation).
 * Attachments and tool payloads are not counted, so the bar reads
 * slightly optimistic on media-heavy chats — documented, not hidden.
 */
object ContextUsage {
    /** Tokens-per-character estimate used by the glow. Kept as a named constant for tests. */
    const val CHARS_PER_TOKEN = 4

    /**
     * Pure fraction of [contextWindowTokens] consumed by [usedChars] of
     * history plus [draftChars] of unsent composer text. Returns 0 when the
     * window is unknown so the glow hides itself.
     */
    fun fraction(usedChars: Int, draftChars: Int, contextWindowTokens: Int): Float {
        if (contextWindowTokens <= 0) return 0f
        val usedTokens = (usedChars.coerceAtLeast(0) + draftChars.coerceAtLeast(0)) / CHARS_PER_TOKEN
        return (usedTokens.toFloat() / contextWindowTokens).coerceIn(0f, 1f)
    }
}

@Composable
fun ContextUsageGlow(
    messages: List<ChatMessage>,
    draftText: String,
    contextWindowTokens: Int,
    modifier: Modifier = Modifier,
) {
    if (contextWindowTokens <= 0) return
    val fraction = remember(messages, draftText, contextWindowTokens) {
        val usedChars = messages.sumOf { it.content.length }
        ContextUsage.fraction(usedChars, draftText.length, contextWindowTokens)
    }
    if (fraction <= 0f) return
    val percent = (fraction * 100).toInt()
    Box(
        modifier = modifier
            .semantics { contentDescription = "Context window $percent percent full" }
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
    }
}
