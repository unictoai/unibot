package ai.unicto.unibot.ui.chat

import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [v0.5.0-agentic-core] Follow-up suggestion chips.
 *
 * After an assistant turn completes, three tappable chips offer natural
 * next questions, derived rule-based from the last user message + answer
 * (no model call — instant and free). Tapping a chip sends it as the next
 * message.
 *
 * Shown only under the LAST assistant turn (see ChatFlatItems); chips
 * stagger in and press with the shared spring spec.
 */

/** Pure heuristic — safe to call from composition, memoized by message id. */
fun generateFollowUpChips(userText: String, answerText: String): List<String> {
    val out = mutableListOf<String>()
    val entity = extractEntity(userText, answerText)
    val q = userText.trim()

    // 1. Entity follow-up ("Tell me more about X").
    if (entity != null) {
        out += "Tell me more about $entity"
    } else {
        out += "Tell me more"
    }

    // 2. Shape depends on the question kind.
    val lower = q.lowercase()
    when {
        lower.startsWith("what is") || lower.startsWith("what are") || lower.startsWith("what's") ->
            out += if (entity != null) "How does $entity work?" else "Give me an example"
        lower.startsWith("how") ->
            out += "What are the common mistakes?"
        lower.startsWith("why") ->
            out += "What are the arguments against this?"
        lower.startsWith("who") ->
            out += "Tell me more about them"
        answerText.length > 600 ->
            out += "Summarize this in 3 points"
        else ->
            out += "Give me an example"
    }

    // 3. Forward-looking closer.
    out += when {
        lower.contains("vs") || lower.contains(" or ") -> "Which one should I pick?"
        lower.startsWith("should i") -> "What are the risks?"
        else -> "What should I ask next?"
    }
    return out.take(3).distinct()
}

/**
 * Best-effort entity: longest capitalized word run (2+ words preferred)
 * from the question first, then the answer. Skips sentence-initial words
 * in the answer to avoid "The", "This", etc.
 */
private fun extractEntity(userText: String, answerText: String): String? {
    val run = Regex("\\b([A-Z][a-zA-Z0-9]+(?:\\s+[A-Z][a-zA-Z0-9]+){0,3})\\b")
    fun pick(text: String, skipFirst: Boolean): String? {
        val matches = run.findAll(text).map { it.groupValues[1] }.toList()
        val filtered = matches.filter { it.length >= 4 && it !in STOPWORDS }
        if (filtered.isEmpty()) return null
        // Prefer multi-word runs ("Big Boss", "iPhone 17").
        val multi = filtered.filter { it.contains(' ') }
        val pool = if (multi.isNotEmpty()) multi else filtered
        return pool.maxByOrNull { it.length }
            ?.takeIf { !(skipFirst && text.trimStart().startsWith(it)) }
            ?: pool.firstOrNull()
    }
    return pick(userText, skipFirst = false) ?: pick(answerText, skipFirst = true)
}

private val STOPWORDS = setOf(
    "This", "That", "These", "Those", "There", "Here", "What", "When",
    "Where", "Which", "While", "With", "From", "Your", "YouTube",
)

@Composable
internal fun FollowUpChipsRow(
    chips: List<String>,
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptic()
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEachIndexed { index, chip ->
            // Springy press feedback (same pattern as GlowingSendButton).
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val scale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (pressed) 0.94f else 1f,
                animationSpec = Motion.SpringSpec,
                label = "chip_press_$index",
            )
            AssistChip(
                onClick = {
                    haptics.tap()
                    onChipClick(chip)
                },
                label = { Text(chip, fontSize = 13.sp) },
                modifier = Modifier
                    .staggeredEntrance(index)
                    .graphicsLayer { scaleX = scale; scaleY = scale },
                shape = RoundedCornerShape(18.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    labelColor = MaterialTheme.colorScheme.primary,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                ),
                interactionSource = interactionSource,
            )
        }
    }
}
