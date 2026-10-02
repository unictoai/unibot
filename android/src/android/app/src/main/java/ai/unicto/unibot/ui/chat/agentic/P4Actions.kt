package ai.unicto.unibot.ui.chat.agentic

import ai.unicto.unibot.ui.chat.ChatViewModel
import java.lang.ref.WeakReference

/**
 * P4 action bridge (v0.2.0).
 *
 * Card composables (checkpoint Resume, …) render deep inside the message
 * list where the ChatViewModel isn't in scope and ChatScreen.kt is
 * off-limits for new wiring. This singleton holds the CURRENT chat's
 * ViewModel (bound on every send, released on cleared) so cards can drive
 * the agent loop. WeakReference: never keep a dead chat alive.
 */
object P4Actions {
    private var bound: WeakReference<ChatViewModel>? = null

    fun bind(viewModel: ChatViewModel) {
        bound = WeakReference(viewModel)
    }

    fun unbind(viewModel: ChatViewModel) {
        if (bound?.get() === viewModel) bound = null
    }

    private fun vm(): ChatViewModel? = bound?.get()

    /**
     * Checkpoint card "Resume": re-enters the agent loop with history
     * intact by sending a follow-up. This CONTINUES the task — it never
     * approves anything (approval cards still fire per tool call).
     */
    fun resumeFromCheckpoint() {
        vm()?.sendMessage("Continue from the checkpoint.")
    }

    fun sendFollowUp(text: String) {
        if (text.isNotBlank()) vm()?.sendMessage(text)
    }
}
