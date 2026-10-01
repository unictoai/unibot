package ai.unicto.unibot.hub

import android.content.Context
import ai.unicto.unibot.R

/**
 * A failure from the hub or from a computer, said in the phone's language. The codes are
 * the ones the relay (`cloud/unibot_cloud/hub.py`) and the runtime's coding service
 * (`unibot/coding/service.py`) use; anything else falls back to one plain sentence,
 * and the provider's or the computer's own wording stays in the log.
 */
object HubErrors {
    fun describe(context: Context, e: Throwable): String {
        val code = (e as? HubException)?.code ?: return context.getString(R.string.ub_hub_err_generic)
        return describe(context, code)
    }

    fun describe(context: Context, code: String): String = context.getString(
        when (code) {
            "disconnected", "closed" -> R.string.ub_hub_err_disconnected
            "timeout" -> R.string.ub_hub_err_timeout
            "device_offline", "no_device" -> R.string.ub_hub_err_device_offline
            "busy" -> R.string.ub_hub_err_busy
            "not_controllable", "self_call" -> R.string.ub_hub_err_not_controllable
            "unknown_agent" -> R.string.ub_hub_err_unknown_agent
            "no_session" -> R.string.ub_hub_err_no_session
            "unknown_action" -> R.string.ub_hub_err_unknown_action
            "bad_key", "bad_device" -> R.string.ub_hub_err_bad_key
            else -> R.string.ub_hub_err_generic
        },
    )
}
