package ai.unicto.unibot.ui.voice

import ai.unicto.unibot.ui.chat.ERROR_KIND_INVALID_KEY
import ai.unicto.unibot.ui.chat.ERROR_KIND_MODEL_NOT_FOUND
import ai.unicto.unibot.ui.chat.ERROR_KIND_NETWORK
import ai.unicto.unibot.ui.chat.ERROR_KIND_OUTPUT_LIMIT
import ai.unicto.unibot.ui.chat.ERROR_KIND_RATE_LIMITED
import ai.unicto.unibot.ui.chat.ERROR_KIND_REQUEST_TOO_LARGE

/**
 * Voice theme item 29: voice error cards gate Retry by kind.
 *
 * Reuses the error-kind taxonomy from chat ([ai.unicto.unibot.ui.chat]
 * ERROR_KIND_*): auth/quota failures offer FIXES (update the key, switch
 * provider) instead of a futile Retry, while genuinely transient failures keep
 * Retry. Pure policy — the voice conversation screen renders [VoiceErrorUi]
 * and the view-model only supplies the kind. Unit-tested.
 */
enum class VoiceErrorAction {
    /** Re-run the failed turn. Only offered when a retry could plausibly help. */
    RETRY,
    /** Open provider settings so the user can fix/replace the API key. */
    UPDATE_KEY,
    /** Open provider settings so the user can pick a different provider. */
    SWITCH_PROVIDER,
    /** Open provider settings so the user can pick a working model. */
    CHANGE_MODEL,
}

data class VoiceErrorUi(
    val kind: String?,
    /** Plain-language card body — never a raw engine string. */
    val message: String,
    /** The fix offered first. */
    val primaryAction: VoiceErrorAction,
    /** Optional second action (e.g. Retry after switching provider failed). */
    val secondaryAction: VoiceErrorAction? = null,
    /** False when retrying the same request is known-futile (bad key, dead model). */
    val allowRetry: Boolean = true,
)

object VoiceErrorPolicy {

    /**
     * Card policy for a voice-turn failure.
     *
     * @param kind the machine-readable kind from the chat taxonomy
     *   ([ai.unicto.unibot.ui.chat.ChatMessage.errorKind] /
     *   [ai.unicto.unibot.ui.chat.ChatViewModel.lastErrorKind]), or null when
     *   the failure carried no kind.
     * @param fallbackMessage user-facing text to show for unknown kinds.
     */
    fun forKind(kind: String?, fallbackMessage: String): VoiceErrorUi = when (kind) {
        ERROR_KIND_INVALID_KEY -> VoiceErrorUi(
            kind = kind,
            message = "Your API key was rejected. Update it in provider settings — " +
                "retrying with the same key won't work.",
            primaryAction = VoiceErrorAction.UPDATE_KEY,
            allowRetry = false,
        )
        ERROR_KIND_RATE_LIMITED -> VoiceErrorUi(
            kind = kind,
            message = "This provider is rate-limiting you right now. Switch to " +
                "another provider, or wait a little and retry.",
            primaryAction = VoiceErrorAction.SWITCH_PROVIDER,
            secondaryAction = VoiceErrorAction.RETRY,
        )
        ERROR_KIND_REQUEST_TOO_LARGE -> VoiceErrorUi(
            kind = kind,
            message = "This chat got too long for the model. Switch to a model " +
                "with a bigger context window to keep talking.",
            primaryAction = VoiceErrorAction.CHANGE_MODEL,
            allowRetry = false,
        )
        ERROR_KIND_MODEL_NOT_FOUND -> VoiceErrorUi(
            kind = kind,
            message = "That model isn't available anymore. Pick a current model " +
                "to keep talking.",
            primaryAction = VoiceErrorAction.CHANGE_MODEL,
            allowRetry = false,
        )
        ERROR_KIND_OUTPUT_LIMIT -> VoiceErrorUi(
            kind = kind,
            message = "The reply hit this model's output limit. Try a model " +
                "that allows longer replies.",
            primaryAction = VoiceErrorAction.CHANGE_MODEL,
            allowRetry = false,
        )
        ERROR_KIND_NETWORK -> VoiceErrorUi(
            kind = kind,
            message = "Couldn't reach the provider. Check your connection and try again.",
            primaryAction = VoiceErrorAction.RETRY,
        )
        else -> VoiceErrorUi(
            kind = kind,
            message = fallbackMessage.ifBlank { "Couldn't get a reply. Try again." },
            primaryAction = VoiceErrorAction.RETRY,
        )
    }
}
