package ai.unicto.unibot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.data.model.ProviderCredential
import ai.unicto.unibot.data.model.ProviderInstance
import ai.unicto.unibot.data.model.ProviderType
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.provider.KeyValidationResult
import ai.unicto.unibot.provider.KeyValidator
import ai.unicto.unibot.ui.components.RowLabel
import ai.unicto.unibot.ui.components.SectionTextField
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.openExternalUrl
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Backlog item 98 — the BYOK setup wizard.
 *
 * Three steps: pick a provider (free-tier suggestions first), paste the key,
 * then live validation against the provider's API before anything is saved.
 * A rejected key never reaches the provider list — the friendly error says
 * what to do instead of dumping HTTP text.
 *
 * This is a standalone wizard; [AddProviderScreen] is untouched. Free-tier
 * suggestions are derived from [ProviderType.signupUrl] so the list stays in
 * sync with the canonical free-tier set.
 */
private enum class ByokStep { PICK, KEY, RESULT }

/** Providers the wizard supports, in display order: free tier first, then the majors. */
private val wizardProviders: List<ProviderType> by lazy {
    val free = ProviderType.entries.filter { it.signupUrl != null }
    val majors = listOf(
        ProviderType.openAI,
        ProviderType.anthropic,
        ProviderType.gemini,
        ProviderType.openRouter,
        ProviderType.xAI,
    )
    (free + majors).distinct()
}

private fun keyPlaceholder(type: ProviderType): String = when (type) {
    ProviderType.anthropic -> "sk-ant-…"
    ProviderType.openAI -> "sk-…"
    ProviderType.gemini -> "AIza…"
    ProviderType.openRouter -> "sk-or-…"
    ProviderType.xAI -> "xai-…"
    ProviderType.groq -> "gsk_…"
    ProviderType.cerebras -> "csk-…"
    ProviderType.githubModels -> "GitHub token…"
    ProviderType.nvidiaNim -> "nvapi-…"
    else -> "API key…"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ByokWizardScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(ByokStep.PICK) }
    var type by remember { mutableStateOf<ProviderType?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KeyValidationResult?>(null) }

    val handleBack: () -> Unit = {
        when (step) {
            ByokStep.PICK -> onBack()
            ByokStep.KEY -> step = ByokStep.PICK
            ByokStep.RESULT -> if (result is KeyValidationResult.Valid) onBack() else step = ByokStep.KEY
        }
    }
    BackHandler { handleBack() }

    val title = when (step) {
        ByokStep.PICK -> "Choose a provider"
        ByokStep.KEY -> "Add your key"
        ByokStep.RESULT -> "Checking your key"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ByokStepIndicator(step = step, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            when (step) {
                ByokStep.PICK -> ByokPickStep(
                    onPick = { picked ->
                        type = picked
                        apiKey = ""
                        result = null
                        step = ByokStep.KEY
                    },
                )
                ByokStep.KEY -> ByokKeyStep(
                    type = type!!,
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    showKey = showKey,
                    onToggleShowKey = { showKey = !showKey },
                    onGetFreeKey = {
                        type!!.signupUrl?.let { openExternalUrl(context, it) }
                    },
                    onValidate = {
                        validating = true
                        result = null
                        val t = type!!
                        val key = apiKey.trim()
                        scope.launch {
                            val r = KeyValidator.validate(t, key)
                            validating = false
                            result = r
                            step = ByokStep.RESULT
                        }
                    },
                    validating = validating,
                )
                ByokStep.RESULT -> ByokResultStep(
                    type = type!!,
                    result = result,
                    validating = validating,
                    onRetry = {
                        result = null
                        step = ByokStep.KEY
                    },
                    onSaveAnyway = {
                        saveWizardProvider(providerRepository, type!!, apiKey.trim(), scope)
                        onSaved()
                    },
                    onDone = {
                        saveWizardProvider(providerRepository, type!!, apiKey.trim(), scope)
                        onSaved()
                    },
                )
            }
        }
    }
}

/** Persists the wizard's provider exactly like the manual form would. */
private fun saveWizardProvider(
    providerRepository: ProviderRepository,
    type: ProviderType,
    apiKey: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val instance = ProviderInstance(
        id = UUID.randomUUID().toString(),
        label = type.displayName,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = null,
        appendV1Suffix = type != ProviderType.gemini,
    )
    providerRepository.addInstance(instance)
    providerRepository.saveApiKey(instance.id, apiKey)
    scope.launch { providerRepository.refreshModels(instance) }
}

/** Three dots: pick → key → check. */
@Composable
private fun ByokStepIndicator(step: ByokStep, modifier: Modifier = Modifier) {
    val index = step.ordinal
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(ByokStep.entries.size) { i ->
            Box(
                Modifier
                    .size(width = if (i == index) 20.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = when (step) {
                ByokStep.PICK -> "Step 1 of 3"
                ByokStep.KEY -> "Step 2 of 3"
                ByokStep.RESULT -> "Step 3 of 3"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Step 1: free-tier providers first (with a FREE pill), then the majors. */
@Composable
private fun ColumnScope.ByokPickStep(onPick: (ProviderType) -> Unit) {
    Text(
        text = "Start free — these providers hand out API keys with a free tier, no card required.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(12.dp))
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(wizardProviders, key = { it.name }) { providerType ->
            ByokProviderRow(
                type = providerType,
                isFreeTier = providerType.signupUrl != null,
                onClick = { onPick(providerType) },
            )
        }
    }
}

@Composable
private fun ByokProviderRow(type: ProviderType, isFreeTier: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(type.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (isFreeTier) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "FREE TIER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
                Text(
                    text = type.signupUrl?.let { "Free tier available" } ?: "Paid plans",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Step 2: the key field, a show toggle, and a "get a free key" link for free-tier providers. */
@Composable
private fun ColumnScope.ByokKeyStep(
    type: ProviderType,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    onGetFreeKey: () -> Unit,
    onValidate: () -> Unit,
    validating: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "Paste your ${type.displayName} API key. It is checked against the provider before anything is saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SettingsSection(
            header = "API key",
            footer = "Your key is stored encrypted on this phone. It is only ever sent to ${type.displayName}.",
        ) {
            SettingsCardBlock {
                RowLabel(text = "${type.displayName} key")
                SectionTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = keyPlaceholder(type),
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleShowKey) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                            )
                        }
                    },
                )
            }
        }
        if (type.signupUrl != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onGetFreeKey, modifier = Modifier.align(Alignment.Start)) {
                Text("Get a free key from ${type.displayName}", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
        UnibotButton(
            onClick = onValidate,
            enabled = apiKey.isNotBlank() && !validating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (validating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Checking…")
            } else {
                Text("Check & continue")
            }
        }
    }
}

/** Step 3: the verdict — friendly copy, then save (or fix the key). */
@Composable
private fun ColumnScope.ByokResultStep(
    type: ProviderType,
    result: KeyValidationResult?,
    validating: Boolean,
    onRetry: () -> Unit,
    onSaveAnyway: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (validating || result == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "Checking your key with ${type.displayName}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val ok = result is KeyValidationResult.Valid
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (ok) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (ok) "Key works" else "Key did not work",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = KeyValidator.friendlyMessage(result),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (ok) {
            UnibotButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Save provider")
            }
        } else {
            UnibotButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Fix the key")
            }
            // Non-auth failures (network, server, unexpected) may be transient —
            // let the user save anyway instead of dead-ending the wizard.
            if (result !is KeyValidationResult.InvalidKey) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSaveAnyway) {
                    Text("Save anyway", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
