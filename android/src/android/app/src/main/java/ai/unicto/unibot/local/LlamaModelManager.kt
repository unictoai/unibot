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
    /**
     * [Wave 8] Estimated peak RAM in MB (weights + KV cache + runtime).
     * Shown in the model manager so users can judge fit for their phone.
     */
    val ramEstimateMb: Int = 2048,
    /**
     * [Wave 8] Relative capability score 1-10 for smart routing: higher =
     * better quality, slower. The Auto router picks by prompt complexity.
     */
    val capabilityScore: Int = 5,
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
            ramEstimateMb = 3800,
            capabilityScore = 9,
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
            ramEstimateMb = 2400,
            capabilityScore = 6,
        ),
        LlamaModel(
            id = "lfm2.5-230m",
            title = "LFM2.5 230M",
            hint = "Smallest · fastest on low-end phones, simplest answers",
            fileName = "LFM2.5-230M-QAD-Q4_0.gguf",
            sizeLabel = "~142 MB",
            minBytes = 120_000_000L,
            template = LlamaChatTemplate.LFM2_CHATML,
            defaultTemperature = 0.3f,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            ramEstimateMb = 700,
            capabilityScore = 3,
        ),
    )

    fun modelUrl(model: LlamaModel): String = "$RELEASE_BASE/${model.fileName}"

    fun modelById(id: String?): LlamaModel? = models.firstOrNull { it.id == id }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        /** [Wave 8] Paused by the user — the .part file is kept for resume. */
        data class Paused(val fraction: Float) : DownloadState
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

    /**
     * [v1.2 Batch F] RAM-based model recommendation for the "Recommended for
     * your phone" card. Picks the strongest model whose RAM estimate fits
     * comfortably inside this phone's total RAM, and returns a human RAM
     * label (e.g. "5.8 GB") for the card's copy.
     */
    data class RamRecommendation(
        val model: LlamaModel,
        val ramLabel: String,
    )

    fun ramRecommendation(context: Context): RamRecommendation {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val totalGb = info.totalMem / 1024.0 / 1024.0 / 1024.0
        val model = when {
            totalGb >= 5.5 -> modelById("qwen3-1.7b")
            totalGb >= 3.0 -> modelById("lfm2-1.2b")
            else -> modelById("lfm2.5-230m")
        } ?: models[0]
        val label = "%.1f GB".format(java.util.Locale.US, totalGb)
        return RamRecommendation(model, label)
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
                pumpQueue()
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        activeModel?.let { setState(it, DownloadState.Idle) }
        activeModel = null
        pumpQueue()
    }

    /**
     * [Wave 8] Pause the in-flight download, keeping the .part file so
     * [download] resumes where it left off. Distinct from [cancel], which
     * discards progress.
     */
    fun pause() {
        val model = activeModel ?: return
        val fraction = (downloadStateOf(model) as? DownloadState.Downloading)?.fraction ?: 0f
        downloadJob?.cancel()
        downloadJob = null
        activeModel = null
        setState(model, DownloadState.Paused(fraction))
        Log.i(TAG, "llm model ${model.id} paused at ${(fraction * 100).toInt()}%")
    }

    /** [Wave 8] Queued model ids waiting for the active download to finish. */
    private val _downloadQueue = MutableStateFlow<List<String>>(emptyList())
    val downloadQueue: StateFlow<List<String>> = _downloadQueue.asStateFlow()

    /**
     * [Wave 8] Add [model] to the download queue, or start it immediately
     * when nothing is downloading. Tapping download on a second model no
     * longer no-ops — it queues.
     */
    fun enqueue(context: Context, model: LlamaModel) {
        if (isDownloaded(context, model)) {
            setState(model, DownloadState.Done)
            return
        }
        val id = model.id
        if (_downloadQueue.value.contains(id)) return
        if (downloadJob?.isActive == true || downloadStateOf(model) is DownloadState.Downloading) {
            _downloadQueue.value = _downloadQueue.value + id
            Log.i(TAG, "llm model $id queued (position ${_downloadQueue.value.size})")
        } else {
            download(context, model)
        }
    }

    /** Start the next queued download, if any. Called after finish/cancel. */
    private fun pumpQueue() {
        val nextId = _downloadQueue.value.firstOrNull() ?: return
        _downloadQueue.value = _downloadQueue.value.drop(1)
        // The context isn't available here; the UI layer re-issues download()
        // via queueHeadHint. Keep the id visible for the UI to pick up.
        _queueHeadHint.value = nextId
    }

    /**
     * [Wave 8] Id of the model the queue wants started next, or null.
     * The UI observes this and calls [download] with a Context.
     */
    private val _queueHeadHint = MutableStateFlow<String?>(null)
    val queueHeadHint: StateFlow<String?> = _queueHeadHint.asStateFlow()

    fun consumeQueueHeadHint(): String? {
        val h = _queueHeadHint.value
        _queueHeadHint.value = null
        return h
    }

    /** [Wave 8] Remove [model] from the queue without starting it. */
    fun dequeue(model: LlamaModel) {
        _downloadQueue.value = _downloadQueue.value - model.id
    }

    // ─── Offline-first + smart routing ────────────────────────────────────
    private const val KEY_OFFLINE_FIRST = "offline_first"

    /**
     * [Wave 8] Offline-first: when ON and at least one model is downloaded,
     * new chats automatically route to the best downloaded on-device model
     * instead of the cloud. The composer shows an "on-device" badge.
     * Pure on-device preference — never uploaded.
     */
    fun isOfflineFirst(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_FIRST, false)

    fun setOfflineFirst(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OFFLINE_FIRST, on).apply()
        Log.i(TAG, "offline-first -> $on")
    }

    /**
     * [Wave 8] "Auto" smart routing for the model picker. Picks the best
     * DOWNLOADED model for [prompt] by a cheap heuristic — no model call:
     * short + simple → smallest downloaded (fastest); long or complex
     * (code, multi-part, "explain thoroughly") → largest downloaded.
     * Returns null when nothing is downloaded (caller falls back to cloud).
     */
    fun autoRoute(context: Context, prompt: String): LlamaModel? {
        val downloaded = models.filter { isDownloaded(context, it) }
        if (downloaded.isEmpty()) return null
        val text = prompt.lowercase()
        val complexHints = listOf(
            "code", "function", "debug", "explain", "thorough",
            "step by step", "compare", "analyze", "essay", "report",
        )
        val complex = prompt.length > 600 || complexHints.any { it in text }
        return if (complex) downloaded.maxByOrNull { it.capabilityScore }
        else downloaded.minByOrNull { it.ramEstimateMb }
    }

    /**
     * [Wave 8] Resolve the effective local model for a new turn: explicit
     * local-mode selection wins; otherwise, when offline-first is on, the
     * Auto router picks. Null = stay on cloud.
     */
    fun effectiveLocalModel(context: Context, prompt: String): LlamaModel? {
        activeModel(context)?.let { return it }
        if (!isOfflineFirst(context)) return null
        return autoRoute(context, prompt)
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
