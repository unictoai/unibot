package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.speech.SherpaTtsEngine
import ai.unicto.unibot.speech.VoiceOutputState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.voice.VoiceConversationPrefs

/**
 * [unibot-voice-conversation] Voice conversation settings: voice speed,
 * on-device voice choice, auto-listen, and whether replies are spoken only
 * inside voice mode.
 *
 * Speed is the shared [VoiceOutputState.speed] (0.5x–2.0x) — one speed for
 * every TTS surface. Voice downloads themselves are managed from the
 * read-aloud settings screen until the TTS voice manager lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceConversationSettingsScreen(
    onBack: () -> Unit,
    onOpenReadAloudSettings: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        VoiceConversationPrefs.init(context)
        // Speed is the shared VoiceOutputState pref — make sure its
        // SharedPreferences are loaded so the slider persists.
        VoiceOutputState.init(context)
    }

    val speed by VoiceOutputState.speed.collectAsState()
    val autoListen by VoiceConversationPrefs.autoListen.collectAsState()
    val speakOnly by VoiceConversationPrefs.speakOnlyInVoiceMode.collectAsState()
    val voiceId by VoiceConversationPrefs.voiceId.collectAsState()

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.ub_voice_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { Spacer(Modifier.height(8.dp)) }

            // ── Speed ──
            item {
                MuseSectionLabel(stringResource(R.string.ub_voice_speed))
                MuseCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.ub_voice_speed),
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
            }

            // ── Voice ──
            item {
                MuseSectionLabel(stringResource(R.string.ub_voice_choice))
                MuseCard {
                    val options = listOf(
                        null to stringResource(R.string.ub_voice_choice_auto),
                    ) + VoiceConversationPrefs.VOICES.map { it.id to it.label }
                    options.forEachIndexed { i, (id, label) ->
                        if (i > 0) MuseRowDivider()
                        MuseRow(
                            title = label,
                            onClick = { VoiceConversationPrefs.setVoiceId(id) },
                            icon = Icons.Outlined.RecordVoiceOver,
                            chevron = false,
                            trailing = if (voiceId == id) {
                                { Icon(Icons.Outlined.Check, contentDescription = null) }
                            } else null,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                MuseCard {
                    MuseRow(
                        title = stringResource(R.string.ub_voice_download_action),
                        value = if (SherpaTtsEngine.isReady) stringResource(R.string.ub_voice_ready) else null,
                        onClick = onOpenReadAloudSettings,
                        icon = Icons.Outlined.Download,
                    )
                }
                MuseCaption(
                    stringResource(R.string.ub_voice_offline_caption),
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }

            // ── Conversation ──
            item {
                MuseSectionLabel(stringResource(R.string.ub_voice_title))
                MuseCard {
                    MuseRow(
                        title = stringResource(R.string.ub_voice_auto_listen),
                        value = stringResource(R.string.ub_voice_auto_listen_desc),
                        onClick = { VoiceConversationPrefs.setAutoListen(!autoListen) },
                        icon = Icons.Outlined.Hearing,
                        chevron = false,
                        trailing = {
                            Switch(
                                checked = autoListen,
                                onCheckedChange = { VoiceConversationPrefs.setAutoListen(it) },
                            )
                        },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.ub_voice_speak_only_title),
                        value = stringResource(R.string.ub_voice_speak_only_desc),
                        onClick = {
                            VoiceConversationPrefs.setSpeakOnlyInVoiceMode(!speakOnly)
                        },
                        icon = Icons.Outlined.Speed,
                        chevron = false,
                        trailing = {
                            Switch(
                                checked = speakOnly,
                                onCheckedChange = {
                                    VoiceConversationPrefs.setSpeakOnlyInVoiceMode(it)
                                },
                            )
                        },
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
