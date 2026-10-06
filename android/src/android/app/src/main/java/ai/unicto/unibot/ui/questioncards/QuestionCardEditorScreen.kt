package ai.unicto.unibot.ui.questioncards

import ai.unicto.unibot.questioncards.AnswerValue
import ai.unicto.unibot.questioncards.QuestionCardEngine
import ai.unicto.unibot.questioncards.QuestionCardStatus
import ai.unicto.unibot.questioncards.QuestionCardStore
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Item 93 — full-screen wizard for one question card. State lives in
 * [QuestionCardStore] (persisted on every keystroke-answer), so leaving and
 * coming back — or the app being killed — resumes mid-wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionCardEditorScreen(
    cardId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { QuestionCardStore(context) }
    val cards by store.cards.collectAsState()
    val card = cards.firstOrNull { it.id == cardId }
    val haptics = rememberHaptic()

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = {
                    Text(
                        card?.title ?: "Question card",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (card == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "This card no longer exists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            QuestionCardHostContent(
                card = card,
                onAnswer = { stepId, value: AnswerValue ->
                    store.transition(cardId) { current ->
                        when (val outcome = QuestionCardEngine.answer(current, stepId, value)) {
                            is QuestionCardEngine.AnswerOutcome.Ok -> outcome.card
                            is QuestionCardEngine.AnswerOutcome.Rejected -> current
                        }
                    }
                },
                onNext = { store.transition(cardId, QuestionCardEngine::next) },
                onBack = { store.transition(cardId, QuestionCardEngine::back) },
                onDismiss = {
                    store.transition(cardId, QuestionCardEngine::dismiss)
                    onBack()
                },
            )
            if (card.status == QuestionCardStatus.COMPLETED) {
                Text(
                    QuestionCardEngine.summary(card),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
