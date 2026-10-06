package ai.unicto.unibot.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.avatar.AgentAvatar
import ai.unicto.unibot.ui.avatar.AgentMood
import ai.unicto.unibot.ui.theme.ChatColors
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.animationsEnabled

/**
 * Branded cold start: the dragon face (breathing, IDLE mood) + "unibot"
 * wordmark + a shimmering "waking up…" line, instead of the old blank
 * Surface. Shown while the home shell loads config/sessions — usually under
 * a second.
 *
 * v1.4.0 item 65: typography roles (no inline sizes), the shimmer loop on
 * the [Motion.Slow] token, and the ambient sweep gated on
 * [animationsEnabled] so "Remove animations" gets a static line.
 */
@Composable
fun ColdStartBrand() {
    val violet = ChatColors.thinking
    val animated = animationsEnabled()
    val sweep by if (animated) {
        val transition = rememberInfiniteTransition(label = "coldstart")
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.Slow * 3, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "coldstart_sweep",
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }
    Surface(color = ChatColors.background, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AgentAvatar(
                    mood = AgentMood.IDLE,
                    size = 88.dp,
                    contentDescription = null,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "unibot",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "waking up…",
                    style = MaterialTheme.typography.bodyMedium.copy(
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
            }
        }
    }
}
