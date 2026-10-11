package ai.unicto.unibot.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.theme.ChatColors

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    // [v1.5-ui-redesign] transform-only pulse: the old version animated
    // padding-top, a layout property that re-measures on every frame.
    // alpha + translationY via graphicsLayer costs no layout passes.
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        translationY = -2f * pulse
                        alpha = 0.4f + 0.6f * pulse
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots pulsing inline with text.
// [v1.5-ui-redesign] alpha pulse only: the old version animated offset-y, a
// layout property. Dots sit on the text baseline tier, muted.
@Composable
internal fun StreamingDotsText() {
    val infiniteTransition = rememberInfiniteTransition(label = "streamDots")
    Row {
        repeat(3) { i ->
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sdot_$i",
            )
            Text(
                text = ".",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = pulse },
            )
        }
    }
}

// ─── Compaction progress ──────────────────────────────────────────────────────

/**
 * [T-android-compact-progress] Live status while a compaction runs, with a
 * Cancel affordance.
 *
 * Compaction used to present as a single unchanging flag for however long it
 * took — which on a slow or rate-limited model could be many minutes, and is
 * indistinguishable from a hang. This ticks once a second so the elapsed count
 * visibly moves, and names the split segment when the run had to fall back to
 * summarising halves, so "still working" is legible rather than inferred.
 */
@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    // Re-reads the clock every second; the changing value is what makes the
    // row demonstrably alive.
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (progress.depth > 0) {
                // Only surfaced once a split actually happened — saying
                // "segment 1" on the common single-call path would imply a
                // complexity that isn't there.
                stringResource(
                    R.string.compact_progress_split,
                    elapsedSec,
                    progress.callsIssued,
                    progress.callBudget,
                )
            } else {
                stringResource(R.string.compact_progress, elapsedSec)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ChatColors.tertiaryText,
            modifier = Modifier.weight(1f),
        )
        // [v1.5-ui-redesign] a real TextButton: proper ripple, touch target
        // and accessibility role instead of a bare clickable Text.
        TextButton(
            onClick = onCancel,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(text = stringResource(R.string.cancel))
        }
    }
}

// ─── Typing Indicator (brand-violet fluid "thinking" animation) ───────────────
// Muse-grade polish: the label gets a slow violet shimmer sweep, the dots
// pulse with staggered scale+alpha (smooth FastOutSlowIn, not a hard bounce),
// and the whole row fades in so it materializes instead of popping.

/**
 * Staged reasoning states for the thinking indicator (v0.4.0 premium feel).
 *
 * The indicator label follows whichever stage is active, so a long turn reads
 * as progress ("Reading your message…" → "Thinking…" → "Writing…") instead of
 * one static line. [ChatViewModel.thinkingStage] is the source of truth;
 * [IDLE] means no turn is live and the indicator should be hidden.
 */
enum class ThinkingState(val label: String) {
    /** No active turn — [TypingIndicator] renders nothing. */
    IDLE(""),

    /** The turn just started; the model is reading the prompt. */
    READING("Reading your message…"),

    /** A web search or tool call is in flight. */
    SEARCHING("Searching the web…"),

    /** The model is reasoning — the default while a turn is live. */
    REASONING("Thinking…"),

    /** Tokens are streaming in. */
    WRITING("Writing…"),
}

@Composable
internal fun TypingIndicator() {
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (UnibotApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by ai.unicto.unibot.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "unibot" }
    TypingIndicatorRow(label = stringResource(R.string.chat_typing_indicator, soulName))
}

/**
 * Staged variant of [TypingIndicator]: the label names the active
 * [ThinkingState] instead of the generic "<name> is thinking…". The shimmer
 * sweep and pulsing dots are identical — only the label changes.
 *
 * [ThinkingState.IDLE] renders nothing; callers should hide the row then.
 */
@Composable
internal fun TypingIndicator(stage: ThinkingState) {
    if (stage == ThinkingState.IDLE) return
    TypingIndicatorRow(label = stage.label)
}

/** Shared muted-label + pulsing-dots row behind both [TypingIndicator] overloads. */
@Composable
private fun TypingIndicatorRow(label: String) {
    val violet = ChatColors.thinking

    // Fade-in entrance.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "typing_enter",
    )

    Row(
        modifier = Modifier
            .padding(top = 2.dp, bottom = 8.dp)
            .graphicsLayer { alpha = enterAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // [v1.5-ui-redesign] plain muted label — the looping gradient-text
        // shimmer is gone (MOTION_INTENSITY 2: a perpetual loop must
        // communicate a state, and "still working" is already said by the
        // dots). Label drops to tertiary tier; answer text keeps full
        // contrast elsewhere.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ChatColors.tertiaryText,
        )
        Spacer(Modifier.width(8.dp))
        // Subtle 3-dot pulse: alpha only — no scale, no layout-affecting
        // props, no gradient. This is the thinking state's one motion.
        val infiniteTransition = rememberInfiniteTransition(label = "typing")
        repeat(3) { index ->
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, delayMillis = index * 200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "typing_dot_$index",
            )
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(violet.copy(alpha = 0.8f), CircleShape),
            )
        }
    }
}
