package ai.unicto.unibot.ui.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.speech.SystemTtsVoiceCatalog
import ai.unicto.unibot.speech.VoiceOutputState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch

/**
 * Read aloud (spoken replies) settings.
 *
 * unibot reads replies with the phone's own TTS engine — offline when the
 * engine has on-device voices. Abdullah's Infinix ships with NO system speech
 * service, so this screen leads with a graceful engine-missing state: what it
 * means, and a one-tap path to the system TTS settings where an engine (e.g.
 * Google's Speech Services) can be installed. Nothing here is a download from
 * us — unibot bundles no TTS model, and BYOK provider voices are picked in
 * the voice-output section of the model picker.
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
