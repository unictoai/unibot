package ai.unicto.unibot.ui.questioncards

import ai.unicto.unibot.questioncards.QuestionCardEngine
import ai.unicto.unibot.questioncards.QuestionCardStatus
import ai.unicto.unibot.questioncards.QuestionCardStore
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Item 93 — the Question cards manager: in-progress wizards (resumable with
 * a tap) above completed ones. Cards started from a chat render inline there
 * via [QuestionCardHost]; this screen is the durable home for all of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionCardsScreen(
    onBack: () -> Unit,
    onOpenCard: (cardId: String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { QuestionCardStore(context) }
    val cards by store.cards.collectAsState()
    val haptics = rememberHaptic()

    val inProgress = cards.filter { it.status == QuestionCardStatus.IN_PROGRESS }
    val done = cards.filter { it.status != QuestionCardStatus.IN_PROGRESS }

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = {
                    Text("Question cards", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (cards.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Quiz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No question cards yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "When the assistant needs a few answers from you, they'll appear here as a short wizard — and pick up where you left off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (inProgress.isNotEmpty()) {
                item { SectionLabel("In progress") }
                items(inProgress, key = { it.id }) { card ->
                    MuseCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { haptics.tap(); onOpenCard(card.id) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(card.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Question ${card.currentStep + 1} of ${card.steps.size} — tap to resume",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${(QuestionCardEngine.progress(card) * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { QuestionCardEngine.progress(card) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (done.isNotEmpty()) {
                item { SectionLabel("Completed") }
                items(done, key = { it.id }) { card ->
                    MuseCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { haptics.tap(); onOpenCard(card.id) },
                    ) {
                        Text(card.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (card.status == QuestionCardStatus.COMPLETED) "Completed" else "Dismissed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
