package ai.unicto.unibot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ai.unicto.unibot.R

/**
 * v0.2.0 P3 — "Quick ask" home-screen widget.
 *
 * RemoteViews cannot host a real text field (EditText is not a supported
 * widget), so the widget shows a dark input-look row plus a send button;
 * both open [QuickAskActivity], a small translucent sheet with a real
 * text field. Sending fires `unibot://ask?text=...`, which prefills the
 * main chat's composer — never auto-sends.
 */
class QuickAskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_ask)
            val askIntent = Intent(context, QuickAskActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                0,
                askIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_ask_field, pending)
            views.setOnClickPendingIntent(R.id.widget_ask_send, pending)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
