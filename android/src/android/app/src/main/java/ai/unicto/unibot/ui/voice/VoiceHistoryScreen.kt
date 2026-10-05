package ai.unicto.unibot.ui.voice
import ai.unicto.unibot.ui.theme.Motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.animationsEnabled
import java.text.DateFormat
import java.util.Date

/**
 * [unibot-voice-history] Past voice conversations: every chat session with at
 * least one completed voice turn ([VoiceConversationHistory]), newest first.
 * Tapping an entry opens it in the normal chat screen.
 *
 * Titles/previews come live from the session DB, so renames and deletions
 * stay in sync; ids whose session is gone (deleted, incognito) are pruned
 * silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceHistoryScreen(
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceConversationHistory.init(context) }
    val voiceIds by VoiceConversationHistory.voiceSessionIds.collectAsState()
    val sessions by chatRepository.observeSessions().collectAsState(initial = emptyList())
    val animated = animationsEnabled()

    // Join voice ids against live sessions; drop ids whose session vanished.
    val entries = remember(sessions, voiceIds) {
        val byId = sessions.associateBy { it.id }
        val gone = voiceIds - byId.keys
        if (gone.isNotEmpty()) {
            gone.forEach { VoiceConversationHistory.forget(context, it) }
        }
        voiceIds.mapNotNull { byId[it] }
            .sortedByDescending { it.updatedAt }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.ub_voice_history_title)) },
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
        if (entries.isEmpty()) {
            VoiceHistoryEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                animated = animated,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    MuseSectionLabel(stringResource(R.string.ub_voice_history_title))
                }
                itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                    AnimatedVisibility(
                        visible = true,
                        enter = if (animated) {
                            fadeIn(tween(220, delayMillis = (index * 35).coerceAtMost(350))) +
                                slideInVertically(tween(220, delayMillis = (index * 35).coerceAtMost(350))) { it / 3 }
                        } else {
                            fadeIn(tween(1))
                        },
                    ) {
                        VoiceHistoryRow(
                            entry = entry,
                            timeLabel = remember(entry.updatedAt) {
                                coarseTime(context, entry.updatedAt)
                            },
                            onClick = { onOpenChat(entry.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun VoiceHistoryRow(
    entry: ChatSessionEntity,
    timeLabel: String,
    onClick: () -> Unit,
) {
    MuseCard(inset = 16.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.ub_voice_history_untitled),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.lastMessage?.takeIf { it.isNotBlank() }?.let { preview ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun VoiceHistoryEmptyState(
    modifier: Modifier = Modifier,
    animated: Boolean,
) {
    AnimatedVisibility(
        visible = true,
        enter = if (animated) fadeIn(tween(Motion.Standard)) else fadeIn(tween(1)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.ub_voice_history_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ub_voice_history_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Coarse static time label, computed once per entry (no ticking needed). */
private fun coarseTime(context: android.content.Context, epochMs: Long): String {
    val secs = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0)
    return when {
        secs < 60 -> context.getString(R.string.ub_voice_time_just_now)
        secs < 3600 -> context.getString(R.string.ub_voice_time_min_ago, (secs / 60).toInt())
        secs < 86_400 -> context.getString(R.string.ub_voice_time_hr_ago, (secs / 3600).toInt())
        secs < 2 * 86_400 -> context.getString(R.string.ub_voice_time_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
    }
}
