package ai.unicto.unibot.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tour
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.animationsEnabled

/**
 * Backlog item 97 — the guided first-run tour.
 *
 * Six stops in about two minutes: welcome, chat, providers (BYOK), swarm,
 * privacy, done. Skippable from the top-right Skip button at any point;
 * resumable — the current stop is persisted via [GuidedTour.saveStep] and
 * restored on re-entry; shown once (fresh installs only, flagged on Skip or
 * Done). The help center offers "Replay tour", which resets the flag and
 * navigates back here.
 *
 * Professional restraint per the UI skill: tonal surfaces, one accent,
 * motion capped at [Motion.Standard].
 */
@Composable
fun GuidedTourScreen(
    onAddProvider: () -> Unit = {},
    onOpenSwarm: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val animated = animationsEnabled()
    var step by remember { mutableIntStateOf(GuidedTour.resumeStep(context)) }

    fun persist(s: Int) = GuidedTour.saveStep(context, s)

    fun finish() {
        GuidedTour.markSeen(context)
        GuidedTour.saveStep(context, 0)
        onDone()
    }

    BackHandler {
        if (step > 0) {
            step -= 1
            persist(step)
        } else {
            finish()
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GuidedTourDots(current = step, total = GuidedTour.STEPS, modifier = Modifier.weight(1f).padding(start = 16.dp))
                TextButton(onClick = ::finish) {
                    Text("Skip", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (!animated) {
                        fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                    } else {
                        val forward = targetState > initialState
                        (slideInHorizontally(tween(Motion.Standard, easing = Motion.FastOutSlowIn)) { if (forward) it / 6 else -it / 6 } +
                            fadeIn(tween(Motion.Quick))) togetherWith
                            (slideOutHorizontally(tween(Motion.Quick, easing = Motion.LinearOutSlowIn)) { if (forward) -it / 6 else it / 6 } +
                                fadeOut(tween(Motion.Quick)))
                    }
                },
                label = "guidedTourStep",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    0 -> GuidedTourStop(
                        icon = Icons.Outlined.Tour,
                        title = "A quick tour of unibot",
                        body = "Two minutes, four stops: how to chat, how to plug in your own model keys, what the swarm does, and how your data stays private.",
                        primaryLabel = "Start the tour",
                        onPrimary = { step = 1; persist(1) },
                    )
                    1 -> GuidedTourStop(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = "Chat with any model",
                        body = "Pick a model from the chip above the composer and switch mid-chat any time — context is kept. You bring the key, so you pay the provider directly; unibot adds nothing on top.",
                        primaryLabel = "Continue",
                        onPrimary = { step = 2; persist(2) },
                    )
                    2 -> GuidedTourStop(
                        icon = Icons.Outlined.Key,
                        title = "Bring your own key",
                        body = "Free tiers from Groq, Cerebras, Mistral, DeepSeek and more work out of the box — no card needed. Keys are validated before saving and stored encrypted on this phone, never sent anywhere else.",
                        primaryLabel = "Continue",
                        onPrimary = { step = 3; persist(3) },
                        actionLabel = "Add a provider key",
                        onAction = onAddProvider,
                    )
                    3 -> GuidedTourStop(
                        icon = Icons.Outlined.Hub,
                        title = "The swarm does deep work",
                        body = "Give the crew a mission: a manager plans, workers research one by one, and a verifier checks the result before you see it. Long missions keep running while you do other things.",
                        primaryLabel = "Continue",
                        onPrimary = { step = 4; persist(4) },
                        actionLabel = "Open the swarm",
                        onAction = onOpenSwarm,
                    )
                    4 -> GuidedTourStop(
                        icon = Icons.Outlined.Lock,
                        title = "Private by architecture",
                        body = "No account, no tracking, no ads. Your chats go to your providers and nowhere else — and the traffic log shows every request the app sends, so you never have to take our word for it.",
                        primaryLabel = "Continue",
                        onPrimary = { step = 5; persist(5) },
                        actionLabel = "See the privacy dashboard",
                        onAction = onOpenPrivacy,
                    )
                    else -> GuidedTourStop(
                        icon = Icons.Filled.Check,
                        title = "You're set",
                        body = "That is the whole tour. You can replay it any time from Settings → Help center, and the help center answers the rest offline — no connection needed.",
                        primaryLabel = "Start chatting",
                        onPrimary = ::finish,
                    )
                }
            }
        }
    }
}

/** One stop: tonal icon disc, title, body, an optional deep-link action, and the primary pill. */
@Composable
private fun GuidedTourStop(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onAction, shape = RoundedCornerShape(50)) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onPrimary,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Stop indicator: dots, the current one longer and accented. */
@Composable
private fun GuidedTourDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(width = if (i == current) 20.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}
