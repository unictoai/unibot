package ai.unicto.unibot.local

import android.util.Log

/**
 * Concrete token-callback target for the native layer.
 *
 * JNI looks up `onToken(String)` on the runtime class of the callback
 * instance passed to [LlamaCpp.nativeGenerate]; a tiny concrete class keeps
 * that lookup trivial instead of going through a Kotlin lambda's `invoke`.
 */
class LlamaTokenForwarder(private val onPiece: (String) -> Unit) {
    @Suppress("unused") // called from llama_jni.cpp via JNI
    fun onToken(piece: String) = onPiece(piece)
}

/**
 * JNI façade over the `unibot_llama` native library (llama.cpp).
 *
 * Mirrors [ai.unicto.unibot.speech.WhisperCpp]: loading is best-effort, so a
 * phone whose install lacks the ABI slice (or a build where the native step
 * failed) degrades to "on-device chat unavailable" instead of crashing at
 * class-load time. [isLoaded] is false in that case and [loadError] carries
 * the cause for the UI to explain.
 *
 * Threading: all native calls block. Call them off the UI thread (the
 * backend runs them on Dispatchers.IO). Only one generation may be in flight
 * per process — [LocalChatService] serializes with a mutex.
 */
internal object LlamaCpp {
    private const val TAG = "LlamaCpp"

    /** Non-null when [System.loadLibrary] threw. */
    var loadError: Throwable? = null
        private set

    val isLoaded: Boolean
        get() = loaded

    private var loaded = false

    init {
        try {
            System.loadLibrary("unibot_llama")
            loaded = true
        } catch (t: Throwable) {
            loadError = t
            Log.w(TAG, "unibot_llama failed to load: ${t.message}")
        }
    }

    /**
     * Load the GGUF at [modelPath] and create a decode context.
     * Returns 0 on failure. The caller owns the handle and must call
     * [nativeFree]. Blocking — call off the UI thread.
     */
    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long

    /**
     * Generate a completion for the FULL templated prompt (history included).
     * Streams each decoded piece through [callback] on the calling thread,
     * then returns the complete text. Stops at the end-of-generation token,
     * [maxTokens], or [nativeCancel]. Blocking — call off the UI thread.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: LlamaTokenForwarder,
    ): String

    /** Ask an in-flight [nativeGenerate] to stop at the next token. */
    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)
}
