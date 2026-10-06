package ai.unicto.unibot.ui.settings

import ai.unicto.unibot.profile.ImportPlan
import ai.unicto.unibot.profile.ImportResult
import ai.unicto.unibot.profile.ProfileExporter
import ai.unicto.unibot.profile.ProfileImporter
import ai.unicto.unibot.profile.profileFileName
import ai.unicto.unibot.ui.components.DialogTextField
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.util.rememberHaptic
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Item 96 — portable profile: export everything (settings, skills, presets)
 * as one file via the share sheet; import with review + per-secret explicit
 * confirmation. Secrets are redacted on export — the file never carries them
 * in cleartext.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortableProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptic()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = null
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                status = "Could not read that file."
                busy = false
                return@launch
            }
            val parsed = ProfileImporter(context).plan(text)
            if (parsed == null) {
                status = "That file is not a unibot profile."
            } else {
                plan = parsed
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = { Text("Portable profile", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MuseCard(modifier = Modifier.fillMaxWidth()) {
                Text("Export", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "One file with your settings, skills and presets. " +
                        "Secrets are redacted — the file never carries them. " +
                        "Your provider API keys never leave this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                UnibotButton(
                    onClick = {
                        haptics.tap()
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                exportAndShare(context)
                            }
                            busy = false
                            if (!ok) status = "Export failed — please try again."
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "Working…" else "Export profile") }
            }

            MuseCard(modifier = Modifier.fillMaxWidth()) {
                Text("Import", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick a profile file to review what it contains before anything is applied. " +
                        "Imported background agents start disabled until you grant them permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                UnibotButton(
                    onClick = { haptics.tap(); fileLauncher.launch("application/json") },
                    enabled = !busy,
                ) { Text("Import profile") }
            }

            if (status != null) {
                Text(
                    status!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    plan?.let { importPlan ->
        ImportReviewDialog(
            plan = importPlan,
            onDismiss = { plan = null },
            onConfirm = { secrets ->
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ProfileImporter(context).apply(importPlan, secrets)
                    }
                    busy = false
                    plan = null
                    result = res
                }
            },
        )
    }

    result?.let { res ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("Import complete") },
            text = {
                Text(
                    "${res.settingsApplied} settings applied\n" +
                        "${res.skillsImported} skills imported\n" +
                        "${res.presetsImported} prompt presets added\n" +
                        "${res.commandsImported} slash commands added\n" +
                        "${res.cardsImported} question cards restored\n" +
                        "${res.agentsImported} background agents added (disabled — grant permission to run them)",
                )
            },
            confirmButton = {
                UnibotTextButton(onClick = { result = null }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun ImportReviewDialog(
    plan: ImportPlan,
    onDismiss: () -> Unit,
    onConfirm: (secrets: Map<String, String>) -> Unit,
) {
    // Per-secret explicit confirmation: a text field per redacted key. Blank
    // = skip that secret (the setting is left untouched).
    var secretValues by remember { mutableStateOf(plan.secretsRequired.associateWith { "" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review profile import") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                if (plan.secretsRequired.isNotEmpty()) {
                    Text(
                        "Redacted secrets",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "These were redacted on export. Re-enter a value to set it, or leave blank to skip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    plan.secretsRequired.forEach { key ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            DialogTextField(
                                value = secretValues[key] ?: "",
                                onValueChange = {
                                    secretValues = secretValues + (key to it)
                                },
                                placeholder = key,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Text(
                        "No secrets in this file — nothing sensitive to confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            UnibotButton(onClick = { onConfirm(secretValues.filterValues { it.isNotBlank() }) }) {
                Text("Import")
            }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Build the profile JSON, write it to cacheDir/shared/, and open the share
 * sheet. Mirrors the MCP server export pattern (FileProvider
 * `${packageName}.fileprovider`, cache-path "shared/").
 */
private fun exportAndShare(context: android.content.Context): Boolean {
    return try {
        val profile = ProfileExporter(context).export()
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, profileFileName())
        file.writeText(profile.toJson().toString(2))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Export unibot profile").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (t: Throwable) {
        false
    }
}
