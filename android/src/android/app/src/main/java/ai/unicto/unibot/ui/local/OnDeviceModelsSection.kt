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
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
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
import ai.unicto.unibot.ui.theme.ChatColors

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

    LaunchedEffect(Unit) {
        LlamaModelManager.loadSelection(ctx)
        localId = LlamaModelManager.localModeModelId(ctx)
    }

    // [v0.4.0-premium-feel] Haptics for model downloads: a tick when a
    // download starts, a warm confirmation when one finishes.
    val haptics = ai.unicto.unibot.ui.util.rememberHaptic()
    var seenDoneIds by remember { mutableStateOf(setOf<String>()) }
    var hapticsArmed by remember { mutableStateOf(false) }
    LaunchedEffect(states) {
        val nowDone = states
            .filterValues { it is LlamaModelManager.DownloadState.Done }
            .keys
        // Only buzz for completions that happen while this screen is open —
        // never for models that were already downloaded before it opened.
        if (hapticsArmed && (nowDone - seenDoneIds).isNotEmpty()) {
            haptics.success()
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

        if (lowRam) {
            LowRamWarning()
        }

        // ── Model rows ──
        LlamaModelManager.models.forEach { model ->
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
                        else -> {
                            haptics.tap()
                            LlamaModelManager.download(ctx, model)
                        }
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
) {
    val accent = MaterialTheme.colorScheme.primary
    val downloading = state is LlamaModelManager.DownloadState.Downloading
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (state) {
                is LlamaModelManager.DownloadState.Downloading ->
                    Text(
                        text = "${(state.fraction * 100).toInt()}%",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = accent,
                    )
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
