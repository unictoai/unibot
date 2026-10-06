package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudQueue // unibot: unibot Cloud row
import androidx.compose.material.icons.outlined.PhoneAndroid // unibot: on-device models row
import androidx.compose.material.icons.outlined.PlayArrow // unibot: YouTube dashboard row
import androidx.compose.material.icons.outlined.Computer // unibot: Computers row
import androidx.compose.material.icons.outlined.TouchApp // unibot: Hands row
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices // P4: pairing row (v0.2.0)
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.HelpOutline // v1.4.0-onboarding help center row
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key // v1.4.0-onboarding guided key setup row
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic // [unibot-voice-conversation]
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Route // P4: smart routing row (v0.2.0)
import androidx.compose.material.icons.outlined.Refresh // v1.4.0-onboarding retired-model check row
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.WifiOff // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.NoPhotography // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.ContentPasteOff // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.AutoDelete // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.FactCheck // [v1.0-wave5-privacy]
import androidx.compose.material.icons.outlined.Storefront // P8: marketplace row
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.CameraAlt // unibot P6: visual ask row
import androidx.compose.material.icons.outlined.CompareArrows // unibot P2: compare row
import androidx.compose.material.icons.outlined.LibraryBooks // unibot P2: prompt library row
import androidx.compose.material.icons.outlined.RecordVoiceOver // unibot P6: read aloud row
import androidx.compose.material.icons.outlined.AutoFixHigh // unibot P6: autofill row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog // [v1.0-wave5-privacy]
import androidx.compose.material3.TextButton // [v1.0-wave5-privacy]
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch // P4: smart routing toggle (v0.2.0)
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState // unibot
import androidx.compose.runtime.collectAsState // unibot: the hub's device list for the Coding row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.BuildConfig
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.components.openExternalUrl
import ai.unicto.unibot.ui.theme.staggeredEntrance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onRootfsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    // [unibot-connectors] Connectors page (Gmail etc.). Default no-op for
    // callers that haven't wired the route yet.
    onConnectorsClick: () -> Unit = {},
    // P8: the agent/skills marketplace directory. Default no-op for callers
    // that haven't wired the route yet.
    onMarketplaceClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    onSystemFilesClick: () -> Unit = {}, // unibot
    onAvatarClick: () -> Unit = {}, // unibot
    onMediaModelsClick: () -> Unit = {}, // unibot: Settings → Image & video models
    onOnDeviceModelsClick: () -> Unit = {}, // unibot: Settings → On-device models (offline LLM)
    onFactMemoriesClick: () -> Unit = {}, // unibot: Settings → Saved memories (v0.5.0 "remember that …")
    onYouTubeDashboardClick: () -> Unit = {}, // unibot: Settings → YouTube dashboard (v1.0 creator tools)
    onCloudClick: () -> Unit = {}, // unibot: Settings → unibot Cloud (the starter allowance)
    onHandsClick: () -> Unit = {}, // unibot: Settings → Hands (the screen as a hand)
    onComputersClick: () -> Unit = {}, // unibot: Settings → Computers (the phone drives a PC)
    onCodingClick: () -> Unit = {}, // unibot: Settings → Coding agents (Cursor/Codex/Claude Code on the account's computers)
    onPairingClick: () -> Unit = {}, // P4: Settings → Pair a computer (v0.2.0)
    onProjectsClick: () -> Unit = {}, // unibot P6: Settings → Projects (chats + files + instructions bundles)
    onVisualAskClick: () -> Unit = {}, // unibot P6: Settings → Ask about camera (visual context)
    onReadAloudClick: () -> Unit = {}, // unibot P6: Settings → Read aloud (spoken replies, TTS voices)
    onVoiceConversationClick: () -> Unit = {}, // [unibot-voice-conversation] Settings → Voice conversation
    onAutofillClick: () -> Unit = {}, // unibot P6: Settings → Autofill (opt-in, on-device)
    onPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    // [P1-app-lock] Settings → App lock. Default no-op for callers that
    // haven't wired the route yet.
    onAppLockClick: () -> Unit = {},
    // Hook accepted for forward-compat with AppNavigation's About route. The
    // About row below still has a TODO onClick in HEAD; future settings-bucket
    // work will wire this through.
    onAboutClick: () -> Unit = {},
    // v1.4.0-onboarding Help center (offline FAQ), the BYOK setup wizard,
    // and the retired-model live check. Default no-op for callers that
    // haven't wired the routes yet.
    onHelpClick: () -> Unit = {},
    onByokWizardClick: () -> Unit = {},
    onModelCheckClick: () -> Unit = {},
    // [P2] Prompt Library (presets + composer modes) entry. Default no-op
    // for back-compat with callers wired before P2.
    onPromptLibraryClick: () -> Unit = {},
    // [P2] Compare Models (side-by-side) entry. Default no-op, same reason.
    onCompareClick: () -> Unit = {},
    // [v1.0-wave5-privacy] Privacy screens. Default no-op for callers that
    // haven't wired the routes yet.
    onPrivacyDashboardClick: () -> Unit = {},
    onTrafficLogClick: () -> Unit = {},
    onPermissionAuditClick: () -> Unit = {},
    // [v1.2] New screens. Default no-op for callers that haven't wired yet.
    onBenchmarkClick: () -> Unit = {},
    onSamplerClick: () -> Unit = {},
    onChatTemplatesClick: () -> Unit = {},
    onScheduledMessagesClick: () -> Unit = {},
    onVoiceHistoryClick: () -> Unit = {},
    onStorageBreakdownClick: () -> Unit = {},
    onScheduledBackupClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var showFeedbackSheet by remember { mutableStateOf(false) }
    // [v1.0-wave5-privacy] Privacy toggles. localOnly/screenshotBlock are
    // hot StateFlows, so the rows flip the moment the setting changes
    // anywhere; the rest re-read on composition.
    val privacyLocalOnly by ai.unicto.unibot.privacy.PrivacyPrefs.localOnly.collectAsState()
    val privacyScreenshotBlock by ai.unicto.unibot.privacy.PrivacyPrefs.screenshotBlock.collectAsState()
    var privacyClipboardClear by remember {
        mutableStateOf(ai.unicto.unibot.privacy.PrivacyPrefs.clipboardAutoClear)
    }
    var privacyAutoDeleteHours by remember {
        mutableStateOf(ai.unicto.unibot.privacy.PrivacyPrefs.autoDeleteDefaultHours)
    }
    var showAutoDeletePicker by remember { mutableStateOf(false) }
    val privacyHaptics = ai.unicto.unibot.ui.util.rememberHaptic()
    // unibot: Muse's settings page — the round back glyph, the title centred,
    // white cards of outlined-glyph rows on the grey canvas, no section headers
    // or subtitles. Every OpenMinis entry is kept; they are regrouped the way
    // Muse groups its own (the agent, the data on the phone, the app, about).
    // The card at the top stands where Muse's plan card stands and shows the
    // model the agent talks to, now that the home header no longer does.
    val providerRepo = (context.applicationContext as? ai.unicto.unibot.UnibotApp)?.providerRepositoryOrNull
    val providerConfig = providerRepo?.config?.collectAsState()?.value
    val defaultGroup = providerConfig?.let { cfg -> cfg.modelGroups.firstOrNull { it.id == cfg.defaultPrimaryGroupId } ?: cfg.modelGroups.firstOrNull() }
    val firstEntry = providerConfig?.let { cfg -> defaultGroup?.memberEntryIds?.firstNotNullOfOrNull { id -> cfg.modelEntries.firstOrNull { it.id == id } } }
    val firstInstance = providerConfig?.instances?.firstOrNull { it.id == firstEntry?.providerInstanceId }
    val modelLine = listOfNotNull(firstInstance?.label, firstEntry?.model?.displayName).joinToString(" · ")
    Scaffold(
        containerColor = ai.unicto.unibot.ui.home.MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
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

            // -- The model (Muse: the plan card) --
            // [v0.4.0-premium-feel] Cards stagger in on first composition.
            ai.unicto.unibot.ui.muse.MuseCard(modifier = Modifier.staggeredEntrance(0)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onModelGroupsClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = defaultGroup?.name ?: stringResource(R.string.ub_settings_no_model),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (defaultGroup == null) stringResource(R.string.ub_settings_no_model_sub)
                                else modelLine.ifEmpty { stringResource(R.string.settings_model_groups) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.ub_settings_change_model),
                        style = MaterialTheme.typography.labelLarge,
                        color = ai.unicto.unibot.ui.home.MuseTones.action,
                    )
                }
                ai.unicto.unibot.ui.muse.MuseRowDivider(inset = 16.dp)
                // unibot: the relay account — signed in shows what is left of the allowance.
                val cloudAccount = ai.unicto.unibot.cloud.UnibotCloud.account(context)
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.ub_cloud_title),
                    icon = Icons.Outlined.CloudQueue,
                    value = when {
                        cloudAccount == null || !ai.unicto.unibot.cloud.UnibotCloud.isSignedIn(context) -> stringResource(R.string.ub_cloud_row_sign_in)
                        // Money relays: today's spend; the hint carries who is signed in.
                        cloudAccount.pricesInMoney -> cloudAccount.hint + " · ¥" + ai.unicto.unibot.ui.cloud.money(cloudAccount.spentTodayCny)
                        cloudAccount.unlimited -> cloudAccount.hint
                        else -> stringResource(R.string.ub_cloud_row_remaining, java.text.NumberFormat.getIntegerInstance().format(cloudAccount.remaining))
                    },
                    onClick = onCloudClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_manage_providers),
                    icon = Icons.Outlined.Lock,
                    onClick = onProvidersClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // v1.4.0-onboarding BYOK setup wizard: free-tier suggestions + live key validation.
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_guided_key_setup),
                    icon = Icons.Outlined.Key,
                    onClick = onByokWizardClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // v1.4.0-onboarding Retired-model live check against provider catalogs.
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_model_check),
                    icon = Icons.Outlined.Refresh,
                    onClick = onModelCheckClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot: the image and video models, which Muse has built in and we set ourselves.
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.ub_media_title),
                    icon = Icons.Outlined.Movie,
                    value = if (ai.unicto.unibot.media.MediaModels.imageEndpoint(context) == null) stringResource(R.string.ub_media_not_set) else null,
                    onClick = onMediaModelsClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot: the offline LLM — opt-in downloads, works in airplane mode.
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = "On-device models",
                    icon = Icons.Outlined.PhoneAndroid,
                    onClick = onOnDeviceModelsClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] On-device model speed benchmark.
                ai.unicto.unibot.ui.local.LocalAiBenchmarkRow(onClick = onBenchmarkClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Sampler settings (temperature, top-p, …) per on-device model.
                ai.unicto.unibot.ui.local.LocalAiSamplerRow(onClick = onSamplerClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_token_usage),
                    icon = Icons.Outlined.BarChart,
                    onClick = onUsageClick,
                )
            }
            ai.unicto.unibot.ui.muse.MuseGap()

            // -- The agent --
            ai.unicto.unibot.ui.muse.MuseCard(modifier = Modifier.staggeredEntrance(1)) {
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_soul), icon = Icons.Outlined.AutoAwesome, onClick = onSoulClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_avatar_title), icon = Icons.Outlined.Face, onClick = onAvatarClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_memory), icon = Icons.Outlined.Psychology, onClick = onMemoryClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot v0.5.0: "remember that …" facts — encrypted on-device.
                ai.unicto.unibot.ui.muse.MuseRow(title = "Saved memories", icon = Icons.Outlined.LibraryBooks, onClick = onFactMemoriesClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot P6: project workspaces — chats + files + custom instructions bundles.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_projects_title), icon = Icons.Outlined.Folder, onClick = onProjectsClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_sysfiles_title), icon = Icons.Outlined.Description, onClick = onSystemFilesClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_skills), icon = Icons.Outlined.Extension, onClick = onSkillsClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [P2] Prompt Library: text presets + composer modes (Study Mode…).
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_prompt_library_title), icon = Icons.Outlined.LibraryBooks, onClick = onPromptLibraryClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [P2] Compare Models: same prompt, two models, side by side.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_compare_title), icon = Icons.Outlined.CompareArrows, onClick = onCompareClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Chat templates / prompt starters.
                ai.unicto.unibot.ui.chat.ChatTemplatesRow(onClick = onChatTemplatesClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Scheduled messages (send later).
                ai.unicto.unibot.ui.chat.ScheduledMessagesRow(onClick = onScheduledMessagesClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_mcp), icon = Icons.Outlined.Dashboard, onClick = onMcpClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [unibot-connectors] Connectors — Gmail etc.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_connectors_title), icon = Icons.Outlined.Email, onClick = onConnectorsClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.0-wave4] Creator dashboard — channel stats + latest uploads.
                ai.unicto.unibot.ui.muse.MuseRow(title = "YouTube dashboard", icon = Icons.Outlined.PlayArrow, onClick = onYouTubeDashboardClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // P8: marketplace — community skills and MCP servers.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_market_title), icon = Icons.Outlined.Storefront, onClick = onMarketplaceClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot: the phone's screen as a hand — off by default.
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.ub_hands_title),
                    icon = Icons.Outlined.TouchApp,
                    value = stringResource(if (ai.unicto.unibot.hands.Hands.enabled(context)) R.string.ub_hands_on else R.string.ub_hands_off),
                    onClick = onHandsClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot: the account's computers, over the hub.
                run {
                    val hubDevices by ai.unicto.unibot.hub.Hub.devices.collectAsState()
                    val me = remember { ai.unicto.unibot.hub.Hub.deviceId(context) }
                    val computers = hubDevices.count { it.id != me && it.isComputer }
                    ai.unicto.unibot.ui.muse.MuseRow(
                        title = stringResource(R.string.ub_pc_title),
                        icon = Icons.Outlined.Computer,
                        value = if (computers == 0) stringResource(R.string.ub_pc_none_short) else computers.toString(),
                        onClick = onComputersClick,
                    )
                }
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot: the coding agents on the account's computers, over the hub.
                run {
                    val hubDevices by ai.unicto.unibot.hub.Hub.devices.collectAsState()
                    val me = remember { ai.unicto.unibot.hub.Hub.deviceId(context) }
                    val withCoding = hubDevices.count { it.id != me && it.online && it.actions.contains("coding.sessions") }
                    ai.unicto.unibot.ui.muse.MuseRow(
                        title = stringResource(R.string.ub_coding_title),
                        icon = Icons.Outlined.Terminal,
                        value = if (withCoding == 0) stringResource(R.string.ub_pc_none_short) else withCoding.toString(),
                        onClick = onCodingClick,
                    )
                }
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_env_vars), icon = Icons.Outlined.Terminal, onClick = onEnvVarsClick)
                // P4 (v0.2.0): phone-to-desktop pairing + smart routing.
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                run {
                    val paired = remember { ai.unicto.unibot.ui.pairing.P4PairingStore.remoteSessions(context).size }
                    ai.unicto.unibot.ui.muse.MuseRow(
                        title = "Pair a computer",
                        icon = Icons.Outlined.Devices,
                        value = if (paired == 0) "Not paired" else "$paired paired",
                        onClick = onPairingClick,
                    )
                }
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                run {
                    var smart by remember { mutableStateOf(ai.unicto.unibot.ui.chat.agentic.P4ModeStore.isSmartRouting(context)) }
                    ai.unicto.unibot.ui.muse.MuseRow(
                        title = "Smart routing",
                        icon = Icons.Outlined.Route,
                        value = if (smart) "On" else "Off",
                        chevron = false,
                        trailing = {
                            Switch(
                                checked = smart,
                                onCheckedChange = {
                                    smart = it
                                    ai.unicto.unibot.ui.chat.agentic.P4ModeStore.setSmartRouting(context, it)
                                },
                            )
                        },
                        onClick = {
                            smart = !smart
                            ai.unicto.unibot.ui.chat.agentic.P4ModeStore.setSmartRouting(context, smart)
                        },
                    )
                }
            }
            ai.unicto.unibot.ui.muse.MuseGap()

            // -- The phone: what the agent may touch, where its files live --
            ai.unicto.unibot.ui.muse.MuseCard(modifier = Modifier.staggeredEntrance(2)) {
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_section_permissions), icon = Icons.Outlined.Shield, onClick = onPermissionsClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.bg_section_header), icon = Icons.Outlined.BatteryFull, onClick = onBackgroundClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_section_storage), icon = Icons.Outlined.Inventory2, onClick = onRootfsClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_shared_folders), icon = Icons.Outlined.Folder, onClick = onSharedFoldersClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_mount_external_folders), icon = Icons.Outlined.FolderShared, onClick = onMountedFoldersClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_backup_restore), icon = Icons.Outlined.Backup, onClick = onBackupClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Scheduled local backup (daily/weekly, on-device only).
                ai.unicto.unibot.ui.settings.ScheduledBackupRow(onClick = onScheduledBackupClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Storage breakdown by category.
                ai.unicto.unibot.ui.settings.StorageBreakdownRow(onClick = onStorageBreakdownClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Battery saver (reduces animations + background work).
                ai.unicto.unibot.ui.settings.BatterySaverRow()
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot P6: visual context — capture a photo and ask the chat about it.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_visual_ask_title), icon = Icons.Outlined.CameraAlt, onClick = onVisualAskClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // unibot P6: autofill from the on-device profile — off by default, opt-in.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_autofill_title), icon = Icons.Outlined.AutoFixHigh, onClick = onAutofillClick)
            }
            ai.unicto.unibot.ui.muse.MuseGap()

            // -- Privacy (v1.0 wave 5): dashboard, traffic log, local-only, guards --
            ai.unicto.unibot.ui.muse.MuseCard(modifier = Modifier.staggeredEntrance(3)) {
                ai.unicto.unibot.ui.muse.MuseRow(title = "Privacy dashboard", icon = Icons.Outlined.Shield, onClick = onPrivacyDashboardClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = "Network traffic log", icon = Icons.Outlined.SwapHoriz, onClick = onTrafficLogClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = "Local-only mode",
                    icon = Icons.Outlined.WifiOff,
                    chevron = false,
                    value = if (privacyLocalOnly) "On" else "Off",
                    trailing = {
                        Switch(
                            checked = privacyLocalOnly,
                            onCheckedChange = {
                                privacyHaptics.toggle()
                                ai.unicto.unibot.privacy.PrivacyPrefs.setLocalOnly(it)
                            },
                        )
                    },
                    onClick = {
                        privacyHaptics.toggle()
                        ai.unicto.unibot.privacy.PrivacyPrefs.setLocalOnly(!privacyLocalOnly)
                    },
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = "Block screenshots",
                    icon = Icons.Outlined.NoPhotography,
                    chevron = false,
                    value = if (privacyScreenshotBlock) "On" else "Off",
                    trailing = {
                        Switch(
                            checked = privacyScreenshotBlock,
                            onCheckedChange = {
                                privacyHaptics.toggle()
                                ai.unicto.unibot.privacy.PrivacyPrefs.setScreenshotBlock(it)
                            },
                        )
                    },
                    onClick = {
                        privacyHaptics.toggle()
                        ai.unicto.unibot.privacy.PrivacyPrefs.setScreenshotBlock(!privacyScreenshotBlock)
                    },
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = "Auto-clear clipboard",
                    icon = Icons.Outlined.ContentPasteOff,
                    chevron = false,
                    value = if (privacyClipboardClear) "On" else "Off",
                    trailing = {
                        Switch(
                            checked = privacyClipboardClear,
                            onCheckedChange = {
                                privacyHaptics.toggle()
                                privacyClipboardClear = it
                                ai.unicto.unibot.privacy.PrivacyPrefs.clipboardAutoClear = it
                            },
                        )
                    },
                    onClick = {
                        privacyHaptics.toggle()
                        privacyClipboardClear = !privacyClipboardClear
                        ai.unicto.unibot.privacy.PrivacyPrefs.clipboardAutoClear = privacyClipboardClear
                    },
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = "Auto-delete chats",
                    icon = Icons.Outlined.AutoDelete,
                    value = privacyAutoDeleteShortLabel(privacyAutoDeleteHours),
                    onClick = { showAutoDeletePicker = true },
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = "Permission audit", icon = Icons.Outlined.FactCheck, onClick = onPermissionAuditClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Auto-lock timer (incl. Never).
                ai.unicto.unibot.ui.privacy.V12AutoLockRow(onAppLockClick = onAppLockClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Password-protect chat exports (AES-256).
                ai.unicto.unibot.ui.privacy.V12ExportPasswordRow()
            }
            ai.unicto.unibot.ui.muse.MuseGap()

            // -- The app --
            ai.unicto.unibot.ui.muse.MuseCard(modifier = Modifier.staggeredEntrance(4)) {
                // unibot P6: spoken replies — on-device TTS voices, engine status, speed.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_read_aloud_title), icon = Icons.Outlined.RecordVoiceOver, onClick = onReadAloudClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [unibot-voice-conversation] full-screen voice mode settings.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.ub_voice_title), icon = Icons.Outlined.Mic, onClick = onVoiceConversationClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [v1.2] Voice conversation history.
                ai.unicto.unibot.ui.voice.VoiceHistoryRow(onOpenVoiceHistory = onVoiceHistoryClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_section_appearance), icon = Icons.Outlined.Palette, onClick = onAppearanceClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // [P1-app-lock] Biometric / device-credential gate on foreground.
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_app_lock), icon = Icons.Outlined.Lock, onClick = onAppLockClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_section_logs), icon = Icons.Outlined.Description, onClick = onLogsClick)
            }
            ai.unicto.unibot.ui.muse.MuseGap()

            // -- About --
            ai.unicto.unibot.ui.muse.MuseCard(modifier = Modifier.staggeredEntrance(5)) {
                ai.unicto.unibot.ui.muse.MuseRow(title = stringResource(R.string.settings_about_unibot), icon = Icons.Outlined.Info, onClick = onAboutClick)
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                // v1.4.0-onboarding In-app help center: searchable offline FAQ and guides.
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_help_center),
                    icon = Icons.Outlined.HelpOutline,
                    onClick = onHelpClick,
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_privacy_policy),
                    icon = Icons.Outlined.FrontHand,
                    // iOS canonical URL — ContentView.swift / AddProviderView.swift
                    onClick = { openExternalUrl(context, "https://github.com/unictoai/unibot/blob/main/docs/privacy.md") },
                )
                ai.unicto.unibot.ui.muse.MuseRowDivider()
                ai.unicto.unibot.ui.muse.MuseRow(
                    title = stringResource(R.string.settings_feedback),
                    icon = Icons.Outlined.Feedback,
                    // unibot: GitHub Issues is the one feedback channel (no
                    // Telegram group, no mailbox), so skip the chooser sheet.
                    onClick = { openExternalUrl(context, buildBugReportUrl()) },
                )
            }
            ai.unicto.unibot.ui.muse.MuseCaption(
                text = stringResource(R.string.ub_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // [v1.0-wave5-privacy] Default auto-delete window picker. The choice is
    // the GLOBAL default; a per-chat override (chat "…" menu) wins for its
    // chat. Deleting here is permanent — the sweep runs on app start.
    if (showAutoDeletePicker) {
        val options = listOf(
            0L to "Never",
            1L to "1 hour",
            24L to "1 day",
            168L to "1 week",
            // Privacy item 60 — the backlog's retention policy: 30/90 days or never.
            720L to "30 days",
            2160L to "90 days",
        )
        AlertDialog(
            onDismissRequest = { showAutoDeletePicker = false },
            title = { Text("Auto-delete chats") },
            text = {
                Column {
                    Text(
                        text = "Chats older than this are deleted when the app starts. " +
                            "A per-chat choice in the chat menu overrides this.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    options.forEach { (hours, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    privacyHaptics.tap()
                                    privacyAutoDeleteHours = hours
                                    ai.unicto.unibot.privacy.PrivacyPrefs.autoDeleteDefaultHours = hours
                                    showAutoDeletePicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (privacyAutoDeleteHours == hours) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoDeletePicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(onDismissRequest = { showFeedbackSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_submit_github_issues),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = stringResource(R.string.settings_feedback_telegram),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, "https://t.me/+2NzhOJuzRyI1YmM1")
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.settings_feedback_email),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildFeedbackMailto())
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/OpenMinis/OpenMinis/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | unibot Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    // unibot: the repository uses an issue form (app_bug_report.yml), which
    // takes its fields as query parameters; `body` is ignored by forms.
    val version = java.net.URLEncoder.encode("$versionName ($versionCode)", "UTF-8")
    val device = java.net.URLEncoder.encode("Android $osVersion (API $sdkInt), $manufacturer $model", "UTF-8")
    return "https://github.com/unictoai/unibot/issues/new" +
        "?template=app_bug_report.yml" +
        "&title=$title" +
        "&version=$version" +
        "&device=$device" +
        "&body=$encodedBody"
}

/**
 * [v1.0-wave5-privacy] Short value label for the "Auto-delete chats" row:
 * Never / 1h / 1d / 1w, falling back to "<n>h" for odd stored values.
 */
private fun privacyAutoDeleteShortLabel(hours: Long): String = when (hours) {
    0L -> "Never"
    1L -> "1h"
    24L -> "1d"
    168L -> "1w"
    else -> "${hours}h"
}

/**
 * Compose a `mailto:` URL with a prefilled subject and body that include
 * app version, Android version, and device model. Mirrors iOS
 * `ContentView.makeFeedbackEmailURL()`.
 */
private fun buildFeedbackMailto(): String {
    val body = """
        Please describe your feedback:


        ---
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
        Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}

        Screenshot (optional): Please attach a screenshot if relevant.
    """.trimIndent()
    val subject = java.net.URLEncoder.encode("unibot Feedback", "UTF-8")
    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    return "mailto:dev@openminis.app?subject=$subject&body=$encodedBody"
}


