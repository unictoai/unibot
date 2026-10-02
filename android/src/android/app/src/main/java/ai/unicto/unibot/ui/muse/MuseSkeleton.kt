package ai.unicto.unibot.ui.muse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.home.MuseTones

/**
 * Skeleton loading card: a shimmer sweep across placeholder bars, used while
 * feed/goals/library content loads. One infinite transition per card — cheap,
 * no per-frame allocations (the brush is recreated only when the animated
 * offset changes, which is the shimmer itself).
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    lineHeight: Dp = 14.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    // The sweep reads on both themes: a translucent white band over the card tone.
    val base = MuseTones.surface
    val brush = Brush.linearGradient(
        colors = listOf(base, Color.White.copy(alpha = 0.09f), base),
        start = Offset(shimmerX * 300f - 300f, 0f),
        end = Offset(shimmerX * 300f, 0f),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(brush)
            .padding(16.dp),
    ) {
        repeat(lines) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(lineHeight)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MuseTones.fill),
            )
            if (i < lines - 1) Spacer(Modifier.height(10.dp))
        }
    }
}
