package ai.unicto.unibot.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * On-device text-to-speech via sherpa-onnx (VITS / Piper voices).
 *
 * Abdullah's Infinix ships with NO system speech service, so this is the TTS
 * half of fully on-device voice: [WhisperCppSpeechRecognitionEngine] covers
 * speech-to-text, this covers text-to-speech. Voices are downloaded on demand
 * by [TtsVoiceModelManager] — never bundled in the APK — and the native
 * library comes from the `com.bihe0832.android:lib-sherpa-onnx` AAR with the
 * hand-ported bindings in `com.k2fsa.sherpa.onnx.Tts.kt`.
 *
 * Loading is best-effort like [WhisperCpp]: [nativeAvailable] is false when
 * the .so can't load, and every entry point degrades silently instead of
 * crashing the process at class-load time.
 */
object SherpaTtsEngine {
    private const val TAG = "SherpaTts"

    /**
     * Minimum plausible voice-model size. Published voices are ~60 MB; a
     * truncated download must never reach the native loader (it aborts the
     * process on corrupt models instead of returning an error).
     */
    private const val MIN_MODEL_BYTES = 1_000_000L

    /** Non-null when [System.loadLibrary] threw. */
    var loadError: Throwable? = null
        private set

    /** True when `libsherpa-onnx-jni.so` loaded successfully. */
    var nativeAvailable = false
        private set

    init {
        runCatching { System.loadLibrary("sherpa-onnx-jni") }
            .onSuccess { nativeAvailable = true }
            .onFailure { loadError = it; Log.w(TAG, "sherpa-onnx-jni failed to load: ${it.message}") }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isSpeaking = MutableStateFlow(false)
    /** True while synthesizing or playing audio. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: OfflineTts? = null
    private var speed = 1.0f
    private var speakJob: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopRequested = false

    /** True when a voice is loaded and [speak] can produce audio. */
    val isReady: Boolean get() = nativeAvailable && tts != null

    /**
     * Load a voice from [voiceDir], which must contain `model.onnx`,
     * `tokens.txt` and the extracted `espeak-ng-data/` directory (the layout
     * [TtsVoiceModelManager.activeVoiceDir] returns). Blocks for a second or
     * two while the model loads — call off the UI thread. Returns false when
     * the native library is unavailable or the model fails to load.
     *
     * [T-android-v121-tts-corrupt-model] The voice files are validated BEFORE
     * the native load: a truncated/interrupted download used to reach
     * `newFromFile`, where sherpa-onnx aborts the process (SIGABRT,
     * self-abort — the exact signature of the v1.2 voice-mode crash) instead
     * of returning an error. Now a bad download degrades to "voice
     * unavailable" and the manager can re-download.
     */
    fun initEngine(context: Context, voiceDir: File, speed: Float): Boolean {
        if (!nativeAvailable) return false
        if (!validateVoiceDir(voiceDir)) return false
        this.speed = speed
        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(voiceDir, "model.onnx").absolutePath,
                        tokens = File(voiceDir, "tokens.txt").absolutePath,
                        dataDir = File(voiceDir, "espeak-ng-data").absolutePath,
                    ),
                    numThreads = 2,
                ),
            )
            val engine = OfflineTts(config)
            synchronized(this) {
                tts?.free()
                tts = engine
            }
            Log.i(TAG, "voice loaded from ${voiceDir.name}, sampleRate=${engine.sampleRate()}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load TTS voice: ${t.message}")
            false
        }
    }

    /**
     * Sanity-check a downloaded voice directory. Voices are ~60 MB; anything
     * far smaller (or missing pieces) is a truncated download and must never
     * reach the native loader — sherpa-onnx aborts the process on corrupt
     * models instead of returning an error.
     */
    private fun validateVoiceDir(voiceDir: File): Boolean {
        val onnx = File(voiceDir, "model.onnx")
        val tokens = File(voiceDir, "tokens.txt")
        val espeakDir = File(voiceDir, "espeak-ng-data")
        val problems = mutableListOf<String>()
        if (!onnx.isFile || onnx.length() < MIN_MODEL_BYTES) {
            problems += "model.onnx missing or truncated (${onnx.length()} bytes)"
        }
        if (!tokens.isFile || tokens.length() == 0L) {
            problems += "tokens.txt missing or empty"
        }
        if (!espeakDir.isDirectory || (espeakDir.list()?.isEmpty() != false)) {
            problems += "espeak-ng-data/ missing or empty"
        }
        if (problems.isNotEmpty()) {
            Log.w(TAG, "voice dir ${voiceDir.name} failed validation: ${problems.joinToString("; ")}")
            return false
        }
        return true
    }

    /**
     * Speak [text] aloud. The text is sanitized with [VoiceTextSanitizer]
     * first (no spoken markdown, no emoji). Synthesis runs on
     * Dispatchers.Default; only one utterance plays at a time — a new
     * [speak] flushes the current one. [onDone] fires exactly once when the
     * utterance ends for any reason (finished or [stop]ped). No-op when the
     * engine isn't ready.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val clean = VoiceTextSanitizer.sanitize(text)
        if (clean.isBlank() || !isReady) {
            onDone()
            return
        }
        stop()
        _isSpeaking.value = true
        stopRequested = false
        speakJob = scope.launch {
            // Identity guard: stop() (or a flushing speak()) cancels the
            // previous job, whose finally must not clobber the new
            // utterance's isSpeaking=true.
            val myJob = coroutineContext[Job]
            try {
                // [T-android-v121-tts-use-after-free] Snapshot the engine
                // under the same lock release() uses. OfflineTts holds its
                // own lock across the blocking generate() call, so a
                // release() racing us waits for synthesis to finish instead
                // of deleting the native object mid-call (SIGABRT).
                val engine = synchronized(this@SherpaTtsEngine) { tts }
                val audio = engine?.generate(clean, sid = 0, speed = speed)
                if (audio == null || stopRequested) return@launch
                playPcm16(audio.samples, audio.sampleRate)
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
            } finally {
                if (speakJob === myJob) _isSpeaking.value = false
                runCatching { onDone() }
            }
        }
    }

    /**
     * Halt playback immediately (tap-to-interrupt). The in-flight [speak]'s
     * [onDone] still fires so queued logic can proceed.
     */
    fun stop() {
        stopRequested = true
        speakJob?.cancel()
        speakJob = null
        runCatching {
            track?.let {
                it.pause()
                it.flush()
                it.stop()
                it.release()
            }
        }
        track = null
        _isSpeaking.value = false
    }

    /** Free the native engine. Call when the voice is deleted or on shutdown. */
    fun release() {
        stop()
        synchronized(this) {
            tts?.free()
            tts = null
        }
    }

    /** Play float [-1, 1] mono samples as 16-bit PCM, honoring [stopRequested]. */
    private fun playPcm16(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty() || stopRequested) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        this.track = track
        try {
            track.play()
            val chunk = ShortArray(4096)
            var i = 0
            while (i < samples.size && !stopRequested) {
                val n = minOf(chunk.size, samples.size - i)
                for (j in 0 until n) {
                    val s = (samples[i + j] * 32767f).toInt().coerceIn(-32768, 32767)
                    chunk[j] = s.toShort()
                }
                var written = 0
                while (written < n && !stopRequested) {
                    val w = track.write(chunk, written, n - written)
                    if (w <= 0) break
                    written += w
                }
                i += n
            }
        } finally {
            runCatching {
                track.stop()
                track.release()
            }
            if (this.track === track) this.track = null
        }
    }
}
