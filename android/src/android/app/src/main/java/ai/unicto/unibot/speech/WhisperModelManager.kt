package ai.unicto.unibot.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the optional offline voice model (whisper.cpp `tiny.en` quantized).
 *
 * The model is NEVER bundled in the APK. The user taps "Download offline
 * voice" in the voice panel and it fetches the file from the `voice-model-v1`
 * GitHub release, then the [WhisperCppSpeechRecognitionEngine] becomes
 * available and the mic works fully on-device — no API key, no account.
 */
object WhisperModelManager {
    private const val TAG = "WhisperModel"

    /** Published by the `Voice model` CI workflow (see .github/workflows/voice-model.yml). */
    const val MODEL_URL =
        "https://github.com/unictoai/unibot/releases/download/voice-model-v1/ggml-tiny.en-q5_1.bin"
    const val MODEL_NAME = "ggml-tiny.en-q5_1.bin"

    /** Exact byte size of the published asset; a mismatch means a bad download. */
    const val EXPECTED_BYTES = 32_166_155L

    /** Human label for the download button, e.g. "~31MB". */
    const val MODEL_SIZE_LABEL = "~31MB"

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String?) : DownloadState
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    fun modelFile(context: Context): File =
        File(context.filesDir, "voice/$MODEL_NAME")

    /** True when a complete, verified model file is on disk. */
    fun isDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.isFile && f.length() == EXPECTED_BYTES
    }

    /**
     * Start (or restart) the download. Progress lands on [downloadState];
     * on success the file is verified by size before [DownloadState.Done].
     * No-op while a download is already in flight.
     */
    fun download(context: Context) {
        if (downloadJob?.isActive == true) return
        if (isDownloaded(context)) {
            _downloadState.value = DownloadState.Done
            return
        }
        _downloadState.value = DownloadState.Downloading(0f)
        val appContext = context.applicationContext
        downloadJob = scope.launch {
            try {
                downloadToFile(appContext)
                _downloadState.value = DownloadState.Done
                Log.i(TAG, "voice model downloaded and verified")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice model download failed: ${e.message}")
                runCatching { tmpFile(appContext).delete() }
                _downloadState.value = DownloadState.Failed(e.message)
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        if (_downloadState.value is DownloadState.Downloading) {
            _downloadState.value = DownloadState.Idle
        }
    }

    /** Delete the model (frees ~31 MB) and reset state. */
    fun delete(context: Context) {
        cancel()
        runCatching { modelFile(context).delete() }
        _downloadState.value = DownloadState.Idle
    }

    private fun tmpFile(context: Context): File =
        File(context.filesDir, "voice/$MODEL_NAME.part")

    private suspend fun downloadToFile(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "voice").also { it.mkdirs() }
        val tmp = tmpFile(context)
        // Resume a partial download if the server allows it.
        val resumeFrom = if (tmp.isFile) tmp.length() else 0L

        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK &&
                code != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw java.io.IOException("HTTP $code")
            }
            val total = if (code == HttpURLConnection.HTTP_PARTIAL) {
                // Content-Length is the remainder; total = resumed + remainder.
                resumeFrom + conn.contentLengthLong.coerceAtLeast(0)
            } else {
                conn.contentLengthLong
            }
            conn.inputStream.use { input ->
                // Append only when resuming (206); otherwise start fresh.
                tmp.outputStream(resumeFrom > 0 && code == HttpURLConnection.HTTP_PARTIAL).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = resumeFrom
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            _downloadState.value =
                                DownloadState.Downloading((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                        // Cooperative cancellation point.
                        kotlinx.coroutines.ensureActive()
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        if (tmp.length() != EXPECTED_BYTES) {
            tmp.delete()
            throw java.io.IOException(
                "size mismatch: got ${tmp.length()} bytes, expected $EXPECTED_BYTES",
            )
        }
        val dest = File(dir, MODEL_NAME)
        if (!tmp.renameTo(dest)) {
            // renameTo can fail across volumes; copy as fallback.
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
