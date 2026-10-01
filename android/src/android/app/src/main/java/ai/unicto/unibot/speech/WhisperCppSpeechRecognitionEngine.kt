package ai.unicto.unibot.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * On-device speech-to-text via whisper.cpp (English q5_1, user picks the
 * size: Quick ~31 MB, Balanced ~57 MB or Best ~181 MB).
 *
 * The model is NOT bundled in the APK — the user opts into downloading one
 * from the `voice-model-v1` GitHub release via [WhisperModelManager], after
 * which this engine reports [isAvailable] and the mic works fully offline:
 * no API key, no account, no network.
 *
 * Capture mirrors [ProviderSpeechRecognitionEngine]'s legacy record-until-stop
 * loop (PCM16 mono 16 kHz via [AudioRecord]); transcription is one-shot on
 * stop, so [supportsPartialResults] is false — same contract as the provider
 * engine, which the UI already handles.
 */
class WhisperCppSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "WhisperASR"
        private const val SAMPLE_RATE = 16_000

        /**
         * Hard cap on a single take. 120 s at 16 kHz PCM16 mono ≈ 3.8 MB in
         * memory; whisper.cpp windows long audio internally, so quality holds.
         */
        private const val MAX_RECORD_SECONDS = 120
    }

    override val id: String = "whisper-offline"
    override val displayName: String = "Offline voice"
    override val supportsPartialResults: Boolean = false

    /** tiny.en is English-only. */
    override val supportedLocales: List<Locale> = listOf(Locale.ENGLISH, Locale("en", "US"))

    override val isAvailable: Boolean
        get() = !degraded &&
            WhisperCpp.isLoaded &&
            WhisperModelManager.activeModelFile(appContext) != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var degraded = false

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO per interface contract
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        if (!WhisperCpp.isLoaded) {
            listener.onError(
                RecognitionError.UNKNOWN,
                "Offline voice engine failed to load (${WhisperCpp.loadError?.message}).",
            )
            return
        }
        val modelFile = WhisperModelManager.activeModelFile(appContext)
        if (modelFile == null || !modelFile.isFile) {
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "Offline voice model not downloaded.",
            )
            return
        }
        if (recording.getAndSet(true)) {
            listener.onError(RecognitionError.RECOGNIZER_BUSY, "A capture is already in flight.")
            return
        }
        cancelled.set(false)

        captureJob = scope.launch {
            // Load the model off the UI thread; even the ~181 MB model
            // loads in well under a few seconds on modern phones.
            // Per-session init keeps the process footprint lean when voice
            // isn't in use.
            val ctx = withContext(Dispatchers.Default) {
                runCatching { WhisperCpp.nativeInit(modelFile.absolutePath) }.getOrDefault(0L)
            }
            if (ctx == 0L) {
                recording.set(false)
                listener.onError(RecognitionError.UNKNOWN, "Could not load the voice model.")
                return@launch
            }
            try {
                captureAndTranscribe(ctx, listener)
            } finally {
                runCatching { WhisperCpp.nativeFree(ctx) }
            }
        }
    }

    private suspend fun captureAndTranscribe(
        ctx: Long,
        listener: SpeechRecognitionEngine.Listener,
    ) = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            recording.set(false)
            listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
            return@withContext
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (e: Exception) {
            recording.set(false)
            listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord init failed: ${e.message}")
            return@withContext
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            recording.set(false)
            listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord not initialized.")
            return@withContext
        }

        val pcm = mutableListOf<Short>()
        val buf = ShortArray(minBuf / 2)
        val maxSamples = SAMPLE_RATE * MAX_RECORD_SECONDS
        try {
            recorder.startRecording()
            listener.onReadyForSpeech()
            while (recording.get() && pcm.size < maxSamples) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) pcm.add(buf[i])
                listener.onRmsDb(rmsDb(buf, n))
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed: ${e.message}", e)
            recording.set(false)
            listener.onError(RecognitionError.AUDIO_ERROR, e.message)
            return@withContext
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (cancelled.get()) return@withContext
        if (pcm.isEmpty()) {
            listener.onError(RecognitionError.NO_MATCH, "No audio captured.")
            return@withContext
        }

        // CPU-bound inference — keep it off the IO dispatcher.
        val text = withContext(Dispatchers.Default) {
            runCatching { WhisperCpp.nativeTranscribe(ctx, pcm.toShortArray()).trim() }
                .getOrDefault("")
        }
        if (cancelled.get()) return@withContext
        if (text.isEmpty()) {
            // [T-android-asr-silent-failure] Speech was captured but nothing
            // came back — this must surface, not vanish like NO_MATCH.
            listener.onError(RecognitionError.TRANSCRIPTION_FAILED, "Couldn't understand the audio.")
        } else {
            listener.onFinal(text)
        }
    }

    override fun stop() {
        // Flip the capture loop off; it then transcribes the take and
        // delivers onFinal/onError.
        recording.set(false)
    }

    override fun cancel() {
        cancelled.set(true)
        recording.set(false)
        captureJob?.cancel()
        captureJob = null
    }

    /** Rough dB estimate over the chunk for the UI waveform ([0, 12] scale). */
    private fun rmsDb(buf: ShortArray, len: Int): Float {
        var sum = 0.0
        for (i in 0 until len) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        if (len == 0) return 0f
        val rms = sqrt(sum / len)
        if (rms <= 1.0) return 0f
        return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
    }
}
