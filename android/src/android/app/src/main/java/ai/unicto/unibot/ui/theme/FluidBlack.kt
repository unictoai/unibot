package ai.unicto.unibot.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import ai.unicto.unibot.ui.theme.Motion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fluid Black signature: a slow, drifting violet aurora on pure black.
 * Three large soft radial blobs orbit at very low alpha — visible enough to
 * feel alive, dim enough to keep text readable and the battery happy.
 * Only shown when the Fluid Black theme (theme_mode = 3) is active.
 */
@Composable
fun FluidAuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "fluid-aurora")
    // 0..1 phase drivers on long, coprime-ish cycles so the motion never repeats visibly.
    val p1 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(22000), RepeatMode.Restart), label = "p1")
    val p2 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(31000), RepeatMode.Restart), label = "p2")
    val p3 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(17000), RepeatMode.Restart), label = "p3")

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun blob(phase: Float, radiusFrac: Float, color: Color, alpha: Float, xBase: Float, yBase: Float) {
            val a = phase * 2f * PI.toFloat()
            val cx = w * (xBase + 0.22f * cos(a))
            val cy = h * (yBase + 0.18f * sin(a * 1.3f))
            val r = w * radiusFrac
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = alpha),
                    1f to color.copy(alpha = 0f),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        // Violet core, indigo drift, magenta whisper — unibot brand on black.
        blob(p1, 0.75f, Color(0xFF6D28D9), 0.20f, 0.5f, 0.28f)
        blob(p2, 0.65f, Color(0xFF4338CA), 0.16f, 0.55f, 0.72f)
        blob(p3, 0.45f, Color(0xFFA21CAF), 0.10f, 0.45f, 0.5f)
    }
}

/**
 * Send button with a breathing violet glow while it can send.
 * Drop-in replacement for the static circle: same size, same tap target.
 */
@Composable
fun GlowingSendButton(
    canActivate: Boolean,
    onSend: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onActiveColor: Color = MaterialTheme.colorScheme.onPrimary,
    size: Dp = 38.dp,
    fluidGlow: Boolean = LocalFluidBlack.current,
) {
    // v1.5 accessibility: the breathing glow is decorative — when the user
    // asked for reduced motion (or battery saver / low-RAM), hold a static
    // glow instead of running the infinite loop.
    val glow: Float = if (decorativeMotionEnabled()) {
        val t = rememberInfiniteTransition(label = "send-glow")
        t.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1600, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "glow",
        ).value
    } else {
        1f
    }
    // [v0.4.1-visible-premium] Springy press-down so taps feel physical.
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(Motion.Instant, easing = Motion.FastOutSlowIn),
        label = "send_press",
    )
    // v1.5 accessibility: 48dp tap target around the 38dp visual, mirroring
    // the InputCircleButton / scroll-FAB treatment elsewhere in the app.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                enabled = canActivate,
                interactionSource = pressSource,
                indication = null,
                onClick = onSend,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Glow halo behind the disc.
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    if (canActivate && fluidGlow)
                        activeColor.copy(alpha = 0.28f * glow)
                    else Color.Transparent,
                    CircleShape,
                )
                .scale(if (canActivate && fluidGlow) 1f + 0.06f * glow else 1f),
        )
        Box(
            modifier = Modifier
                .size(size)
                .scale(pressScale)
                .background(
                    if (canActivate) activeColor
                    else activeColor.copy(alpha = 0.25f),
                    CircleShape,
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (canActivate) onActiveColor
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Wraps the top-bar agent avatar in a slowly breathing glow ring.
 * The avatar itself is untouched — only light around it moves.
 */
@Composable
fun BreathingGlowAvatar(
    avatar: @Composable () -> Unit,
    glowColor: Color = Color(0xFF8B5CF6),
) {
    val t = rememberInfiniteTransition(label = "avatar-breathe")
    val breathe by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(
                    Brush.radialGradient(
                        0f to glowColor.copy(alpha = 0.45f * breathe),
                        1f to glowColor.copy(alpha = 0f),
                    ),
                    CircleShape,
                ),
        )
        avatar()
    }
}
