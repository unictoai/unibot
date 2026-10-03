package ai.unicto.unibot.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.animationsEnabled

/**
 * The one brand loader for the whole app (Wave 9).
 *
 * Three violet dots breathing in a staggered pulse on a [Motion.Slow]
 * loop — the same visual language as the typing indicator's dots, so
 * "waiting" looks identical everywhere. When the user disabled animations
 * ([Motion.animationsEnabled] false) the dots render statically instead
 * of looping forever.
 *
 * Replaces ad-hoc [androidx.compose.material3.CircularProgressIndicator]
 * usage in chat-adjacent loading states; data screens keep using
 * [SkeletonList]/[SkeletonCard] from Skeleton.kt.
 */
@Composable
fun UnibotLoader(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val violet = MaterialTheme.colorScheme.primary
    val animated = animationsEnabled()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (animated) {
            val transition = rememberInfiniteTransition(label = "unibot_loader")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { index ->
                    val pulse by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                Motion.Slow,
                                delayMillis = index * 180,
                                easing = LinearEasing,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "loader_dot_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer {
                                val s = 0.7f + 0.3f * pulse
                                scaleX = s
                                scaleY = s
                                alpha = 0.4f + 0.6f * pulse
                            }
                            .background(violet, CircleShape),
                    )
                }
            }
        } else {
            // Static: same layout, no infinite loop (accessibility).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(violet.copy(alpha = 0.6f), CircleShape),
                    )
                }
            }
        }
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
