package ai.unicto.unibot.ui.local

// [P7-on-device-llm] "On-device" section for the chat model picker: route
// selector + opt-in model downloader in one place.
//
// This is the whole local-chat UX surface: downloaded models are selectable
// rows (tapping one flips LlamaModelManager into local mode, which
// ChatViewModel.sendMessage checks before the cloud agent loop); missing
// models show download rows with explicit size warnings. Everything is
// self-contained — the host sheet only needs to drop this composable into
// its list and pass onDone for dismissal.
//
// NOTE: user-visible strings are hardcoded English here (the voice-model
// panel this mirrors uses R.string). They should move to strings.xml when
// localization starts; hardcoded was chosen so a resource typo can't break
// the build with no local compiler to catch it.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.local.LlamaModel
import ai.unicto.unibot.local.LlamaModelManager
import ai.unicto.unibot.local.LocalCapabilities
import ai.unicto.unibot.local.LocalChatService
import ai.unicto.unibot.ui.components.EmptyState // [Wave 9b] no-downloads empty state
import ai.unicto.unibot.ui.theme.ChatColors
import ai.unicto.unibot.ui.theme.staggeredEntrance // [Wave 9b] model-list choreography
import ai.unicto.unibot.ui.theme.successPop // [Wave 9b] download-complete celebration

/**
 * The "On-device" picker section. [onDone] is called after a route change
 * so the host sheet can dismiss itself.
 */
@Composable
fun OnDeviceModelsSection(onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val states by LlamaModelManager.downloadStates.collectAsState()
    val selected by LlamaModelManager.selectedModel.collectAsState()
    var localId by remember { mutableStateOf(LlamaModelManager.localModeModelId(ctx)) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    // [Wave 9b] "Browse models" expands the downloadable list when nothing
    // is downloaded yet (see the empty state at the model rows below).
    var browseExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LlamaModelManager.loadSelection(ctx)
        localId = LlamaModelManager.localModeModelId(ctx)
    }

    // [v0.4.0-premium-feel] Haptics for model downloads: a tick when a
    // download starts, a warm confirmation when one finishes.
    val haptics = ai.unicto.unibot.ui.util.rememberHaptic()
    var seenDoneIds by remember { mutableStateOf(setOf<String>()) }
    var hapticsArmed by remember { mutableStateOf(false) }
    // [Wave 9b] Download-complete celebration: ids that flip to Done while
    // the section is open also pop their row (successPop below). Mirrors the
    // hapticsArmed guard — models already downloaded before opening never
    // celebrate.
    var justDoneIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(states) {
        val nowDone = states
            .filterValues { it is LlamaModelManager.DownloadState.Done }
            .keys
        // Only buzz for completions that happen while this screen is open —
        // never for models that were already downloaded before it opened.
        val fresh = nowDone - seenDoneIds
        if (hapticsArmed && fresh.isNotEmpty()) {
            haptics.success()
            justDoneIds = fresh
        }
        seenDoneIds = nowDone
        hapticsArmed = true
    }

    val lowRam = remember { LlamaModelManager.isLowRamDevice(ctx) }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Header ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "On-device",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OfflineBadge()
        }
        Text(
            text = "Private chat that works in airplane mode — no API key, no account, nothing leaves your phone except optional web searches below.",
            style = TextStyle(fontSize = 12.sp),
            color = ChatColors.secondaryText,
        )

        // [v12-F] Batch F: RAM-based model recommendation card ("Your phone:
        // X GB RAM → recommended: <model>") plus the last on-device turn's
        // context usage (tokens used of the 2048-token window).
        RecommendedModelCard(
            onDownload = { model ->
                haptics.tap()
                LlamaModelManager.enqueue(ctx, model)
            },
            onUse = { model ->
                haptics.tap()
                LlamaModelManager.setLocalMode(ctx, model)
                localId = model.id
                onDone()
            },
        )
        LastTurnContextRow()

        // ── Web search toggle ──
        var webSearch by remember { mutableStateOf(LocalCapabilities.isWebSearchEnabled(ctx)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Web search",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "When a question needs live info, look it up online and feed the results to the on-device model. Sends the question text to DuckDuckGo.",
                    style = TextStyle(fontSize = 12.sp),
                    color = ChatColors.secondaryText,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = webSearch,
                onCheckedChange = {
                    webSearch = it
                    LocalCapabilities.setWebSearchEnabled(ctx, it)
                },
            )
        }

        // ── Offline-first toggle ──
        // [Wave 8] When ON and a model is downloaded, new chats route to
        // the best on-device model automatically (Auto smart routing).
        var offlineFirst by remember { mutableStateOf(LlamaModelManager.isOfflineFirst(ctx)) }
        val anyDownloaded = LlamaModelManager.models.any {
            states[it.id] is LlamaModelManager.DownloadState.Done
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Offline-first",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "New chats automatically use the best downloaded on-device model. Nothing leaves your phone.",
                    style = TextStyle(fontSize = 12.sp),
                    color = ChatColors.secondaryText,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = offlineFirst,
                onCheckedChange = {
                    offlineFirst = it
                    LlamaModelManager.setOfflineFirst(ctx, it)
                    haptics.toggle()
                },
                enabled = anyDownloaded || offlineFirst,
            )
        }

        if (lowRam) {
            LowRamWarning()
        }

        // ── Auto (smart routing) row ──
        // [Wave 8] One-tap Auto: enables offline-first and routes now.
        if (anyDownloaded && localId == null && !offlineFirst) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.inputIconBg, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.tap()
                        LlamaModelManager.setOfflineFirst(ctx, true)
                        offlineFirst = true
                        val pick = LlamaModelManager.autoRoute(ctx, "")
                        if (pick != null) {
                            LlamaModelManager.setLocalMode(ctx, pick)
                            localId = pick.id
                            onDone()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // [v1.1.1] Column, not Row: the subtitle is long and used to
                // greedily consume the full row width, starving the weighted
                // title down to ~0dp so it wrapped one letter per line
                // (vertical text). Stacked layout can never do that.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "✨ Auto (on-device)",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                    Text(
                        text = "Let unibot pick the best downloaded model per question",
                        style = TextStyle(fontSize = 11.sp),
                        color = ChatColors.secondaryText,
                    )
                }
            }
        }

        // ── Download queue ──
        // [Wave 8] Show queued models waiting for the active download.
        val queue by LlamaModelManager.downloadQueue.collectAsState()
        if (queue.isNotEmpty()) {
            Text(
                text = "Queued: " + queue.mapNotNull { LlamaModelManager.modelById(it)?.title }
                    .joinToString(", "),
                style = TextStyle(fontSize = 11.sp),
                color = ChatColors.secondaryText,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // When the queue head is ready, the UI (which has a Context) starts it.
        val queueHead by LlamaModelManager.queueHeadHint.collectAsState()
        LaunchedEffect(queueHead) {
            queueHead?.let { id ->
                LlamaModelManager.consumeQueueHeadHint()
                LlamaModelManager.modelById(id)?.let { LlamaModelManager.download(ctx, it) }
            }
        }

        // ── Model rows ──
        // [Wave 9b] Nothing downloaded yet: lead with the shared branded
        // empty state; "Browse models" expands the downloadable list inline
        // (EmptyState's CTA already fires haptics.tap()). Once a model is
        // downloaded — or the list was expanded — rows show directly.
        if (!anyDownloaded && !browseExpanded) {
            EmptyState(
                // Outlined set (filled.Download is also imported for the
                // row icons; both usages stay qualified so they coexist).
                icon = Icons.Outlined.Download,
                title = "No on-device models",
                hint = "Download a model to chat fully offline — your data never leaves this phone.",
                ctaLabel = "Browse models",
                onCta = { browseExpanded = true },
            )
        } else {
            // [Wave 9b] Downloadable models cascade in by index.
            LlamaModelManager.models.forEachIndexed { index, model ->
                val state = states[model.id] ?: LlamaModelManager.DownloadState.Idle
                val downloaded = state is LlamaModelManager.DownloadState.Done
                val isLocalActive = localId == model.id
                LlamaModelRow(
                    model = model,
                    state = state,
                    downloaded = downloaded,
                    isLocalActive = isLocalActive,
                    isSelected = selected.id == model.id,
                    confirmDelete = confirmDeleteId == model.id,
                    // [Wave 9b] Rows stagger in; a row whose download just
                    // completed pops (the visual half of haptics.success()).
                    staggerIndex = index,
                    celebrateDownload = model.id in justDoneIds,
                    onTap = {
                        when {
                            downloaded -> {
                                // Route selector: flip the whole chat to this model.
                                LlamaModelManager.setLocalMode(ctx, model)
                                localId = model.id
                                confirmDeleteId = null
                                onDone()
                            }
                            state is LlamaModelManager.DownloadState.Failed -> {
                                haptics.tap()
                                LlamaModelManager.download(ctx, model)
                            }
                            state is LlamaModelManager.DownloadState.Paused -> {
                                haptics.tap()
                                LlamaModelManager.download(ctx, model)
                            }
                            else -> {
                                haptics.tap()
                                // [Wave 8] Queue when another download is active.
                                LlamaModelManager.enqueue(ctx, model)
                            }
                        }
                    },
                    onPauseResume = {
                        val s = LlamaModelManager.downloadStateOf(model)
                        if (s is LlamaModelManager.DownloadState.Downloading) {
                            LlamaModelManager.pause()
                        } else {
                            LlamaModelManager.download(ctx, model)
                        }
                    },
                    onDelete = {
                        if (confirmDeleteId == model.id) {
                            LlamaModelManager.delete(ctx, model)
                            localId = LlamaModelManager.localModeModelId(ctx)
                            confirmDeleteId = null
                        } else {
                            confirmDeleteId = model.id
                        }
                    },
                )
            }
        }

        // ── Back to cloud ──
        if (localId != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        LlamaModelManager.setLocalMode(ctx, null)
                        LocalChatService.release()
                        localId = null
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "← Back to cloud models",
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = accent,
                )
            }
        }

        Text(
            text = "Downloads are large (~1 GB) — Wi-Fi recommended. Models are stored on your phone only and never uploaded.",
            style = TextStyle(fontSize = 11.sp),
            color = ChatColors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

/** Small "works offline" pill shown in the section header and on active rows. */
@Composable
private fun OfflineBadge() {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Offline",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = accent,
        )
    }
}

@Composable
private fun LowRamWarning() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "This phone looks entry-level — on-device chat may be slow. LFM2 1.2B is the safer pick.",
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Smartphone,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Needs ~2 GB free RAM while chatting.",
            style = TextStyle(fontSize = 11.sp),
            color = ChatColors.secondaryText,
        )
    }
}

/** One row: radio + tier/hint on the left, size / progress / action on the right. */
@Composable
private fun LlamaModelRow(
    model: LlamaModel,
    state: LlamaModelManager.DownloadState,
    downloaded: Boolean,
    isLocalActive: Boolean,
    isSelected: Boolean,
    confirmDelete: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit = {},
    // [Wave 9b] Entrance cascade index for the downloadable-models list.
    staggerIndex: Int = 0,
    // [Wave 9b] True when this model's download just completed while the
    // section is open — the row pops (visual half of haptics.success()).
    celebrateDownload: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val downloading = state is LlamaModelManager.DownloadState.Downloading
    val paused = state is LlamaModelManager.DownloadState.Paused
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // [Wave 9b] Entrance cascade + download-complete pop, applied
            // outside the clip so the whole card scales as one unit.
            .staggeredEntrance(staggerIndex)
            .successPop(if (celebrateDownload) Unit else null)
            .background(ChatColors.inputIconBg, RoundedCornerShape(14.dp))
            .border(
                width = if (isLocalActive) 1.dp else 0.5.dp,
                color = if (isLocalActive) accent else ChatColors.inputIconBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !downloading,
            ) { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Selection dot — filled violet when this model is the active route.
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .border(1.5.dp, ChatColors.secondaryText, CircleShape)
                    .background(
                        if (downloaded && isLocalActive) accent else androidx.compose.ui.graphics.Color.Transparent,
                        CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.title,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isLocalActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        OfflineBadge()
                    }
                }
                Text(
                    text = model.hint,
                    style = TextStyle(fontSize = 11.sp),
                    color = ChatColors.secondaryText,
                )
                // [Wave 8] RAM estimate so users can judge fit for their phone.
                Text(
                    text = "Needs ~${model.ramEstimateMb / 1000}.${(model.ramEstimateMb % 1000) / 100} GB free RAM",
                    style = TextStyle(fontSize = 10.sp),
                    color = ChatColors.secondaryText.copy(alpha = 0.8f),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (state) {
                is LlamaModelManager.DownloadState.Downloading ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${(state.fraction * 100).toInt()}%",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = accent,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onPauseResume, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause download",
                                tint = ChatColors.secondaryText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                is LlamaModelManager.DownloadState.Paused ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${(state.fraction * 100).toInt()}% paused",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = ChatColors.secondaryText,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onPauseResume, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Resume download",
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                is LlamaModelManager.DownloadState.Failed ->
                    Text(
                        text = "Retry",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.error,
                    )
                is LlamaModelManager.DownloadState.Done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLocalActive) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(
                                text = "Use",
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                color = ChatColors.secondaryText,
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = if (confirmDelete) "Tap again to confirm delete" else "Delete model",
                                tint = if (confirmDelete) MaterialTheme.colorScheme.error else ChatColors.secondaryText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                else ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = ChatColors.secondaryText,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = model.sizeLabel,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
            }
        }
    }
    if (confirmDelete) {
        Text(
            text = "Tap the trash icon again to delete the ${model.sizeLabel} file and free space.",
            style = TextStyle(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
        )
    }
}
