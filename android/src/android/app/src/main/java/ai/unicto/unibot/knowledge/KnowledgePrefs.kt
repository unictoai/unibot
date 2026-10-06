package ai.unicto.unibot.knowledge

import android.content.Context

/**
 * Tiny synchronous preferences for the knowledge base.
 *
 * SharedPreferences (not DataStore) deliberately: the chat send hook reads
 * these on the send path, which is not suspend, so the read must be
 * synchronous. Two keys only.
 */
class KnowledgePrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch: chat searches the knowledge base before each send. */
    var searchEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_ENABLED, value).apply()

    /**
     * One-shot document scope, staged by DocumentScreen's "Ask in chat":
     * the next send from [sessionId] searches ONLY [docId]. Consumed by
     * [consumeScopedDoc] so later turns search the whole base again.
     */
    fun stageScopedDoc(sessionId: String, docId: String) {
        prefs.edit()
            .putString(KEY_SCOPED_SESSION, sessionId)
            .putString(KEY_SCOPED_DOC, docId)
            .apply()
    }

    fun consumeScopedDoc(sessionId: String): String? {
        val stagedSession = prefs.getString(KEY_SCOPED_SESSION, null)
        if (stagedSession != sessionId) return null
        val docId = prefs.getString(KEY_SCOPED_DOC, null)
        prefs.edit().remove(KEY_SCOPED_SESSION).remove(KEY_SCOPED_DOC).apply()
        return docId
    }

    private companion object {
        const val PREFS = "knowledge_prefs"
        const val KEY_SEARCH_ENABLED = "search_enabled"
        const val KEY_SCOPED_SESSION = "scoped_session"
        const val KEY_SCOPED_DOC = "scoped_doc"
    }
}
