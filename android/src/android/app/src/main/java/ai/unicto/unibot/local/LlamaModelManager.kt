package ai.unicto.unibot.local

import android.app.ActivityManager
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

/** Chat template applied to the prompt in [LocalLlamaBackend]. */
enum class LlamaChatTemplate {
    /** `<|im_start|>role\n…<|im_end|>` (Qwen3). */
    QWEN3_CHATML,

    /**
     * ChatML-like with a `<|startoftext|>` prefix
     * (LiquidAI LFM2 family — verified against the LFM2 model card).
     */
    LFM2_CHATML,
}

/** One downloadable on-device chat model (llama.cpp GGUF, Q4_K_M). */
data class LlamaModel(
    /** Stable id, persisted as the user's selection. */
    val id: String,
    /** Short name shown in the picker, e.g. "Qwen3 1.7B". */
    val title: String,
    /** One-line hint about the tradeoff, e.g. "Strongest quality · needs 4 GB+ free RAM". */
    val hint: String,
    val fileName: String,
    /** Human label for the picker, e.g. "~1.1 GB". */
    val sizeLabel: String,
    /**
     * Conservative floor for the download-integrity check. Exact byte sizes
     * can't be pinned until the `llm-model-v1` release is published by CI
     * (see .github/workflows/llm-model.yml) — HF is unreachable from this
     * environment, so these floors plus the HTTP Content-Length check below
     * are the v1 integrity story. A follow-up commit should pin exact sizes
     * like WhisperModelManager does once the release exists.
     */
    val minBytes: Long,
    val template: LlamaChatTemplate,
    /** Default sampling temperature (per model-card guidance). */
    val defaultTemperature: Float,
    val systemPrompt: String,
)

/**
 * Owns the optional on-device chat models (llama.cpp GGUF).
 *
 * Mirrors [ai.unicto.unibot.speech.WhisperModelManager]: the models are NEVER
 * bundled in the APK. The user opts into a download in the model picker's
 * "On-device" section — with explicit size warnings (~1 GB each) and a
 * low-RAM warning on entry-level phones — and it fetches the GGUF from the
 * `llm-model-v1` GitHub release (published by the `LLM model` CI workflow).
 * Once downloaded, picking the model routes chat to [LocalChatService]:
 * no API key, no account, no network — airplane-mode functional.
 */
object LlamaModelManager {
    private const val TAG = "LlamaModel"
    private const val PREFS = "llama_model_prefs"
    private const val KEY_SELECTED = "selected_model_id"

    /**
     * Null = cloud models (default). Set to a [LlamaModel.id] when the user
     * picks an on-device model in the model picker — that is the route
     * selector: [ChatViewModel.sendMessage] checks this before the cloud
     * agent loop.
     */
    private const val KEY_LOCAL_MODE = "local_mode_model_id"

    /** Published by the `LLM model` CI workflow (see .github/workflows/llm-model.yml). */
    private const val RELEASE_BASE =
        "https://github.com/unictoai/unibot/releases/download/llm-model-v1"

    private const val DEFAULT_SYSTEM_PROMPT =
        "You are unibot, a helpful AI assistant running fully offline on the user's Android phone. " +
            "Be concise and direct. If you don't know something, say so — never invent facts."

    val models: List<LlamaModel> = listOf(
        LlamaModel(
            id = "qwen3-1.7b",
            title = "Qwen3 1.7B",
            hint = "Strongest quality · needs 4 GB+ free RAM",
            fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            sizeLabel = "~1.3 GB",
            minBytes = 1_000_000_000L,
            template = LlamaChatTemplate.QWEN3_CHATML,
            defaultTemperature = 0.7f,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        LlamaModel(
            id = "lfm2-1.2b",
            title = "LFM2 1.2B",
            hint = "Faster on low-end phones · still capable",
            fileName = "LFM2-1.2B-Q4_K_M.gguf",
            sizeLabel = "~800 MB",
            minBytes = 650_000_000L,
            template = LlamaChatTemplate.LFM2_CHATML,
            defaultTemperature = 0.3f,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
    )

    fun modelUrl(model: LlamaModel): String = "$RELEASE_BASE/${model.fileName}"

    fun modelById(id: String?): LlamaModel? = models.firstOrNull { it.id == id }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String?) : DownloadState
    }

    /** Per-model download state, keyed by [LlamaModel.id]. */
    private val _downloadStates =
        MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> =
        _downloadStates.asStateFlow()

    fun downloadStateOf(model: LlamaModel): DownloadState =
        _downloadStates.value[model.id] ?: DownloadState.Idle

    private val _selectedModel = MutableStateFlow(models[0])
    val selectedModel: StateFlow<LlamaModel> = _selectedModel.asStateFlow()

    /** Read the persisted selection (call once from the UI). */
    fun loadSelection(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_SELECTED, null)
        models.firstOrNull { it.id == id }?.let { _selectedModel.value = it }
        // Refresh Done states for models already on disk.
        val done = models.filter { isDownloaded(context, it) }
            .associate { it.id to (DownloadState.Done as DownloadState) }
        if (done.isNotEmpty()) _downloadStates.value = _downloadStates.value + done
    }

    fun select(context: Context, model: LlamaModel) {
        _selectedModel.value = model
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, model.id).apply()
    }

    /**
     * The route selector. When non-null, chat turns run on-device via
     * [LocalChatService] instead of the cloud provider loop.
     */
    fun localModeModelId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL_MODE, null)

    fun isLocalModeActive(context: Context): Boolean =
        localModeModelId(context)?.let { modelById(it) } != null

    /** The downloaded model the local route will actually use, or null. */
    fun activeModel(context: Context): LlamaModel? {
        val id = localModeModelId(context) ?: return null
        val model = modelById(id) ?: return null
        return if (isDownloaded(context, model)) model else null
    }

    /**
     * Enter on-device mode with [model] (must already be downloaded), or pass
     * null to go back to cloud models. Switching back releases the native
     * model from RAM.
     */
    fun setLocalMode(context: Context, model: LlamaModel?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOCAL_MODE, model?.id).apply()
        if (model != null) select(context, model)
        else LocalChatService.release()
        Log.i(TAG, "local mode -> ${model?.id ?: "cloud"}")
    }

    /** True on entry-level phones where a 1 GB model + KV cache will struggle. */
    fun isLowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) return true
        // memoryClass is per-app heap in MB; <= 256 MB heap ~ entry-level device.
        return (am?.memoryClass ?: 512) <= 256
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var activeModel: LlamaModel? = null

    fun modelFile(context: Context, model: LlamaModel): File =
        File(context.filesDir, "llm/${model.fileName}")

    /** True when a complete file for [model] is on disk (floor-checked). */
    fun isDownloaded(context: Context, model: LlamaModel): Boolean {
        val f = modelFile(context, model)
        return f.isFile && f.length() >= model.minBytes
    }

    /**
     * Start (or restart) the download of [model]. Progress lands on
     * [downloadStates]; on success the file is integrity-checked and the
     * model becomes the selected one. No-op while a download is in flight.
     *
     * This is the ONLY network touch in the on-device feature, and it only
     * runs when the user taps Download — the generate path never touches
     * the network (see [LlmBackend]'s privacy contract).
     */
    fun download(context: Context, model: LlamaModel) {
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
                Log.i(TAG, "llm model ${model.id} downloaded and verified")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "llm model ${model.id} download failed: ${e.message}")
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
    fun delete(context: Context, model: LlamaModel) {
        if (activeModel == model) cancel()
        if (localModeModelId(context) == model.id) setLocalMode(context, null)
        runCatching { modelFile(context, model).delete() }
        setState(model, DownloadState.Idle)
    }

    private fun setState(model: LlamaModel, state: DownloadState) {
        _downloadStates.value = _downloadStates.value + (model.id to state)
    }

    private fun tmpFile(context: Context, model: LlamaModel): File =
        File(context.filesDir, "llm/${model.fileName}.part")

    private suspend fun downloadToFile(context: Context, model: LlamaModel) =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "llm").also { it.mkdirs() }
            val tmp = tmpFile(context, model)
            // Resume a partial download if the server allows it.
            val resumeFrom = if (tmp.isFile) tmp.length() else 0L

            val conn = (URL(modelUrl(model)).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
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

                // Integrity: the server-declared total is authoritative when
                // present; otherwise fall back to the per-model size floor.
                if (total > 0 && tmp.length() != total) {
                    tmp.delete()
                    throw java.io.IOException(
                        "size mismatch: got ${tmp.length()} bytes, expected $total",
                    )
                }
            } finally {
                conn.disconnect()
            }

            if (tmp.length() < model.minBytes) {
                tmp.delete()
                throw java.io.IOException(
                    "download too small (${tmp.length()} bytes) — likely a truncated file",
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
