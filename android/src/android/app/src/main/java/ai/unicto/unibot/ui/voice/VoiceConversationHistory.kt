package ai.unicto.unibot.ui.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [unibot-voice-history] Which chat sessions have had a voice conversation.
 *
 * No flag exists on the session entity (adding a Room column would need a
 * migration), so this keeps a small SharedPreferences-backed set of session
 * ids plus the last voice-turn timestamp per session. The history screen
 * joins these ids against [ai.unicto.unibot.data.repository.ChatRepository.observeSessions]
 * for titles, last messages and ordering — so a session that was deleted
 * (or is incognito, which never lands in the DB) simply never renders.
 *
 * Everything stays on the phone: no network, no account.
 */
object VoiceConversationHistory {

    private const val PREFS = "voice_conversation_history"
    private const val KEY_IDS = "voiceSessionIds"
    private const val TS_PREFIX = "ts_"

    /** Bounded so one chatty session can't grow the prefs file forever. */
    private const val MAX_ENTRIES = 200

    private val _voiceSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val voiceSessionIds: StateFlow<Set<String>> = _voiceSessionIds.asStateFlow()

    @Volatile
    private var loaded = false

    /** Idempotent; safe to call per composition. */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _voiceSessionIds.value = prefsOf(context).getStringSet(KEY_IDS, emptySet())
            ?.toSet().orEmpty()
    }

    /**
     * Mark [sessionId] as voice-used after at least one completed voice turn.
     * Called when the voice conversation screen is left.
     */
    fun recordVoiceSession(context: Context, sessionId: String) {
        init(context)
        val sp = prefsOf(context)
        val now = System.currentTimeMillis()
        sp.edit()
            .putStringSet(KEY_IDS, (_voiceSessionIds.value + sessionId).takeLastBounded())
            .putLong(TS_PREFIX + sessionId, now)
            .apply()
        _voiceSessionIds.value = sp.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
    }

    /** Last completed voice-turn time for [sessionId], or 0 when unknown. */
    fun lastVoiceAt(context: Context, sessionId: String): Long =
        prefsOf(context).getLong(TS_PREFIX + sessionId, 0L)

    /** Forget one session (e.g. after it was deleted elsewhere). */
    fun forget(context: Context, sessionId: String) {
        init(context)
        val sp = prefsOf(context)
        val ids = _voiceSessionIds.value - sessionId
        sp.edit()
            .putStringSet(KEY_IDS, ids)
            .remove(TS_PREFIX + sessionId)
            .apply()
        _voiceSessionIds.value = ids
    }

    private fun prefsOf(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keep the newest [MAX_ENTRIES] ids, dropping the oldest first. */
    private fun Set<String>.takeLastBounded(): Set<String> =
        if (size <= MAX_ENTRIES) this else toList().takeLast(MAX_ENTRIES).toSet()
}
