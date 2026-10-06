package ai.unicto.unibot.ui.settings

import android.content.Context

/**
 * v1.4.0 item 72 — "show once per version bump" persistence.
 *
 * The last-shown version lives in a tiny SharedPreferences file. Fresh
 * installs count as "shown" (no changelog popup on first launch) — the
 * current version is still recorded so the NEXT bump triggers.
 *
 * [shouldShow] is pure so the version-bump rule is unit-testable.
 */
object WhatsNewStore {
    private const val PREFS = "whats_new_prefs"
    private const val KEY_LAST_SEEN = "last_seen_version"

    fun shouldShow(currentVersion: String, lastSeenVersion: String?): Boolean {
        if (currentVersion.isBlank()) return false
        // null = fresh install (or pre-changelog build): record, don't pop.
        if (lastSeenVersion == null) return false
        return lastSeenVersion != currentVersion
    }

    fun lastSeen(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SEEN, null)

    fun markShown(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SEEN, version)
            .apply()
    }
}
