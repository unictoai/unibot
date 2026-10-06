package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.connectors.gmail.GmailLabelRule
import ai.unicto.unibot.connectors.gmail.GmailLabelRuleStore
import ai.unicto.unibot.scheduled.GmailLabelWorker
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.util.rememberHaptic
import kotlinx.coroutines.launch

/**
 * Gmail auto-label rules panel (item 43), shown under the Gmail row in
 * the Connectors screen.
 *
 * - Master toggle: schedules/cancels the battery-disciplined WorkManager
 *   run ([GmailLabelWorker], every 2h, network-connected + battery-not-low).
 * - Rules list with per-rule enable toggle and delete.
 * - Inline add form: name + match fields + label to apply.
 *
 * Professional-UI rules: theme tokens only, 48dp-min targets via
 * UnibotTextButton / Switch, plain error text, no decorative motion.
 */
@Composable
fun GmailLabelRulesPanel(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()
    val store = remember { GmailLabelRuleStore() }

    var expanded by remember { mutableStateOf(false) }
    var autoEnabled by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf<List<GmailLabelRule>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Add-form state
    var fName by remember { mutableStateOf("") }
    var fFrom by remember { mutableStateOf("") }
    var fSubject by remember { mutableStateOf("") }
    var fQuery by remember { mutableStateOf("") }
    var fLabel by remember { mutableStateOf("") }

    fun refresh() {
        autoEnabled = store.isAutoLabelEnabled(context)
        rules = store.getRules(context)
    }
    LaunchedEffect(Unit) { refresh() }

    fun matchSummary(r: GmailLabelRule): String {
        val parts = mutableListOf<String>()
        if (r.fromContains.isNotBlank()) parts.add("from “${r.fromContains}”")
        if (r.subjectContains.isNotBlank()) parts.add("subject “${r.subjectContains}”")
        if (r.query.isNotBlank()) parts.add("search “${r.query}”")
        return (if (parts.isEmpty()) "all recent mail" else parts.joinToString(", ")) +
            " → label “${r.labelName}”"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ub_connectors_gmail_label_rules) +
                    (if (rules.isNotEmpty()) " (${rules.count { it.enabled }}/${rules.size})" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            UnibotTextButton(onClick = { haptics.tap(); expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.ub_connectors_gmail_label_rules_hide
                        else R.string.ub_connectors_gmail_label_rules,
                    ),
                )
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ub_connectors_gmail_label_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.ub_connectors_gmail_label_auto_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoEnabled,
                    onCheckedChange = { on ->
                        haptics.tap()
                        scope.launch {
                            store.setAutoLabelEnabled(context, on)
                            if (on) GmailLabelWorker.schedule(context)
                            else GmailLabelWorker.cancel(context)
                            refresh()
                            haptics.success()
                        }
                    },
                )
            }

            if (rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.ub_connectors_gmail_label_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = matchSummary(rule),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    store.saveRules(
                                        context,
                                        rules.map { if (it.id == rule.id) it.copy(enabled = on) else it },
                                    )
                                    refresh()
                                }
                            },
                        )
                        UnibotTextButton(onClick = {
                            haptics.tap()
                            scope.launch {
                                store.saveRules(context, rules.filter { it.id != rule.id })
                                refresh()
                            }
                        }) {
                            Text(stringResource(R.string.ub_connectors_gmail_label_delete))
                        }
                    }
                }
            }

            if (!adding) {
                UnibotTextButton(
                    onClick = { haptics.tap(); adding = true; error = null },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.ub_connectors_gmail_label_add))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = fName, onValueChange = { fName = it; error = null },
                        label = { Text(stringResource(R.string.ub_connectors_gmail_label_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(),
                    )
                    OutlinedTextField(
                        value = fFrom, onValueChange = { fFrom = it; error = null },
                        label = { Text(stringResource(R.string.ub_connectors_gmail_label_from)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(),
                    )
                    OutlinedTextField(
                        value = fSubject, onValueChange = { fSubject = it; error = null },
                        label = { Text(stringResource(R.string.ub_connectors_gmail_label_subject)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(),
                    )
                    OutlinedTextField(
                        value = fQuery, onValueChange = { fQuery = it; error = null },
                        label = { Text(stringResource(R.string.ub_connectors_gmail_label_query)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(),
                    )
                    OutlinedTextField(
                        value = fLabel, onValueChange = { fLabel = it; error = null },
                        label = { Text(stringResource(R.string.ub_connectors_gmail_label_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(),
                    )
                    if (error != null) {
                        Text(
                            text = error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UnibotTextButton(onClick = {
                            if (fName.isBlank() || fLabel.isBlank()) {
                                error = context.getString(R.string.ub_connectors_gmail_label_need)
                                haptics.error()
                                return@UnibotTextButton
                            }
                            if (fFrom.isBlank() && fSubject.isBlank() && fQuery.isBlank()) {
                                error = context.getString(R.string.ub_connectors_gmail_label_need_match)
                                haptics.error()
                                return@UnibotTextButton
                            }
                            haptics.tap()
                            scope.launch {
                                val rule = GmailLabelRule(
                                    name = fName.trim(),
                                    fromContains = fFrom.trim(),
                                    subjectContains = fSubject.trim(),
                                    query = fQuery.trim(),
                                    labelName = fLabel.trim(),
                                )
                                store.saveRules(context, rules + rule)
                                fName = ""; fFrom = ""; fSubject = ""; fQuery = ""; fLabel = ""
                                adding = false
                                refresh()
                                haptics.success()
                            }
                        }) {
                            Text(stringResource(R.string.ub_connectors_gmail_label_save))
                        }
                        UnibotTextButton(onClick = { adding = false; error = null }) {
                            Text(stringResource(R.string.ub_connectors_gmail_label_cancel))
                        }
                    }
                }
            }
        }
    }
}
