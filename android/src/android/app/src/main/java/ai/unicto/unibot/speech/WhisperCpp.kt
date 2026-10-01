package ai.unicto.unibot.speech

import android.util.Log

/**
 * JNI façade over the `unibot_whisper` native library (whisper.cpp).
 *
 * Loading is best-effort: on devices where the native lib fails to load
 * (missing ABI slice, corrupted install) [isLoaded] is false and the
 * [WhisperCppSpeechRecognitionEngine] reports itself unavailable instead of
 * crashing the process at class-load time.
 */
internal object WhisperCpp {
    private const val TAG = "WhisperCpp"

    /** Non-null when [System.loadLibrary] threw. */
    var loadError: Throwable? = null
        private set

    val isLoaded: Boolean
        get() = loaded

    private var loaded = false

    init {
        try {
            System.loadLibrary("unibot_whisper")
            loaded = true
        } catch (t: Throwable) {
            loadError = t
            Log.w(TAG, "unibot_whisper failed to load: ${t.message}")
        }
    }

    /**
     * Load the model file into a whisper context. Returns 0 on failure.
     * The caller owns the handle and must call [nativeFree].
     */
    external fun nativeInit(modelPath: String): Long

    /** One-shot transcription of 16 kHz mono PCM. Blocking — call off the UI thread. */
    external fun nativeTranscribe(ctx: Long, pcm: ShortArray): String

    external fun nativeFree(ctx: Long)
}
