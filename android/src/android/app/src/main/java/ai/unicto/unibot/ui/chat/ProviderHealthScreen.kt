package ai.unicto.unibot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.data.model.ProviderCredential
import ai.unicto.unibot.data.model.ProviderInstance
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.UbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_PROVIDER_HEALTH = "unibot/provider_health"

/**
 * v1.4.0 items 23 + 24 — provider health dashboard and free-tier quota tracker.
 *
 * One screen per provider instance: live status (from a real `/models`
 * probe, or sign-in state for OAuth), rate-limit/auth signals, and today's
 * usage against typical free-tier daily limits with a gentle hint at 80%.
 * Quota limits are labelled approximate — the provider's own dashboard is
 * authoritative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderHealthScreen(
    providerRepository: ProviderRepository,
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenProvider: (instanceId: String) -> Unit,
    onAddProvider: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProviderHealthStore(context) }
    val prober = remember { ProviderHealthProber(context, providerRepository) }

    var instances by remember { mutableStateOf(providerRepository.instances) }
    var health by remember { mutableStateOf<Map<String, ProviderHealth>>(emptyMap()) }
    var quotaUsed by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var probing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }

    fun refreshBaseline() {
        instances = providerRepository.instances
        health = instances.mapNotNull { inst ->
            store.get(inst.id)?.let { inst.id to it }
        }.toMap()
    }

    LaunchedEffect(Unit) {
        refreshBaseline()
        quotaUsed = withContext(Dispatchers.IO) {
            instances.associate { it.id to todayRequestCount(chatRepository, it.id) }
        }
        loaded = true
    }

    fun probeOne(inst: ProviderInstance) {
        if (inst.id in probing) return
        probing = probing + inst.id
        scope.launch(Dispatchers.IO) {
            val result = prober.probe(inst)
            store.put(result)
            withContext(Dispatchers.Main) {
                health = health + (inst.id to result)
                probing = probing - inst.id
            }
        }
    }

    fun probeAll() = instances.forEach { probeOne(it) }

    Column(modifier = Modifier.fillMaxSize()) {
        MuseTopAppBar(
            title = { Text("Provider health") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = ::probeAll) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Check all")
                }
            },
        )
        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Loading…",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (instances.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No providers yet",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add a provider to see its live status and quota here.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                UnibotTextButton(onClick = onAddProvider) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add provider")
                }
            }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            MuseCaption(
                "Live status per provider, plus today's usage against typical " +
                    "free-tier daily limits. Limits change — your provider's " +
                    "dashboard is authoritative.",
            )
            instances.forEach { inst ->
                ProviderHealthCard(
                    instance = inst,
                    health = health[inst.id],
                    quotaUsed = quotaUsed[inst.id] ?: 0,
                    probing = inst.id in probing,
                    hasKey = providerRepository.usableApiKey(inst)?.isNotEmpty() == true,
                    onCheck = { probeOne(inst) },
                    onOpen = { onOpenProvider(inst.id) },
                )
            }
        }
    }
}

@Composable
private fun ProviderHealthCard(
    instance: ProviderInstance,
    health: ProviderHealth?,
    quotaUsed: Int,
    probing: Boolean,
    hasKey: Boolean,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
) {
    val limit = FreeTierLimits.dailyRequests(instance.providerType)
    val quotaState = limit?.let { quotaStateFor(quotaUsed, it) }

    MuseCard(
        modifier = Modifier.clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (health?.status) {
                HealthStatus.HEALTHY -> UbColors.success
                HealthStatus.AUTH_ERROR, HealthStatus.RATE_LIMITED -> UbColors.warning
                HealthStatus.ERROR, HealthStatus.UNREACHABLE -> UbColors.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.label.ifBlank { instance.providerType.displayName },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(instance.providerType.displayName)
                        append(" · ")
                        append(
                            when {
                                instance.credentialType == ProviderCredential.oauth -> "OAuth"
                                hasKey -> "API key set"
                                else -> "No API key"
                            },
                        )
                        health?.let {
                            append(" · ")
                            append(it.detail ?: it.status.name.lowercase().replace('_', ' '))
                            if (it.checkedAt > 0) append(" (${relativeTime(it.checkedAt)})")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            UnibotTextButton(onClick = onCheck, enabled = !probing) {
                Text(if (probing) "…" else "Check", fontSize = 13.sp)
            }
        }

        if (limit != null && quotaState != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Today ≈ $quotaUsed / ${formatLimit(limit)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (quotaState == QuotaState.NEAR_LIMIT) {
                    Text(
                        text = "80% of free daily limit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = UbColors.warning,
                    )
                } else if (quotaState == QuotaState.EXHAUSTED) {
                    Text(
                        text = "Daily limit reached",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = UbColors.error,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (quotaUsed.toFloat() / limit).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = when (quotaState) {
                    QuotaState.OK -> UbColors.success
                    QuotaState.NEAR_LIMIT -> UbColors.warning
                    QuotaState.EXHAUSTED -> UbColors.error
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

private fun formatLimit(limit: Int): String = when {
    limit >= 1_000 -> "${limit / 1_000}k"
    else -> limit.toString()
}

private fun relativeTime(at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        else -> "${mins / 60}h ago"
    }
}
