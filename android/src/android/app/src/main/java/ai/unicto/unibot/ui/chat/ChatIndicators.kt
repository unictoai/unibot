package ai.unicto.unibot.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.theme.ChatColors

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .padding(top = (-offset).dp.coerceAtLeast(0.dp))
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    val infiniteTransition = rememberInfiniteTransition(label = "streamDots")
    Row {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sdot_$i",
            )
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.offset(y = offset.dp),
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
            fontSize = 14.sp,
            color = ChatColors.tertiaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cancel),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ─── Typing Indicator (brand-violet fluid "thinking" animation) ───────────────
// Muse-grade polish: the label gets a slow violet shimmer sweep, the dots
// pulse with staggered scale+alpha (smooth FastOutSlowIn, not a hard bounce),
// and the whole row fades in so it materializes instead of popping.
@Composable
internal fun TypingIndicator() {
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (UnibotApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by ai.unicto.unibot.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "unibot" }
    val violet = ChatColors.thinking

    // Fade-in entrance.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "typing_enter",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    // Shimmer sweep across the label, left → right, looping.
    val sweep by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing_sweep",
    )

    Row(
        modifier = Modifier
            .padding(top = 2.dp, bottom = 8.dp)
            .graphicsLayer { alpha = enterAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            style = TextStyle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        violet.copy(alpha = 0.45f),
                        violet,
                        violet.copy(alpha = 0.45f),
                    ),
                    start = Offset(sweep * 600f - 300f, 0f),
                    end = Offset(sweep * 600f + 300f, 0f),
                ),
            ),
        )
        Spacer(Modifier.width(8.dp))
        repeat(3) { index ->
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0f,
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
                    .graphicsLayer {
                        val s = 0.65f + 0.35f * pulse
                        scaleX = s
                        scaleY = s
                        alpha = 0.35f + 0.65f * pulse
                    }
                    .background(violet, CircleShape),
            )
        }
    }
}
