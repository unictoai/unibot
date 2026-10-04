package ai.unicto.unibot.ui.sessions

import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.db.MessageEntity

/**
 * [T-android-v128-duplicate-rollback] Minimal seam for duplicating a session,
 * extracted so the rollback behavior is unit-testable without a database.
 * Production wires it to [ai.unicto.unibot.data.repository.ChatRepository].
 */
internal interface SessionDuplicateOps {
    suspend fun getSession(id: String): ChatSessionEntity?
    suspend fun loadMessages(id: String): List<MessageEntity>
    suspend fun createSession(modelId: String, title: String): ChatSessionEntity
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        partsJson: String,
        tokenUsage: String?,
        reasoningContent: String?,
    ): MessageEntity
    suspend fun deleteSession(id: String)
}

/**
 * [T-android-v128-duplicate-rollback] Copy a session's messages into a new
 * session. A failure mid-copy (e.g. SQLiteFullException on a full disk) must
 * not crash the caller and must not leave a half-created copy behind: the
 * partial session is deleted (best-effort) and [onError] reports a
 * user-visible message.
 */
internal suspend fun duplicateSessionWithRollback(
    ops: SessionDuplicateOps,
    id: String,
    onError: (String) -> Unit,
) {
    var newSession: ChatSessionEntity? = null
    try {
        val session = ops.getSession(id) ?: return
        val messages = ops.loadMessages(id)
        newSession = ops.createSession(
            modelId = session.modelId,
            title = "${session.title ?: "Chat"} (Copy)",
        )
        for (msg in messages) {
            ops.appendMessage(
                sessionId = newSession.id,
                role = msg.role,
                partsJson = msg.partsJson,
                tokenUsage = msg.tokenUsage,
                reasoningContent = msg.reasoningContent,
            )
        }
    } catch (e: Exception) {
        // Roll back the half-created copy, if any, then report. The rollback
        // itself is best-effort — never let it mask the original failure.
        val partialId = newSession?.id
        if (partialId != null) {
            runCatching { ops.deleteSession(partialId) }
        }
        onError("Couldn't duplicate chat — storage may be full.")
    }
}
