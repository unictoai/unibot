package ai.unicto.unibot.ui.chat

import android.content.Context
import ai.unicto.unibot.data.model.ProviderCredential
import ai.unicto.unibot.data.model.ProviderInstance
import ai.unicto.unibot.data.model.ProviderType
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * v1.4.0 items 23 + 24 — provider health dashboard logic and the free-tier
 * quota tracker.
 *
 * Item 23 reads the structured HTTP status plumbing (item 79 —
 * [ai.unicto.unibot.data.model.LLMError.httpStatus]) wherever it is
 * available: the live probe below classifies real `/models` responses, and
 * the auth-error mapping reuses the same numeric codes the chat cards use.
 * The quota tracker counts today's assistant rows per provider instance from
 * the DB (approximate — one row per turn) against typical free-tier daily
 * limits.
 *
 * Pure pieces ([FreeTierLimits], [quotaStateFor], [startOfTodayMs]) are
 * unit-tested; the prober does real network I/O on Dispatchers.IO.
 */

/** Typical free-tier DAILY REQUEST limits by provider type. Null = paid-only or no documented request cap. */
object FreeTierLimits {
    /**
     * Well-known documented free-tier request caps. These are TYPICAL values
     * and providers change them — the UI always labels them as such and the
     * provider's own dashboard is authoritative.
     */
    fun dailyRequests(type: ProviderType): Int? = when (type) {
        ProviderType.groq -> 14_400
        ProviderType.gemini -> 1_500
        ProviderType.openRouter -> 200
        else -> null
    }
}

/** Quota position for one provider instance today. */
enum class QuotaState { OK, NEAR_LIMIT, EXHAUSTED }

/** Pure: where [used] requests sit against a [limit]. NEAR_LIMIT at >= 80%. */
fun quotaStateFor(used: Int, limit: Int): QuotaState = when {
    limit <= 0 -> QuotaState.OK
    used >= limit -> QuotaState.EXHAUSTED
    used >= (limit * 0.8).toInt() -> QuotaState.NEAR_LIMIT
    else -> QuotaState.OK
}

/** Start of the local day, epoch millis. Quota windows reset daily. */
internal fun startOfTodayMs(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** "2026-10-06" — the once-per-day hint gate key. */
internal fun todayKey(): String {
    val cal = Calendar.getInstance()
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}

/** Live probe outcome for one provider instance. */
enum class HealthStatus {
    HEALTHY,
    AUTH_ERROR,
    RATE_LIMITED,
    ERROR,
    UNREACHABLE,
    NOT_CONFIGURED,
    UNKNOWN,
}

data class ProviderHealth(
    val instanceId: String,
    val status: HealthStatus,
    val httpStatus: Int?,
    val detail: String?,
    val checkedAt: Long,
)

/** SharedPreferences cache of the last probe per instance — the screen shows something instantly. */
class ProviderHealthStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(instanceId: String): ProviderHealth? {
        val raw = prefs.getString(KEY_PREFIX + instanceId, null) ?: return null
        return try {
            val o = JSONObject(raw)
            ProviderHealth(
                instanceId = instanceId,
                status = runCatching { HealthStatus.valueOf(o.optString("status")) }
                    .getOrDefault(HealthStatus.UNKNOWN),
                httpStatus = o.optInt("httpStatus", -1).takeIf { it >= 0 },
                detail = o.optString("detail", null)?.takeIf { it.isNotBlank() },
                checkedAt = o.optLong("checkedAt", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun put(health: ProviderHealth) {
        val o = JSONObject()
            .put("status", health.status.name)
            .put("checkedAt", health.checkedAt)
        health.httpStatus?.let { o.put("httpStatus", it) }
        health.detail?.let { o.put("detail", it) }
        prefs.edit().putString(KEY_PREFIX + health.instanceId, o.toString()).apply()
    }

    companion object {
        private const val PREFS = "unibot_provider_health"
        private const val KEY_PREFIX = "health_"
    }
}

/**
 * Item 23 — live per-provider status probe. GETs the provider's `/models`
 * endpoint with the stored credential and classifies the numeric response.
 * OAuth instances are checked via sign-in state (no wire call — the token
 * refresh path owns the network there).
 */
class ProviderHealthProber(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun probe(instance: ProviderInstance): ProviderHealth =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!instance.isEnabled) {
                return@withContext ProviderHealth(
                    instance.id, HealthStatus.UNKNOWN, null, "Disabled", now,
                )
            }
            if (instance.credentialType == ProviderCredential.oauth) {
                val authed = ai.unicto.unibot.auth.OAuthManager
                    .forInstance(context, instance)?.isAuthenticated() == true
                return@withContext ProviderHealth(
                    instance.id,
                    if (authed) HealthStatus.HEALTHY else HealthStatus.AUTH_ERROR,
                    null,
                    if (authed) "Signed in" else "Not signed in",
                    now,
                )
            }
            val key = providerRepository.usableApiKey(instance)
            if (key.isNullOrEmpty()) {
                return@withContext ProviderHealth(
                    instance.id, HealthStatus.NOT_CONFIGURED, null, "No API key set", now,
                )
            }
            val base = (instance.effectiveBaseURL
                ?: instance.providerType.defaultBaseUrl)?.trimEnd('/')
            if (base.isNullOrEmpty()) {
                return@withContext ProviderHealth(
                    instance.id, HealthStatus.UNKNOWN, null, "No endpoint to probe", now,
                )
            }
            val (url, headers) = when (instance.providerType) {
                ProviderType.anthropic -> "$base/models" to mapOf(
                    "x-api-key" to key,
                    "anthropic-version" to "2023-06-01",
                )
                // Gemini's key travels in the x-goog-api-key header, never the URL.
                ProviderType.gemini -> "$base/models" to mapOf("x-goog-api-key" to key)
                else -> "$base/models" to mapOf("Authorization" to "Bearer $key")
            }
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            try {
                client.newCall(builder.build()).execute().use { resp ->
                    val code = resp.code
                    val status = when {
                        code in 200..299 -> HealthStatus.HEALTHY
                        code == 401 || code == 403 -> HealthStatus.AUTH_ERROR
                        code == 429 -> HealthStatus.RATE_LIMITED
                        else -> HealthStatus.ERROR
                    }
                    val detail = when (status) {
                        HealthStatus.HEALTHY -> "OK"
                        HealthStatus.AUTH_ERROR -> "Key rejected"
                        HealthStatus.RATE_LIMITED -> "Rate limited"
                        else -> "HTTP $code"
                    }
                    ProviderHealth(instance.id, status, code, detail, now)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "probe ${instance.id} failed: ${t.message}")
                ProviderHealth(
                    instance.id, HealthStatus.UNREACHABLE, null,
                    "Unreachable", now,
                )
            }
        }

    companion object {
        private const val TAG = "ProviderHealthProber"
    }
}

/** Item 24 — today's approximate request count for one provider instance. */
suspend fun todayRequestCount(
    chatRepository: ChatRepository,
    instanceId: String,
): Int = chatRepository.countAssistantSince(instanceId, startOfTodayMs())
