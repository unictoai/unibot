package ai.unicto.unibot.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.components.openExternalUrl
import ai.unicto.unibot.cloud.AllowanceSignal
import ai.unicto.unibot.cloud.UnibotCloud
import ai.unicto.unibot.sysfiles.SystemFiles
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * Settings → unibot Cloud: the account. Who is signed in (the hint, never the number), the
 * password, the devices holding a key, what was used of the allowance and what is left, what was used
 * by kind (chat, pictures, video, calls) and by model, the account's own history, the provider's
 * pages, and the ways out — this phone, everywhere, or the account itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenProvider: (instanceId: String) -> Unit,
    onOpenModelGroups: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(UnibotCloud.account(context)) }
    var signedIn by remember { mutableStateOf(UnibotCloud.isSignedIn(context)) }
    var sessions by remember { mutableStateOf<List<UnibotCloud.Session>>(emptyList()) }
    var events by remember { mutableStateOf<List<UnibotCloud.Event>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var showToday by remember { mutableStateOf(true) }
    var passwordDialog by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        scope.launch {
            try {
                account = UnibotCloud.refresh(context)
                signedIn = UnibotCloud.isSignedIn(context)
                if (signedIn) {
                    sessions = runCatching { UnibotCloud.sessions(context) }.getOrDefault(sessions)
                    events = runCatching { UnibotCloud.events(context) }.getOrDefault(events)
                }
            } catch (e: Exception) {
                error = UnibotCloud.describe(context, e)
            }
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { if (signedIn) refresh() }

    fun leave() {
        account = null
        signedIn = false
        sessions = emptyList()
        events = emptyList()
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.ub_cloud_title)) },
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
                        Text(stringResource(R.string.ub_cloud_not_signed_in), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.ub_cloud_not_signed_in_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(title = stringResource(R.string.ub_cloud_sign_in), onClick = onSignIn, titleColor = MuseTones.action)
                }
            } else {
                val a = account

                // -- who ---------------------------------------------------------------------
                MuseCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(MuseTones.fill),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (a?.hint?.firstOrNull { it.isLetterOrDigit() } ?: 'n').uppercaseChar().toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MuseTones.action,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(text = a?.hint ?: stringResource(R.string.ub_cloud_title), style = MaterialTheme.typography.titleMedium)
                            val channel = when (a?.channel) {
                                "phone" -> stringResource(R.string.ub_cloud_channel_phone)
                                "email" -> stringResource(R.string.ub_cloud_channel_email)
                                else -> ""
                            }
                            val since = a?.createdAt?.takeIf { it > 0 }?.let {
                                stringResource(R.string.ub_cloud_since, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)))
                            }
                            Text(
                                text = listOfNotNull(channel.takeIf { it.isNotEmpty() }, since).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = stringResource(R.string.ub_cloud_password),
                        icon = Icons.Outlined.Password,
                        value = stringResource(if (a?.hasPassword == true) R.string.ub_cloud_password_set else R.string.ub_cloud_password_unset),
                        onClick = { passwordDialog = true },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.ub_cloud_devices_signed_in),
                        icon = Icons.Outlined.PhoneAndroid,
                        value = (if (sessions.isNotEmpty()) sessions.size else a?.sessions ?: 0).toString(),
                        chevron = false,
                        onClick = { refresh() },
                    )
                    if (a?.accountId?.isNotEmpty() == true) {
                        Text(
                            text = stringResource(R.string.ub_cloud_account_id, a.accountId.take(8)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 10.dp),
                        )
                    }
                }
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MuseTones.action,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }

                // -- allowance ---------------------------------------------------------------
                MuseGap()
                MuseCard {
                    Column(Modifier.padding(16.dp)) {
                        if (a != null && a.pricesInMoney) {
                            // The relay prices requests in money (0.5: one pool for the account's
                            // lifetime): used of the pool in both currencies, what is left, the
                            // token line, and how the pool grows.
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (a.limited) stringResource(R.string.ub_cloud_allowance_used, money(a.spentTotalCny), money(a.toUsd(a.spentTotalCny)))
                                    else stringResource(R.string.ub_cloud_spent_total, money(a.spentTotalCny), money(a.toUsd(a.spentTotalCny))),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = if (a.limited) stringResource(R.string.ub_cloud_allowance_of, money(a.grantCny), money(a.toUsd(a.grantCny)))
                                    else stringResource(R.string.ub_cloud_spend_member),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (a.limited) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { a.spendFraction },
                                    color = when {
                                        a.exhausted -> MaterialTheme.colorScheme.error
                                        a.warn || a.spendFraction >= 0.8f -> Color(0xFFD97706)
                                        else -> MuseTones.action
                                    },
                                    trackColor = MuseTones.fill,
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.ub_cloud_allowance_left, money(a.leftCny.coerceAtLeast(0.0)), money(a.toUsd(a.leftCny.coerceAtLeast(0.0)))),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (a.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                text = stringResource(R.string.ub_cloud_tokens_line, numbers.format(a.used), numbers.format(a.usedToday)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (a.limited && a.allowanceCny > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.ub_cloud_allowance_why, money(a.allowanceCny), money(a.inviteBonusCny), money(a.contributeBonusCny)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (a != null && a.unlimited) {
                            // No ceiling on this relay: what was used, nothing to run out of.
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.ub_cloud_unlimited),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.ub_cloud_used_total, numbers.format(a.used)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.ub_cloud_used_today_open, numbers.format(a.usedToday)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (a != null) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.ub_cloud_remaining, numbers.format(a.remaining)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.ub_cloud_of_granted, numbers.format(a.granted)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { a.fraction },
                                color = MuseTones.action,
                                trackColor = MuseTones.fill,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (a.dailyCap > 0) {
                                    stringResource(R.string.ub_cloud_used_today, numbers.format(a.usedToday), numbers.format(a.dailyCap))
                                } else {
                                    stringResource(R.string.ub_cloud_used_today_open, numbers.format(a.usedToday))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.ub_cloud_refreshing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = if (refreshing) stringResource(R.string.ub_cloud_refreshing) else stringResource(R.string.ub_cloud_refresh),
                        icon = Icons.Outlined.Refresh,
                        chevron = false,
                        onClick = { refresh() },
                    )
                }

                // -- invitations: the code, the link, what came of it -------------------------
                if (a != null && a.inviteCode.isNotBlank()) {
                    MuseSectionLabel(stringResource(R.string.ub_cloud_invite_title))
                    InviteCard(a)
                }

                // -- usage by kind and by model ---------------------------------------------
                val usage = a?.usage
                val priced = a?.pricesInMoney == true
                if (usage != null) {
                    MuseSectionLabel(stringResource(R.string.ub_cloud_usage_title))
                    MuseCard {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .fillMaxWidth()
                                .background(MuseTones.fill, RoundedCornerShape(10.dp))
                                .padding(3.dp),
                        ) {
                            SegmentTab(stringResource(R.string.ub_cloud_usage_today), showToday, Modifier.weight(1f)) { showToday = true }
                            SegmentTab(stringResource(R.string.ub_cloud_usage_total), !showToday, Modifier.weight(1f)) { showToday = false }
                        }
                        val rows = (if (showToday) usage.todayByKind else usage.totalByKind).filter { it.requests > 0 }
                        if (rows.isEmpty()) {
                            Text(
                                text = stringResource(R.string.ub_cloud_usage_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            )
                        } else {
                            rows.forEachIndexed { i, r ->
                                if (i > 0) MuseRowDivider()
                                UsageLine(
                                    icon = kindIcon(r.kind),
                                    title = kindLabel(r.kind),
                                    detail = if (r.tokens > 0) {
                                        stringResource(R.string.ub_cloud_usage_row, r.requests, numbers.format(r.tokens))
                                    } else {
                                        stringResource(R.string.ub_cloud_usage_row_notokens, r.requests)
                                    },
                                    amount = if (priced) "¥" + money(r.costCny) else numbers.format(r.charged),
                                )
                            }
                        }
                        val models = if (showToday) emptyList() else usage.byModel.filter { it.requests > 0 }
                        if (models.isNotEmpty()) {
                            MuseRowDivider(inset = 16.dp)
                            Text(
                                text = stringResource(R.string.ub_cloud_usage_by_model),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                            models.forEach { r ->
                                UsageLine(
                                    icon = null,
                                    title = r.model,
                                    detail = if (r.tokens > 0) {
                                        stringResource(R.string.ub_cloud_usage_row, r.requests, numbers.format(r.tokens))
                                    } else {
                                        stringResource(R.string.ub_cloud_usage_row_notokens, r.requests)
                                    },
                                    amount = if (priced) "¥" + money(r.costCny) else numbers.format(r.charged),
                                    compact = true,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                // -- the three ways on, when the pool is spent or nearly ------------------------
                if (a != null && a.limited && (a.exhausted || a.warn)) {
                    MuseGap()
                    AllowanceWaysCard(
                        info = AllowanceSignal.Exhausted(
                            leftCny = a.leftCny.coerceAtLeast(0.0),
                            grantCny = a.grantCny,
                            inviteUrl = a.inviteUrl,
                            contributeBonusAvailable = a.contributeBonusAvailable,
                            ownKeyDocs = a.ownKeyDocs,
                        ),
                        exhausted = a.exhausted,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onChanged = { account = UnibotCloud.account(context) },
                    )
                }

                // -- the co-creation programme: off by default, the person's own switch ----------
                if (a != null) {
                    MuseSectionLabel(stringResource(R.string.ub_cloud_contribute_title))
                    MuseCard {
                        MuseRow(
                            title = when {
                                a.contribute -> stringResource(R.string.ub_cloud_contribute_on, a.samples)
                                a.contributeBonusAvailable && a.contributeBonusCny > 0 -> stringResource(R.string.ub_cloud_contribute_off_bonus, money(a.contributeBonusCny))
                                else -> stringResource(R.string.ub_cloud_contribute_off)
                            },
                            chevron = false,
                            onClick = {
                                if (busy) return@MuseRow
                                busy = true
                                scope.launch {
                                    try {
                                        val r = UnibotCloud.setContribute(context, !a.contribute)
                                        account = r.account
                                        if (r.bonusGranted) notice = context.getString(R.string.ub_cloud_contribute_joined, money(r.bonusCny))
                                    } catch (e: Exception) { error = UnibotCloud.describe(context, e) }
                                    busy = false
                                }
                            },
                            trailing = {
                                Switch(checked = a.contribute, enabled = !busy, onCheckedChange = { on ->
                                    if (busy) return@Switch
                                    busy = true
                                    scope.launch {
                                        try {
                                            val r = UnibotCloud.setContribute(context, on)
                                            account = r.account
                                            if (r.bonusGranted) notice = context.getString(R.string.ub_cloud_contribute_joined, money(r.bonusCny))
                                        } catch (e: Exception) { error = UnibotCloud.describe(context, e) }
                                        busy = false
                                    }
                                })
                            },
                        )
                        Text(
                            text = stringResource(R.string.ub_cloud_contribute_why),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                        )
                        if (a.samples > 0) {
                            MuseRowDivider(inset = 16.dp)
                            MuseRow(
                                title = stringResource(R.string.ub_cloud_contribute_delete),
                                icon = Icons.Outlined.DeleteOutline,
                                titleColor = MaterialTheme.colorScheme.error,
                                chevron = false,
                                onClick = { confirm = Confirm.DELETE_SAMPLES },
                            )
                        }
                    }
                }

                // -- sign-ins ---------------------------------------------------------------
                if (sessions.isNotEmpty()) {
                    MuseSectionLabel(stringResource(R.string.ub_cloud_devices_signed_in))
                    MuseCard {
                        sessions.forEachIndexed { i, s ->
                            if (i > 0) MuseRowDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (s.device.contains("web", ignoreCase = true)) Icons.Outlined.Language
                                    else if (s.device.contains("mac", true) || s.device.contains("windows", true) || s.device.contains("linux", true)) Icons.Outlined.Computer
                                    else Icons.Outlined.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    val deviceName = s.device.ifBlank { stringResource(R.string.ub_cloud_device_unknown) }
                                    val thisOne = if (s.current) "  ·  " + stringResource(R.string.ub_cloud_this_device) else ""
                                    Text(
                                        text = deviceName + thisOne,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = listOf(
                                            stringResource(if (s.via == "password") R.string.ub_cloud_via_password else R.string.ub_cloud_via_code),
                                            stringResource(
                                                R.string.ub_cloud_last_used,
                                                SystemFiles.relative(context, (if (s.lastUsedAt > 0) s.lastUsedAt else s.createdAt) * 1000),
                                            ),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!s.current) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                try {
                                                    UnibotCloud.revokeSession(context, s.prefix)
                                                    sessions = sessions.filterNot { it.prefix == s.prefix }
                                                } catch (e: Exception) {
                                                    error = UnibotCloud.describe(context, e)
                                                }
                                                busy = false
                                            }
                                        },
                                    ) { Text(stringResource(R.string.ub_cloud_sign_out), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }

                // -- history ----------------------------------------------------------------
                if (events.isNotEmpty()) {
                    MuseSectionLabel(stringResource(R.string.ub_cloud_activity))
                    MuseCard {
                        events.take(12).forEachIndexed { i, e ->
                            if (i > 0) MuseRowDivider(inset = 16.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = eventLabel(e.kind),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (e.kind == "sign_in.failed" || e.kind == "budget.refused" || e.kind == "upstream.error") {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (e.detail.isNotBlank() && !e.kind.startsWith("password")) {
                                        Text(
                                            text = e.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    text = SystemFiles.relative(context, e.ts * 1000),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                MuseGap()
                CommunityNoticeCard()
                MuseGap()
                MuseCard {
                    Text(
                        text = stringResource(R.string.ub_cloud_how_it_works),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = stringResource(R.string.ub_cloud_open_provider),
                        icon = Icons.Outlined.Tune,
                        onClick = { UnibotCloud.instance(context)?.let { onOpenProvider(it.id) } },
                    )
                    MuseRowDivider()
                    MuseRow(title = stringResource(R.string.settings_model_groups), onClick = onOpenModelGroups)
                }
                MuseGap()
                DevicesSection()

                // -- the ways out -----------------------------------------------------------
                MuseGap()
                MuseCard {
                    MuseRow(
                        title = stringResource(R.string.ub_cloud_sign_out_here),
                        icon = Icons.Outlined.Logout,
                        chevron = false,
                        onClick = { confirm = Confirm.SIGN_OUT },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.ub_cloud_sign_out_everywhere),
                        icon = Icons.Outlined.Logout,
                        chevron = false,
                        onClick = { confirm = Confirm.SIGN_OUT_ALL },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.ub_cloud_delete_account),
                        icon = Icons.Outlined.DeleteOutline,
                        chevron = false,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirm = Confirm.DELETE },
                    )
                }
                Text(
                    text = stringResource(R.string.ub_cloud_sign_out_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        when (which) {
                            Confirm.SIGN_OUT -> R.string.ub_cloud_sign_out_here
                            Confirm.SIGN_OUT_ALL -> R.string.ub_cloud_sign_out_everywhere
                            Confirm.DELETE -> R.string.ub_cloud_delete_account
                            Confirm.DELETE_SAMPLES -> R.string.ub_cloud_contribute_delete
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        when (which) {
                            Confirm.SIGN_OUT -> R.string.ub_cloud_sign_out_confirm
                            Confirm.SIGN_OUT_ALL -> R.string.ub_cloud_sign_out_everywhere_confirm
                            Confirm.DELETE -> R.string.ub_cloud_delete_account_confirm
                            Confirm.DELETE_SAMPLES -> R.string.ub_cloud_contribute_delete_confirm
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                when (which) {
                                    Confirm.SIGN_OUT -> { UnibotCloud.signOut(context); leave() }
                                    Confirm.SIGN_OUT_ALL -> { UnibotCloud.signOutEverywhere(context, includingThis = true); leave() }
                                    Confirm.DELETE -> { UnibotCloud.deleteAccount(context); leave() }
                                    Confirm.DELETE_SAMPLES -> {
                                        val n = UnibotCloud.deleteSamples(context)
                                        notice = context.getString(R.string.ub_cloud_contribute_deleted, n)
                                        account = UnibotCloud.account(context)
                                    }
                                }
                            } catch (e: Exception) {
                                error = UnibotCloud.describe(context, e)
                            }
                            busy = false
                            confirm = null
                        }
                    },
                ) {
                    Text(
                        stringResource(if (which == Confirm.DELETE || which == Confirm.DELETE_SAMPLES) R.string.delete else R.string.ub_cloud_sign_out),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (passwordDialog) {
        PasswordDialog(
            hasPassword = account?.hasPassword == true,
            onDismiss = { passwordDialog = false },
            onSaved = { removed ->
                passwordDialog = false
                account = account?.copy(hasPassword = !removed)
                notice = context.getString(if (removed) R.string.ub_cloud_password_removed else R.string.ub_cloud_password_saved)
                refresh()
            },
        )
    }
}

private enum class Confirm { SIGN_OUT, SIGN_OUT_ALL, DELETE, DELETE_SAMPLES }

/** Set, change or remove the password; the relay decides whether the current one is needed. */
@Composable
private fun PasswordDialog(hasPassword: Boolean, onDismiss: () -> Unit, onSaved: (removed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MuseTones.action,
        cursorColor = MuseTones.action,
        focusedLabelColor = MuseTones.action,
    )
    val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()

    fun save(remove: Boolean) {
        if (busy) return
        if (!remove) {
            if (next.length < 8) { error = context.getString(R.string.ub_cloud_err_password_short); return }
            if (next != again) { error = context.getString(R.string.ub_cloud_password_mismatch); return }
        }
        error = null
        busy = true
        scope.launch {
            try {
                UnibotCloud.setPassword(context, if (remove) "" else next, current.takeIf { it.isNotEmpty() })
                onSaved(remove)
            } catch (e: Exception) {
                error = UnibotCloud.describe(context, e)
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (hasPassword) R.string.ub_cloud_password_title_change else R.string.ub_cloud_password_title_set)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ub_cloud_password_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(12.dp))
                if (hasPassword) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it },
                        label = { Text(stringResource(R.string.ub_cloud_password_current)) },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = { Text(stringResource(R.string.ub_cloud_password_new)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null, tint = muted)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it },
                    label = { Text(stringResource(R.string.ub_cloud_password_confirm)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (hasPassword) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { save(remove = true) }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.ub_cloud_password_remove), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(remove = false) }, enabled = !busy && next.isNotEmpty()) {
                Text(stringResource(R.string.save), color = MuseTones.action)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** One line of the usage table: a glyph, the kind, requests · tokens, and what it cost. */
@Composable
private fun UsageLine(icon: ImageVector?, title: String, detail: String, amount: String, compact: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = amount,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One half of a two-way switch inside a card (today / all time). */
@Composable
private fun SegmentTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MuseTones.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "chat" -> Icons.Outlined.ChatBubbleOutline
    "image" -> Icons.Outlined.Image
    "video" -> Icons.Outlined.Videocam
    "realtime" -> Icons.Outlined.Phone
    else -> Icons.Outlined.Tune
}

@Composable
private fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "chat" -> R.string.ub_usage_kind_chat
        "image" -> R.string.ub_usage_kind_image
        "video" -> R.string.ub_usage_kind_video
        "realtime" -> R.string.ub_usage_kind_realtime
        else -> R.string.ub_usage_kind_other
    },
)

@Composable
private fun eventLabel(kind: String): String = when (kind) {
    "account.created" -> stringResource(R.string.ub_cloud_ev_account_created)
    "contribute.on" -> stringResource(R.string.ub_cloud_ev_contribute_on)
    "contribute.off" -> stringResource(R.string.ub_cloud_ev_contribute_off)
    "contribute.deleted" -> stringResource(R.string.ub_cloud_ev_contribute_deleted)
    "invite.accepted" -> stringResource(R.string.ub_cloud_ev_invite_accepted)
    "invite.used" -> stringResource(R.string.ub_cloud_ev_invite_used)
    "credit.granted" -> stringResource(R.string.ub_cloud_ev_credit_granted)
    "sign_in.code" -> stringResource(R.string.ub_cloud_ev_sign_in_code)
    "sign_in.password" -> stringResource(R.string.ub_cloud_ev_sign_in_password)
    "sign_in.failed" -> stringResource(R.string.ub_cloud_ev_sign_in_failed)
    "password.set" -> stringResource(R.string.ub_cloud_ev_password_set)
    "password.changed" -> stringResource(R.string.ub_cloud_ev_password_changed)
    "password.cleared" -> stringResource(R.string.ub_cloud_ev_password_cleared)
    "sign_out" -> stringResource(R.string.ub_cloud_ev_sign_out)
    "sign_out.all" -> stringResource(R.string.ub_cloud_ev_sign_out_all)
    "budget.refused" -> stringResource(R.string.ub_cloud_ev_budget_refused)
    "upstream.error" -> stringResource(R.string.ub_cloud_ev_upstream_error)
    else -> kind
}

/** ¥ / $ amounts: whole numbers above a hundred, cents otherwise, fen-fractions for the tiny ones. */
internal fun money(v: Double): String = when {
    v >= 100 -> String.format(java.util.Locale.US, "%.0f", v)
    v >= 1 -> String.format(java.util.Locale.US, "%.2f", v)
    v > 0 && v < 0.01 -> String.format(java.util.Locale.US, "%.4f", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
}

/**
 * Invite a friend: the account's code and link (each new sign-up with it adds to the pool),
 * how many came and what they added.
 */
@Composable
private fun InviteCard(a: UnibotCloud.Account) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    val relayBase = UnibotCloud.baseUrl(context).trimEnd('/')
    val link = a.inviteUrl.ifBlank { if (relayBase.isNotBlank()) "$relayBase/web/?invite=${a.inviteCode}" else "" }
    MuseCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ub_cloud_invite_why, money(a.inviteBonusCny)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ub_cloud_invite_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.inviteCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                }
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(a.inviteCode)); copied = true }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (copied) R.string.ub_cloud_invite_copied else R.string.ub_cloud_invite_copy))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val text = context.getString(R.string.ub_cloud_invite_share_text, a.inviteCode, link)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.ub_cloud_invite_share)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ub_cloud_invite_share), fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            val facts = buildList {
                add(stringResource(R.string.ub_cloud_invite_count, a.invites))
                if (a.inviteEarnedCny > 0) add(stringResource(R.string.ub_cloud_invite_earned, money(a.inviteEarnedCny)))
            }
            Text(
                text = facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The notice on the site, with the whole story: who pays, what is kept, how to help. */
const val NOTICE_URL = "https://github.com/unictoai/unibot#readme"

/**
 * The community notice: unibot is free, open source and non-profit; who pays; what the relay
 * keeps; and the invitation to file issues and pull requests — with the repository one tap
 * away. The title opens the same notice on the site. Shown on the account page, the sign-in
 * screen and (in short) the first-run screen.
 */
@Composable
fun CommunityNoticeCard(inset: Dp = 16.dp) {
    val context = LocalContext.current
    MuseCard(inset = inset) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().clickable { openExternalUrl(context, NOTICE_URL) },
            ) {
                Icon(Icons.Outlined.Code, contentDescription = null, tint = MuseTones.action, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ub_cloud_notice_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(14.dp))
            }
            Text(
                stringResource(R.string.ub_cloud_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.ub_cloud_notice_closing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = { openExternalUrl(context, "https://github.com/unictoai/unibot") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ub_notice_github))
                }
                TextButton(onClick = { openExternalUrl(context, "https://github.com/unictoai/unibot/issues/new/choose") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ub_notice_issue))
                }
            }
        }
    }
}
