package ai.unicto.unibot.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LlmBackend] over llama.cpp ([LlamaCpp]).
 *
 * Lifecycle: the model is loaded lazily on the first [generate] and stays
 * resident for fast follow-up turns; [LocalChatService.release] (or [close])
 * unloads it. Only one generation runs at a time per process.
 *
 * Privacy: this file performs ZERO network I/O. The model file is read from
 * app-private storage; tokens never leave the device. (The one network touch
 * in the feature — the opt-in GGUF download — lives in [LlamaModelManager]
 * and only runs on an explicit user tap.)
 */
class LocalLlamaBackend(
    private val appContext: Context,
    val model: LlamaModel,
) : LlmBackend {

    override val id: String = "llama-${model.id}"
    override val displayName: String = model.title

    private var handle: Long = 0L

    /** Context window: small enough for the KV cache to fit low-end phones. */
    private val nCtx: Int = 2048

    /**
     * [v1.2 Batch F] The context-window size — the denominator of the
     * context-size indicator ("312 of 2048 tokens used").
     */
    val contextMax: Int
        get() = nCtx

    /**
     * [v1.2 Batch F] Token counts from the most recent [generate] on this
     * backend (prompt tokens the model saw, reply tokens it produced).
     * Read from the native handle right after generation returns.
     */
    var lastPromptTokens: Int = 0
        private set
    var lastGeneratedTokens: Int = 0
        private set

    /** Load the model into RAM without generating (benchmark warm-up). */
    fun loadModel() {
        ensureLoaded()
    }

    private val nThreads: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    override val isReady: Boolean
        get() = LlamaCpp.isLoaded && LlamaModelManager.isDownloaded(appContext, model)

    override fun notReadyReason(): String? = when {
        !LlamaCpp.isLoaded ->
            "On-device engine failed to load on this phone" +
                (LlamaCpp.loadError?.message?.let { ": $it" } ?: "")
        !LlamaModelManager.isDownloaded(appContext, model) ->
            "${model.title} isn't downloaded yet"
        else -> null
    }

    private fun ensureLoaded() {
        if (handle != 0L) return
        if (!LlamaCpp.isLoaded) {
            throw IllegalStateException(
                "On-device engine unavailable" +
                    (LlamaCpp.loadError?.message?.let { " (${it})" } ?: ""),
            )
        }
        val file = LlamaModelManager.modelFile(appContext, model)
        if (!file.isFile || file.length() < model.minBytes) {
            throw IllegalStateException(
                "${model.title} is missing or incomplete — delete it and download again.",
            )
        }
        handle = LlamaCpp.nativeInit(file.absolutePath, nCtx, nThreads)
        if (handle == 0L) {
            throw IllegalStateException(
                "Could not load ${model.title} — the file may be corrupt. " +
                    "Try deleting it and downloading again.",
            )
        }
        Log.i(TAG, "loaded ${model.id} (nCtx=$nCtx threads=$nThreads)")
    }

    override suspend fun generate(
        systemPrompt: String?,
        history: List<LlmTurn>,
        prompt: String,
        samplerOverride: SamplerSettings?,
        onToken: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        ensureLoaded()
        val h = handle
        // Stop-button support: cancelling this coroutine must stop the
        // blocking native call promptly.
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                try {
                    LlamaCpp.nativeCancel(h)
                } catch (_: Throwable) {
                }
            }
        }
        // [v1.2 Batch F] Sampling comes from the user's per-model Sampler
        // settings (or the one-turn override) — these flow into the native
        // sampler chain on every turn.
        val s = samplerOverride ?: SamplerSettingsStore.load(appContext, model)
        val fullPrompt = applyTemplate(model.template, systemPrompt, history, prompt)
        val raw = LlamaCpp.nativeGenerate(
            h,
            fullPrompt,
            s.maxTokens,
            s.temperature,
            s.topP,
            s.topK,
            s.repeatPenalty,
            LlamaTokenForwarder(onToken),
        )
        // [v1.2 Batch F] Token counts for the benchmark + context indicator.
        val stats = LlamaCpp.nativeLastTurnStats(h)
        lastPromptTokens = (stats ushr 32).toInt()
        lastGeneratedTokens = stats.toInt()
        // Cooperative cancellation point after the blocking native call.
        ensureActive()
        stripStopSequences(raw).trim()
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) {
            try {
                LlamaCpp.nativeCancel(h)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeCancel failed: ${t.message}")
            }
        }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            try {
                LlamaCpp.nativeFree(h)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeFree failed: ${t.message}")
            }
            Log.i(TAG, "unloaded ${model.id}")
        }
    }

    companion object {
        private const val TAG = "LocalLlama"

        /**
         * Apply the model's chat template. Kept in Kotlin (not llama.cpp's
         * `common`) so the native surface stays minimal. Templates verified
         * against the model cards: Qwen3 uses plain ChatML; LFM2 uses a
         * ChatML-like template with a `<|startoftext|>` prefix.
         */
        fun applyTemplate(
            template: LlamaChatTemplate,
            systemPrompt: String?,
            history: List<LlmTurn>,
            prompt: String,
        ): String {
            val bos = if (template == LlamaChatTemplate.LFM2_CHATML) "<|startoftext|>" else ""
            fun turn(role: String, text: String): String =
                "<|im_start|>$role\n$text<|im_end|>\n"
            return buildString {
                append(bos)
                val sys = systemPrompt?.takeIf { it.isNotBlank() }
                if (sys != null) append(turn("system", sys))
                // Keep the most recent turns that plausibly fit; the native
                // layer drops the oldest prompt tokens as a backstop.
                val recent = history.takeLast(12)
                for (t in recent) {
                    if (t.text.isBlank()) continue
                    append(turn(if (t.role == "assistant") "assistant" else "user", t.text))
                }
                append(turn("user", prompt))
                append("<|im_start|>assistant\n")
            }
        }

        /** Cut the reply at the template's end-of-turn marker if it leaked through. */
        fun stripStopSequences(text: String): String {
            val idx = text.indexOf("<|im_end|>")
            return if (idx >= 0) text.substring(0, idx) else text
        }
    }
}

/**
 * Process-wide owner of the on-device backend: serializes generations,
 * (re)loads the backend when the selected model changes, and releases the
 * ~1 GB native allocation when the user leaves on-device mode.
 */
object LocalChatService {
    private const val TAG = "LocalChatService"
    private val mutex = Mutex()
    private var backend: LocalLlamaBackend? = null
    private var loadedModelId: String? = null

    /**
     * Run one local turn. Loads [model] on first use (or when the selection
     * changed). Never touches the network.
     */
    suspend fun generate(
        context: Context,
        model: LlamaModel,
        systemPrompt: String?,
        history: List<LlmTurn>,
        prompt: String,
        maxTokens: Int = 1024,
        onToken: (String) -> Unit,
    ): String = mutex.withLock {
        val appContext = context.applicationContext
        if (backend == null || loadedModelId != model.id) {
            backend?.close()
            backend = LocalLlamaBackend(appContext, model)
            loadedModelId = model.id
        }
        val b = backend ?: throw IllegalStateException("On-device backend unavailable")
        if (!b.isReady) {
            throw IllegalStateException(b.notReadyReason() ?: "On-device model not ready")
        }
        // [v1.2 Batch F] Sampling (temperature, top-p/top-k, repeat
        // penalty, max tokens) comes from the model's persisted Sampler
        // settings, which the backend reads itself.
        val text = b.generate(
            systemPrompt = systemPrompt,
            history = history,
            prompt = prompt,
            samplerOverride = null,
            onToken = onToken,
        )
        // [v1.2 Batch F] Publish token counts for the context-size
        // indicator on the on-device model screen.
        _lastTurnStats.value = TurnStats(
            modelTitle = model.title,
            promptTokens = b.lastPromptTokens,
            generatedTokens = b.lastGeneratedTokens,
            contextMax = b.contextMax,
        )
        text
    }

    /**
     * [v1.2 Batch F] Stats from the most recent on-device turn. Observed by
     * the context-size indicator ("312 of 2048 tokens used").
     */
    data class TurnStats(
        val modelTitle: String,
        val promptTokens: Int,
        val generatedTokens: Int,
        val contextMax: Int,
    )

    private val _lastTurnStats = MutableStateFlow<TurnStats?>(null)
    val lastTurnStats: StateFlow<TurnStats?> = _lastTurnStats.asStateFlow()

    /** True when a model is currently resident in RAM. */
    fun isModelLoaded(): Boolean = backend != null

    fun cancel() {
        try {
            backend?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed: ${t.message}")
        }
    }

    /** Unload the native model, freeing its RAM. Called when leaving on-device mode. */
    fun release() {
        try {
            backend?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed: ${t.message}")
        } finally {
            backend = null
            loadedModelId = null
        }
    }
}
