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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.ui.avatar.AgentAvatar
import ai.unicto.unibot.ui.avatar.AgentMood
import ai.unicto.unibot.ui.theme.ChatColors

/**
 * Branded cold start: the dragon face (breathing, IDLE mood) + "unibot"
 * wordmark + a shimmering "waking up…" line, instead of the old blank
 * Surface. Shown while the home shell loads config/sessions — usually under
 * a second.
 */
@Composable
fun ColdStartBrand() {
    val violet = ChatColors.thinking
    val transition = rememberInfiniteTransition(label = "coldstart")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "coldstart_sweep",
    )
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
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "waking up…",
                    fontSize = 14.sp,
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
            }
        }
    }
}
