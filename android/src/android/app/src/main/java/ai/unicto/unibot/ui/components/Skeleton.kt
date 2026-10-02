package ai.unicto.unibot.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.theme.Motion

/**
 * Violet-tinted skeleton placeholders for the v0.4.0 "premium feel" pass —
 * shown while a list is loading instead of a bare spinner, so the screen
 * already has the shape of what's coming.
 *
 * The shimmer reuses the linear-gradient sweep technique from the chat
 * typing indicator: a violet band travelling left → right on a [Motion.Slow]
 * loop. Each public composable runs one infinite transition — cheap, no
 * per-frame allocations (the brush is recreated only when the animated
 * offset changes, which is the shimmer itself).
 *
 * (This lives in ui.components rather than ui.muse: [ai.unicto.unibot.ui.muse.SkeletonCard]
 * already exists for feed/artifact placeholders with a white-band sweep —
 * these are the violet brand variants for settings/chat surfaces.)
 */

/** Shared shimmer clock — call once per loading surface, pass [sweep] down. */
@Composable
private fun rememberSkeletonSweep(): Float {
    val transition = rememberInfiniteTransition(label = "ubSkeleton")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Slow, easing = Motion.Linear),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton_sweep",
    )
    return sweep
}

/** The violet shimmer brush for a given [sweep] position. */
@Composable
private fun skeletonBrush(sweep: Float): Brush {
    val violet = MaterialTheme.colorScheme.primary
    return Brush.linearGradient(
        colors = listOf(
            violet.copy(alpha = 0.05f),
            violet.copy(alpha = 0.16f),
            violet.copy(alpha = 0.05f),
        ),
        start = Offset(sweep * 500f - 250f, 0f),
        end = Offset(sweep * 500f + 250f, 0f),
    )
}

/**
 * A single placeholder bar — the building block. [widthFraction] lets the
 * last line of a block run short like real text.
 */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 14.dp,
) {
    val sweep = rememberSkeletonSweep()
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(7.dp))
            .background(skeletonBrush(sweep)),
    )
}

/**
 * A placeholder card of [lines] bars, shaped like a settings row group.
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    lineHeight: Dp = 14.dp,
) {
    val sweep = rememberSkeletonSweep()
    val brush = skeletonBrush(sweep)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(lines) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(lineHeight)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            )
        }
    }
}

/**
 * A vertical stack of [count] placeholder cards with [spacing] — drop-in
 * replacement for a "loading…" spinner above a list.
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 4,
    linesPerCard: Int = 2,
    spacing: Dp = 12.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(count) {
            SkeletonCard(lines = linesPerCard)
        }
    }
}
