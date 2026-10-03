package ai.unicto.unibot.ui.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.unicto.unibot.R
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.offload.OffloadPermissionManager
import ai.unicto.unibot.speech.SherpaTtsEngine
import ai.unicto.unibot.speech.WhisperModelManager
import ai.unicto.unibot.speech.SpeechRecognitionManager
import ai.unicto.unibot.status.KeepAwake
import ai.unicto.unibot.ui.chat.ChatViewModel
import ai.unicto.unibot.ui.chat.ChatViewModelStore
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.animationsEnabled
import kotlin.math.cos
import kotlin.math.sin

/**
 * [unibot-voice-conversation] The flagship voice mode screen: a full-screen
 * conversation with an animated orb, live transcript, and the hands-free
 * talk loop ([VoiceConversationViewModel]).
 *
 * Layout, top to bottom: back + ON-DEVICE badge + settings shortcut; the
 * orb (one per state, all animated unless the user enabled "Remove
 * animations"); a live transcript card; the last exchange; a "Download
 * voices" card while no TTS voice is on the phone; bottom mic toggle +
 * auto-listen switch. Tapping anywhere while unibot speaks interrupts it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceConversationScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: ai.unicto.unibot.data.repository.MemoryRepository? = null,
    skillRepository: ai.unicto.unibot.data.repository.SkillRepository? = null,
    mcpRepository: ai.unicto.unibot.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onOpenReadAloudSettings: () -> Unit,
) {
    val context = LocalContext.current

    // The session's ChatViewModel — same process-level instance ChatScreen
    // uses, so the voice loop and the chat UI share one agent loop.
    val chatViewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    val vm: VoiceConversationViewModel = viewModel(
        factory = VoiceConversationViewModel.factory(
            chatViewModel = chatViewModel,
            ensureMicPermission = { ensureVoiceMicPermission(context) },
            // [v1.2] Lets the VM tell a bogus engine permission failure
            // apart from a genuinely missing grant.
            hasMicPermission = { isMicUsable(context) },
        ),
    )

    val convState by vm.state.collectAsState()
    val partial by vm.partialTranscript.collectAsState()
    val exchange by vm.lastExchange.collectAsState()
    val level by vm.audioLevel.collectAsState()
    val levels by SpeechRecognitionManager.audioLevels.collectAsState()
    val onDevice by vm.onDeviceBadge.collectAsState()
    val autoListen by vm.autoListen.collectAsState()

    // TTS voice readiness is not a flow (the voice manager owns downloads);
    // refresh it whenever we come back to the foreground (e.g. returning
    // from the downloads screen).
    var ttsReady by remember { mutableStateOf(SherpaTtsEngine.isReady) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ttsReady = SherpaTtsEngine.isReady
                vm.refreshBadge()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    VoiceConversationPrefs.init(context)

    // [v1.1.2] System mic-permission dialog via ActivityResultLauncher.
    // Denial (or "don't ask again") lands in State.PermissionDenied — a
    // graceful card with a Settings deep-link — never a raw
    // "RECORD_AUDIO required" dead-end.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startConversation(sessionId)
        else vm.onPermissionDenied()
    }
    fun requestMicPermission() {
        if (
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.startConversation(sessionId)
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Mic rationale on first entry: explain the on-device story BEFORE the
    // system permission dialog appears.
    var showRationale by remember {
        mutableStateOf(!VoiceConversationPrefs.wasMicRationaleShown(context))
    }
    LaunchedEffect(sessionId) {
        if (!showRationale) requestMicPermission()
    }

    // Keep the screen awake for the whole conversation.
    KeepAwake.Effect(convState != VoiceConversationViewModel.State.Idle)

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                VoiceConversationPrefs.markMicRationaleShown(context)
                requestMicPermission()
            },
            title = { Text(stringResource(R.string.ub_voice_mic_rationale_title)) },
            text = { Text(stringResource(R.string.ub_voice_mic_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    VoiceConversationPrefs.markMicRationaleShown(context)
                    requestMicPermission()
                }) { Text(stringResource(R.string.ub_voice_mic_rationale_continue)) }
            },
        )
    }

    val speaking = convState is VoiceConversationViewModel.State.Speaking
    Scaffold(containerColor = MuseTones.canvas) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Tap-to-interrupt: anywhere during SPEAKING stops TTS.
                // Child clickables still consume their own taps first.
                .pointerInput(speaking) {
                    detectTapGestures(onTap = { if (speaking) vm.interrupt() })
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MuseTopAppBar(
                    title = { Text(stringResource(R.string.ub_voice_title)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            vm.stopConversation()
                            onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        if (onDevice) {
                            OnDeviceBadge()
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = onOpenVoiceSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.ub_voice_settings_desc),
                            )
                        }
                    },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    VoiceOrb(
                        convState = convState,
                        audioLevel = level,
                        levels = levels,
                        modifier = Modifier.size(248.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = statusText(convState),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))

                    // Live transcript card.
                    if (convState is VoiceConversationViewModel.State.Listening ||
                        partial.isNotBlank()
                    ) {
                        MuseCard(inset = 0.dp) {
                            Text(
                                text = partial.ifBlank { stringResource(R.string.ub_voice_listening) },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (partial.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // Last exchange.
                    exchange?.let { (you, said) ->
                        if (you.isNotBlank() || said.isNotBlank()) {
                            MuseSectionLabel(stringResource(R.string.ub_voice_last_exchange))
                            MuseCard(inset = 0.dp) {
                                Column(Modifier.padding(16.dp)) {
                                    if (you.isNotBlank()) {
                                        ExchangeLine(
                                            label = stringResource(R.string.ub_voice_you_said),
                                            text = you,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    if (said.isNotBlank()) {
                                        ExchangeLine(
                                            label = stringResource(R.string.ub_voice_unibot_said),
                                            text = said,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    // [v1.1.2] Graceful mic-denied state: rationale + a deep-link
                    // to the app's system Settings page (covers plain denial
                    // AND "don't ask again"). Never a raw constant.
                    if (convState is VoiceConversationViewModel.State.PermissionDenied) {
                        MuseCard(inset = 0.dp) {
                            Column(
                                Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.mic_permission_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.mic_permission_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(onClick = {
                                        micPermissionLauncher.launch(
                                            android.Manifest.permission.RECORD_AUDIO,
                                        )
                                    }) {
                                        Text(stringResource(R.string.ub_voice_retry))
                                    }
                                    FilledTonalButton(onClick = {
                                        context.startActivity(
                                            Intent(
                                                SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            ).apply {
                                                data = Uri.fromParts(
                                                    "package",
                                                    context.packageName,
                                                    null,
                                                )
                                            },
                                        )
                                    }) {
                                        Text(stringResource(R.string.mic_permission_open_settings))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // Error card with retry.
                    (convState as? VoiceConversationViewModel.State.Error)?.let { err ->
                        MuseCard(inset = 0.dp) {
                            Column(
                                Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = err.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { vm.startConversation(sessionId) }) {
                                    Text(stringResource(R.string.ub_voice_retry))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // [v1.2] No speech-to-text model on the phone: the real
                    // recovery is downloading the offline model right here,
                    // with live progress — not just an error string.
                    if (convState is VoiceConversationViewModel.State.NoSttModel) {
                        SttModelDownloadCard(
                            onDownloaded = { vm.startConversation(sessionId) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // Download-voices prompt while no TTS voice is on the phone.
                    if (!ttsReady) {
                        MuseCard(inset = 0.dp) {
                            Row(
                                // [v1.2] fillMaxWidth: without it the weighted
                                // Column measures its Text at intrinsic (unwrapped)
                                // width on some densities and the body gets
                                // clipped mid-sentence at the card edge.
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.ub_voice_download_title),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.ub_voice_download_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onOpenReadAloudSettings) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.ub_voice_download_action))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ── Bottom controls ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.ub_voice_auto_listen),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = autoListen,
                            onCheckedChange = { VoiceConversationPrefs.setAutoListen(it) },
                        )
                    }
                    MicToggleButton(
                        convState = convState,
                        onToggle = {
                            when (convState) {
                                is VoiceConversationViewModel.State.Speaking -> vm.interrupt()
                                else -> vm.toggleListening()
                            }
                        },
                    )
                    // Balances the auto-listen row so the mic stays centred.
                    Spacer(Modifier.width(96.dp))
                }
                MuseCaption(
                    text = stringResource(R.string.ub_voice_auto_listen_desc),
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun ExchangeLine(label: String, text: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "ON-DEVICE" chip: mic icon + "100% on-device", shown when the loop is fully offline. */
@Composable
private fun OnDeviceBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.ub_voice_on_device),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// [v1.2] The recovery card for [VoiceConversationViewModel.State.NoSttModel]:
// downloads the offline whisper model right here with live progress, then
// fires [onDownloaded] so the conversation starts immediately. Reuses
// [WhisperModelManager] — the same models the chat voice panel offers.
@Composable
private fun SttModelDownloadCard(onDownloaded: () -> Unit) {
    val context = LocalContext.current
    val model by WhisperModelManager.selectedModel.collectAsState()
    val states by WhisperModelManager.downloadStates.collectAsState()

    LaunchedEffect(Unit) { WhisperModelManager.loadSelection(context) }

    val dlState = states[model.id] ?: WhisperModelManager.DownloadState.Idle
    // The manager auto-selects on success; watch for Done to kick off.
    LaunchedEffect(dlState) {
        if (dlState is WhisperModelManager.DownloadState.Done) onDownloaded()
    }

    MuseCard(inset = 0.dp) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.voice_panel_no_engine_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.voice_panel_no_engine_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            when (dlState) {
                is WhisperModelManager.DownloadState.Downloading -> {
                    val fraction = (dlState as WhisperModelManager.DownloadState.Downloading).fraction
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${model.title} · ${model.sizeLabel} · ${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { WhisperModelManager.cancel() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is WhisperModelManager.DownloadState.Failed -> {
                    Text(
                        text = (dlState as WhisperModelManager.DownloadState.Failed).message
                            ?: stringResource(R.string.ub_voice_download_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { WhisperModelManager.download(context, model) }) {
                        Text(stringResource(R.string.ub_voice_retry))
                    }
                }
                else -> {
                    FilledTonalButton(onClick = { WhisperModelManager.download(context, model) }) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                R.string.ub_voice_download_model_action,
                                model.title,
                                model.sizeLabel,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MicToggleButton(
    convState: VoiceConversationViewModel.State,
    onToggle: () -> Unit,
) {
    val violet = MaterialTheme.colorScheme.primary
    val listening = convState is VoiceConversationViewModel.State.Listening
    val speaking = convState is VoiceConversationViewModel.State.Speaking
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (listening || speaking) Color(0xFFDC2626) else violet)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when {
                speaking -> Icons.Filled.Stop
                listening -> Icons.Filled.MicOff
                else -> Icons.Filled.Mic
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

// ─── The orb ─────────────────────────────────────────────────────────────

/**
 * Canvas-drawn glowing orb in the brand violet. One look per state, all
 * driven by infinite transitions; when the user enabled "Remove animations"
 * ([animationsEnabled] false) it renders a single static orb.
 *
 *  - IDLE: slow breathe.
 *  - LISTENING: pulsing rings synced to the mic level + waveform bars.
 *  - THINKING: orbiting dots + a rotating shimmer arc.
 *  - SPEAKING: expanding sound waves.
 *  - ERROR: static orb (the error card below carries the message).
 */
@Composable
private fun VoiceOrb(
    convState: VoiceConversationViewModel.State,
    audioLevel: Float,
    levels: List<Float>,
    modifier: Modifier = Modifier,
) {
    val animated = animationsEnabled()
    val violet = MaterialTheme.colorScheme.primary
    val violetLight = violet.copy(alpha = 0.55f)
    val violetFaint = violet.copy(alpha = 0.14f)

    val clock = rememberInfiniteTransition(label = "voiceOrb")
    val breathe by clock.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val ringPhase by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring",
    )
    val orbitAngle by clock.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit",
    )
    val speakPulse by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speak",
    )

    // [v1.2] Smooth morph between states: the orb eases into each state's
    // energy level instead of snapping when the conversation moves
    // idle → listening → thinking → speaking → error.
    val targetEnergy = when (convState) {
        is VoiceConversationViewModel.State.Listening -> 1.12f
        is VoiceConversationViewModel.State.Speaking -> 1.08f
        is VoiceConversationViewModel.State.Thinking -> 1.04f
        is VoiceConversationViewModel.State.Error -> 0.94f
        else -> 1f
    }
    val energy by animateFloatAsState(
        targetValue = if (animated) targetEnergy else 1f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "energy",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val base = size.minDimension / 2f * 0.52f
        val breatheScale = if (animated) breathe else 1f

        fun ringRadius(t: Float): Float = base * 1.12f + t * (size.minDimension * 0.22f)

        // Expanding rings sit BEHIND the orb.
        if (animated) {
            when (convState) {
                is VoiceConversationViewModel.State.Listening -> {
                    for (i in 0 until 3) {
                        val t = (ringPhase + i / 3f) % 1f
                        drawCircle(
                            color = violet.copy(alpha = (1f - t) * 0.4f),
                            radius = ringRadius(t) * (1f + audioLevel * 0.15f),
                            center = center,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
                is VoiceConversationViewModel.State.Speaking -> {
                    for (i in 0 until 3) {
                        val t = (ringPhase + i / 3f) % 1f
                        drawCircle(
                            color = violet.copy(alpha = (1f - t) * 0.5f),
                            radius = ringRadius(t),
                            center = center,
                            style = Stroke(width = (2.5f + 2f * (1f - t)).dp.toPx()),
                        )
                    }
                }
                else -> Unit
            }
        }

        // Soft glow halo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(violetFaint, Color.Transparent),
                center = center,
                radius = base * 2.1f,
            ),
            radius = base * 2.1f,
            center = center,
        )

        // Core orb with a radial violet gradient.
        val orbScale = breatheScale * energy * when (convState) {
            is VoiceConversationViewModel.State.Listening -> 1f + audioLevel * 0.22f
            is VoiceConversationViewModel.State.Speaking -> if (animated) 1f + speakPulse * 0.07f else 1f
            else -> 1f
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(violetLight, violet, violet.copy(alpha = 0.85f)),
                center = Offset(cx - base * 0.25f, cy - base * 0.3f),
                radius = base * 1.7f * orbScale,
            ),
            radius = base * orbScale,
            center = center,
        )
        // Inner highlight — the glossy top-left sheen.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(cx - base * 0.35f, cy - base * 0.4f),
                radius = base * 0.75f,
            ),
            radius = base * 0.75f,
            center = Offset(cx - base * 0.35f, cy - base * 0.4f),
        )

        if (animated) {
            when (convState) {
                // Waveform bars ringing the orb, driven by the live levels.
                is VoiceConversationViewModel.State.Listening -> {
                    val bars = 28
                    val inner = base * 1.35f
                    for (i in 0 until bars) {
                        val angle = (i * 360f / bars) * (Math.PI / 180f)
                        val sample = levels.getOrNull((i * levels.size / bars).coerceAtMost(levels.size - 1)) ?: 0f
                        val barLen = (5.dp + 26.dp * sample).toPx()
                        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                        drawLine(
                            color = violet.copy(alpha = 0.75f),
                            start = center + dir * inner,
                            end = center + dir * (inner + barLen),
                            strokeWidth = 4.dp.toPx(),
                        )
                    }
                }
                // Orbiting dots + a rotating shimmer arc.
                is VoiceConversationViewModel.State.Thinking -> {
                    val orbitR = base * 1.45f
                    for (i in 0 until 3) {
                        val a = ((orbitAngle + i * 120f) * Math.PI / 180f)
                        drawCircle(
                            color = violet.copy(alpha = 0.9f),
                            radius = 7.dp.toPx(),
                            center = center + Offset(
                                cos(a).toFloat() * orbitR,
                                sin(a).toFloat() * orbitR,
                            ),
                        )
                    }
                    drawArc(
                        color = violet.copy(alpha = 0.5f),
                        startAngle = orbitAngle,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = Offset(cx - base * 1.2f, cy - base * 1.2f),
                        size = androidx.compose.ui.geometry.Size(base * 2.4f, base * 2.4f),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
                else -> Unit
            }
        }
    }
}

// [v1.2] True only when the mic is actually usable right now: the runtime
// permission is granted AND AppOps isn't blocking capture (e.g. the system
// mic privacy toggle in quick settings). Catches the "granted but still
// broken" case where the dialog said yes yet recording can't start.
fun isMicUsable(context: Context): Boolean {
    if (
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) return false
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_RECORD_AUDIO,
            android.os.Process.myUid(),
            context.packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        true // Can't ask AppOps — trust the runtime grant.
    }
}

// ─── Mic permission ───────────────────────────────────────────────────────

/**
 * The shared 3-stage RECORD_AUDIO permission flow (system dialog →
 * post-DENY poll → in-app settings gate), mirroring ChatScreen's
 * `ensureMicPermissionFlow`. Returns true when granted.
 */
suspend fun ensureVoiceMicPermission(context: Context): Boolean {
    val perm = android.Manifest.permission.RECORD_AUDIO
    val hasPerm: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
    if (hasPerm()) return true
    var result = OffloadPermissionManager.requestAndroidPermission(listOf(perm))
    if (result == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
        OffloadPermissionManager.pollForPermissionGrant(hasPerm)
    ) {
        result = OffloadPermissionManager.AndroidPermissionResult.GRANTED
    }
    if (result == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
        result = OffloadPermissionManager.requestSettingsGate(
            OffloadPermissionManager.SettingsGateRequest(
                id = perm,
                title = context.getString(R.string.mic_permission_title),
                message = context.getString(R.string.mic_permission_message),
                settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                requiresPackageUri = true,
                positiveLabel = context.getString(R.string.mic_permission_open_settings),
                negativeLabel = context.getString(R.string.mic_permission_cancel),
            ),
            check = hasPerm,
        )
    }
    return result == OffloadPermissionManager.AndroidPermissionResult.GRANTED
}

@Composable
private fun statusText(state: VoiceConversationViewModel.State): String = when (state) {
    is VoiceConversationViewModel.State.Idle -> stringResource(R.string.ub_voice_idle)
    is VoiceConversationViewModel.State.Listening -> stringResource(R.string.ub_voice_listening)
    is VoiceConversationViewModel.State.Thinking -> stringResource(R.string.ub_voice_thinking)
    is VoiceConversationViewModel.State.Speaking -> stringResource(R.string.ub_voice_speaking)
    // [v1.2] Denied / no-model cards carry their own text; the orb just idles.
    is VoiceConversationViewModel.State.PermissionDenied -> stringResource(R.string.ub_voice_idle)
    is VoiceConversationViewModel.State.NoSttModel -> stringResource(R.string.ub_voice_idle)
    is VoiceConversationViewModel.State.Error -> stringResource(R.string.ub_voice_retry)
}
