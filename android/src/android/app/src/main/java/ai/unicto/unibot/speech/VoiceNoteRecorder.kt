package ai.unicto.unibot.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Voice theme item 32: async voice notes — record a voice message in chat;
 * it transcribes on send.
 *
 * Unlike live dictation ([SpeechRecognitionManager]), this captures raw
 * 16 kHz mono PCM to a WAV file with NO streaming recognition: the note is
 * an artefact the user can replay, and transcription happens once, at send
 * time ([VoiceNoteTranscriber]). WAV (not AAC/3GP) is deliberate — the file
 * feeds whisper.cpp's PCM transcriber directly AND the provider transcription
 * endpoints accept it.
 *
 * Battery discipline: the AudioRecord thread runs only while recording, with
 * a hard [MAX_DURATION_MS] cap; [stop]/[cancel] always release the recorder.
 * Callers must hold RECORD_AUDIO (the UI runs the runtime permission flow
 * before starting).
 */
class VoiceNoteRecorder(private val appContext: Context) {

    data class VoiceNote(val file: File, val durationMs: Long)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** 0f..1f rolling mic amplitude for the recording indicator. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    /**
     * Start capturing to a new WAV file in the app cache. Returns false when
     * the mic permission is missing or no recorder could be built — the
     * caller shows the permission flow / an error instead of recording
     * silence.
     */
    fun start(): Boolean {
        if (_isRecording.value) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "no valid AudioRecord buffer size")
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord construction failed: ${t.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val file = File(appContext.cacheDir, "voice_note_${System.currentTimeMillis()}.wav")
        return try {
            // Placeholder header; patched with real sizes on stop().
            file.outputStream().use { WavHeader.writePlaceholder(it, SAMPLE_RATE) }
            val pcmOut = RandomAccessFile(file, "rw").apply { seek(WavHeader.SIZE_BYTES.toLong()) }
            recorder = record
            outputFile = file
            startedAtMs = android.os.SystemClock.elapsedRealtime()
            _isRecording.value = true
            record.startRecording()
            val thread = Thread({ recordLoop(record, pcmOut) }, "voice-note-record")
            recordThread = thread
            thread.start()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "voice note start failed: ${t.message}")
            runCatching { record.release() }
            recorder = null
            file.delete()
            false
        }
    }

    /**
     * Stop capture and return the note. Null when nothing was recording or
     * the take was too short to be useful ([MIN_DURATION_MS]).
     */
    fun stop(): VoiceNote? {
        if (!_isRecording.value) return null
        _isRecording.value = false
        val file = outputFile
        outputFile = null
        return try {
            recordThread?.join(2_000)
            recordThread = null
            recorder?.runCatching { stop() }
            recorder?.release()
            recorder = null
            if (file == null || !file.exists()) return null
            val durationMs = android.os.SystemClock.elapsedRealtime() - startedAtMs
            if (durationMs < MIN_DURATION_MS) {
                file.delete()
                return null
            }
            WavHeader.patchSizes(file, SAMPLE_RATE)
            VoiceNote(file, durationMs)
        } catch (t: Throwable) {
            Log.w(TAG, "voice note stop failed: ${t.message}")
            file?.delete()
            null
        } finally {
            _amplitude.value = 0f
        }
    }

    /** Abandon the take: stop capture and delete the file. */
    fun cancel() {
        _isRecording.value = false
        val file = outputFile
        outputFile = null
        recordThread?.join(2_000)
        recordThread = null
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        file?.delete()
        _amplitude.value = 0f
    }

    private fun recordLoop(record: AudioRecord, pcmOut: RandomAccessFile) {
        val buf = ShortArray(2048)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            while (_isRecording.value) {
                // Hard cap: a forgotten recording must not fill the disk or
                // hold the mic forever.
                if (android.os.SystemClock.elapsedRealtime() - startedAt > MAX_DURATION_MS) break
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    val bytes = ByteArray(n * 2)
                    var peak = 0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        if (abs(s) > peak) peak = abs(s)
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    pcmOut.write(bytes)
                    _amplitude.value = (peak / 32768f).coerceIn(0f, 1f)
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "record loop failed: ${t.message}")
        } finally {
            runCatching { pcmOut.close() }
            // The cap path breaks the loop without going through stop(): mark
            // not-recording so a later stop() still finalizes the file.
            _isRecording.value = false
        }
    }

    companion object {
        private const val TAG = "VoiceNoteRecorder"
        const val SAMPLE_RATE = 16_000
        /** Takes shorter than this are discarded (pocket taps). */
        const val MIN_DURATION_MS = 800L
        /** Hard cap on a single take. */
        const val MAX_DURATION_MS = 5 * 60 * 1_000L
    }
}

/**
 * Minimal WAV writer: 16-bit PCM mono. Pure — unit-tested via [write] byte
 * output. The recorder writes a placeholder header up front (streaming
 * capture can't know the data size in advance) and patches it on stop.
 */
object WavHeader {
    const val SIZE_BYTES = 44

    fun writePlaceholder(out: OutputStream, sampleRate: Int) {
        write(out, dataBytes = 0, sampleRate = sampleRate)
    }

    fun write(out: OutputStream, dataBytes: Int, sampleRate: Int) {
        val buf = ByteArray(SIZE_BYTES)
        fun ascii(offset: Int, s: String) {
            s.toByteArray().copyInto(buf, offset)
        }
        fun le32(offset: Int, v: Int) {
            buf[offset] = (v and 0xFF).toByte()
            buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
            buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
            buf[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(offset: Int, v: Int) {
            buf[offset] = (v and 0xFF).toByte()
            buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1) // PCM
        le16(22, 1) // mono
        le32(24, sampleRate)
        le32(28, sampleRate * 2) // byte rate: sampleRate * channels * bits/8
        le16(32, 2) // block align
        le16(34, 16) // bits per sample
        ascii(36, "data")
        le32(40, dataBytes)
        out.write(buf)
    }

    /** Rewrite the size fields after capture finished. */
    fun patchSizes(file: File, sampleRate: Int) {
        val dataBytes = (file.length() - SIZE_BYTES).toInt().coerceAtLeast(0)
        val buf = java.io.ByteArrayOutputStream(SIZE_BYTES)
        write(buf, dataBytes, sampleRate)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(buf.toByteArray())
        }
    }
}
