package ai.unicto.unibot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import ai.unicto.unibot.R
import ai.unicto.unibot.data.db.AppDatabase
import ai.unicto.unibot.data.db.ChatSessionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v1.2 Batch H — "Recent chats" home-screen widget (medium, ~3x3).
 *
 * Lists the [MAX_ROWS] most recent chats (title + relative time), read
 * read-only from the Room sessions table via
 * [ai.unicto.unibot.data.db.ChatDao.listSessions] (already ordered by
 * `updated_at DESC`). Tapping a row opens that chat via the existing
 * `unibot://session/<id>` deep link; tapping the header opens the app;
 * the header refresh button re-reads the store on demand.
 *
 * Refresh: [onUpdate] on every widget tick, [ACTION_SESSIONS_CHANGED]
 * (broadcast contract — no session-saving code was modified to send it;
 * the manual refresh button covers staleness), plus an explicit
 * [ACTION_MANUAL_REFRESH] from the header button.
 *
 * DB access runs off the main thread: [onUpdate] holds the broadcast
 * open with `goAsync()` and does the Room query on [widgetScope]
 * (Dispatchers.IO). The widget is static RemoteViews — no animations.
 */
class RecentChatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        widgetScope.launch {
            try {
                for (appWidgetId in appWidgetIds) {
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SESSIONS_CHANGED ||
            intent.action == ACTION_MANUAL_REFRESH
        ) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, RecentChatsWidgetProvider::class.java),
            )
            onUpdate(context, mgr, ids)
        }
    }

    companion object {
        /**
         * Broadcast contract: re-read the sessions store and redraw.
         * The manifest registers this action on the receiver; the manual
         * refresh button sends [ACTION_MANUAL_REFRESH] explicitly instead.
         */
        const val ACTION_SESSIONS_CHANGED = "ai.unicto.unibot.SESSIONS_CHANGED"

        private const val ACTION_MANUAL_REFRESH =
            "ai.unicto.unibot.widget.RECENT_CHATS_REFRESH"

        private const val MAX_ROWS = 4

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-read the store and redraw every live widget. Safe to call often. */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, RecentChatsWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            val intent = Intent(context, RecentChatsWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        private suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_recent_chats)

            val sessions = runCatching {
                AppDatabase.getInstance(context).chatDao().listSessions()
            }.getOrDefault(emptyList()).take(MAX_ROWS)

            views.removeAllViews(R.id.widget_recent_container)
            if (sessions.isEmpty()) {
                views.addView(
                    R.id.widget_recent_container,
                    emptyRow(context, openAppIntent(context)),
                )
            } else {
                sessions.forEach { session ->
                    views.addView(
                        R.id.widget_recent_container,
                        chatRow(context, session),
                    )
                }
            }

            views.setOnClickPendingIntent(
                R.id.widget_recent_header,
                openAppIntent(context),
            )
            views.setOnClickPendingIntent(
                R.id.widget_recent_refresh,
                refreshIntent(context),
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val launch = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("unibot://action/new_chat")).apply {
                    setPackage(context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            return PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, RecentChatsWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openSessionIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("unibot://session/$sessionId"),
            ).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun chatRow(context: Context, session: ChatSessionEntity): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_recent_chat_row)
            val title = session.title?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.ub_widget_recent_chats_new)
            row.setTextViewText(R.id.widget_recent_title, title)
            row.setTextViewText(R.id.widget_recent_time, timeLabel(session.updatedAt))
            // Violet dot = brand accent (matches the Tasks widget's enabled dot).
            row.setInt(
                R.id.widget_recent_dot,
                "setColorFilter",
                0xFFA78BFA.toInt(),
            )
            row.setOnClickPendingIntent(
                R.id.widget_recent_row_root,
                openSessionIntent(context, session.id),
            )
            return row
        }

        private fun emptyRow(context: Context, openApp: PendingIntent): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_recent_chat_row)
            row.setTextViewText(
                R.id.widget_recent_title,
                context.getString(R.string.ub_widget_recent_chats_empty),
            )
            row.setTextViewText(
                R.id.widget_recent_time,
                context.getString(R.string.ub_widget_recent_chats_empty_hint),
            )
            row.setViewVisibility(R.id.widget_recent_dot, View.INVISIBLE)
            row.setOnClickPendingIntent(R.id.widget_recent_row_root, openApp)
            return row
        }

        private fun timeLabel(updatedAt: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - updatedAt
            if (diff < 0) return ""
            val minutes = diff / 60_000L
            if (minutes < 1) return "Just now"
            if (minutes < 60) return "$minutes min ago"
            val cal = Calendar.getInstance().apply { timeInMillis = updatedAt }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            fun sameDay(a: Calendar, b: Calendar): Boolean =
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            return when {
                sameDay(cal, today) -> "${minutes / 60} hr ago"
                sameDay(cal, yesterday) -> "Yesterday"
                else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(updatedAt))
            }
        }
    }
}
