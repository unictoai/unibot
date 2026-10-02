package ai.unicto.unibot.projects

import android.content.Context

/**
 * Scoping hook: a project's custom instructions prepend to the system prompt of
 * every chat filed into it. Called from `ChatViewModel.buildSystemPrompt()`;
 * kept in this file (not ChatViewModel) so the feature's prompt contract lives
 * next to the feature.
 *
 * Prepended as a labeled block *before* the identity section, so prompt caching
 * still sees a stable prefix for all of a project's chats.
 */
object ProjectPrompt {
    fun instructionsBlock(context: Context, sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        val project = runCatching { ProjectStore.get(context).projectForSession(sessionId) }
            .getOrNull() ?: return null
        val text = project.instructions.trim()
        if (text.isEmpty()) return null
        return "Project \"${project.name}\" — custom instructions (the user wrote these for this project; they outrank generic defaults):\n$text"
    }
}
