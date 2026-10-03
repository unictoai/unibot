package ai.unicto.unibot.ui.chat

import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

const val ROUTE_CHAT_STATS = "unibot/chat_stats"

/** Aggregated fun stats. Computed on-device from the local DB. */
data class ChatStats(
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalWords: Int = 0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val longestStreakDays: Int = 0,
    val mostActiveHour: Int = -1,
    val starredCount: Int = 0,
)

/**
 * [Wave 8] Chat stats screen: totals, words, streaks, most-active hour —
 * with animated count-ups. All computed on-device from the local database;
 * nothing leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatStatsScreen(
    chatRepository: ChatRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<ChatStats?>(null) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { computeStats(context, chatRepository) }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text("Chat stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val s = stats
        if (s == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Chats", s.totalChats, 0, Modifier.weight(1f))
                        StatCard("Messages", s.totalMessages, 1, Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Words exchanged", s.totalWords, 2, Modifier.weight(1f))
                        StatCard("Day streak", s.longestStreakDays, 3, Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("You sent", s.userMessages, 4, Modifier.weight(1f))
                        StatCard("unibot sent", s.assistantMessages, 5, Modifier.weight(1f))
                    }
                }
                item {
                    val hourLabel = if (s.mostActiveHour >= 0) {
                        val h = s.mostActiveHour
                        val ampm = if (h < 12) "AM" else "PM"
                        val h12 = if (h % 12 == 0) 12 else h % 12
                        "$h12 $ampm"
                    } else "—"
                    InfoCard(
                        index = 6,
                        title = "Most active hour",
                        value = hourLabel,
                        subtitle = "When you and unibot talk the most",
                    )
                }
                item {
                    InfoCard(
                        index = 7,
                        title = "Starred messages",
                        value = s.starredCount.toString(),
                        subtitle = "Long-press any message to star it",
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "Computed on this phone · never uploaded",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, index: Int, modifier: Modifier = Modifier) {
    // [Wave 8] Animated count-up on entrance.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val animated by animateIntAsState(
        targetValue = if (started) value else 0,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = Motion.Emphasis,
            easing = Motion.FastOutSlowIn,
        ),
        label = "stat_$label",
    )
    Column(
        modifier = modifier
            .staggeredEntrance(index)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatNumber(animated),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoCard(index: Int, title: String, value: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(n / 1_000f)
    else -> n.toString()
}

/** Rough text extraction from parts_json for word counting. */
private fun roughText(partsJson: String): String {
    // Pull "text" fields out of the parts JSON without a full parse.
    val sb = StringBuilder()
    val regex = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    for (m in regex.findAll(partsJson)) {
        sb.append(m.groupValues[1].replace("\\n", " ").replace("\\\"", "\"")).append(' ')
        if (sb.length > 4000) break
    }
    return sb.toString()
}

private suspend fun computeStats(
    context: android.content.Context,
    chatRepository: ChatRepository,
): ChatStats {
    return try {
        // First emission of the sessions flow.
        val sessionList = chatRepository.observeSessions().first()
        var totalMessages = 0
        var totalWords = 0
        var userMessages = 0
        var assistantMessages = 0
        val activeDays = mutableSetOf<String>()
        val hourCounts = IntArray(24)
        val cal = Calendar.getInstance()
        for (s in sessionList) {
            val msgs = chatRepository.loadMessages(s.id)
            totalMessages += msgs.size
            for (m in msgs) {
                when (m.role.lowercase()) {
                    "user" -> userMessages++
                    "assistant" -> assistantMessages++
                }
                val text = roughText(m.partsJson)
                if (text.isNotBlank()) {
                    totalWords += text.split(Regex("\\s+")).size
                }
                cal.timeInMillis = m.createdAt
                val day = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
                activeDays.add(day)
                hourCounts[cal.get(Calendar.HOUR_OF_DAY)]++
            }
        }
        // Longest streak of consecutive active days.
        val sortedDays = activeDays.mapNotNull {
            val parts = it.split("-")
            if (parts.size == 2) parts[0].toInt() * 1000 + parts[1].toInt() else null
        }.sorted()
        var best = 0
        var cur = 0
        var prev = -2
        for (d in sortedDays) {
            cur = if (d == prev + 1) cur + 1 else 1
            best = maxOf(best, cur)
            prev = d
        }
        val topHour = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: -1
        val stars = StarredMessageStore(context).getAll().size
        ChatStats(
            totalChats = sessionList.size,
            totalMessages = totalMessages,
            totalWords = totalWords,
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            longestStreakDays = best,
            mostActiveHour = if (hourCounts.sum() > 0) topHour else -1,
            starredCount = stars,
        )
    } catch (t: Throwable) {
        ChatStats()
    }
}
