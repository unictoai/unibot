package ai.unicto.unibot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 *
 * v1.2 Batch H — added a mic button: it fires the existing
 * `unibot://action/voice_chat` deep link, which opens a fresh draft chat
 * with the voice input mic auto-triggered (DeepLinkHandler.NewVoiceChat →
 * DeepLinkCoordinator.ChatAction.START_VOICE). The text-field + send
 * behavior is unchanged.
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

            val voiceIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("unibot://action/voice_chat"),
            ).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val voicePending = PendingIntent.getActivity(
                context,
                1,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_ask_mic, voicePending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
