package ai.unicto.unibot.ui.marketplace

import androidx.compose.material3.ExperimentalMaterial3Api
import ai.unicto.unibot.R
import ai.unicto.unibot.data.repository.MCPRepository
import ai.unicto.unibot.data.repository.SkillRepository
import ai.unicto.unibot.marketplace.MarketplaceCatalog
import ai.unicto.unibot.marketplace.MarketplaceEntry
import ai.unicto.unibot.marketplace.MarketplaceEntryType
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent/skills marketplace (P8) — a directory of community agent configs and
 * skills, under Settings.
 *
 * Sources: the bundled offline catalog plus an optional custom catalog URL
 * (fetched only when the person taps Load — never automatically). Installing
 * writes into the existing storage: skills via [SkillRepository] (the same
 * path as a manual SKILL.md import, so they show up under Settings → Skills
 * with enable/disable), MCP servers via [MCPRepository] (Settings → MCP).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    skillRepository: SkillRepository,
    mcpRepository: MCPRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val bundled = remember { MarketplaceCatalog.loadBundled(context) }
    var remoteEntries by remember { mutableStateOf<List<MarketplaceEntry>>(emptyList()) }
    var customUrl by remember { mutableStateOf(MarketplaceCatalog.customCatalogUrl(context).orEmpty()) }
    var loadingRemote by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }

    val installedSkills by skillRepository.skills.collectAsState()
    val installedMcps by mcpRepository.servers.collectAsState()

    val all = remember(bundled, remoteEntries) { bundled + remoteEntries }
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) all else all.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q) ||
                it.tags.any { t -> t.lowercase().contains(q) } || it.author.lowercase().contains(q)
        }
    }

    fun installedSkillFor(entry: MarketplaceEntry): SkillRepository.Skill? =
        installedSkills.firstOrNull { it.id == entry.id || it.name.equals(entry.name, ignoreCase = true) }

    fun installedMcpFor(entry: MarketplaceEntry): MCPRepository.MCPServerConfig? =
        installedMcps.firstOrNull { it.id == entry.id }

    fun install(entry: MarketplaceEntry) {
        if (installingId != null) return
        installingId = entry.id
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (entry.type) {
                        MarketplaceEntryType.SKILL -> {
                            val body = entry.skillBody
                                ?: throw IllegalStateException("This entry has no skill content")
                            val skill = skillRepository.importFromContent(
                                body,
                                SkillRepository.ImportSource.FILE,
                                entry.sourceUrl,
                            ) ?: throw IllegalStateException("Could not parse the skill")
                            skillRepository.setEnabled(skill.id, true)
                        }
                        MarketplaceEntryType.MCP -> {
                            val ok = mcpRepository.add(
                                MCPRepository.MCPServerConfig(
                                    id = entry.id,
                                    note = entry.description.take(200),
                                    url = entry.mcpUrl,
                                    command = entry.mcpCommand,
                                    args = entry.mcpArgs,
                                    env = entry.mcpEnv,
                                ),
                            )
                            if (!ok) throw IllegalStateException("A server with this id already exists")
                        }
                    }
                }
            } catch (t: Throwable) {
                error = context.getString(R.string.ub_market_install_failed, t.message ?: t.javaClass.simpleName)
            }
            installingId = null
        }
    }

    fun loadRemoteCatalog() {
        val url = customUrl.trim()
        if (url.isBlank() || loadingRemote) return
        loadingRemote = true
        remoteError = null
        scope.launch {
            try {
                val entries = MarketplaceCatalog.loadRemote(url)
                MarketplaceCatalog.setCustomCatalogUrl(context, url)
                remoteEntries = entries
            } catch (t: Throwable) {
                remoteError = context.getString(
                    R.string.ub_market_load_failed,
                    t.message ?: t.javaClass.simpleName,
                )
            }
            loadingRemote = false
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.ub_market_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.ub_market_search_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (filtered.isEmpty()) {
                item {
                    MuseCaption(stringResource(R.string.ub_market_empty, query.ifBlank { "…" }))
                }
            }

            items(filtered, key = { it.id }) { entry ->
                val expanded = expandedId == entry.id
                MuseCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedId = if (expanded) null else entry.id }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (entry.type == MarketplaceEntryType.SKILL) Icons.Outlined.Extension else Icons.Outlined.Dashboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.ub_market_by, entry.author, entry.version),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TypeChip(entry)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                        )

                        AnimatedVisibility(visible = expanded) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                if (entry.tags.isNotEmpty()) {
                                    Text(
                                        entry.tags.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(
                                    stringResource(
                                        if (entry.type == MarketplaceEntryType.SKILL) R.string.ub_market_what_skill
                                        else R.string.ub_market_what_mcp,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                EntryAction(
                                    entry = entry,
                                    installedSkill = installedSkillFor(entry),
                                    installedMcp = installedMcpFor(entry),
                                    installing = installingId == entry.id,
                                    onInstall = { install(entry) },
                                    onSetSkillEnabled = { skill, on -> skillRepository.setEnabled(skill.id, on) },
                                    onSetMcpEnabled = { server, on -> mcpRepository.setEnabled(server.id, on) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                MuseGap()
                MuseSectionLabel(stringResource(R.string.ub_market_catalog_title))
                MuseCard() {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.ub_market_bundled, bundled.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (remoteEntries.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.ub_market_loaded, remoteEntries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ub_market_custom_url)) },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        UnibotButton(
                            onClick = ::loadRemoteCatalog,
                            enabled = !loadingRemote && customUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ub_market_load))
                        }
                        remoteError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                MuseCaption(stringResource(R.string.ub_market_custom_note))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TypeChip(entry: MarketplaceEntry) {
    val label = stringResource(
        if (entry.type == MarketplaceEntryType.SKILL) R.string.ub_market_skill else R.string.ub_market_mcp,
    ) + if (entry.bundled) " · " + stringResource(R.string.ub_market_builtin) else ""
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun EntryAction(
    entry: MarketplaceEntry,
    installedSkill: SkillRepository.Skill?,
    installedMcp: MCPRepository.MCPServerConfig?,
    installing: Boolean,
    onInstall: () -> Unit,
    onSetSkillEnabled: (SkillRepository.Skill, Boolean) -> Unit,
    onSetMcpEnabled: (MCPRepository.MCPServerConfig, Boolean) -> Unit,
) {
    when {
        installedSkill != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ub_market_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ub_market_enable_toggle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = installedSkill.isEnabled,
                    onCheckedChange = { onSetSkillEnabled(installedSkill, it) },
                )
            }
        }
        installedMcp != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ub_market_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ub_market_enable_toggle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = installedMcp.enabled,
                    onCheckedChange = { onSetMcpEnabled(installedMcp, it) },
                )
            }
        }
        else -> {
            UnibotTextButton(onClick = onInstall, enabled = !installing) {
                Text(
                    if (installing) stringResource(R.string.ub_market_installing)
                    else stringResource(R.string.ub_market_install),
                )
            }
        }
    }
}
