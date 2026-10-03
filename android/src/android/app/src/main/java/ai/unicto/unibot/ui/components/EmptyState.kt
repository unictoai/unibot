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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.animationsEnabled
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic

/**
 * Shared branded empty state for every empty list in the app (no chats, no
 * models, no memories, no tasks, no stars, no results…).
 *
 * Brand language: a violet radial-glow circle holding the icon, with the
 * same shimmer sweep the typing indicator uses gliding across it while
 * [Motion.animationsEnabled] is true (static glow when the user disabled
 * animations). Title + hint + optional CTA below, whole block staggering
 * in via [staggeredEntrance].
 *
 * Replaces the dozen hand-rolled icon+text columns scattered across
 * settings/chat/sessions/scheduled screens (Wave 9 audit).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
) {
    val haptics = rememberHaptic()
    val violet = MaterialTheme.colorScheme.primary
    val animated = animationsEnabled()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(0),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Glowing medallion.
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Radial violet glow behind the icon disc.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                violet.copy(alpha = 0.28f),
                                violet.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            // Icon disc.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = violet,
                    modifier = Modifier.size(32.dp),
                )
            }
            // Shimmer sweep across the medallion (ambient, Motion.Slow loop).
            if (animated) {
                val transition = rememberInfiniteTransition(label = "empty_shimmer")
                val sweep by transition.animateFloat(
                    initialValue = -1f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(Motion.Slow * 2, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "empty_sweep",
                )
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    violet.copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                                start = Offset(sweep * 120f - 60f, 0f),
                                end = Offset(sweep * 120f + 60f, 0f),
                            ),
                        ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        if (ctaLabel != null && onCta != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    haptics.tap()
                    onCta()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = violet,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(ctaLabel)
            }
        }
    }
}
