package ai.unicto.unibot.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable offline voice model (whisper.cpp English q5_1). */
data class WhisperModel(
    /** Stable id, persisted as the user's selection. */
    val id: String,
    /** Short quality tier name shown in the picker, e.g. "Quick". */
    val title: String,
    /** One-line hint about the tradeoff, e.g. "Fast, good for short commands". */
    val hint: String,
    val fileName: String,
    /** Exact byte size of the published release asset; a mismatch means a bad download. */
    val expectedBytes: Long,
    /** Human label for the picker, e.g. "~31MB". */
    val sizeLabel: String,
)

/**
 * Owns the optional offline voice models (whisper.cpp English quantized).
 *
 * The models are NEVER bundled in the APK. The user picks a size in the voice
 * panel — Quick (~31 MB), Balanced (~57 MB) or Best (~181 MB) — and it
 * fetches the file from the `voice-model-v1` GitHub release, then the
 * [WhisperCppSpeechRecognitionEngine] becomes available and the mic works
 * fully on-device: no API key, no account, no network.
 */
object WhisperModelManager {
    private const val TAG = "WhisperModel"
    private const val PREFS = "whisper_model_prefs"
    private const val KEY_SELECTED = "selected_model_id"

    /** Published by the `Voice model` CI workflow (see .github/workflows/voice-model.yml). */
    private const val RELEASE_BASE =
        "https://github.com/unictoai/unibot/releases/download/voice-model-v1"

    val models: List<WhisperModel> = listOf(
        WhisperModel(
            id = "tiny",
            title = "Quick",
            hint = "Fastest, good for short commands",
            fileName = "ggml-tiny.en-q5_1.bin",
            expectedBytes = 32_166_155L,
            sizeLabel = "~31MB",
        ),
        WhisperModel(
            id = "base",
            title = "Balanced",
            hint = "Better accuracy, still quick",
            fileName = "ggml-base.en-q5_1.bin",
            expectedBytes = 59_721_011L,
            sizeLabel = "~57MB",
        ),
        WhisperModel(
            id = "small",
            title = "Best",
            hint = "Most accurate, slower on old phones",
            fileName = "ggml-small.en-q5_1.bin",
            expectedBytes = 190_098_681L,
            sizeLabel = "~181MB",
        ),
    )

    fun modelUrl(model: WhisperModel): String = "$RELEASE_BASE/${model.fileName}"

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String?) : DownloadState
    }

    /** Per-model download state, keyed by [WhisperModel.id]. */
    private val _downloadStates =
        MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> =
        _downloadStates.asStateFlow()

    fun downloadStateOf(model: WhisperModel): DownloadState =
        _downloadStates.value[model.id] ?: DownloadState.Idle

    private val _selectedModel = MutableStateFlow(models[0])
    val selectedModel: StateFlow<WhisperModel> = _selectedModel.asStateFlow()

    /** Read the persisted selection (call once from the UI). */
    fun loadSelection(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
        models.firstOrNull { it.id == id }?.let { _selectedModel.value = it }
        // Refresh Done states for models already on disk.
        val done = models.filter { isDownloaded(context, it) }
            .associate { it.id to (DownloadState.Done as DownloadState) }
        if (done.isNotEmpty()) _downloadStates.value = _downloadStates.value + done
    }

    fun select(context: Context, model: WhisperModel) {
        _selectedModel.value = model
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, model.id).apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var activeModel: WhisperModel? = null

    fun modelFile(context: Context, model: WhisperModel): File =
        File(context.filesDir, "voice/${model.fileName}")

    /** True when a complete, verified file for [model] is on disk. */
    fun isDownloaded(context: Context, model: WhisperModel): Boolean {
        val f = modelFile(context, model)
        return f.isFile && f.length() == model.expectedBytes
    }

    /**
     * The file the engine actually loads: the selected model when downloaded,
     * otherwise any downloaded model. Null when nothing is on disk.
     */
    fun activeModelFile(context: Context): File? {
        val sel = _selectedModel.value
        if (isDownloaded(context, sel)) return modelFile(context, sel)
        return models.firstOrNull { isDownloaded(context, it) }
            ?.let { modelFile(context, it) }
    }

    /**
     * Start (or restart) the download of [model]. Progress lands on
     * [downloadStates]; on success the file is verified by size and the model
     * becomes the selected one. No-op while a download is already in flight.
     */
    fun download(context: Context, model: WhisperModel) {
        if (downloadJob?.isActive == true) return
        if (isDownloaded(context, model)) {
            setState(model, DownloadState.Done)
            select(context, model)
            return
        }
        setState(model, DownloadState.Downloading(0f))
        activeModel = model
        val appContext = context.applicationContext
        downloadJob = scope.launch {
            try {
                downloadToFile(appContext, model)
                setState(model, DownloadState.Done)
                select(appContext, model)
                Log.i(TAG, "voice model ${model.id} downloaded and verified")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice model ${model.id} download failed: ${e.message}")
                runCatching { tmpFile(appContext, model).delete() }
                setState(model, DownloadState.Failed(e.message))
            } finally {
                activeModel = null
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        activeModel?.let { setState(it, DownloadState.Idle) }
        activeModel = null
    }

    /** Delete one model's file (frees its space) and reset its state. */
    fun delete(context: Context, model: WhisperModel) {
        if (activeModel == model) cancel()
        runCatching { modelFile(context, model).delete() }
        setState(model, DownloadState.Idle)
    }

    private fun setState(model: WhisperModel, state: DownloadState) {
        _downloadStates.value = _downloadStates.value + (model.id to state)
    }

    private fun tmpFile(context: Context, model: WhisperModel): File =
        File(context.filesDir, "voice/${model.fileName}.part")

    private suspend fun downloadToFile(context: Context, model: WhisperModel) =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "voice").also { it.mkdirs() }
            val tmp = tmpFile(context, model)
            // Resume a partial download if the server allows it.
            val resumeFrom = if (tmp.isFile) tmp.length() else 0L

            val conn = (URL(modelUrl(model)).openConnection() as HttpURLConnection).apply {
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
                    val append = resumeFrom > 0 && code == HttpURLConnection.HTTP_PARTIAL
                    java.io.FileOutputStream(tmp, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var written = resumeFrom
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                setState(
                                    model,
                                    DownloadState.Downloading(
                                        (written.toFloat() / total).coerceIn(0f, 1f),
                                    ),
                                )
                            }
                            // Cooperative cancellation point.
                            ensureActive()
                        }
                        out.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tmp.length() != model.expectedBytes) {
                tmp.delete()
                throw java.io.IOException(
                    "size mismatch: got ${tmp.length()} bytes, expected ${model.expectedBytes}",
                )
            }
            val dest = File(dir, model.fileName)
            if (!tmp.renameTo(dest)) {
                // renameTo can fail across volumes; copy as fallback.
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
}
