package ai.unicto.unibot.ui.chat

// [v12-B] Backing VM for the scheduled-messages screen. Thin wrapper around
// the existing alarm infra ([ScheduledTaskManager] + [ScheduledTask] +
// [ScheduledTaskAlarmReceiver] + [ScheduledAgentRunner]):
//
// A scheduled message is a one-shot ONCE task (hidden = true so it never
// clutters the Routines list) whose prompt IS the message text. When the
// alarm fires, the runner sends the prompt into the target session as a
// fresh user turn, the assistant replies, and a completion notification
// deep-links to the chat. Privacy-first: everything fires on-device via
// AlarmManager — nothing leaves the phone to schedule.

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.unicto.unibot.UnibotApp
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.scheduled.ScheduledRepeatMode
import ai.unicto.unibot.scheduled.ScheduledTargetMode
import ai.unicto.unibot.scheduled.ScheduledTask
import ai.unicto.unibot.scheduled.ScheduledTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ScheduledMessagesViewModel(private val appContext: Context) : ViewModel() {

    private val manager = ScheduledTaskManager(appContext)
    private val app get() = appContext as UnibotApp

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ScheduledMessagesViewModel(context.applicationContext) as T
            }
    }

    /**
     * [T-android-scheduled-lateinit-crash-156] Guard for the half-built
     * Application state (safe-mode early-return): repository reads must not
     * be unconditional. Mirrors [ai.unicto.unibot.ui.scheduled.ScheduledTasksViewModel].
     */
    private fun ready(): Boolean =
        (appContext as? UnibotApp)?.subsystemsReady() == true

    /**
     * This screen's tasks only: hidden one-shots with no goal attached.
     * (hidden is also used by goal checks — those carry a goalId.)
     * Pending first (soonest trigger), then delivered (most recent).
     */
    val messages: StateFlow<List<ScheduledTask>> = manager.store().observe()
        .map { list ->
            list.filter { it.hidden && it.goalId == null }
                .sortedWith(
                    compareBy(
                        { !it.enabled },
                        { it.nextTriggerMs() ?: Long.MAX_VALUE },
                        { -(it.lastFiredAt ?: 0L) },
                    ),
                )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lightweight session row for the "send into" picker. */
    data class SessionOption(val id: String, val title: String, val updatedAt: Long)

    suspend fun listSessions(limit: Int = 100): List<SessionOption> =
        withContext(Dispatchers.IO) {
            if (!ready()) return@withContext emptyList()
            app.chatRepository.querySessionsMeta(
                sessionIds = null, keywords = null, limit = limit,
                startMs = null, endMs = null,
            ).map {
                SessionOption(it.id, it.title?.ifBlank { null } ?: "Untitled", it.lastActive)
            }
        }

    /**
     * Schedule [text] for [triggerAtMs]. [targetSessionId] null → new chat,
     * else the message is appended to that session. Runs on IO; the manager
     * registers the exact alarm itself.
     */
    fun schedule(
        text: String,
        triggerAtMs: Long,
        targetSessionId: String?,
        onDone: (ok: Boolean) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = triggerAtMs
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val at = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
                manager.create(
                    ScheduledTask(
                        label = text.take(60).ifBlank { "Scheduled message" },
                        timeOfDayHour = at.get(Calendar.HOUR_OF_DAY),
                        timeOfDayMinute = at.get(Calendar.MINUTE),
                        repeatMode = ScheduledRepeatMode.ONCE,
                        prompt = text,
                        targetMode = if (targetSessionId == null)
                            ScheduledTargetMode.NewSession
                        else
                            ScheduledTargetMode.AppendToSession(targetSessionId),
                        startDateMs = dayStart,
                        // Hidden from the Routines list — this screen owns it.
                        hidden = true,
                    ),
                )
            }.onFailure {
                AppLogger.error("SchedMessages", "schedule failed: ${it.message}")
            }.isSuccess
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun cancel(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { manager.delete(taskId) }
                .onFailure { AppLogger.error("SchedMessages", "cancel failed: ${it.message}") }
        }
    }
}
