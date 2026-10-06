package ai.unicto.unibot.ui.onboarding

import android.content.Context

/**
 * Backlog item 97 — the two-minute guided first-run tour: chat, providers,
 * swarm, and privacy. Professional, skippable, resumable, shown once.
 *
 * This is the product tour, distinct from [OnboardingTour] (the v1.1.2
 * setup flow: provider key or on-device models). On a fresh install the
 * setup tour runs first; the guided tour follows it, then first-run setup
 * resumes. Progress is persisted per step, so a user who leaves mid-tour
 * resumes where they stopped instead of restarting.
 *
 * Replay lives in the help center: [reset] clears the seen flag and the
 * saved step so the tour runs again from the start.
 */
object GuidedTour {
    private const val PREFS = "unibot"
    private const val KEY_SEEN = "tour.guided.seen.v1"
    private const val KEY_STEP = "tour.guided.step.v1"

    /** Number of stops in the tour (welcome, chat, providers, swarm, privacy, done). */
    const val STEPS = 6

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** The stop to resume at, clamped to the tour range. 0 = start over. */
    fun resumeStep(context: Context): Int =
        prefs(context).getInt(KEY_STEP, 0).coerceIn(0, STEPS - 1)

    fun saveStep(context: Context, step: Int) {
        prefs(context).edit().putInt(KEY_STEP, step.coerceIn(0, STEPS - 1)).apply()
    }

    /** Clears the seen flag and progress so the tour can be replayed. */
    fun reset(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_SEEN, false)
            .putInt(KEY_STEP, 0)
            .apply()
    }

    /**
     * Shows once: only on a fresh install (upgrades never see it), only
     * while unseen. The caller also gates on its own flow state.
     */
    fun shouldShow(context: Context): Boolean =
        !isSeen(context) && OnboardingTour.isFreshInstall(context)
}
