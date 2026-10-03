package ai.unicto.unibot.speech

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream
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

/** One downloadable on-device TTS voice (sherpa-onnx VITS / Piper). */
data class TtsVoice(
    /** Stable id, persisted as the user's selection. */
    val id: String,
    /** Display name, e.g. "Amy". */
    val name: String,
    /** Locale/gender line, e.g. "US female". */
    val detail: String,
    /** One-line hint about the tradeoff, e.g. "Balanced quality, ~60 MB". */
    val hint: String,
    /** Release asset name of the VITS model. */
    val onnxAsset: String,
    /** Exact byte size of the published .onnx asset; a mismatch means a bad download. */
    val onnxBytes: Long,
    /** Release asset name of the phoneme token list. */
    val tokensAsset: String,
    /** Exact byte size of the published .tokens.txt asset. */
    val tokensBytes: Long,
    /** Human label for the picker, e.g. "~60MB". */
    val sizeLabel: String,
)

/**
 * Owns the optional on-device TTS voices (sherpa-onnx VITS / Piper).
 *
 * The voices are NEVER bundled in the APK. The user picks one in Read aloud
 * settings — Amy, Ryan, Lessac, Joe or Alan — and it fetches the model +
 * token list from the
 * `tts-voice-v1` GitHub release, plus the shared `espeak-ng-data.tar.gz`
 * phoneme data (downloaded once, extracted into each voice's directory).
 * [SherpaTtsEngine] then speaks fully on-device: no API key, no account, no
 * network — which matters because Abdullah's Infinix ships with NO system
 * speech service.
 *
 * On disk, each voice is self-contained under `tts-voices/<id>/`:
 * `model.onnx`, `tokens.txt`, and the extracted `espeak-ng-data/` directory —
 * exactly the layout [SherpaTtsEngine.initEngine] expects.
 */
object TtsVoiceModelManager {
    private const val TAG = "TtsVoice"
    private const val PREFS = "tts_voice_prefs"
    private const val KEY_SELECTED = "selected_voice_id"

    /** Published by the `TTS voice` CI workflow (see .github/workflows/tts-voice.yml). */
    private const val RELEASE_BASE =
        "https://github.com/unictoai/unibot/releases/download/tts-voice-v1"

    // Exact published sizes, verified by the tts-voice.yml workflow before
    // every publish (it fails the run when the built files drift from these).
    private const val AMY_ONNX_BYTES = 63_104_526L
    private const val AMY_TOKENS_BYTES = 763L
    private const val RYAN_ONNX_BYTES = 63_201_294L
    private const val RYAN_TOKENS_BYTES = 921L
    // v1.2: three more medium-quality Piper voices, published by the same
    // tts-voice.yml workflow. All medium voices share the VITS architecture,
    // so their .onnx files are byte-identical in size (63,201,294). Tokens
    // sizes were measured by running the workflow's exact generator script
    // against the published .onnx.json files.
    private const val LESSAC_ONNX_BYTES = 63_201_294L
    private const val LESSAC_TOKENS_BYTES = 921L
    private const val JOE_ONNX_BYTES = 63_201_294L
    private const val JOE_TOKENS_BYTES = 899L
    private const val ALAN_ONNX_BYTES = 63_201_294L
    private const val ALAN_TOKENS_BYTES = 921L
    private const val ESPEAK_TARBALL_BYTES = 8_990_538L
    private const val ESPEAK_ASSET = "espeak-ng-data.tar.gz"

    val voices: List<TtsVoice> = listOf(
        TtsVoice(
            id = "amy",
            name = "Amy",
            detail = "US female",
            hint = "Balanced quality, ~60 MB",
            onnxAsset = "en_US-amy-low.onnx",
            onnxBytes = AMY_ONNX_BYTES,
            tokensAsset = "en_US-amy-low.tokens.txt",
            tokensBytes = AMY_TOKENS_BYTES,
            sizeLabel = "~60MB",
        ),
        TtsVoice(
            id = "ryan",
            name = "Ryan",
            detail = "US male",
            hint = "Best quality, ~60 MB",
            onnxAsset = "en_US-ryan-medium.onnx",
            onnxBytes = RYAN_ONNX_BYTES,
            tokensAsset = "en_US-ryan-medium.tokens.txt",
            tokensBytes = RYAN_TOKENS_BYTES,
            sizeLabel = "~60MB",
        ),
        TtsVoice(
            id = "lessac",
            name = "Lessac",
            detail = "US female",
            hint = "Clear narration voice, ~60 MB",
            onnxAsset = "en_US-lessac-medium.onnx",
            onnxBytes = LESSAC_ONNX_BYTES,
            tokensAsset = "en_US-lessac-medium.tokens.txt",
            tokensBytes = LESSAC_TOKENS_BYTES,
            sizeLabel = "~60MB",
        ),
        TtsVoice(
            id = "joe",
            name = "Joe",
            detail = "US male",
            hint = "Warm conversational voice, ~60 MB",
            onnxAsset = "en_US-joe-medium.onnx",
            onnxBytes = JOE_ONNX_BYTES,
            tokensAsset = "en_US-joe-medium.tokens.txt",
            tokensBytes = JOE_TOKENS_BYTES,
            sizeLabel = "~60MB",
        ),
        TtsVoice(
            id = "alan",
            name = "Alan",
            detail = "British male",
            hint = "Refined British voice, ~60 MB",
            onnxAsset = "en_GB-alan-medium.onnx",
            onnxBytes = ALAN_ONNX_BYTES,
            tokensAsset = "en_GB-alan-medium.tokens.txt",
            tokensBytes = ALAN_TOKENS_BYTES,
            sizeLabel = "~60MB",
        ),
    )

    fun voiceUrl(asset: String): String = "$RELEASE_BASE/$asset"

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String?) : DownloadState
    }

    /** Per-voice download state, keyed by [TtsVoice.id]. */
    private val _downloadStates =
        MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> =
        _downloadStates.asStateFlow()

    fun downloadStateOf(voice: TtsVoice): DownloadState =
        _downloadStates.value[voice.id] ?: DownloadState.Idle

    private val _selectedVoice = MutableStateFlow(voices[0])
    val selectedVoice: StateFlow<TtsVoice> = _selectedVoice.asStateFlow()

    /** Read the persisted selection (call once from the UI). */
    fun loadSelection(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
        voices.firstOrNull { it.id == id }?.let { _selectedVoice.value = it }
        // Refresh Done states for voices already on disk.
        val done = voices.filter { isDownloaded(context, it) }
            .associate { it.id to (DownloadState.Done as DownloadState) }
        if (done.isNotEmpty()) _downloadStates.value = _downloadStates.value + done
    }

    fun select(context: Context, voice: TtsVoice) {
        _selectedVoice.value = voice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, voice.id).apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var activeVoice: TtsVoice? = null

    /** Self-contained voice directory: model.onnx + tokens.txt + espeak-ng-data/. */
    fun voiceDir(context: Context, voice: TtsVoice): File =
        File(context.filesDir, "tts-voices/${voice.id}")

    private fun modelFile(context: Context, voice: TtsVoice): File =
        File(voiceDir(context, voice), "model.onnx")

    private fun tokensFile(context: Context, voice: TtsVoice): File =
        File(voiceDir(context, voice), "tokens.txt")

    private fun espeakDir(context: Context, voice: TtsVoice): File =
        File(voiceDir(context, voice), "espeak-ng-data")

    /** Shared tarball, downloaded once and cached for every voice. */
    private fun espeakTarball(context: Context): File =
        File(context.filesDir, "tts-voices/$ESPEAK_ASSET")

    /** True when the extracted phoneme data looks complete. */
    private fun isEspeakExtracted(context: Context, voice: TtsVoice): Boolean {
        val dir = espeakDir(context, voice)
        return dir.isDirectory && (dir.list()?.size ?: 0) > 100
    }

    /** True when a complete, verified voice is on disk. */
    fun isDownloaded(context: Context, voice: TtsVoice): Boolean {
        val model = modelFile(context, voice)
        val tokens = tokensFile(context, voice)
        return model.isFile && model.length() == voice.onnxBytes &&
            tokens.isFile && tokens.length() == voice.tokensBytes &&
            isEspeakExtracted(context, voice)
    }

    /**
     * The directory the engine actually loads: the selected voice when
     * complete, otherwise any complete voice. Null when nothing is on disk.
     */
    fun activeVoiceDir(context: Context): File? {
        val sel = _selectedVoice.value
        if (isDownloaded(context, sel)) return voiceDir(context, sel)
        return voices.firstOrNull { isDownloaded(context, it) }
            ?.let { voiceDir(context, it) }
    }

    /**
     * Load [SherpaTtsEngine] from the active voice directory. False when no
     * voice is downloaded or the native library failed to load.
     */
    fun ensureEngineReady(context: Context, speed: Float): Boolean {
        val dir = activeVoiceDir(context) ?: return false
        return SherpaTtsEngine.initEngine(context, dir, speed)
    }

    /**
     * Start (or restart) the download of [voice]: model + tokens + the shared
     * espeak tarball (once), then extraction. Progress lands on
     * [downloadStates]; on success the files are verified by size and the
     * voice becomes the selected one. No-op while a download is in flight.
     */
    fun download(context: Context, voice: TtsVoice) {
        if (downloadJob?.isActive == true) return
        if (isDownloaded(context, voice)) {
            setState(voice, DownloadState.Done)
            select(context, voice)
            return
        }
        setState(voice, DownloadState.Downloading(0f))
        activeVoice = voice
        val appContext = context.applicationContext
        downloadJob = scope.launch {
            try {
                downloadVoice(appContext, voice)
                setState(voice, DownloadState.Done)
                select(appContext, voice)
                Log.i(TAG, "TTS voice ${voice.id} downloaded and verified")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TTS voice ${voice.id} download failed: ${e.message}")
                setState(voice, DownloadState.Failed(e.message))
            } finally {
                activeVoice = null
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        activeVoice?.let { setState(it, DownloadState.Idle) }
        activeVoice = null
    }

    /**
     * Delete one voice's files (frees its space) and reset its state. The
     * shared espeak tarball stays cached so re-downloading the voice later is
     * fast. Releases the engine when the deleted voice was the selected one.
     */
    fun delete(context: Context, voice: TtsVoice) {
        if (activeVoice == voice) cancel()
        runCatching { voiceDir(context, voice).deleteRecursively() }
        setState(voice, DownloadState.Idle)
        if (_selectedVoice.value.id == voice.id) {
            SherpaTtsEngine.release()
        }
    }

    private fun setState(voice: TtsVoice, state: DownloadState) {
        _downloadStates.value = _downloadStates.value + (voice.id to state)
    }

    private suspend fun downloadVoice(context: Context, voice: TtsVoice) =
        withContext(Dispatchers.IO) {
            val dir = voiceDir(context, voice).also { it.mkdirs() }
            val needOnnx = modelFile(context, voice).let { !it.isFile || it.length() != voice.onnxBytes }
            val needTokens = tokensFile(context, voice).let { !it.isFile || it.length() != voice.tokensBytes }
            val needEspeakDl = espeakTarball(context).let { !it.isFile || it.length() != ESPEAK_TARBALL_BYTES }
            val needExtract = !isEspeakExtracted(context, voice)

            val total = (if (needOnnx) voice.onnxBytes else 0L) +
                (if (needTokens) voice.tokensBytes else 0L) +
                (if (needEspeakDl) ESPEAK_TARBALL_BYTES else 0L)
            var base = 0L
            fun report(written: Long) {
                if (total > 0) {
                    setState(
                        voice,
                        DownloadState.Downloading(
                            ((base + written).toFloat() / total).coerceIn(0f, 1f),
                        ),
                    )
                }
            }

            if (needOnnx) {
                fetchUrlToFile(
                    voiceUrl(voice.onnxAsset),
                    File(dir, "model.onnx.part"),
                    modelFile(context, voice),
                    voice.onnxBytes,
                    ::report,
                )
                base += voice.onnxBytes
            }
            if (needTokens) {
                fetchUrlToFile(
                    voiceUrl(voice.tokensAsset),
                    File(dir, "tokens.txt.part"),
                    tokensFile(context, voice),
                    voice.tokensBytes,
                    ::report,
                )
                base += voice.tokensBytes
            }
            if (needEspeakDl) {
                val tarball = espeakTarball(context)
                fetchUrlToFile(
                    voiceUrl(ESPEAK_ASSET),
                    File(tarball.parentFile, "$ESPEAK_ASSET.part"),
                    tarball,
                    ESPEAK_TARBALL_BYTES,
                    ::report,
                )
                base += ESPEAK_TARBALL_BYTES
            }
            if (needExtract) {
                setState(voice, DownloadState.Downloading(1f))
                extractTarGz(espeakTarball(context), dir)
                if (!isEspeakExtracted(context, voice)) {
                    runCatching { espeakDir(context, voice).deleteRecursively() }
                    throw java.io.IOException("espeak-ng-data extraction failed verification")
                }
            }
        }

    /**
     * Resume-capable download with exact size verification, mirroring
     * [WhisperModelManager]: partial `.part` files resume when the server
     * allows it, a size mismatch deletes the temp file and fails loudly.
     */
    private suspend fun fetchUrlToFile(
        url: String,
        tmp: File,
        dest: File,
        expectedBytes: Long,
        onBytes: (written: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        tmp.parentFile?.mkdirs()
        val resumeFrom = if (tmp.isFile) tmp.length() else 0L

        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code != java.net.HttpURLConnection.HTTP_OK &&
                code != java.net.HttpURLConnection.HTTP_PARTIAL
            ) {
                throw java.io.IOException("HTTP $code")
            }
            conn.inputStream.use { input ->
                val append = resumeFrom > 0 && code == java.net.HttpURLConnection.HTTP_PARTIAL
                java.io.FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = resumeFrom
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onBytes(written)
                        // Cooperative cancellation point.
                        ensureActive()
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        if (tmp.length() != expectedBytes) {
            tmp.delete()
            throw java.io.IOException(
                "size mismatch: got ${tmp.length()} bytes, expected $expectedBytes",
            )
        }
        if (!tmp.renameTo(dest)) {
            // renameTo can fail across volumes; copy as fallback.
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Minimal tar extractor for the espeak-ng-data.tar.gz (GNU-format tar
     * inside gzip). Dependency-free on purpose: java.util.zip handles the
     * gzip layer, and tar headers are just 512-byte records. Path traversal
     * entries are rejected.
     */
    private fun extractTarGz(tarGz: File, destDir: File) {
        GZIPInputStream(tarGz.inputStream().buffered()).use { gz ->
            val header = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = gz.read(header, read, 512 - read)
                    if (n < 0) {
                        if (read == 0) return // clean end of archive
                        throw java.io.IOException("truncated tar header")
                    }
                    read += n
                }
                if (header.all { it == 0.toByte() }) return // end-of-archive marker
                val name = header.copyOfRange(0, 100).toString(Charsets.US_ASCII).trimEnd('\u0000')
                val size = header.copyOfRange(124, 136).toString(Charsets.US_ASCII)
                    .trimEnd('\u0000').trim().toLongOrNull(8) ?: 0L
                val type = header[156].toInt().toChar()
                val target = File(destDir, name)
                // Reject path traversal: the entry must stay inside destDir.
                if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw java.io.IOException("tar entry escapes destination: $name")
                }
                when (type) {
                    '5' -> target.mkdirs()
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            var remaining = size
                            val buf = ByteArray(8192)
                            while (remaining > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) throw java.io.IOException("truncated tar entry: $name")
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                    }
                    // Other types (links, devices) are not in this archive; skip.
                }
                // Skip padding to the next 512-byte boundary.
                val skip = (512 - size % 512) % 512
                var skipped = 0L
                while (skipped < skip) {
                    val n = gz.skip(skip - skipped)
                    if (n <= 0) break
                    skipped += n
                }
            }
        }
    }
}
