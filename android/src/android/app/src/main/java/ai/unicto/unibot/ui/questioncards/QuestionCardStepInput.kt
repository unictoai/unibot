package ai.unicto.unibot.ui.questioncards

import ai.unicto.unibot.questioncards.AnswerValue
import ai.unicto.unibot.questioncards.CardStep
import ai.unicto.unibot.questioncards.CardStepKind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Item 93 — answer input for one wizard step. Controlled component: the
 * parent owns the current [AnswerValue] and receives every change via
 * [onAnswer]. Professional restraint: plain M3 inputs, no decoration.
 */
@Composable
fun CardStepInput(
    step: CardStep,
    current: AnswerValue?,
    onAnswer: (AnswerValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(step.prompt, style = MaterialTheme.typography.titleSmall)
        when (step.kind) {
            CardStepKind.TEXT -> {
                var text by remember(step.id) {
                    mutableStateOf((current as? AnswerValue.Text)?.text ?: "")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onAnswer(AnswerValue.Text(it))
                    },
                    placeholder = { if (step.placeholder.isNotBlank()) Text(step.placeholder) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                )
            }
            CardStepKind.CHOICE -> {
                val selected = (current as? AnswerValue.Choice)?.option
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    step.options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAnswer(AnswerValue.Choice(option)) }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selected == option,
                                onClick = { onAnswer(AnswerValue.Choice(option)) },
                            )
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            CardStepKind.MULTI_CHOICE -> {
                val selected = (current as? AnswerValue.MultiChoice)?.options?.toSet() ?: emptySet()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    step.options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (option in selected) selected - option else selected + option
                                    onAnswer(AnswerValue.MultiChoice(next.toList()))
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = option in selected,
                                onCheckedChange = { checked ->
                                    val next = if (checked) selected + option else selected - option
                                    onAnswer(AnswerValue.MultiChoice(next.toList()))
                                },
                            )
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            CardStepKind.CONFIRM -> {
                val confirmed = (current as? AnswerValue.Confirm)?.confirmed
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = confirmed == true,
                        onClick = { onAnswer(AnswerValue.Confirm(true)) },
                        label = { Text("Yes") },
                    )
                    FilterChip(
                        selected = confirmed == false,
                        onClick = { onAnswer(AnswerValue.Confirm(false)) },
                        label = { Text("No") },
                    )
                }
            }
            CardStepKind.SCALE -> {
                val value = (current as? AnswerValue.Scale)?.value?.toFloat()
                    ?: ((step.scaleMin + step.scaleMax) / 2f)
                Column {
                    Slider(
                        value = value,
                        onValueChange = { onAnswer(AnswerValue.Scale(it.roundToInt().coerceIn(step.scaleMin, step.scaleMax))) },
                        valueRange = step.scaleMin.toFloat()..step.scaleMax.toFloat(),
                        steps = (step.scaleMax - step.scaleMin - 1).coerceAtLeast(0),
                    )
                    Text(
                        "${value.roundToInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
