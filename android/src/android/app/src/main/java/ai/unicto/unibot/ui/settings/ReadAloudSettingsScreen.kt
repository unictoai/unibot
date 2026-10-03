package ai.unicto.unibot.ui.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.speech.SherpaTtsEngine
import ai.unicto.unibot.speech.SystemTtsVoiceCatalog
import ai.unicto.unibot.speech.TtsVoiceModelManager
import ai.unicto.unibot.speech.VoiceOutputState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Read aloud (spoken replies) settings.
 *
 * Two paths to spoken replies:
 *  1. On-device voices (sherpa-onnx VITS) — downloaded once from unibot's
 *     releases, synthesized and played entirely on this phone. This is the
 *     path that works on Abdullah's Infinix, which ships with NO system
 *     speech service.
 *  2. The phone's own system TTS engine — offline when the engine has
 *     on-device voices — with a graceful engine-missing state and a one-tap
 *     path to the system TTS settings where an engine (e.g. Google's Speech
 *     Services) can be installed.
 *
 * Nothing here is bundled in the APK — unibot ships no TTS model — and BYOK
 * provider voices are picked in the voice-output section of the model picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engineState by remember { mutableStateOf<EngineState>(EngineState.Checking) }
    var voices by remember { mutableStateOf<List<SystemTtsVoiceCatalog.SystemVoice>>(emptyList()) }
    val pickedVoice by VoiceOutputState.systemVoiceName.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()
    val probe = remember {
        TextToSpeech(context) { status ->
            engineState = if (status == TextToSpeech.SUCCESS) EngineState.Ready else EngineState.Missing
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { probe.shutdown() } } }

    LaunchedEffect(engineState) {
        if (engineState == EngineState.Ready) {
            voices = SystemTtsVoiceCatalog.voices(context)
        }
    }
    // Devices with no engine may never call onInit (same blackhole
    // TextToSpeechManager guards with INIT_TIMEOUT_MS) — don't spin forever.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(6_000)
        if (engineState == EngineState.Checking) engineState = EngineState.Missing
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Read aloud") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { Spacer(Modifier.height(8.dp)) }

            // ── On-device voices (sherpa-onnx VITS) ──
            item { OnDeviceVoicesSection() }

            // ── Engine status ──
            item {
                MuseCard {
                    when (engineState) {
                        EngineState.Checking -> {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking the speech engine…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        EngineState.Ready -> {
                            MuseRow(
                                title = "Speech engine",
                                value = "Ready",
                                onClick = {},
                                icon = Icons.Outlined.VolumeUp,
                                chevron = false,
                            )
                        }
                        EngineState.Missing -> {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(26.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        "No speech engine on this phone",
                                        fontSize = 16.sp,
                                        lineHeight = 21.sp,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "This phone has no text-to-speech service, so spoken replies can't play yet. Install one (Google's Speech Services is free) in the system text-to-speech settings, then come back here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 21.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent("com.android.settings.TTS_SETTINGS")
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }) { Text("Open system TTS settings") }
                            }
                        }
                    }
                }
            }

            if (engineState == EngineState.Ready) {
                // ── Voice ──
                item {
                    MuseSectionLabel("Voice")
                    MuseCard {
                        MuseRow(
                            title = "Auto",
                            value = if (pickedVoice == null) "Selected" else null,
                            onClick = { VoiceOutputState.setSystemVoice(null, null) },
                            icon = Icons.Outlined.RecordVoiceOver,
                            chevron = false,
                            trailing = if (pickedVoice == null) {
                                { Icon(Icons.Outlined.Check, contentDescription = null) }
                            } else null,
                        )
                        voices.forEachIndexed { i, v ->
                            if (i > 0) MuseRowDivider()
                            MuseRow(
                                title = v.label,
                                value = if (v.networkRequired) "Online" else null,
                                onClick = { VoiceOutputState.setSystemVoice(v.name, v.label) },
                                icon = Icons.Outlined.RecordVoiceOver,
                                chevron = false,
                                trailing = if (pickedVoice == v.name) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                    MuseCaption(
                        "Auto follows the reply's language. Picking a voice always uses it, like the iOS app.",
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                }

                // ── Speed ──
                item {
                    MuseSectionLabel("Speed")
                    MuseCard {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Playback speed",
                                    fontSize = 16.sp,
                                    lineHeight = 21.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    VoiceOutputState.speedLabel(speed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = speed,
                                onValueChange = { VoiceOutputState.setSpeed(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 5,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    MuseCard {
                        MuseRow(
                            title = "Test voice",
                            value = "Hear a sample",
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        // Speak through the same on-device engine path as
                                        // read-aloud; provider-voice routing lives in chat.
                                        val tts = TextToSpeech(context, null)
                                        kotlinx.coroutines.delay(1200)
                                        tts.speak(
                                            "This is how unibot sounds on your phone.",
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "readaloud-test",
                                        )
                                    }
                                }
                            },
                            icon = Icons.Outlined.VolumeUp,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            } else {
                item {
                    MuseCaption(
                        "The speaker button on assistant messages and the read-aloud capsule start working once an engine is installed.",
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private enum class EngineState { Checking, Ready, Missing }

/** Small "ON-DEVICE" pill marking the sherpa-onnx voice section. */
@Composable
private fun OnDeviceBadge() {
    Text(
        text = "ON-DEVICE",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * On-device TTS voices (sherpa-onnx VITS / Piper), above the system-engine
 * section. Each voice downloads once from unibot's `tts-voice-v1` release —
 * model + token list + the shared espeak-ng phoneme data — then [SherpaTtsEngine]
 * synthesizes and plays entirely on this phone. Tapping a downloaded voice
 * selects it (checkmark); "Test voice" speaks through the on-device engine
 * when a voice is downloaded, otherwise falls back to the system-engine path.
 */
@Composable
private fun OnDeviceVoicesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by TtsVoiceModelManager.downloadStates.collectAsState()
    val selected by TtsVoiceModelManager.selectedVoice.collectAsState()
    val isSpeaking by SherpaTtsEngine.isSpeaking.collectAsState()

    LaunchedEffect(Unit) { TtsVoiceModelManager.loadSelection(context) }

    Row(
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "On-device voices",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        OnDeviceBadge()
    }
    MuseCard {
        TtsVoiceModelManager.voices.forEachIndexed { i, voice ->
            if (i > 0) MuseRowDivider()
            val state = states[voice.id] ?: TtsVoiceModelManager.DownloadState.Idle
            val downloaded = state is TtsVoiceModelManager.DownloadState.Done
            val isSelected = downloaded && selected.id == voice.id
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = downloaded) {
                        TtsVoiceModelManager.select(context, voice)
                    }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = voice.name,
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                        )
                        Text(
                            text = "${voice.detail} · ${voice.hint}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (state) {
                        is TtsVoiceModelManager.DownloadState.Downloading -> Text(
                            text = "${(state.fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is TtsVoiceModelManager.DownloadState.Failed -> TextButton(
                            onClick = { TtsVoiceModelManager.download(context, voice) },
                        ) { Text("Retry") }
                        is TtsVoiceModelManager.DownloadState.Done -> TextButton(
                            onClick = { TtsVoiceModelManager.delete(context, voice) },
                        ) { Text("Delete") }
                        else -> TextButton(
                            onClick = { TtsVoiceModelManager.download(context, voice) },
                        ) { Text("Download") }
                    }
                }
                // [v1.1.2] Size shown once in the description line above —
                // the redundant "Size:" line was removed.
                if (state is TtsVoiceModelManager.DownloadState.Downloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        MuseRowDivider()
        MuseRow(
            title = "Test voice",
            value = if (isSpeaking) "Speaking…" else "Hear a sample",
            onClick = {
                if (SherpaTtsEngine.isSpeaking.value) {
                    SherpaTtsEngine.stop()
                } else {
                    scope.launch(Dispatchers.Default) {
                        // On-device engine when a voice is downloaded; otherwise
                        // the same system-engine path as the test below.
                        val ready = TtsVoiceModelManager.ensureEngineReady(
                            context,
                            VoiceOutputState.speed.value,
                        )
                        if (ready) {
                            SherpaTtsEngine.speak("This is how unibot sounds on your phone.")
                        } else {
                            runCatching {
                                val tts = TextToSpeech(context, null)
                                kotlinx.coroutines.delay(1200)
                                tts.speak(
                                    "This is how unibot sounds on your phone.",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "readaloud-test-ondevice",
                                )
                            }
                        }
                    }
                }
            },
            icon = Icons.Outlined.VolumeUp,
        )
    }
    MuseCaption(
        "Voices download once from unibot's releases and speak entirely on this phone — " +
            "no system speech service needed, and nothing is ever uploaded.",
    )
}
