package ai.unicto.unibot.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.ui.muse.MuseTones
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val ROUTE_STARRED_MESSAGES = "unibot/starred_messages"

/** One starred message: enough to render the row and jump back to it. */
@Serializable
data class StarredMessage(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val preview: String,
    val isUser: Boolean,
    val starredAtMs: Long = System.currentTimeMillis(),
)

/**
 * [Wave 8] Starred messages store.
 *
 * Long-press any message → Star. Stars are message bookmarks: the preview
 * and jump target live here, the message itself stays in its chat.
 * Plain SharedPreferences JSON (previews aren't secrets); the full message
 * content is never duplicated — only a 160-char preview.
 */
class StarredMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("starred_messages", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(): List<StarredMessage> = try {
        val raw = prefs.getString(KEY_STARS, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(StarredMessage.serializer()), raw)
            .sortedByDescending { it.starredAtMs }
    } catch (_: Exception) {
        emptyList()
    }

    fun isStarred(messageId: String): Boolean = getAll().any { it.messageId == messageId }

    fun toggle(star: StarredMessage): Boolean {
        val all = getAll().toMutableList()
        val existing = all.indexOfFirst { it.messageId == star.messageId }
        val nowStarred = if (existing >= 0) {
            all.removeAt(existing)
            false
        } else {
            all.add(0, star)
            true
        }
        persist(all)
        return nowStarred
    }

    fun remove(messageId: String) {
        persist(getAll().filterNot { it.messageId == messageId })
    }

    fun clearForSession(sessionId: String) {
        persist(getAll().filterNot { it.sessionId == sessionId })
    }

    private fun persist(all: List<StarredMessage>) {
        prefs.edit()
            .putString(KEY_STARS, json.encodeToString(ListSerializer(StarredMessage.serializer()), all))
            .apply()
    }

    companion object {
        private const val KEY_STARS = "stars_json"
    }
}

/**
 * [Wave 8] Starred messages screen. Rows show the preview, the chat title,
 * and the date; tapping jumps to the chat. Swipe-free delete via trash icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredMessagesScreen(
    onBack: () -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { StarredMessageStore(context) }
    var stars by remember { mutableStateOf<List<StarredMessage>>(emptyList()) }
    val haptics = rememberHaptic()

    LaunchedEffect(Unit) { stars = store.getAll() }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text("Starred messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (stars.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.StarOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No starred messages",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Long-press any message in a chat and tap Star to pin it here.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(stars, key = { _, s -> s.messageId }) { index, star ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggeredEntrance(index)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptics.tap()
                                onOpenChat(star.sessionId)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = star.preview,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${star.sessionTitle} · ${formatDate(star.starredAtMs)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.tap()
                                store.remove(star.messageId)
                                stars = store.getAll()
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Unstar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
