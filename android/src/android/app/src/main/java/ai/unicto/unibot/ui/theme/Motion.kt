package ai.unicto.unibot.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.provider.Settings

/**
 * unibot motion language — the v0.4.0 "premium feel" pass.
 *
 * One set of durations, easings, and stagger rules that every new animation
 * references instead of hardcoded tweens, so the whole app moves with the
 * same timing. Existing screens keep their own specs; new work (entrances,
 * skeletons, nav transitions, thinking states) goes through here.
 *
 * Durations are milliseconds:
 * - [Instant] — press ripples, tiny state flips the eye should barely notice.
 * - [Quick] — exits, dismissals, things leaving the screen.
 * - [Standard] — the default: entrances, pushes, fades.
 * - [Emphasis] — moments that deserve weight (send, confirm, reveal).
 * - [Slow] — ambient loops (shimmers, breathing glows).
 */
object Motion {
    const val Instant = 120
    const val Quick = 200
    const val Standard = 300
    const val Emphasis = 450
    const val Slow = 700

    /** Default easing for things arriving: confident, settles softly. */
    val FastOutSlowIn: Easing = FastOutSlowInEasing

    /** Gentle ease for things receding or secondary motion. */
    val LinearOutSlowIn: Easing = LinearOutSlowInEasing

    /** Constant speed — reserved for infinite loops (shimmer sweeps). */
    val Linear: Easing = LinearEasing

    /**
     * Playful-but-controlled spring for interactive motion (press feedback,
     * toggles, small pops). Damping 0.8 keeps it from feeling bouncy;
     * stiffness 380 keeps it snappy.
     */
    val SpringSpec: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Per-item stagger step for list/card entrances. */
    const val StaggerStepMs = 40

    /** Stagger delays never grow past this — long lists still settle fast. */
    const val StaggerMaxMs = 400

    /**
     * Entrance delay for the item at [index]: index × 40ms, capped at 400ms.
     */
    fun staggerDelay(index: Int): Int =
        (index.coerceAtLeast(0) * StaggerStepMs).coerceAtMost(StaggerMaxMs)

    /**
     * Raw system animator duration scale (1.0 = normal). 0 means the user
     * enabled "Remove animations" in accessibility settings. No permission
     * needed to read [Settings.Global.ANIMATOR_DURATION_SCALE].
     */
    fun animatorDurationScale(context: Context): Float =
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (_: Exception) {
            1f
        }
}

/**
 * True when the app should play motion: the system animator duration scale
 * is not zero. Wave 9 uses this to gate infinite ambient loops (shimmers,
 * breathing glows, loaders) so "Remove animations" is honoured — a
 * quality/accessibility touch, not a feature flag.
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    // [v12-G] Battery saver extends the "Remove animations" accessibility
    // gate: when the saver is on, motion is off app-wide (ambient loops,
    // entrances, success pops — everything behind this gate).
    remember(context) { ai.unicto.unibot.ui.settings.BatterySaverStore.ensureLoaded(context) }
    val batterySaver by ai.unicto.unibot.ui.settings.BatterySaverStore.enabled.collectAsState()
    return remember(batterySaver) {
        Motion.animatorDurationScale(context) != 0f && !batterySaver
    }
}

/**
 * True when decorative motion may play: full [animationsEnabled] plus a
 * low-RAM gate. On entry-level phones (see
 * [ai.unicto.unibot.local.LlamaModelManager.isLowRamDevice] — reused here,
 * no second heuristic) staggered entrances and ambient shimmer loops are
 * skipped and content simply appears; micro-feedback (press states,
 * success pops) keeps working. Cheap one-shot animations are fine on 4GB
 * phones — it's the repeating and list-wide ones that drop frames.
 */
@Composable
fun decorativeMotionEnabled(): Boolean {
    if (!animationsEnabled()) return false
    val context = LocalContext.current
    return remember(context) {
        !ai.unicto.unibot.local.LlamaModelManager.isLowRamDevice(context)
    }
}

/**
 * Brief scale-pop for success moments (model download complete, reminder
 * saved, connector connected, backup finished): snaps to 0.9 then springs
 * back to 1.0 on [Motion.SpringSpec] every time [trigger] changes to a
 * non-null value. Pair with `haptics.success()` at the call site — the
 * motion is the visual half of the celebration. No-op when animations are
 * disabled.
 */
@Composable
fun Modifier.successPop(trigger: Any?): Modifier {
    if (!animationsEnabled()) return this
    val scale = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger != null) {
            scale.snapTo(0.9f)
            scale.animateTo(1f, Motion.SpringSpec)
        }
    }
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Fade + slight rise entrance, staggered by [index] via [Motion.staggerDelay].
 *
 * Plays once per composition — navigating away and back replays it, which is
 * the "animate on first composition per screen visit" behaviour settings
 * screens want. Cheap: two float animations, no layout passes.
 *
 * Each call site gets its own remembered state, so wrapping N cards with
 * indices 0..N-1 staggers them automatically.
 */
@Composable
fun Modifier.staggeredEntrance(index: Int): Modifier {
    // Accessibility / low-RAM: when the user removed animations, or on
    // entry-level phones where list-wide staggers drop frames, content
    // just appears.
    if (!decorativeMotionEnabled()) return this
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val spec = tween<Float>(
        durationMillis = Motion.Standard,
        delayMillis = Motion.staggerDelay(index),
        easing = Motion.FastOutSlowIn,
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spec,
        label = "stagger_alpha_$index",
    )
    val rise by animateFloatAsState(
        targetValue = if (shown) 0f else 14f,
        animationSpec = spec,
        label = "stagger_rise_$index",
    )
    return this.graphicsLayer {
        this.alpha = alpha
        translationY = rise
    }
}
