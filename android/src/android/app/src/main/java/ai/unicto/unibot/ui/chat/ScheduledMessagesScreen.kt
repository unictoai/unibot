package ai.unicto.unibot.ui.chat

// [v12-B] Scheduled messages: compose a message + pick date/time → unibot
// sends it into the chat later via the existing alarm infra (see
// [ScheduledMessagesViewModel]). On-device only; the alarm survives reboot
// through AlarmReceiver's BOOT_COMPLETED re-registration.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.unicto.unibot.R
import ai.unicto.unibot.scheduled.ScheduledTargetMode
import ai.unicto.unibot.ui.components.EmptyState
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptic()
    val vm: ScheduledMessagesViewModel = viewModel(factory = ScheduledMessagesViewModel.factory(context))
    val tasks by vm.messages.collectAsState()

    var messageText by remember { mutableStateOf("") }
    var dateMs by remember { mutableStateOf(startOfToday()) }
    val timeState = rememberTimePickerState(
        initialHour = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24,
        initialMinute = 0,
        is24Hour = true,
    )
    var showDatePicker by remember { mutableStateOf(false) }
    var useExistingChat by remember { mutableStateOf(false) }
    var targetSessionId by remember { mutableStateOf<String?>(null) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val sessions = remember { mutableStateListOf<ScheduledMessagesViewModel.SessionOption>() }
    LaunchedEffect(Unit) {
        sessions.addAll(vm.listSessions())
    }
    val targetTitle = remember(useExistingChat, targetSessionId, sessions) {
        if (!useExistingChat) context.getString(R.string.v12_scheduled_messages_new_chat)
        else sessions.firstOrNull { it.id == targetSessionId }?.title
            ?: context.getString(R.string.v12_scheduled_messages_pick_chat)
    }

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.v12_scheduled_messages_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Composer card ──
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.v12_scheduled_messages_compose_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it; error = null },
                        label = { Text(stringResource(R.string.v12_scheduled_messages_field_message)) },
                        placeholder = { Text(stringResource(R.string.v12_scheduled_messages_message_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PickerChip(
                            label = stringResource(R.string.v12_scheduled_messages_field_date),
                            value = dateLabel(dateMs),
                            icon = Icons.Outlined.CalendarMonth,
                            onClick = { showDatePicker = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Compact time entry (keyboard input, like the Routines editor).
                    TimeInput(state = timeState)
                    // Target chat.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !useExistingChat,
                            onClick = { haptics.tap(); useExistingChat = false; targetSessionId = null },
                            label = { Text(stringResource(R.string.v12_scheduled_messages_new_chat)) },
                        )
                        FilterChip(
                            selected = useExistingChat,
                            onClick = { haptics.tap(); useExistingChat = true },
                            label = { Text(stringResource(R.string.v12_scheduled_messages_pick_chat)) },
                        )
                    }
                    if (useExistingChat) {
                        Box {
                            PickerChip(
                                label = stringResource(R.string.v12_scheduled_messages_field_chat),
                                value = targetTitle,
                                icon = Icons.Outlined.ChatBubbleOutline,
                                onClick = { showSessionMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = showSessionMenu,
                                onDismissRequest = { showSessionMenu = false },
                            ) {
                                sessions.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                s.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = {
                                            haptics.tap()
                                            targetSessionId = s.id
                                            showSessionMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (error != null) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            val text = messageText.trim()
                            if (text.isEmpty()) {
                                error = context.getString(R.string.v12_scheduled_messages_empty_error)
                                haptics.error()
                                return@Button
                            }
                            val triggerAt = dateMs +
                                timeState.hour * 3_600_000L + timeState.minute * 60_000L
                            if (triggerAt <= System.currentTimeMillis() + 30_000L) {
                                error = context.getString(R.string.v12_scheduled_messages_past_error)
                                haptics.error()
                                return@Button
                            }
                            if (useExistingChat && targetSessionId == null) {
                                error = context.getString(R.string.v12_scheduled_messages_pick_chat_error)
                                haptics.error()
                                return@Button
                            }
                            vm.schedule(
                                text = text,
                                triggerAtMs = triggerAt,
                                targetSessionId = if (useExistingChat) targetSessionId else null,
                            ) { ok ->
                                if (ok) {
                                    haptics.success()
                                    messageText = ""
                                    error = null
                                } else {
                                    error = context.getString(R.string.v12_scheduled_messages_failed)
                                    haptics.error()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.v12_scheduled_messages_schedule))
                    }
                }
            }

            // ── List ──
            if (tasks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.v12_scheduled_messages_none),
                        hint = stringResource(R.string.v12_scheduled_messages_none_hint),
                    )
                }
            } else {
                itemsIndexed(tasks, key = { _, t -> t.id }) { index, task ->
                    ScheduledMessageRow(
                        taskId = task.id,
                        preview = task.label.ifBlank { task.prompt.take(80) },
                        triggerLabel = triggerLabel(task),
                        targetLabel = targetLabel(task, sessions),
                        delivered = !task.enabled,
                        sessionId = task.lastResultSessionId,
                        modifier = Modifier.staggeredEntrance(index),
                        onOpenSession = onOpenSession,
                        onDelete = { haptics.tap(); vm.cancel(task.id) },
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        DateDialog(
            initialMs = dateMs,
            onDismiss = { showDatePicker = false },
            onPick = { picked ->
                dateMs = picked
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun PickerChip(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ScheduledMessageRow(
    taskId: String,
    preview: String,
    triggerLabel: String,
    targetLabel: String,
    delivered: Boolean,
    sessionId: String?,
    modifier: Modifier = Modifier,
    onOpenSession: (sessionId: String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preview,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$triggerLabel · $targetLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val ctx = LocalContext.current
            Text(
                text = if (delivered) ctx.getString(R.string.v12_scheduled_messages_delivered)
                else ctx.getString(R.string.v12_scheduled_messages_pending),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (delivered) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
            )
            if (delivered && sessionId != null) {
                Spacer(Modifier.height(4.dp))
                UnibotTextButton(onClick = { onOpenSession(sessionId) }) {
                    Text(stringResource(R.string.v12_scheduled_messages_open_chat), fontSize = 13.sp)
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.v12_scheduled_messages_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initialMs: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            UnibotTextButton(onClick = {
                state.selectedDateMillis?.let { onPick(startOfLocalDay(it)) }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/** DatePicker hands back a UTC-midnight ms; convert to local start-of-day. */
private fun startOfLocalDay(utcMidnightMs: Long): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcMidnightMs
    }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

private fun startOfToday(): Long {
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun dateLabel(ms: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(ms))

private fun triggerLabel(task: ai.unicto.unibot.scheduled.ScheduledTask): String {
    val ms = task.nextTriggerMs() ?: task.lastFiredAt ?: return "—"
    return SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(ms))
}

@Composable
private fun targetLabel(
    task: ai.unicto.unibot.scheduled.ScheduledTask,
    sessions: List<ScheduledMessagesViewModel.SessionOption>,
): String = when (val mode = task.targetMode) {
    is ScheduledTargetMode.NewSession ->
        stringResource(R.string.v12_scheduled_messages_new_chat)
    is ScheduledTargetMode.AppendToSession ->
        sessions.firstOrNull { it.id == mode.sessionId }?.title
            ?: stringResource(R.string.v12_scheduled_messages_pick_chat)
    is ScheduledTargetMode.RerunMessage ->
        stringResource(R.string.v12_scheduled_messages_pick_chat)
}
