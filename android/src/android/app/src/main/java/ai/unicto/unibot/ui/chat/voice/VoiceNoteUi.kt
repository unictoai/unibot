package ai.unicto.unibot.ui.chat.voice

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.speech.VoiceNoteRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Voice theme item 32: voice-note UI — the recording bar, the composer draft
 * chip, and the bubble player row.
 *
 * Professional restraint: no waveform theatrics, no animations beyond the
 * progress indicators the platform provides. The red dot marks recording
 * (a purposeful state signal); everything else is type, spacing and one
 * accent.
 */

/** Recording in progress: red dot + elapsed timer + amplitude hint + stop. */
@Composable
fun VoiceNoteRecorderBar(
    recorder: VoiceNoteRecorder,
    elapsedMs: Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amplitude by recorder.amplitude.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ub_voice_note_recording),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f),
        )
        // Amplitude as a thin progress bar — cheap, honest level feedback.
        LinearProgressIndicator(
            progress = { amplitude },
            modifier = Modifier.width(48.dp),
            color = MaterialTheme.colorScheme.error,
            trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.ub_voice_note_delete),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The recorded note sitting in the composer, waiting for send: play/pause
 * preview, duration, delete. While [transcribing] a spinner replaces the
 * play button — the note transcribes on send.
 */
@Composable
fun VoiceNoteDraftChip(
    note: VoiceNoteRecorder.VoiceNote,
    transcribing: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (transcribing) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ub_voice_note_transcribing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            VoiceNotePlayButton(
                path = note.file.absolutePath,
                durationMs = note.durationMs,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ub_voice_note_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(note.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.ub_voice_note_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Playable voice-note row inside a user message bubble: play/pause,
 * progress, duration. Self-contained MediaPlayer with proper release.
 */
@Composable
fun VoiceNotePlayerRow(
    path: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val player = remember(path) {
        MediaPlayer().apply {
            runCatching {
                setDataSource(path)
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            runCatching {
                if (isPlaying) stop()
                release()
            }
        }
    }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (isActive) {
            val dur = runCatching { player.duration }.getOrDefault(0)
            val pos = runCatching { player.currentPosition }.getOrDefault(0)
            progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            if (!runCatching { player.isPlaying }.getOrDefault(false)) {
                playing = false
                progress = 0f
                break
            }
            delay(200)
        }
    }
    DisposableEffect(player) {
        player.setOnCompletionListener {
            playing = false
            progress = 0f
        }
        onDispose { player.setOnCompletionListener(null) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        IconButton(
            onClick = {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    runCatching {
                        player.start()
                        playing = true
                    }
                }
            },
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.ub_voice_note_pause else R.string.ub_voice_note_play,
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact play/pause toggle used by the draft chip. */
@Composable
private fun VoiceNotePlayButton(
    path: String,
    durationMs: Long,
    tint: androidx.compose.ui.graphics.Color,
) {
    val player = remember(path) {
        MediaPlayer().apply { runCatching { setDataSource(path); prepare() } }
    }
    DisposableEffect(player) {
        onDispose { runCatching { if (isPlaying) stop(); release() } }
    }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        player.setOnCompletionListener { playing = false }
        onDispose { player.setOnCompletionListener(null) }
    }
    IconButton(
        onClick = {
            if (playing) {
                runCatching { player.pause() }
                playing = false
            } else {
                runCatching { player.start() }
                playing = runCatching { player.isPlaying }.getOrDefault(false)
            }
        },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playing) R.string.ub_voice_note_pause else R.string.ub_voice_note_play,
            ),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
