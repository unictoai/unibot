package ai.unicto.unibot.connectors.gmail

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Gmail auto-label rules (item 43): user-defined rules the agent applies
 * to incoming mail.
 *
 * A rule matches a message when ALL of its non-blank match fields hit:
 * - [fromContains]: substring of the From header (case-insensitive)
 * - [subjectContains]: substring of the Subject (case-insensitive)
 * - [query]: extra Gmail search syntax AND-ed with the built-in query
 *
 * Matching messages get [labelName] applied (created on first use).
 * Rules only touch messages received in the last [LOOKBACK_HOURS] so a
 * newly-created rule can't relabel years of history.
 *
 * Application runs in [ai.unicto.unibot.scheduled.GmailLabelWorker] —
 * WorkManager, battery-disciplined (unmetered-not-required but
 * battery-not-low + network-connected constraints), user-toggleable via
 * [isAutoLabelEnabled]. The rules list + toggle are managed in
 * Settings → Connectors → Gmail → Label rules.
 *
 * Matching ([matches]) and JSON round-trip are pure and unit-tested.
 */
data class GmailLabelRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val fromContains: String = "",
    val subjectContains: String = "",
    val query: String = "",
    val labelName: String,
    val enabled: Boolean = true,
) {
    /** True when every non-blank match field hits. Pure — unit-tested. */
    fun matches(from: String, subject: String): Boolean {
        if (fromContains.isNotBlank() &&
            !from.contains(fromContains, ignoreCase = true)
        ) return false
        if (subjectContains.isNotBlank() &&
            !subject.contains(subjectContains, ignoreCase = true)
        ) return false
        return true
    }

    /** Gmail search query scoping this rule to recent mail. */
    fun gmailQuery(): String {
        val parts = mutableListOf("newer_than:${LOOKBACK_HOURS}h")
        if (query.isNotBlank()) parts.add("($query)")
        return parts.joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("from_contains", fromContains)
        put("subject_contains", subjectContains)
        put("query", query)
        put("label_name", labelName)
        put("enabled", enabled)
    }

    companion object {
        /** Rules only look back this far — never relabel ancient history. */
        const val LOOKBACK_HOURS = 24

        fun fromJson(o: JSONObject): GmailLabelRule? {
            val name = o.optString("name", "").ifBlank { return null }
            val label = o.optString("label_name", "").ifBlank { return null }
            return GmailLabelRule(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                name = name,
                fromContains = o.optString("from_contains", ""),
                subjectContains = o.optString("subject_contains", ""),
                query = o.optString("query", ""),
                labelName = label,
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

/** Encrypted persistence for the rules list + master toggle. */
class GmailLabelRuleStore {

    private val file = "gmail_label_rules"
    private val keyRules = "rules"
    private val keyEnabled = "auto_label_enabled"

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, file)
                .also { prefsRef = it }
        }

    fun getRules(context: Context): List<GmailLabelRule> {
        val raw = prefs(context).getString(keyRules, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { GmailLabelRule.fromJson(arr.optJSONObject(it) ?: JSONObject()) }
        }.onFailure {
            AppLogger.warning(TAG, "stored rules unreadable: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun saveRules(context: Context, rules: List<GmailLabelRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(keyRules, arr.toString()).apply()
    }

    fun isAutoLabelEnabled(context: Context): Boolean =
        prefs(context).getBoolean(keyEnabled, false)

    fun setAutoLabelEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(keyEnabled, enabled).apply()
    }

    companion object {
        private const val TAG = "GmailLabelRules"
    }
}
