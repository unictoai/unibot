package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.connectors.outlook.OutlookAzureConfig
import ai.unicto.unibot.connectors.outlook.OutlookConnector
import ai.unicto.unibot.connectors.outlook.OutlookOAuth
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.util.rememberHaptic
import kotlinx.coroutines.launch

/**
 * One-tap Azure app setup for the Outlook connector (item 35).
 *
 * unibot ships with no Microsoft client ID — the user brings their own
 * Azure app registration (BYOK). This panel explains the three portal
 * steps, takes the client ID / tenant / redirect URI, and saves them into
 * [OutlookAzureConfigStore]. The moment the client ID is saved,
 * [OutlookConnector.isConfigured] flips true and the Outlook row's Connect
 * button lights up — [onConfiguredChanged] lets the parent row refresh its
 * state immediately.
 *
 * Professional-UI rules: theme tokens only, 48dp-min touch targets via
 * UnibotTextButton, plain loading/error/empty states, no decorative
 * motion (plain conditional layout, no custom tweens).
 */
@Composable
fun OutlookAzureSetupPanel(
    onConfiguredChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()

    var expanded by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var tenant by remember { mutableStateOf(OutlookAzureConfig.DEFAULT_TENANT) }
    var redirectUri by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedTick by remember { mutableStateOf(0) }

    fun refresh() {
        val cfg = OutlookConnector.loadAzureConfig(context)
        clientId = cfg.clientId
        tenant = cfg.tenant.ifBlank { OutlookAzureConfig.DEFAULT_TENANT }
        redirectUri = cfg.redirectUri
        configured = cfg.isConfigured
    }
    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (configured) R.string.ub_connectors_outlook_azure_configured
                    else R.string.ub_connectors_outlook_azure_not_configured,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            UnibotTextButton(onClick = { haptics.tap(); expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.ub_connectors_outlook_azure_hide
                        else R.string.ub_connectors_outlook_azure_setup,
                    ),
                )
            }
        }

        if (expanded) {
            Text(
                text = stringResource(
                    R.string.ub_connectors_outlook_azure_steps,
                    OutlookOAuth.loopbackRedirectUris().joinToString("\n") { "• $it" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim(); error = null },
                label = { Text(stringResource(R.string.ub_connectors_outlook_azure_client_id)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = TextFieldDefaults.colors(),
            )
            OutlinedTextField(
                value = tenant,
                onValueChange = { tenant = it.trim(); error = null },
                label = { Text(stringResource(R.string.ub_connectors_outlook_azure_tenant)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = TextFieldDefaults.colors(),
            )
            OutlinedTextField(
                value = redirectUri,
                onValueChange = { redirectUri = it.trim(); error = null },
                label = { Text(stringResource(R.string.ub_connectors_outlook_azure_redirect)) },
                singleLine = true,
                supportingText = {
                    Text(stringResource(R.string.ub_connectors_outlook_azure_redirect_hint))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = TextFieldDefaults.colors(),
            )
            if (error != null) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (savedTick > 0 && error == null) {
                Text(
                    text = stringResource(R.string.ub_connectors_outlook_azure_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UnibotTextButton(
                    onClick = {
                        if (clientId.isBlank()) {
                            error = context.getString(R.string.ub_connectors_outlook_azure_need_id)
                            haptics.error()
                            return@UnibotTextButton
                        }
                        haptics.tap()
                        saving = true
                        error = null
                        scope.launch {
                            OutlookConnector.saveAzureConfig(
                                context,
                                OutlookAzureConfig(
                                    clientId = clientId,
                                    tenant = tenant.ifBlank { OutlookAzureConfig.DEFAULT_TENANT },
                                    redirectUri = redirectUri,
                                ),
                            )
                            saving = false
                            savedTick++
                            refresh()
                            onConfiguredChanged()
                            haptics.success()
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(stringResource(R.string.ub_connectors_outlook_azure_save))
                }
                UnibotTextButton(
                    onClick = {
                        haptics.tap()
                        scope.launch {
                            OutlookConnector.saveAzureConfig(context, OutlookAzureConfig())
                            savedTick = 0
                            refresh()
                            onConfiguredChanged()
                        }
                    },
                    enabled = !saving && configured,
                ) {
                    Text(stringResource(R.string.ub_connectors_outlook_azure_clear))
                }
            }
        }
    }
}
