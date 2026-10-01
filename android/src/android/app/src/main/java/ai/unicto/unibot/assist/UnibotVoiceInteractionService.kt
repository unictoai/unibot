package ai.unicto.unibot.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

private const val TAG = "UnibotVoiceAssist"

/**
 * Declaring this service is what makes unibot appear in
 * Settings → Apps → Default apps → Digital assistant app.
 *
 * v1 behaviour: when the system invokes the assistant (long-press home /
 * power-button gesture / corner swipe, depending on the OEM), we open a new
 * unibot chat with voice input auto-started (the same flow as the launcher
 * "voice chat" quick action: minis://action/voice_chat).
 *
 * Screen-context assist (AssistStructure / screenshot) is accepted but
 * ignored in v1 — the session just hands off to the app.
 */
class UnibotVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VoiceInteractionService ready")
    }
}

/** Binds assistant invocations to [UnibotVoiceInteractionSession]. */
class UnibotVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle): VoiceInteractionSession {
        return UnibotVoiceInteractionSession(this)
    }
}

class UnibotVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    /** Guard: the framework can call onShow + onHandleAssist for one invocation. */
    private var launched = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launchVoiceChat()
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        launchVoiceChat()
    }

    private fun launchVoiceChat() {
        if (launched) return
        launched = true
        try {
            // Same deep link as the launcher "voice chat" quick action:
            // MainActivity opens a fresh draft chat and ChatScreen auto-fires
            // voice input on first compose (DeepLinkCoordinator ChatAction.START_VOICE).
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://action/voice_chat")).apply {
                `package` = context.packageName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to launch voice chat from assistant session", t)
        } finally {
            // Dismiss the (empty) assist UI — the app is now in front.
            hide()
        }
    }
}
