package ai.unicto.unibot.privacy

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wave 5 (v1.0) — privacy core. The single source of truth for every privacy
 * toggle and per-chat privacy choice, backed by encrypted (AES-256)
 * SharedPreferences created through [EncryptedPrefsFactory.safeCreate].
 *
 * Call [init] once from [ai.unicto.unibot.MainActivity.onCreate] (or any
 * early app entry point). Until then every read returns its default and
 * every write is a no-op — privacy code paths stay crash-free even in the
 * crash-safe-mode boot where heavy init is skipped.
 *
 * Reactivity: the two settings that drive live system behaviour
 * ([localOnly] — network gate, [screenshotBlock] — FLAG_SECURE) are exposed
 * as [StateFlow]s. Everything else is read on demand from the prefs file;
 * UI that needs updates re-reads on navigation, which is where those
 * settings are presented.
 *
 * Honesty note: nothing here phones home. The prefs file
 * (`privacy_prefs`) lives on this phone only; the traffic log lives in
 * memory only. Privacy claims in the UI must only repeat what this file
 * and [PrivacyNetworkGate] actually implement.
 */
object PrivacyPrefs {

    // -- keys ----------------------------------------------------------------

    private const val FILE = "privacy_prefs"

    private const val KEY_LOCAL_ONLY = "local_only"
    private const val KEY_SCREENSHOT_BLOCK = "screenshot_block"
    private const val KEY_CLIPBOARD_AUTO_CLEAR = "clipboard_auto_clear"
    private const val KEY_AUTO_DELETE_DEFAULT_HOURS = "auto_delete_default_hours"
    private const val KEY_KILL_SWITCH = "kill_switch_engaged"
    private const val KEY_ON_DEVICE_ONLY = "on_device_only"

    /** Per-chat auto-delete: "auto_delete_hours:<sessionId>" → hours, 0/absent = follow default. */
    private fun autoDeleteKey(sessionId: String) = "auto_delete_hours:$sessionId"

    /** Per-chat lock: "chat_locked:<sessionId>" → true. */
    private fun lockKey(sessionId: String) = "chat_locked:$sessionId"

    // -- init ----------------------------------------------------------------

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Opens the encrypted prefs file. Safe to call repeatedly and from any
     * thread; the first call wins. Must run on a context that outlives the
     * caller — pass the Application context.
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
            prefs = sp
            // Seed the hot flows from disk so a process restart reflects
            // the user's real choices, not compile-time defaults.
            _localOnly.value = sp.getBoolean(KEY_LOCAL_ONLY, false)
            _screenshotBlock.value = sp.getBoolean(KEY_SCREENSHOT_BLOCK, false)
            _killSwitch.value = sp.getBoolean(KEY_KILL_SWITCH, false)
            _onDeviceOnly.value = sp.getBoolean(KEY_ON_DEVICE_ONLY, false)
        }
    }

    // -- global toggles ------------------------------------------------------

    private val _localOnly = MutableStateFlow(false)

    /**
     * Local-only mode: [PrivacyNetworkGate] refuses every request whose host
     * is not in its allowlist (+127.0.0.1/localhost for the OAuth loopback).
     * Only AI-provider hosts are allowlisted, so turning this on breaks
     * connector OAuth refresh and web search until it is turned off — the
     * UI says exactly that.
     */
    val localOnly: StateFlow<Boolean> = _localOnly.asStateFlow()

    private val _screenshotBlock = MutableStateFlow(false)

    /** When true, MainActivity applies FLAG_SECURE (no screenshots/screen recordings). */
    val screenshotBlock: StateFlow<Boolean> = _screenshotBlock.asStateFlow()

    private val _killSwitch = MutableStateFlow(false)

    /**
     * Kill switch (privacy item 58): one tap severs all network app-wide.
     * Persisted deliberately — a kill the user engaged must survive a
     * process restart, and the banner + Quick Settings tile always show the
     * live state so it can never look "mysteriously offline".
     */
    val killSwitch: StateFlow<Boolean> = _killSwitch.asStateFlow()

    private val _onDeviceOnly = MutableStateFlow(false)

    /**
     * On-device-only mode (privacy item 62): the persistent twin of the
     * kill switch. Every remote host is blocked, so only on-device models
     * and on-device voice can run. Unlike local-only mode, no provider
     * allowlist applies — nothing may leave the phone.
     */
    val onDeviceOnly: StateFlow<Boolean> = _onDeviceOnly.asStateFlow()

    /** Copies are wiped from the clipboard 60s after copying (see ClipboardGuard). */
    var clipboardAutoClear: Boolean
        get() = prefs?.getBoolean(KEY_CLIPBOARD_AUTO_CLEAR, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_CLIPBOARD_AUTO_CLEAR, value)?.apply()
        }

    /**
     * Default chat auto-delete window in hours. 0 = never (default).
     * A per-chat override ([setChatAutoDelete]) wins over this for its chat.
     */
    var autoDeleteDefaultHours: Long
        get() = prefs?.getLong(KEY_AUTO_DELETE_DEFAULT_HOURS, 0L) ?: 0L
        set(value) {
            prefs?.edit()?.putLong(KEY_AUTO_DELETE_DEFAULT_HOURS, value)?.apply()
        }

    // -- setters for the reactive flows ---------------------------------------

    fun setLocalOnly(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_LOCAL_ONLY, enabled)?.apply()
        _localOnly.value = enabled
        // The gate reads this flag directly, so network behaviour changes
        // the moment the toggle flips — no restart, no re-login.
        PrivacyNetworkGate.localOnlyEnabled = enabled
    }

    fun setScreenshotBlock(block: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SCREENSHOT_BLOCK, block)?.apply()
        _screenshotBlock.value = block
    }

    fun setKillSwitch(engaged: Boolean) {
        prefs?.edit()?.putBoolean(KEY_KILL_SWITCH, engaged)?.apply()
        _killSwitch.value = engaged
        // The gate reads this flag directly, so network behaviour changes
        // the moment the toggle flips — no restart, no re-login.
        PrivacyNetworkGate.killSwitchEngaged = engaged
    }

    fun setOnDeviceOnly(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ON_DEVICE_ONLY, enabled)?.apply()
        _onDeviceOnly.value = enabled
        PrivacyNetworkGate.onDeviceOnlyEnabled = enabled
    }

    // -- per-chat state --------------------------------------------------------

    /**
     * Effective auto-delete window for [sessionId] in hours: the per-chat
     * override when set, otherwise the global default. 0 = never delete.
     */
    fun effectiveAutoDeleteHours(sessionId: String): Long {
        val sp = prefs ?: return 0L
        return if (sp.contains(autoDeleteKey(sessionId))) {
            sp.getLong(autoDeleteKey(sessionId), 0L)
        } else {
            sp.getLong(KEY_AUTO_DELETE_DEFAULT_HOURS, 0L)
        }
    }

    /** Per-chat override. null clears the override (chat follows the global default). */
    fun setChatAutoDelete(sessionId: String, hours: Long?) {
        val editor = prefs?.edit() ?: return
        if (hours == null) editor.remove(autoDeleteKey(sessionId))
        else editor.putLong(autoDeleteKey(sessionId), hours)
        editor.apply()
    }

    fun isChatLocked(sessionId: String): Boolean =
        prefs?.getBoolean(lockKey(sessionId), false) ?: false

    fun setChatLocked(sessionId: String, locked: Boolean) {
        prefs?.edit()?.putBoolean(lockKey(sessionId), locked)?.apply()
    }
}
