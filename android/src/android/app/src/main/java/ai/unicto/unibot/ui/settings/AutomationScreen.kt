package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar

/**
 * v0.2.0 P3 — the Automation help screen (Settings → Background →
 * Automation). Documents the three Tasker/Automate broadcast intents,
 * the two home-screen widgets, and background approval notifications.
 * In the Muse settings design language: section labels, 24dp cards,
 * tap-to-copy on the intent action strings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    fun copy(text: String) = clipboard.setText(AnnotatedString(text))

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.ub_automation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.size(8.dp))

            MuseSectionLabel(stringResource(R.string.ub_automation_intents_section))
            MuseCard {
                IntentDocRow(
                    title = stringResource(R.string.ub_automation_ask_title),
                    action = "ai.unicto.unibot.ASK",
                    extras = stringResource(R.string.ub_automation_ask_extra),
                    description = stringResource(R.string.ub_automation_ask_desc),
                    onCopy = ::copy,
                )
                MuseRowDivider()
                IntentDocRow(
                    title = stringResource(R.string.ub_automation_newchat_title),
                    action = "ai.unicto.unibot.NEW_CHAT",
                    extras = null,
                    description = stringResource(R.string.ub_automation_newchat_desc),
                    onCopy = ::copy,
                )
                MuseRowDivider()
                IntentDocRow(
                    title = stringResource(R.string.ub_automation_routine_title),
                    action = "ai.unicto.unibot.RUN_ROUTINE",
                    extras = stringResource(R.string.ub_automation_routine_extra),
                    description = stringResource(R.string.ub_automation_routine_desc),
                    onCopy = ::copy,
                )
            }
            MuseCaption(stringResource(R.string.ub_automation_intents_caption))

            MuseGap()
            MuseSectionLabel(stringResource(R.string.ub_automation_tasker_section))
            MuseCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TaskerStep("1", stringResource(R.string.ub_automation_tasker_step1))
                    TaskerStep("2", stringResource(R.string.ub_automation_tasker_step2))
                    TaskerStep("3", stringResource(R.string.ub_automation_tasker_step3))
                    TaskerStep("4", stringResource(R.string.ub_automation_tasker_step4))
                }
            }
            MuseCaption(stringResource(R.string.ub_automation_tasker_caption))

            MuseGap()
            MuseSectionLabel(stringResource(R.string.ub_automation_widgets_section))
            MuseCard {
                MuseRow(
                    title = stringResource(R.string.ub_automation_widget_ask_title),
                    value = null,
                    chevron = false,
                    onClick = {},
                )
            }
            MuseCaption(stringResource(R.string.ub_automation_widget_ask_desc))
            MuseCard {
                MuseRow(
                    title = stringResource(R.string.ub_automation_widget_tasks_title),
                    value = null,
                    chevron = false,
                    onClick = {},
                )
            }
            MuseCaption(stringResource(R.string.ub_automation_widget_tasks_desc))

            MuseGap()
            MuseSectionLabel(stringResource(R.string.ub_automation_approvals_section))
            MuseCaption(stringResource(R.string.ub_automation_approvals_desc))

            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun IntentDocRow(
    title: String,
    action: String,
    extras: String?,
    description: String,
    onCopy: (String) -> Unit,
) {
    // Tapping the row copies the intent action string — the gesture is
    // the affordance (a separate copy button would break the Muse row
    // language; the caption under the text says so).
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onCopy(action) },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = action,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        if (extras != null) {
            Text(
                text = extras,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = description,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.ub_automation_tap_to_copy),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TaskerStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = number,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
