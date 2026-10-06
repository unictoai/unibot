package ai.unicto.unibot.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hand-over wait (item 40): the agent pauses — never fails — at a page it
 * cannot complete itself (login, CAPTCHA / 2FA, payment, consent screen)
 * until the user finishes in the visible browser and taps "Done".
 *
 * Flow:
 * 1. The agent calls `browser_use` with action `wait_for_user` and a short
 *    `message` ("Sign in to ExampleBank, then tap Done").
 * 2. [awaitUser] posts the [Request] (the browser UI shows a banner with a
 *    Done button) and suspends the tool call.
 * 3. The user takes over the visible browser (the existing take-control
 *    path), completes the step, taps Done → [resolve].
 * 4. The tool call returns success and the agent continues with whatever
 *    the user left on screen.
 *
 * Cancellation (user dismisses the banner / closes the browser) and the
 * timeout both end the wait WITHOUT throwing: the tool returns a plain
 * outcome the model can react to (e.g. ask the user what happened).
 *
 * Pure Kotlin — no Android dependencies — so the state machine is
 * unit-testable on the JVM.
 */
class HandoverWaitController {

    data class Request(
        /** Short instruction shown in the banner, e.g. "Sign in, then tap Done". */
        val message: String,
        /** Page URL when the wait started, for the banner subtitle. */
        val pageUrl: String?,
    )

    enum class Outcome { RESUMED, CANCELLED, TIMED_OUT }

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Post the wait and suspend until [resolve], [cancel], or [timeoutMs].
     * Only one wait is active at a time; a second call cancels the first
     * (defensive — the agent loop is serial per tab).
     */
    suspend fun awaitUser(
        message: String,
        pageUrl: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Outcome {
        pending?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        _request.value = Request(message.ifBlank { DEFAULT_MESSAGE }, pageUrl)
        val resumed = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending = null
        _request.value = null
        return when (resumed) {
            true -> Outcome.RESUMED
            false -> Outcome.CANCELLED
            null -> Outcome.TIMED_OUT
        }
    }

    /** The user tapped Done — the agent continues. */
    fun resolve() {
        pending?.complete(true)
    }

    /** The wait was dismissed — the agent gets CANCELLED, not an error. */
    fun cancel() {
        pending?.complete(false)
    }

    companion object {
        /** 10 minutes: logins with 2FA can take a while; the banner shows. */
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
        const val DEFAULT_MESSAGE = "Complete the step in the browser, then tap Done."
    }
}
