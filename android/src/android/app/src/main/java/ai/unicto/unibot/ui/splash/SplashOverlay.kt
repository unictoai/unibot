package ai.unicto.unibot.ui.splash

import ai.unicto.unibot.R
import ai.unicto.unibot.ui.theme.Motion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * [v0.4.2-first-impression] Lightweight launch splash overlay.
 *
 * An in-app splash (not the Android 12 SplashScreen API — minSdk is 26 and
 * the androidx splashscreen compat library is deliberately not added; this
 * zero-dependency overlay covers every API level identically).
 *
 * Behaviour:
 * - The app's real content composes underneath immediately — this overlay
 *   never delays readiness, it only covers the first paint.
 * - The launcher icon scales in (0.8 → 1.0) and fades in on
 *   [Motion.Emphasis]; the "unibot" wordmark fades up just behind it.
 * - After a ~600ms brand beat the overlay exits with a fade + slight
 *   upward drift ([Motion.Standard]), then [onDismissed] fires so the host
 *   can drop it from composition entirely.
 *
 * Shown once per process start (host holds the visibility state in a plain
 * `remember`, so rotation never replays it).
 */
@Composable
fun SplashOverlay(onDismissed: () -> Unit) {
    var visible by remember { mutableStateOf(true) }

    // Brand beat, then start the exit.
    LaunchedEffect(Unit) {
        delay(600)
        visible = false
    }
    // Once the exit animation has had time to finish, tell the host to
    // remove the overlay so it costs nothing afterwards.
    LaunchedEffect(visible) {
        if (!visible) {
            delay(Motion.Standard.toLong() + 50)
            onDismissed()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(Motion.Emphasis, easing = Motion.FastOutSlowIn),
        ) + scaleIn(
            initialScale = 0.92f,
            animationSpec = tween(Motion.Emphasis, easing = Motion.FastOutSlowIn),
        ),
        exit = fadeOut(
            animationSpec = tween(Motion.Standard, easing = Motion.LinearOutSlowIn),
        ),
        label = "splash_overlay",
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Logo entrance: scale + fade, slightly bolder than the
                // overlay's own enter so the mark "pops" first.
                var logoIn by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { logoIn = true }
                val logoScale by animateFloatAsState(
                    targetValue = if (logoIn) 1f else 0.8f,
                    animationSpec = tween(Motion.Emphasis, easing = Motion.FastOutSlowIn),
                    label = "splash_logo_scale",
                )
                val logoAlpha by animateFloatAsState(
                    targetValue = if (logoIn) 1f else 0f,
                    animationSpec = tween(Motion.Emphasis, easing = Motion.FastOutSlowIn),
                    label = "splash_logo_alpha",
                )
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(112.dp)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                            alpha = logoAlpha
                        },
                )
                Spacer(Modifier.height(16.dp))
                // Wordmark: fades in a touch later via the same entrance.
                val wordAlpha by animateFloatAsState(
                    targetValue = if (logoIn) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = Motion.Emphasis,
                        delayMillis = 120,
                        easing = Motion.FastOutSlowIn,
                    ),
                    label = "splash_word_alpha",
                )
                Text(
                    text = "unibot",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.graphicsLayer { alpha = wordAlpha },
                )
            }
        }
    }
}
