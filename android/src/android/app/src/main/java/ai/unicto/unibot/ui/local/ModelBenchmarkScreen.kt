package ai.unicto.unibot.ui.local

// [v1.2 Batch F] Model benchmark screen (Routes.MODEL_BENCHMARK).
//
// Picks one of the user's DOWNLOADED on-device models, runs a fixed test
// prompt through it, and reports tokens/sec + time-to-first-token measured
// on THIS phone — with a friendly speed rating. Fully offline: the prompt,
// the model and the timing never leave the device. Models are never bundled;
// the benchmark only offers models the user already downloaded.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.local.LlamaCpp
import ai.unicto.unibot.local.LlamaModelManager
import ai.unicto.unibot.local.LocalLlamaBackend
import ai.unicto.unibot.local.SamplerSettings
import ai.unicto.unibot.ui.components.EmptyState
import ai.unicto.unibot.ui.components.UnibotLoader
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.ChatColors
import ai.unicto.unibot.ui.theme.animationsEnabled
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fixed prompt + sampler so every benchmark run is comparable. */
private const val BENCHMARK_PROMPT = "Explain in three short sentences why the sky is blue."
private val BENCHMARK_SAMPLER = SamplerSettings(
    temperature = 0.6f,
    topP = 0.9f,
    topK = 40,
    repeatPenalty = 1.1f,
    maxTokens = 96,
)

private data class BenchmarkResult(
    val tokensPerSec: Double,
    val ttftMs: Long,
    val generatedTokens: Int,
    val promptTokens: Int,
    val modelTitle: String,
)

private fun ratingRes(tokensPerSec: Double): Int = when {
    tokensPerSec >= 25 -> R.string.v12_rating_blazing
    tokensPerSec >= 12 -> R.string.v12_rating_fast
    tokensPerSec >= 6 -> R.string.v12_rating_steady
    tokensPerSec >= 2 -> R.string.v12_rating_slow
    else -> R.string.v12_rating_veryslow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBenchmarkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val appCtx = remember { ctx.applicationContext }
    val downloadedModels = remember {
        LlamaModelManager.models.filter { LlamaModelManager.isDownloaded(ctx, it) }
    }
    var selectedId by remember { mutableStateOf(downloadedModels.firstOrNull()?.id) }
    var running by remember { mutableStateOf(false) }
    var stageRes by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    val animated = animationsEnabled()
    val accent = MaterialTheme.colorScheme.primary

    fun start() {
        val model = LlamaModelManager.modelById(selectedId) ?: return
        haptics.tap()
        running = true
        result = null
        error = null
        stageRes = R.string.v12_benchmark_loading
        job = scope.launch(Dispatchers.IO) {
            val backend = LocalLlamaBackend(appCtx, model)
            try {
                if (!LlamaCpp.isLoaded) {
                    throw IllegalStateException(
                        "On-device engine failed to load on this phone" +
                            (LlamaCpp.loadError?.message?.let { ": $it" } ?: ""),
                    )
                }
                backend.loadModel()
                withContext(Dispatchers.Main) {
                    stageRes = R.string.v12_benchmark_generating
                }
                val t0 = System.nanoTime()
                var firstTokenAt = 0L
                backend.generate(
                    systemPrompt = null,
                    history = emptyList(),
                    prompt = BENCHMARK_PROMPT,
                    samplerOverride = BENCHMARK_SAMPLER,
                ) {
                    if (firstTokenAt == 0L) firstTokenAt = System.nanoTime()
                }
                val t1 = System.nanoTime()
                val secs = (t1 - t0) / 1_000_000_000.0
                val gen = backend.lastGeneratedTokens
                val res = BenchmarkResult(
                    tokensPerSec = if (secs > 0) gen / secs else 0.0,
                    ttftMs = if (firstTokenAt > 0) (firstTokenAt - t0) / 1_000_000 else 0,
                    generatedTokens = gen,
                    promptTokens = backend.lastPromptTokens,
                    modelTitle = model.title,
                )
                withContext(Dispatchers.Main) {
                    result = res
                    running = false
                    stageRes = null
                    haptics.success()
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    running = false
                    stageRes = null
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Benchmark failed"
                    running = false
                    stageRes = null
                }
            } finally {
                backend.close()
            }
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.v12_benchmark_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Offline privacy badge ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.v12_benchmark_offline),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.v12_benchmark_note),
                    style = TextStyle(fontSize = 12.sp),
                    color = ChatColors.secondaryText,
                    modifier = Modifier.weight(1f),
                )
            }

            if (downloadedModels.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.v12_benchmark_no_models),
                    hint = stringResource(R.string.v12_benchmark_no_models_hint),
                )
            } else {
                // ── Model picker ──
                Text(
                    text = stringResource(R.string.v12_benchmark_pick_model),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    downloadedModels.forEachIndexed { index, model ->
                        FilterChip(
                            selected = model.id == selectedId,
                            onClick = {
                                if (!running) {
                                    selectedId = model.id
                                    result = null
                                    error = null
                                                            }
                            },
                            label = { Text(model.title) },
                            modifier = if (animated) Modifier.staggeredEntrance(index) else Modifier,
                        )
                    }
                }

                // ── Run / progress ──
                if (running) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        UnibotLoader(label = stageRes?.let { stringResource(it) })
                        stageRes?.let {
                            Text(
                                text = stringResource(it),
                                style = TextStyle(fontSize = 13.sp),
                                color = ChatColors.secondaryText,
                            )
                        }
                        OutlinedButton(onClick = { job?.cancel() }) {
                            Text(stringResource(R.string.v12_benchmark_cancel))
                        }
                    }
                } else {
                    Button(
                        onClick = { start() },
                        enabled = selectedId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.v12_benchmark_run))
                    }
                }

                // ── Error ──
                error?.let {
                    Text(
                        text = "${stringResource(R.string.v12_benchmark_failed)}: $it",
                        style = TextStyle(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // ── Result ──
                result?.let { r ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = r.modelTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(ratingRes(r.tokensPerSec)),
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                color = accent,
                                modifier = Modifier
                                    .background(
                                        accent.copy(alpha = 0.12f),
                                        RoundedCornerShape(50),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f", r.tokensPerSec),
                            style = TextStyle(
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.v12_benchmark_tps),
                            style = TextStyle(fontSize = 13.sp),
                            color = ChatColors.secondaryText,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BenchmarkStat(
                                label = stringResource(R.string.v12_benchmark_ttft),
                                value = "${r.ttftMs} ms",
                                modifier = Modifier.weight(1f),
                            )
                            BenchmarkStat(
                                label = stringResource(R.string.v12_benchmark_tokens),
                                value = "${r.generatedTokens}",
                                modifier = Modifier.weight(1f),
                            )
                            BenchmarkStat(
                                label = stringResource(R.string.v12_benchmark_prompt_tokens),
                                value = "${r.promptTokens}",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = stringResource(R.string.v12_benchmark_rating_hint),
                            style = TextStyle(fontSize = 11.sp),
                            color = ChatColors.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(ChatColors.inputIconBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp),
            color = ChatColors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
