package ai.unicto.unibot.guard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [P1-app-lock] Optional gate on app foreground: after the app has been in
 * the background longer than the chosen timeout, returning to it requires
 * the phone's own screen lock (fingerprint / face / PIN / pattern) via
 * [DeviceCredential] — the platform BiometricPrompt with device-credential
 * fallback, so no extra dependency and no biometric enrollment requirement
 * beyond what the user already set up.
 *
 * Wiring:
 * - [UnibotApp.onCreate] calls [init] + [install].
 * - [install] registers ActivityLifecycleCallbacks; on resume past the
 *   timeout, [locked] flips true.
 * - MainActivity observes [locked] and paints [AppLockOverlay] over the UI;
 *   the overlay's Unlock button calls [authenticate].
 *
 * Nothing here persists secrets; the prefs only hold the toggle + timeout.
 */
object AppLock {

    private const val PREFS = "app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMEOUT_MINUTES = "timeout_minutes"

    /** Lock timeout choices, in minutes. 0 = immediately on every return. */
    // [v12-D] NEVER = "never auto-lock": the toggle stays on but returning
    // to the app never triggers the gate by itself.
    const val NEVER_TIMEOUT_MINUTES = -1L
    val TIMEOUT_OPTIONS = listOf(0L, 1L, 5L, 15L, 60L, NEVER_TIMEOUT_MINUTES)

    const val DEFAULT_TIMEOUT_MINUTES = 5L

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _timeoutMinutes = MutableStateFlow(DEFAULT_TIMEOUT_MINUTES)
    val timeoutMinutes: StateFlow<Long> = _timeoutMinutes.asStateFlow()

    private val _locked = MutableStateFlow(false)

    /** True while the foreground gate is up and the UI must stay hidden. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var lastPausedAt: Long = 0L
    private var promptShowing = false

    /** Load persisted settings. Idempotent; call from Application.onCreate. */
    fun init(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _timeoutMinutes.value = prefs.getLong(KEY_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES)
            .takeIf { it in TIMEOUT_OPTIONS } ?: DEFAULT_TIMEOUT_MINUTES
    }

    /** Watch foreground/background transitions for the whole process. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                lastPausedAt = System.currentTimeMillis()
            }

            override fun onActivityResumed(activity: Activity) {
                maybeLock(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Whether the lock can actually work on this phone: the system prompt
     * path needs API 29+, and there must be a device lock to confirm with.
     */
    fun canLock(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DeviceCredential.available(context)

    private fun maybeLock(activity: Activity) {
        if (!_enabled.value) return
        if (_locked.value || promptShowing) return
        // First launch of the process is not a "return" — no lock.
        if (lastPausedAt == 0L) return
        // [v12-D] "Never": auto-lock disabled, the gate never trips on return.
        if (_timeoutMinutes.value == NEVER_TIMEOUT_MINUTES) return
        val timeoutMs = _timeoutMinutes.value * 60_000L
        if (System.currentTimeMillis() - lastPausedAt < timeoutMs) return
        // Without a device lock (or on API < 29) there is nothing to confirm
        // with; the settings screen disables the toggle in this state.
        if (!canLock(activity)) return
        _locked.value = true
    }

    /**
     * Show the system confirm prompt. On success the gate lifts; on
     * failure/cancel it stays up and the overlay's Unlock button can
     * re-trigger. [onDone] runs on the main thread.
     */
    fun authenticate(activity: Activity, onDone: (Boolean) -> Unit) {
        if (promptShowing) return
        promptShowing = true
        DeviceCredential.confirm(
            activity,
            "Unlock unibot",
            "Confirm it's you to continue",
        ) { ok ->
            promptShowing = false
            if (ok) {
                _locked.value = false
                // Don't let the resume that follows the prompt's own
                // dismissal instantly re-lock (timeout = immediately).
                lastPausedAt = 0L
            }
            onDone(ok)
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (!on) _locked.value = false
    }

    fun setTimeoutMinutes(context: Context, minutes: Long) {
        val clamped = minutes.takeIf { it in TIMEOUT_OPTIONS } ?: DEFAULT_TIMEOUT_MINUTES
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TIMEOUT_MINUTES, clamped).apply()
        _timeoutMinutes.value = clamped
    }

    /** Human label for a timeout option, e.g. "5 minutes" / "Immediately". */
    fun timeoutLabel(minutes: Long): String = when (minutes) {
        0L -> "Immediately"
        1L -> "After 1 minute"
        60L -> "After 1 hour"
        NEVER_TIMEOUT_MINUTES -> "Never" // [v12-D]
        else -> "After $minutes minutes"
    }
}
