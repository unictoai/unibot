package ai.unicto.unibot.ui.questioncards

import ai.unicto.unibot.questioncards.AnswerValue
import ai.unicto.unibot.questioncards.QuestionCard
import ai.unicto.unibot.questioncards.QuestionCardEngine
import ai.unicto.unibot.questioncards.QuestionCardStatus
import ai.unicto.unibot.questioncards.QuestionCardStore
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Item 93 — the embeddable in-chat renderer for a question card.
 *
 * INTEGRATION NOTE for the chat theme worker: render this inside the message
 * list wherever an assistant message carries a question-card payload
 * (suggested contract: message metadata key `question_card_id`). The host
 * reads live state from [QuestionCardStore], so answering here updates every
 * other surface (the Question cards screen, a resumed editor) automatically.
 * When the card completes, [onCompleted] fires with the compiled summary —
 * the chat layer can feed that back into the agent loop as the user's reply.
 *
 * Everything here is also reachable from the Question cards settings screen
 * for cards started outside a chat.
 */
@Composable
fun QuestionCardHost(
    cardId: String,
    onCompleted: (summary: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { QuestionCardStore(context) }
    val cards by store.cards.collectAsState()
    val card = cards.firstOrNull { it.id == cardId } ?: return

    QuestionCardHostContent(
        card = card,
        onAnswer = { stepId, value ->
            store.transition(cardId) { current ->
                when (val outcome = QuestionCardEngine.answer(current, stepId, value)) {
                    is QuestionCardEngine.AnswerOutcome.Ok -> outcome.card
                    is QuestionCardEngine.AnswerOutcome.Rejected -> current
                }
            }
        },
        onNext = {
            val updated = store.transition(cardId, QuestionCardEngine::next)
            if (updated?.status == QuestionCardStatus.COMPLETED) {
                onCompleted(QuestionCardEngine.summary(updated))
            }
        },
        onBack = { store.transition(cardId, QuestionCardEngine::back) },
        onDismiss = { store.transition(cardId, QuestionCardEngine::dismiss) },
        modifier = modifier,
    )
}

@Composable
fun QuestionCardHostContent(
    card: QuestionCard,
    onAnswer: (stepId: String, value: AnswerValue) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuseCard(modifier = modifier.fillMaxWidth()) {
        when (card.status) {
            QuestionCardStatus.COMPLETED -> {
                Text("Done — ${card.title}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your answers were sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QuestionCardStatus.DISMISSED -> {
                Text("Card dismissed", style = MaterialTheme.typography.titleSmall)
            }
            QuestionCardStatus.IN_PROGRESS -> {
                val step = card.steps.getOrNull(card.currentStep)
                if (step == null) {
                    Text("This card has no questions.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            card.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${card.currentStep + 1}/${card.steps.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { QuestionCardEngine.progress(card) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var draft by remember(step.id) { mutableStateOf(card.answers[step.id]) }
                    CardStepInput(
                        step = step,
                        current = draft,
                        onAnswer = {
                            draft = it
                            onAnswer(step.id, it)
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (card.currentStep > 0) {
                            UnibotTextButton(onClick = onBack) { Text("Back") }
                        }
                        Spacer(Modifier.weight(1f))
                        UnibotTextButton(onClick = onDismiss) { Text("Dismiss") }
                        UnibotButton(
                            onClick = onNext,
                            enabled = draft?.let { QuestionCardEngine.validateAnswer(step, it) == null }
                                ?: !step.required,
                        ) {
                            Text(if (card.currentStep == card.steps.size - 1) "Finish" else "Next")
                        }
                    }
                }
            }
        }
    }
}
