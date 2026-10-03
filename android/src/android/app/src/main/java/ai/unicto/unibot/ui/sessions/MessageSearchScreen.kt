package ai.unicto.unibot.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.data.repository.MessageSearchMatch
import ai.unicto.unibot.ui.components.EmptyState
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val ROUTE_MESSAGE_SEARCH = "unibot/message_search"

/**
 * [Wave 8] Message search across ALL chats. Type a query → matching
 * messages grouped by chat (newest first), tap a result to jump into the
 * chat. Debounced; runs on-device against the local DB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchScreen(
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MessageSearchMatch>>(emptyList()) }
    var sessionTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    fun runSearch(q: String) {
        debounceJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            searched = false
            searching = false
            return
        }
        searching = true
        debounceJob = scope.launch {
            delay(350)
            val keywords = q.trim().split(Regex("\\s+")).filter { it.length >= 2 }
            if (keywords.isEmpty()) {
                searching = false
                return@launch
            }
            val matches = try {
                chatRepository.searchMessages(null, keywords, 60, null, null)
            } catch (_: Exception) {
                emptyList()
            }
            // Resolve chat titles for grouping headers.
            val titles = mutableMapOf<String, String>()
            for (m in matches) {
                if (!titles.containsKey(m.sessionId)) {
                    titles[m.sessionId] = try {
                        chatRepository.getSession(m.sessionId)?.title ?: "Chat"
                    } catch (_: Exception) {
                        "Chat"
                    }
                }
            }
            results = matches
            sessionTitles = titles
            searched = true
            searching = false
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text("Search messages") },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    runSearch(it)
                },
                placeholder = { Text("Search all chats…") },
                leadingIcon = {
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.tap()
                            query = ""
                            runSearch("")
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.size(12.dp))
            if (searched && results.isEmpty() && !searching) {
                // [v1.0-wave9a] Shared branded empty state. The pre-search
                // (not-yet-searched) state stays the plain empty list as-is.
                EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "No results",
                    hint = "Try different keywords — search looks through all your chats.",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Group by chat, newest chat first.
                val grouped = results.groupBy { it.sessionId }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    var flatIndex = 0
                    grouped.forEach { (sessionId, matches) ->
                        item(key = "h_$sessionId") {
                            Text(
                                text = sessionTitles[sessionId] ?: "Chat",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        itemsIndexed(matches, key = { _, m -> m.messageId }) { _, match ->
                            val idx = flatIndex++
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .staggeredEntrance(idx)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        haptics.tap()
                                        onOpenChat(sessionId)
                                    }
                                    .padding(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = match.snippet,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.size(2.dp))
                                    Text(
                                        text = "${if (match.role.equals("user", true)) "You" else "unibot"} · ${formatTs(match.createdAt)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
