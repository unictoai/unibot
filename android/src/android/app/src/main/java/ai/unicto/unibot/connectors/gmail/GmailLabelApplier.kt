package ai.unicto.unibot.connectors.gmail

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Shared rule-application engine for Gmail auto-label rules (item 43).
 * Used by [ai.unicto.unibot.scheduled.GmailLabelWorker] (background) and
 * the `gmail_apply_label_rules` agent tool (on-demand).
 *
 * For each enabled rule: ensure the label exists, search the last 24h of
 * mail with the rule's query, filter with the substring matchers, and
 * batch-apply the label. Never throws — per-rule failures are logged
 * and skipped.
 */
object GmailLabelApplier {

    private const val TAG = "GmailLabelApplier"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class ApplyReport(
        val rulesRun: Int,
        val labeledTotal: Int,
        val perRule: List<Pair<String, Int>>,
    )

    suspend fun applyAll(context: Context): ApplyReport = withContext(Dispatchers.IO) {
        val store = GmailLabelRuleStore()
        val rules = store.getRules(context).filter { it.enabled }
        val perRule = mutableListOf<Pair<String, Int>>()
        var total = 0
        for (rule in rules) {
            val n = runCatching { applyRule(context, rule) }.getOrElse { e ->
                AppLogger.warning(TAG, "[${rule.name}] failed: ${e.message}")
                -1
            }
            if (n >= 0) {
                perRule.add(rule.name to n)
                total += n
            }
        }
        ApplyReport(rules.size, total, perRule)
    }

    private suspend fun applyRule(context: Context, rule: GmailLabelRule): Int {
        val labelId = when (val l = GmailApi.ensureLabel(context, rule.labelName)) {
            is GmailApi.ApiResult.Ok -> l.value
            else -> {
                AppLogger.warning(TAG, "[${rule.name}] ensureLabel failed")
                return -1
            }
        }
        val matchedIds = when (val s = GmailApi.search(context, rule.gmailQuery(), 50)) {
            is GmailApi.ApiResult.Ok ->
                s.value.filter { rule.matches(it.from, it.subject) }.map { it.id }
            else -> {
                AppLogger.warning(TAG, "[${rule.name}] search failed")
                return -1
            }
        }
        if (matchedIds.isEmpty()) return 0
        return labelByIds(context, matchedIds, labelId)
    }

    private suspend fun labelByIds(
        context: Context,
        ids: List<String>,
        labelId: String,
    ): Int {
        val t = GmailOAuth.validAccessToken(context) ?: return 0
        val payload = JSONObject().apply {
            put("ids", JSONArray(ids))
            put("addLabelIds", JSONArray().put(labelId))
        }.toString().toRequestBody("application/json".toMediaType())
        return runCatching {
            http.newCall(
                Request.Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/batchModify")
                    .header("Authorization", "Bearer $t")
                    .post(payload)
                    .build(),
            ).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 204) ids.size else 0
            }
        }.getOrElse {
            AppLogger.warning(TAG, "[labelByIds] ${it.message}")
            0
        }
    }
}
