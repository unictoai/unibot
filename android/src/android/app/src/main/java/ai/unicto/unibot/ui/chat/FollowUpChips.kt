package ai.unicto.unibot.ui.chat

import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

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
            // [v1.5-ui-redesign] hand-rolled chip (the BOM's AssistChip API
            // shifted): neutral hairline + full-contrast text, standard M3
            // ripple press. No springy scale, no violet — the accent is
            // reserved for the send button. Staggered entrance kept: 3
            // chips, first entrance only, 40ms steps (within the bar).
            Box(
                modifier = Modifier
                    .staggeredEntrance(index)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(18.dp),
                    )
                    .clickable(
                        onClick = {
                            haptics.tap()
                            onChipClick(chip)
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = chip,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
