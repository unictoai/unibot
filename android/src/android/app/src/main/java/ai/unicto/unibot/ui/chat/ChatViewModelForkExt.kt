package ai.unicto.unibot.ui.chat

// [P2-fork] "Fork from here": clone the session carrying history up to and
// including a message, then jump into the new branch.

import ai.unicto.unibot.data.SessionForkManager
import ai.unicto.unibot.logging.AppLogger
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork the current session at [messageId]: a new independent session is
 * created with the history up to AND INCLUDING that message, and the UI
 * navigates into it via [ai.unicto.unibot.ui.home.HomeBus].
 *
 * [messageId] may be a user or assistant message id from [_messages].
 * Assistant UI bubbles carry a temp `assistant_…` in-memory id (not the DB
 * row id), so they resolve through the turn range after their anchor user
 * message instead of by id.
 */
fun ChatViewModel.forkFromMessage(messageId: String) {
    if (_isStreaming.value) {
        Toast.makeText(context, "Wait for the current turn to finish before forking.", Toast.LENGTH_SHORT).show()
        return
    }
    val messages = _messages.value
    val idx = messages.indexOfFirst { it.id == messageId }
    if (idx < 0) return
    val msg = messages[idx]
    viewModelScope.launch {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return@launch
        // Resolve the fork cutoff to a DB sort_order:
        // - user bubble → its own row (in-memory id == DB id);
        // - assistant bubble → the last row of its turn (temp id → range).
        val cutoffSortOrder: Int? = withContext(Dispatchers.IO) {
            if (msg.role == "assistant") {
                val anchorIdx = messages.subList(0, idx).indexOfLast { it.role == "user" }
                if (anchorIdx < 0) return@withContext null
                turnEntities(sid, messages[anchorIdx], anchorIdx)
                    .maxOfOrNull { it.sortOrder }
            } else {
                chatRepository.getMessageById(msg.id)?.sortOrder
            }
        }
        if (cutoffSortOrder == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Can't fork this message.", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }
        // Map the cutoff back to a message id for forkSessionFrom.
        val anchorDbId = withContext(Dispatchers.IO) {
            chatRepository.loadMessages(sid)
                .filter { it.sortOrder <= cutoffSortOrder }
                .maxByOrNull { it.sortOrder }?.id
        }
        if (anchorDbId == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Can't fork this message.", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }
        val forkManager = SessionForkManager(context, chatRepository)
        val newId = withContext(Dispatchers.IO) {
            forkManager.forkSessionFrom(sid, anchorDbId)
        }
        if (newId != null) {
            AppLogger.info(TAG, "[Fork] $sid @ $anchorDbId → $newId")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Forked into a new chat.", Toast.LENGTH_SHORT).show()
            }
            ai.unicto.unibot.ui.home.HomeBus.showSession(newId)
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Fork failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
