package ai.unicto.unibot.local

/**
 * One conversation turn for a local backend. `role` is "user" or "assistant".
 */
data class LlmTurn(val role: String, val text: String)

/**
 * [P7-on-device-llm] The seam between the chat UI and any on-device LLM
 * engine. The cloud path keeps using [ai.unicto.unibot.provider.LLMProvider]
 * (with its agent loop, tools and fallbacks); when the user picks an
 * on-device model, [ChatViewModel.sendLocalMessage] talks to this interface
 * instead.
 *
 * Privacy contract: implementations MUST NOT perform any network I/O on the
 * generate path — no telemetry, no model-list refresh, no analytics. The only
 * network touch in the whole feature is the explicit, opt-in model download
 * in [LlamaModelManager], which the user starts by tapping Download.
 */
interface LlmBackend {
    /** Stable id, e.g. "llama-qwen3-1.7b". Surfaced in logs, not the UI. */
    val id: String

    /** Short human name for the model picker, e.g. "Qwen3 1.7B". */
    val displayName: String

    /**
     * True when a turn can actually run right now: the native library loaded
     * AND a complete model file is on disk.
     */
    val isReady: Boolean

    /** Short human reason when [isReady] is false (shown in the UI). */
    fun notReadyReason(): String?

    /**
     * Stream one assistant reply. [history] is oldest-first and excludes the
     * current [prompt]. Calls [onToken] for every decoded piece (on the
     * caller's thread) and returns the full reply text.
     *
     * Cooperative cancellation: throwing [kotlinx.coroutines.CancellationException]
     * from the caller's coroutine must stop generation promptly.
     */
    suspend fun generate(
        systemPrompt: String?,
        history: List<LlmTurn>,
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit,
    ): String

    /** Ask an in-flight [generate] to stop at the next token. */
    fun cancel()

    /** Release the native context and model (frees ~1 GB+ RAM). */
    fun close()
}
