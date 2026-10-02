package ai.unicto.unibot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import ai.unicto.unibot.R
import ai.unicto.unibot.scheduled.ScheduledTask
import ai.unicto.unibot.scheduled.ScheduledTaskStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v0.2.0 P3 — "Routines" home-screen widget.
 *
 * Shows the user's scheduled routines (up to [MAX_ROWS]) with their next
 * run time, read straight from [ScheduledTaskStore]. Tapping the header
 * opens the routines list; tapping a row opens that routine's editor.
 *
 * Refresh: [onUpdate] on every widget tick, plus [ACTION_ROUTINES_CHANGED]
 * which [ai.unicto.unibot.scheduled.ScheduledTaskManager] broadcasts after
 * every create / update / enable / delete.
 */
class TasksWidgetProvider : android.appwidget.AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ROUTINES_CHANGED) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TasksWidgetProvider::class.java))
            onUpdate(context, mgr, ids)
        }
    }

    companion object {
        /** Broadcast by ScheduledTaskManager after any routine mutation. */
        const val ACTION_ROUTINES_CHANGED = "ai.unicto.unibot.ROUTINES_CHANGED"

        private const val MAX_ROWS = 4

        /** Ask every live widget to re-read the store. Safe to call often. */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TasksWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, TasksWidgetProvider::class.java).apply {
                action = ACTION_ROUTINES_CHANGED
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_tasks)

            val tasks = runCatching { ScheduledTaskStore(context).all() }
                .getOrDefault(emptyList())
                .filter { !it.hidden }
                .sortedBy { it.nextTriggerMs() ?: Long.MAX_VALUE }
                .take(MAX_ROWS)

            views.setTextViewText(
                R.id.widget_tasks_count,
                context.resources.getQuantityString(
                    R.plurals.ub_widget_tasks_count, tasks.size, tasks.size,
                ),
            )

            views.removeAllViews(R.id.widget_tasks_container)
            if (tasks.isEmpty()) {
                views.addView(
                    R.id.widget_tasks_container,
                    emptyRow(context, listIntent(context, 0)),
                )
            } else {
                tasks.forEach { task ->
                    views.addView(
                        R.id.widget_tasks_container,
                        taskRow(context, task),
                    )
                }
            }

            views.setOnClickPendingIntent(
                R.id.widget_tasks_header,
                listIntent(context, 0),
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun listIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("unibot://settings/routines"),
            ).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun editorIntent(context: Context, taskId: String): PendingIntent {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("unibot://settings/routines?task=$taskId"),
            ).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context,
                taskId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun taskRow(context: Context, task: ScheduledTask): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_task_row)
            row.setTextViewText(R.id.widget_task_title, task.label.ifBlank { "Routine" })
            row.setTextViewText(R.id.widget_task_subtitle, nextRunLabel(task))
            // Enabled = violet dot, paused = dim grey.
            row.setInt(
                R.id.widget_task_dot,
                "setColorFilter",
                if (task.enabled) 0xFFA78BFA.toInt() else 0xFF636366.toInt(),
            )
            row.setOnClickPendingIntent(R.id.widget_task_row_root, editorIntent(context, task.id))
            return row
        }

        private fun emptyRow(context: Context, openList: PendingIntent): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_task_row)
            row.setTextViewText(
                R.id.widget_task_title,
                context.getString(R.string.ub_widget_tasks_empty),
            )
            row.setTextViewText(
                R.id.widget_task_subtitle,
                context.getString(R.string.ub_widget_tasks_empty_hint),
            )
            row.setViewVisibility(R.id.widget_task_dot, android.view.View.INVISIBLE)
            row.setOnClickPendingIntent(R.id.widget_task_row_root, openList)
            return row
        }

        private fun nextRunLabel(task: ScheduledTask): String {
            if (!task.enabled) return "Paused"
            val next = task.nextTriggerMs() ?: return "No upcoming run"
            val cal = Calendar.getInstance().apply { timeInMillis = next }
            val today = Calendar.getInstance()
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            fun sameDay(a: Calendar, b: Calendar): Boolean =
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            val day = when {
                sameDay(cal, today) -> "Today"
                sameDay(cal, tomorrow) -> "Tomorrow"
                else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(next))
            }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next))
            return "$day · $time"
        }
    }
}
