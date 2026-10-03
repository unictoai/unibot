package ai.unicto.unibot.ui.onboarding

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.animationsEnabled
import ai.unicto.unibot.ui.theme.staggeredEntrance

/**
 * v1.1.2 — the 60-second guided first run. A four-page animated tour shown only on a
 * fresh install, before [FirstRunSetupScreen]:
 *
 * 1. **Welcome** — the ninja mascot, "Meet unibot", the one-line pitch.
 * 2. **Choice** — two big cards: paste a provider API key (the existing provider
 *    onboarding) or try offline (the existing on-device model download list).
 * 3. **First chat** — a "Say hello 👋" suggested prompt chip that drops into the
 *    main chat with the composer pre-filled.
 * 4. **Done** — "You're set!" plus two or three tips (voice-mode mic, the 31
 *    connectors, on-device privacy).
 *
 * Shown once, ever: a persistent flag is set on completion or skip, and the tour
 * only ever qualifies on a fresh install (install time ≈ update time), so users
 * upgrading from an earlier version never see it. Finishing the tour also marks
 * the legacy setup's source step chosen, so [FirstRunSetupScreen] resumes at
 * models/hands/meet instead of showing its own welcome page again.
 */
object OnboardingTour {
    private const val PREFS = "unibot"
    private const val KEY_SEEN = "tour.seen.v1"

    fun isSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** True when this install looks brand-new (not an upgrade of an earlier version). */
    fun isFreshInstall(context: Context): Boolean = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime - info.firstInstallTime < 10 * 60_000L
    } catch (_: Exception) {
        false
    }

    /** Only on a fresh install, only while the setup flow would show anyway, only once. */
    fun shouldShow(context: Context, hasProviders: Boolean, hasSessions: Boolean, setupDone: Boolean): Boolean =
        !isSeen(context) && isFreshInstall(context) &&
            FirstRunSetup.needed(hasProviders, hasSessions, setupDone)
}

/**
 * The four-page tour. Skippable at any point via the top-right Skip button; [onSkip],
 * [onFinish] and [onSendPrompt] all end the tour (the caller persists the seen flag).
 */
@Composable
fun OnboardingTourScreen(
    onAddProvider: () -> Unit,
    onOffline: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    val animated = animationsEnabled()
    var step by remember { mutableIntStateOf(0) }

    Surface(color = MuseTones.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                TourDots(current = step, total = 4, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                    Text(stringResource(R.string.ub_tour_skip), color = MuseTones.action, fontSize = 14.sp)
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (!animated) {
                        fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                    } else {
                        val forward = targetState > initialState
                        (slideInHorizontally(tween(Motion.Standard, easing = Motion.FastOutSlowIn)) { if (forward) it / 5 else -it / 5 } +
                            fadeIn(tween(Motion.Quick))) togetherWith
                            (slideOutHorizontally(tween(Motion.Quick, easing = Motion.LinearOutSlowIn)) { if (forward) -it / 5 else it / 5 } +
                                fadeOut(tween(Motion.Quick)))
                    }
                },
                label = "ubTourStep",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    0 -> TourWelcomeStep(onNext = { step = 1 })
                    1 -> TourChoiceStep(
                        onKey = onAddProvider,
                        onOffline = onOffline,
                        onContinue = { step = 2 },
                    )
                    2 -> TourFirstChatStep(
                        onSend = { onSendPrompt(it) },
                        onContinue = { step = 3 },
                    )
                    else -> TourDoneStep(onFinish = onFinish)
                }
            }
        }
    }
}

// ── the four steps ─────────────────────────────────────────────────────────

/** Step 1: the ninja, the headline, the pitch — with an animated entrance. */
@Composable
private fun TourWelcomeStep(onNext: () -> Unit) {
    val animated = animationsEnabled()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown || !animated) 1f else 0.82f,
        animationSpec = tween(Motion.Emphasis, easing = Motion.FastOutSlowIn),
        label = "tourMascotScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown || !animated) 1f else 0f,
        animationSpec = tween(Motion.Standard),
        label = "tourMascotAlpha",
    )
    TourPage(
        primaryLabel = stringResource(R.string.ub_tour_get_started),
        onPrimary = onNext,
    ) {
        Spacer(Modifier.height(32.dp))
        Image(
            painter = painterResource(R.drawable.ub_logo_mascot),
            contentDescription = null,
            modifier = Modifier
                .size(148.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.ub_tour_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = if (animated) Modifier.staggeredEntrance(1) else Modifier,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.ub_tour_pitch),
            fontSize = 15.5.sp,
            lineHeight = 23.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .let { if (animated) it.staggeredEntrance(2) else it },
        )
    }
}

/** Step 2: the two doors — a provider key for smarter cloud models, or on-device for private offline ones. */
@Composable
private fun TourChoiceStep(onKey: () -> Unit, onOffline: () -> Unit, onContinue: () -> Unit) {
    val animated = animationsEnabled()
    TourPage(
        primaryLabel = stringResource(R.string.ub_tour_continue),
        onPrimary = onContinue,
        title = stringResource(R.string.ub_tour_choice_title),
        subtitle = stringResource(R.string.ub_tour_choice_sub),
    ) {
        TourChoiceCard(
            index = 0,
            icon = Icons.Outlined.Key,
            title = stringResource(R.string.ub_tour_key_title),
            body = stringResource(R.string.ub_tour_key_body),
            onClick = onKey,
            animated = animated,
        )
        Spacer(Modifier.height(12.dp))
        TourChoiceCard(
            index = 1,
            icon = Icons.Outlined.CloudDownload,
            title = stringResource(R.string.ub_tour_offline_title),
            body = stringResource(R.string.ub_tour_offline_body),
            onClick = onOffline,
            animated = animated,
        )
    }
}

/** Step 3: the first hello — a suggested prompt chip that drops straight into the chat. */
@Composable
private fun TourFirstChatStep(onSend: (String) -> Unit, onContinue: () -> Unit) {
    val prompt = stringResource(R.string.ub_tour_chat_chip)
    TourPage(
        primaryLabel = stringResource(R.string.ub_tour_chat_button),
        onPrimary = { onSend(prompt) },
        secondaryLabel = stringResource(R.string.ub_tour_continue),
        onSecondary = onContinue,
        title = stringResource(R.string.ub_tour_chat_title),
        subtitle = stringResource(R.string.ub_tour_chat_sub),
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = { onSend(prompt) },
            shape = RoundedCornerShape(50),
            color = MuseTones.fill,
            modifier = Modifier.staggeredEntranceSafe(0),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MuseTones.action,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(prompt, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Step 4: you're set — the tips, then into the app. */
@Composable
private fun TourDoneStep(onFinish: () -> Unit) {
    val animated = animationsEnabled()
    TourPage(
        primaryLabel = stringResource(R.string.ub_tour_start_chatting),
        onPrimary = onFinish,
        title = stringResource(R.string.ub_tour_done_title),
        subtitle = stringResource(R.string.ub_tour_done_sub),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E9E5B).copy(alpha = 0.14f))
                .let { if (animated) it.staggeredEntrance(0) else it },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF2E9E5B), modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        TourTip(
            index = 1,
            icon = Icons.Outlined.Mic,
            text = stringResource(R.string.ub_tour_tip_voice),
            animated = animated,
        )
        Spacer(Modifier.height(10.dp))
        TourTip(
            index = 2,
            icon = Icons.Outlined.Extension,
            text = stringResource(R.string.ub_tour_tip_connectors),
            animated = animated,
        )
        Spacer(Modifier.height(10.dp))
        TourTip(
            index = 3,
            icon = Icons.Outlined.Lock,
            text = stringResource(R.string.ub_tour_tip_private),
            animated = animated,
        )
    }
}

// ── the shape of a tour page ───────────────────────────────────────────────

/** Centered column with optional title, the step's own content, and the pill(s) at the bottom. */
@Composable
private fun TourPage(
    primaryLabel: String,
    onPrimary: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val animated = animationsEnabled()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = if (animated) Modifier.staggeredEntrance(0) else Modifier,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .let { if (animated) it.staggeredEntrance(1) else it },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            content()
            Spacer(Modifier.height(20.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onPrimary,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(primaryLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (secondaryLabel != null) {
                TextButton(onClick = onSecondary) {
                    Text(secondaryLabel, color = MuseTones.action, fontSize = 14.sp)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** One of the two doors on the choice step: a big tappable card, staggered in. */
@Composable
private fun TourChoiceCard(
    index: Int,
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    animated: Boolean,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MuseTones.fill,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (animated) it.staggeredEntrance(index) else it },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(MuseTones.disc),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MuseTones.action, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(body, fontSize = 13.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One tip on the done step: glyph on a disc, a line of text. */
@Composable
private fun TourTip(index: Int, icon: ImageVector, text: String, animated: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (animated) it.staggeredEntrance(index) else it },
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(MuseTones.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** The page indicator: four dots, the current one violet and a little longer. */
@Composable
private fun TourDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == current) 18.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) MuseTones.action else MuseTones.hairline),
            )
        }
    }
}

/** [staggeredEntrance] that no-ops when the user removed animations. */
@Composable
private fun Modifier.staggeredEntranceSafe(index: Int): Modifier =
    if (animationsEnabled()) staggeredEntrance(index) else this
