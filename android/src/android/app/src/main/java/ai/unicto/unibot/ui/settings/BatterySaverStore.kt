package ai.unicto.unibot.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ai.unicto.unibot.notification.BatterySaverNotifier

/**
 * [v12-G] Battery saver mode — a real power-saving switch, not a placebo.
 *
 * When on, the app-wide motion gate ([ai.unicto.unibot.ui.theme.animationsEnabled])
 * reports motion off (extending the system's "Remove animations" accessibility
 * setting), and periodic background work slows down:
 * - [ai.unicto.unibot.browser.BrowserTabPool] idle-tab eviction sweep: 60s → 5min
 * - [ai.unicto.unibot.ui.chat.SystemResourceMonitor] sampling: 2s → 10s
 *
 * An ongoing low-priority notification ([BatterySaverNotifier]) is the visible
 * indicator while the mode is on; it is removed the moment the toggle flips off.
 *
 * Process-wide singleton: every reader (Compose rows, the motion gate,
 * background loops) observes the same [enabled] flow, so a toggle in
 * Settings takes effect everywhere without restarting anything.
 */
object BatterySaverStore {

    private const val PREFS_NAME = "v12g_battery_saver"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    /**
     * Load the persisted value into [enabled]. Idempotent; safe to call from
     * composition (via remember) and from background loops. Until the first
     * call the flow reports false (saver off), matching the default.
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
            loaded = true
        }
    }

    /**
     * Synchronous read for non-Compose callers (polling loops). Warms the
     * cache on first use; afterwards it is a volatile read.
     */
    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _enabled.value
    }

    /**
     * Flip the mode. Persists, updates every observer, and shows/hides the
     * indicator notification so the two can never disagree.
     */
    fun setEnabled(context: Context, on: Boolean) {
        ensureLoaded(context.applicationContext)
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (on) BatterySaverNotifier.show(context.applicationContext)
        else BatterySaverNotifier.hide(context.applicationContext)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
