package ai.unicto.unibot.provider

import ai.unicto.unibot.ui.navigation.Routes

/**
 * v1.4.0 item 81 — turns the bare "No provider configured" snackbar into
 * a guided fix.
 *
 * The chat surfaces provider errors as plain text; [guidedFixFor] maps the
 * known ones onto an action the UI can render (snackbar action pill →
 * navigation route). The route goes to the provider list, where an empty
 * state already guides the user through adding their first provider.
 *
 * Kept in the provider layer (not the chat UI) so every surface that can
 * hit "no provider" — chat, swarm, voice — resolves the same fix.
 */
object NoProviderGuidance {

    /**
     * The guided fix for an error: the action label and the navigation
     * route it opens. Null when the error has no guided fix.
     */
    data class GuidedFix(
        val actionLabel: String,
        val route: String,
    )

    /**
     * Matches the exact error strings the app emits for a missing provider
     * ([ai.unicto.unibot.ui.chat.ChatViewModel] sets "No provider
     * configured"). Case-insensitive, trimmed — but deliberately NOT a
     * substring/regex match: "No provider configured for voice" is a
     * different problem with a different fix.
     */
    fun guidedFixFor(errorText: String?): GuidedFix? {
        if (errorText == null) return null
        return when (errorText.trim().lowercase()) {
            "no provider configured" -> GuidedFix(
                actionLabel = "Set up",
                route = Routes.PROVIDER_LIST,
            )
            else -> null
        }
    }
}
