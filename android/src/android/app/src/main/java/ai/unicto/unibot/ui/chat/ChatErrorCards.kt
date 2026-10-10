package ai.unicto.unibot.ui.chat

/**
 * v1.4.0 item 13 — friendly error cards for HTTP statuses that used to
 * surface as raw provider JSON text.
 *
 * Every card is plain language plus ONE fix action. The numeric code and
 * the raw provider body never appear in the banner — they travel in
 * [ChatMessage.errorRaw] and are only reachable through the banner's
 * long-press copy.
 *
 * Pure logic, no Android — fully unit-tested. The chat layer maps a
 * numeric [ai.unicto.unibot.data.model.LLMError.httpStatus] to a kind via
 * [errorKindForHttpStatus] and renders the card from [httpStatusCard].
 */

/**
 * Machine-readable kind for [ChatMessage.errorKind] for the item-13 cards.
 * Full kind = "http_" + code, e.g. "http_402". The numeric suffix keeps the
 * mapping reversible without parsing the banner text.
 */
const val ERROR_KIND_HTTP_PREFIX = "http_"

/** Machine-readable kind for v1.4.0 item 15 — the OAuth sign-in expired. */
const val ERROR_KIND_OAUTH_EXPIRED = "oauth_expired"

/** HTTP statuses that get a friendly card instead of raw provider text. */
val HTTP_CARD_CODES: Set<Int> = setOf(400, 402, 408, 409, 410, 422, 451)

fun errorKindForHttpStatus(code: Int): String = "$ERROR_KIND_HTTP_PREFIX$code"

fun errorKindIsHttpStatus(kind: String?): Boolean =
    kind != null && kind.startsWith(ERROR_KIND_HTTP_PREFIX) &&
        kind.removePrefix(ERROR_KIND_HTTP_PREFIX).toIntOrNull() in HTTP_CARD_CODES

/** The single fix action a card offers. Rendered as one pill next to the copy. */
enum class HttpStatusFix {
    /** Re-sending can plausibly succeed (timeouts, conflicts). */
    RETRY,
    /** The request needs a different model — retrying the same one repeats the failure. */
    CHANGE_MODEL,
    /** The account needs attention — open the provider's settings page. */
    PROVIDER_SETTINGS,
    /** Payment/quota issue — open the model picker filtered to free models. */
    FIND_FREE_MODEL,
    /** Nothing actionable — explanation only, no fix pill. */
    NONE,
}

data class HttpStatusCard(
    val code: Int,
    /** Short headline — never contains the raw code or provider JSON. */
    val headline: String,
    /** One plain-language sentence with model + provider filled in. */
    val body: String,
    val fix: HttpStatusFix,
    /** Pill label for [fix]; empty when [fix] is NONE. */
    val fixLabel: String,
    /** False when re-sending the identical request is known-futile. */
    val allowRetry: Boolean,
)

/**
 * Friendly card copy for an HTTP status. [modelName]/[providerName] are
 * display names; blanks degrade to "this model" / "the provider".
 */
fun httpStatusCard(code: Int, modelName: String, providerName: String): HttpStatusCard {
    val model = modelName.ifBlank { "this model" }
    val provider = providerName.ifBlank { "the provider" }
    return when (code) {
        400 -> HttpStatusCard(
            code = code,
            headline = "Request rejected",
            body = "$provider didn't accept the request for $model — " +
                "usually a parameter this model doesn't support.",
            fix = HttpStatusFix.CHANGE_MODEL,
            fixLabel = "Change model",
            allowRetry = false,
        )
        402 -> HttpStatusCard(
            code = code,
            headline = "Payment required",
            body = "$provider needs billing enabled or more quota before " +
                "$model can reply. Pick a free model below to keep chatting.",
            fix = HttpStatusFix.FIND_FREE_MODEL,
            fixLabel = "Find a free model",
            allowRetry = false,
        )
        408 -> HttpStatusCard(
            code = code,
            headline = "Request timed out",
            body = "$provider took too long to answer. Your message is safe — " +
                "trying again usually works.",
            fix = HttpStatusFix.RETRY,
            fixLabel = "Retry",
            allowRetry = true,
        )
        409 -> HttpStatusCard(
            code = code,
            headline = "Request conflict",
            body = "$provider reported a conflict for this request. " +
                "Trying again usually resolves it.",
            fix = HttpStatusFix.RETRY,
            fixLabel = "Retry",
            allowRetry = true,
        )
        410 -> HttpStatusCard(
            code = code,
            headline = "No longer available",
            body = "That endpoint or model version was retired by $provider. " +
                "Pick a current model below.",
            fix = HttpStatusFix.CHANGE_MODEL,
            fixLabel = "Change model",
            allowRetry = false,
        )
        422 -> HttpStatusCard(
            code = code,
            headline = "Couldn't process that",
            body = "$provider couldn't process the request for $model. " +
                "Try rephrasing, or pick another model.",
            fix = HttpStatusFix.CHANGE_MODEL,
            fixLabel = "Change model",
            allowRetry = false,
        )
        451 -> HttpStatusCard(
            code = code,
            headline = "Blocked for legal reasons",
            body = "$provider refused this request for legal reasons. " +
                "Nothing is wrong with your setup.",
            fix = HttpStatusFix.NONE,
            fixLabel = "",
            allowRetry = false,
        )
        else -> HttpStatusCard(
            code = code,
            headline = "Provider error",
            body = "$provider returned an unexpected response.",
            fix = HttpStatusFix.RETRY,
            fixLabel = "Retry",
            allowRetry = true,
        )
    }
}

/** Friendly card body for v1.4.0 item 14 — invalid API key, no futile Retry. */
internal fun friendlyInvalidKeyText(providerName: String): String {
    val provider = providerName.ifBlank { "the provider" }
    return "The API key for $provider was rejected. Update it in provider " +
        "settings — retrying with the same key won't work."
}

/** Friendly card body for v1.4.0 item 15 — expired OAuth sign-in. */
internal fun friendlyOAuthExpiredText(providerName: String): String {
    val provider = providerName.ifBlank { "the provider" }
    return "Your sign-in with $provider expired. Sign in again to keep chatting."
}

// ── Banner wiring helpers ─────────────────────────────────────────────
// The banner text is built at error time (setInlineError); these resolve
// only the fix PILL from a persisted/restored error kind, so a card
// restored after a restart still offers the right action.

private fun httpCodeForKind(kind: String?): Int? =
    kind?.takeIf { errorKindIsHttpStatus(it) }
        ?.removePrefix(ERROR_KIND_HTTP_PREFIX)?.toIntOrNull()

/** The fix enum for an error kind, or null when the kind isn't an HTTP-status card. */
fun httpFixForKind(kind: String?): HttpStatusFix? {
    val code = httpCodeForKind(kind) ?: return null
    return httpStatusCard(code, "", "").fix
}

/** The fix pill label for an error kind, or null when there is no pill. */
fun httpFixLabelForKind(kind: String?): String? {
    val code = httpCodeForKind(kind) ?: return null
    return httpStatusCard(code, "", "").fixLabel.takeIf { it.isNotEmpty() }
}

/** The fix pill action for an error kind, or null when there is no pill. */
fun httpFixActionForKind(
    kind: String?,
    onRetryLast: () -> Unit,
    onChangeModel: () -> Unit,
    onOpenProvider: () -> Unit,
    onFindFreeModel: () -> Unit = onChangeModel,
): (() -> Unit)? = when (httpFixForKind(kind)) {
    HttpStatusFix.RETRY -> onRetryLast
    HttpStatusFix.CHANGE_MODEL -> onChangeModel
    HttpStatusFix.PROVIDER_SETTINGS -> onOpenProvider
    HttpStatusFix.FIND_FREE_MODEL -> onFindFreeModel
    HttpStatusFix.NONE, null -> null
}
