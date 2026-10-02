package ai.unicto.unibot.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Tiny haptic helper for the v0.4.0 "premium feel" pass — [rememberHaptic]
 * hands back a [HapticController] whose [HapticController.tap],
 * [HapticController.toggle], [HapticController.success], and
 * [HapticController.error] map to the matching [HapticFeedbackConstants].
 *
 * Everything is guarded: [View.performHapticFeedback] is a no-op when the
 * system has haptics disabled, unknown constants are ignored by older
 * releases, and any ROM-level throw is swallowed — a missing vibrator must
 * never crash the app.
 */
class HapticController(private val view: View) {
    private fun perform(feedbackConstant: Int) {
        try {
            view.performHapticFeedback(feedbackConstant)
        } catch (_: Exception) {
            // Guarded: some devices/ROMs throw on haptic calls; feel must
            // never become a crash.
        }
    }

    /** Light tick for taps: send button, download start, row presses. */
    fun tap() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Slightly firmer tick for on/off switches. */
    fun toggle() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /**
     * Warm confirmation for completed work (download finished). CONFIRM is
     * API 30+; below that we fall back to VIRTUAL_KEY, which every release
     * honours.
     */
    fun success() =
        perform(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )

    /**
     * Sharp refusal for failures. REJECT is API 30+; below that LONG_PRESS
     * is the closest "something went wrong" buzz.
     */
    fun error() =
        perform(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS,
        )
}

/**
 * Remembers a [HapticController] bound to the current view. Call from any
 * composable, then invoke e.g. `haptics.tap()` inside click handlers —
 * never inside composition itself.
 */
@Composable
fun rememberHaptic(): HapticController {
    val view = LocalView.current
    return remember(view) { HapticController(view) }
}
