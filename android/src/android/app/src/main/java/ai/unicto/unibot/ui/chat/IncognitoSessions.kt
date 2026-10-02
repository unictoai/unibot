package ai.unicto.unibot.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [P1-incognito] In-memory registry of incognito chat sessions.
 *
 * An incognito session is never written to the database: [ChatRepository]
 * no-ops every session-scoped write while the session id is registered here,
 * the UI keeps running off the ViewModel's in-memory message list, and the
 * flag is dropped when the chat closes (or the process dies) — so the
 * conversation simply ceases to exist.
 *
 * Deliberately NOT persisted: writing the flag to disk would itself be a
 * record of the chat. Keyed on the draft id (`__incognito__…`) until
 * [ChatViewModel.ensureSession] promotes the draft, at which point the flag
 * is [rebind]-ed onto the real session id.
 */
object IncognitoSessions {

    /** Draft id prefix for chats born incognito ("New incognito chat"). */
    const val DRAFT_PREFIX = "__incognito__"

    private val _incognitoIds = MutableStateFlow<Set<String>>(emptySet())

    /** Observable set of incognito session ids (for badges / menus). */
    val incognitoIds: StateFlow<Set<String>> = _incognitoIds.asStateFlow()

    /**
     * Ids of in-memory-only message rows, per incognito session. The
     * per-message update paths ([ChatRepository.updateMessageParts] etc.)
     * only receive a message id, so they consult this to no-op instead of
     * touching the database.
     */
    private val incognitoMessageIds = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    fun trackMessage(sessionId: String, messageId: String) {
        incognitoMessageIds.getOrPut(sessionId) { mutableSetOf() }.add(messageId)
    }

    @Synchronized
    fun isIncognitoMessage(messageId: String): Boolean =
        incognitoMessageIds.values.any { it.contains(messageId) }

    fun isIncognito(sessionId: String): Boolean =
        sessionId.isNotEmpty() && _incognitoIds.value.contains(sessionId)

    fun isIncognitoDraft(sessionId: String): Boolean = sessionId.startsWith(DRAFT_PREFIX)

    fun setIncognito(sessionId: String, on: Boolean) {
        if (sessionId.isEmpty()) return
        _incognitoIds.value =
            if (on) _incognitoIds.value + sessionId
            else _incognitoIds.value - sessionId
    }

    /**
     * Move the flag from a draft key to the promoted real session id.
     * No-op when the draft was never flagged.
     */
    fun rebind(fromId: String, toId: String) {
        if (fromId.isEmpty() || toId.isEmpty() || fromId == toId) return
        if (_incognitoIds.value.contains(fromId)) {
            _incognitoIds.value = _incognitoIds.value - fromId + toId
        }
    }

    /** Drop the flag when the chat closes — the messages were never persisted. */
    @Synchronized
    fun clear(sessionId: String) {
        if (sessionId.isEmpty()) return
        _incognitoIds.value = _incognitoIds.value - sessionId
        incognitoMessageIds.remove(sessionId)
    }

    /** Mint a fresh draft id for "New incognito chat". */
    fun newDraftId(): String = "$DRAFT_PREFIX${java.util.UUID.randomUUID()}"
}
