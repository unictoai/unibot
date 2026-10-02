package ai.unicto.unibot.local

import ai.unicto.unibot.ui.chat.ChatMessage

/**
 * [P7-on-device-llm] Pure helpers for the local chat route. The actual
 * message-list and DB writes live in [ChatViewModel.sendLocalMessage]; this
 * object only answers "should we go local?" and shapes history for the
 * backend — so it stays unit-testable without the ViewModel.
 */
object LocalChatRouter {

    /**
     * Build the backend history from the UI message list: user/assistant
     * turns only, oldest-first, skipping the in-progress assistant bubble,
     * queued placeholders and empty rows. The caller's new user text is
     * passed to the backend as `prompt`, not duplicated here.
     */
    fun buildTurns(
        messages: List<ChatMessage>,
        excludeAssistantId: String? = null,
    ): List<LlmTurn> =
        messages
            .filter { (it.role == "user" || it.role == "assistant") }
            .filterNot { it.id == excludeAssistantId }
            .filterNot { it.isQueued }
            .filterNot { it.role == "assistant" && it.content.isBlank() && it.isStreaming }
            .mapNotNull { msg ->
                val text = msg.content.trim()
                if (text.isEmpty()) null else LlmTurn(msg.role, text)
            }
            .takeLast(24)
}
