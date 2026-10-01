package ai.unicto.unibot.cloud

import android.content.Context
import org.json.JSONObject

/**
 * The relay's "the free allowance is used up" answer, caught on its way through the model
 * client. unibot Cloud refuses a model call with HTTP 429 and a structured body
 * (`error.code = "allowance_exhausted"` plus what is left, the pool, the invite link, whether
 * the co-creation bonus is still open, and the guide for one's own key). The generic client
 * maps every 429 to "rate limited", so the body would be lost; this keeps the last one for a
 * few seconds so the chat can show the three ways on instead of a bare error.
 */
object AllowanceSignal {
    /** What the relay said, in yuan. */
    data class Exhausted(
        val leftCny: Double,
        val grantCny: Double,
        val inviteUrl: String,
        val contributeBonusAvailable: Boolean,
        val ownKeyDocs: String,
        val at: Long = System.currentTimeMillis(),
    )

    @Volatile private var last: Exhausted? = null

    /** Call for every failed HTTP reply of a model request; only the relay's 429 is kept. */
    fun noteHttpError(status: Int, body: String?) {
        if (status != 429 || body.isNullOrBlank() || !body.contains("allowance_exhausted")) return
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull() ?: return
        if (err.optString("code") != "allowance_exhausted") return
        last = Exhausted(
            leftCny = err.optDouble("left", 0.0),
            grantCny = err.optDouble("grant", 0.0),
            inviteUrl = err.optString("invite_url", ""),
            contributeBonusAvailable = err.optBoolean("contribute_bonus_available", false),
            ownKeyDocs = err.optString("own_key_docs", ""),
        )
    }

    /** The exhaustion behind the error the chat is about to show, if it was the relay's; consumed. */
    fun takeFresh(maxAgeMs: Long = 30_000): Exhausted? {
        val e = last ?: return null
        last = null
        return e.takeIf { System.currentTimeMillis() - it.at <= maxAgeMs }
    }

    /** The same, from the cached account (the account page shows it whenever the pool is spent). */
    fun fromAccount(context: Context): Exhausted? {
        val a = UnibotCloud.account(context) ?: return null
        if (!a.exhausted) return null
        return Exhausted(
            leftCny = a.leftCny.coerceAtLeast(0.0),
            grantCny = a.grantCny,
            inviteUrl = a.inviteUrl,
            contributeBonusAvailable = a.contributeBonusAvailable,
            ownKeyDocs = a.ownKeyDocs,
        )
    }
}
