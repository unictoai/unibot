package ai.unicto.unibot.ui.cloud

import androidx.compose.material3.ExperimentalMaterial3Api
import ai.unicto.unibot.R
import ai.unicto.unibot.cloud.UnibotCloud
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.sync.SessionSyncEngine
import ai.unicto.unibot.sync.SyncOutcome
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Device sync (P8) — the opt-in cross-device continuity screen, reached from
 * the unibot Cloud account page.
 *
 * What it does today: the toggle, the passphrase-derived E2E key, the local
 * snapshot builder and the sync engine are all real. What it cannot do yet:
 * actually move snapshots — the cloud relay has no session-sync endpoint, so
 * "Sync now" builds and encrypts the snapshot, then honestly reports that the
 * relay cannot take it. The limitation is stated on the screen itself, not
 * buried in a log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSyncScreen(
    chatRepository: ChatRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(UnibotCloud.isSignedIn(context)) }
    var enabled by remember { mutableStateOf(SessionSyncEngine.isEnabled(context)) }
    var passphrase by remember { mutableStateOf("") }
    var hasPassphrase by remember { mutableStateOf(SessionSyncEngine.hasPassphrase()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastSyncAt by remember { mutableStateOf(SessionSyncEngine.lastSyncAt(context)) }
    var lastError by remember { mutableStateOf(SessionSyncEngine.lastError(context)) }
    var sessionCount by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        signedIn = UnibotCloud.isSignedIn(context)
        sessionCount = runCatching { chatRepository.observeSessions().first().size }.getOrDefault(-1)
    }

    fun runSync() {
        if (working) return
        working = true
        status = null
        scope.launch {
            if (passphrase.isNotBlank()) {
                SessionSyncEngine.setPassphrase(passphrase.toCharArray())
                hasPassphrase = true
            }
            status = when (val outcome = SessionSyncEngine.syncNow(context, chatRepository)) {
                is SyncOutcome.Done -> context.getString(R.string.ub_sync_done, outcome.sessions)
                is SyncOutcome.NotReady -> outcome.reason
                is SyncOutcome.Failed -> context.getString(R.string.ub_sync_unavailable, outcome.reason)
            }
            lastSyncAt = SessionSyncEngine.lastSyncAt(context)
            lastError = SessionSyncEngine.lastError(context)
            working = false
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.ub_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
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
            Spacer(Modifier.height(8.dp))

            if (!signedIn) {
                MuseCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.ub_sync_need_signin),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                return@Column
            }

            MuseSectionLabel(stringResource(R.string.ub_sync_title))
            MuseCard {
                MuseRow(
                    title = stringResource(R.string.ub_sync_toggle),
                    value = stringResource(if (enabled) R.string.ub_sync_toggle_on else R.string.ub_sync_toggle_off),
                    icon = Icons.Outlined.Sync,
                    chevron = false,
                    trailing = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                SessionSyncEngine.setEnabled(context, it)
                                if (!it) {
                                    passphrase = ""
                                    hasPassphrase = false
                                }
                            },
                        )
                    },
                    onClick = {
                        enabled = !enabled
                        SessionSyncEngine.setEnabled(context, enabled)
                        if (!enabled) {
                            passphrase = ""
                            hasPassphrase = false
                        }
                    },
                )
            }
            MuseCaption(stringResource(R.string.ub_sync_row_subtitle))

            if (enabled) {
                MuseGap()
                MuseSectionLabel(stringResource(R.string.ub_sync_passphrase_label))
                MuseCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ub_sync_passphrase_hint)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                        )
                        if (hasPassphrase) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.ub_sync_key_ready),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                MuseCaption(stringResource(R.string.ub_sync_passphrase_note))

                MuseGap()
                MuseSectionLabel(stringResource(R.string.ub_sync_status_title))
                MuseCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(
                                R.string.ub_sync_last_sync,
                                if (lastSyncAt > 0) {
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(lastSyncAt))
                                } else {
                                    stringResource(R.string.ub_sync_never)
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (sessionCount >= 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.ub_sync_sessions_on_device, sessionCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        (status ?: lastError)?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                MuseGap()
                UnibotButton(
                    onClick = ::runSync,
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(if (working) stringResource(R.string.ub_sync_working) else stringResource(R.string.ub_sync_now))
                }
                MuseGap()
            }

            MuseCaption(stringResource(R.string.ub_sync_relay_limitation))
            Spacer(Modifier.height(24.dp))
        }
    }
}
