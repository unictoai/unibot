package ai.unicto.unibot.speech

import android.content.Context
import android.util.Log
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.provider.voice.VoiceInputRequest
import ai.unicto.unibot.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice theme item 32: transcribe a recorded voice note ONCE, at send time.
 *
 * Route order is privacy-first, matching the app's offline bias:
 *  1. on-device whisper.cpp (zero network) when a model is downloaded;
 *  2. the user's configured voice-input provider (their own key, BYOK);
 *  3. throw [NoTranscriberException] — the UI then tells the user to download
 *     the offline model instead of failing silently.
 *
 * The system SpeechRecognizer has no file-input API, so it is deliberately
 * not a route: a voice note must transcribe deterministically, not only on
 * ROMs with a working recognition service.
 */
object VoiceNoteTranscriber {

    /** No transcription route available (no offline model, no provider). */
    class NoTranscriberException :
        Exception("No voice transcription available — download the offline voice model first.")

    /**
     * Transcribe [audioFile] (a WAV written by [VoiceNoteRecorder]) to text.
     * Blocking native/network work runs off the main thread.
     */
    suspend fun transcribe(
        context: Context,
        audioFile: File,
        providerRepository: ProviderRepository,
    ): String = withContext(Dispatchers.IO) {
        // 1. On-device whisper — the offline path.
        val modelFile = WhisperModelManager.activeModelFile(context)
        if (modelFile != null && WhisperCpp.isLoaded) {
            val pcm = readWavPcm16(audioFile)
            if (pcm != null) {
                val text = transcribeOffline(modelFile.absolutePath, pcm)
                if (text.isNotBlank()) return@withContext text
                Log.w(TAG, "offline whisper returned blank — trying provider route")
            }
        }
        // 2. Provider transcription endpoint (BYOK).
        val choice = runCatching { providerRepository.resolveVoiceInputChoice() }.getOrNull()
        val entry = choice?.takeIf { !it.isSystem }?.entry
        if (entry != null) {
            val (instance, modelEntry) = entry
            val apiKey = providerRepository.loadApiKey(instance.id)
            val voice = VoiceProviderFactory.make(instance, apiKey)
            if (voice != null && apiKey != null) {
                val response = voice.transcribe(
                    VoiceInputRequest(
                        audioData = audioFile.readBytes(),
                        model = modelEntry.model.id,
                        resolvedModel = modelEntry.model,
                    ),
                )
                if (response.text.isNotBlank()) return@withContext response.text.trim()
            }
        }
        throw NoTranscriberException()
    }

    private suspend fun transcribeOffline(modelPath: String, pcm: ShortArray): String =
        withContext(Dispatchers.Default) {
            val ctx = WhisperCpp.nativeInit(modelPath)
            if (ctx == 0L) {
                Log.w(TAG, "whisper nativeInit failed")
                return@withContext ""
            }
            try {
                WhisperCpp.nativeTranscribe(ctx, pcm).trim()
            } catch (t: Throwable) {
                Log.w(TAG, "whisper transcribe failed: ${t.message}")
                ""
            } finally {
                runCatching { WhisperCpp.nativeFree(ctx) }
            }
        }

    /**
     * Read 16-bit mono PCM samples from a WAV file (44-byte header written by
     * [WavHeader]). Null when the file isn't a readable WAV. Internal for
     * testing.
     */
    internal fun readWavPcm16(file: File): ShortArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= WavHeader.SIZE_BYTES) return null
            if (!(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte())) return null
            val pcmBytes = bytes.copyOfRange(WavHeader.SIZE_BYTES, bytes.size)
            val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(pcmBytes.size / 2) { buf.short }
        } catch (t: Throwable) {
            Log.w(TAG, "wav read failed: ${t.message}")
            null
        }
    }

    private const val TAG = "VoiceNoteTranscriber"
}
