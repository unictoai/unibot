package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Live API-key validation for the BYOK setup wizard (backlog item 98).
 *
 * Pings the provider's model-listing endpoint with the pasted key BEFORE the
 * provider is saved, so a typo or wrong key is caught on the wizard instead
 * of surfacing as a failed first chat. Results map to friendly, actionable
 * copy via [friendlyMessage] — never raw HTTP text.
 *
 * New code only; the provider wire implementations are untouched.
 */
sealed interface KeyValidationResult {
    /** The key worked. `modelCount` is -1 when the body could not be parsed. */
    data class Valid(val modelCount: Int) : KeyValidationResult
    /** 401 — the key was rejected outright. */
    data object InvalidKey : KeyValidationResult
    /** 403 — authenticated but refused (plan, region, or endpoint restriction). */
    data object Forbidden : KeyValidationResult
    /** 429 — too many attempts; ask the user to wait. */
    data object RateLimited : KeyValidationResult
    /** 5xx — the provider's problem, not the key's. */
    data class ServerError(val code: Int) : KeyValidationResult
    /** DNS / timeout / no route — the provider was unreachable. */
    data object NetworkUnreachable : KeyValidationResult
    /** Any other unexpected status. */
    data class Unexpected(val code: Int) : KeyValidationResult
}

object KeyValidator {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val TIMEOUT_S = 15L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolves the validation endpoint for a provider type. A non-blank
     * [baseOverride] wins (third-party relays); otherwise the provider's
     * canonical base is used. Returns null when no endpoint is known, in
     * which case the caller should skip validation rather than guess.
     */
    fun endpointFor(type: ProviderType, baseOverride: String?): String? {
        // Gemini's validation endpoint is fixed (the v1beta models
        // collection); it has no defaultBaseUrl/officialBase, so resolve
        // it before the base-or-null early return below.
        if (type == ProviderType.gemini) return "https://generativelanguage.googleapis.com/v1beta/models"
        val base = baseOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: type.defaultBaseUrl
            ?: officialBase(type)
            ?: return null
        return when (type) {
            ProviderType.anthropic -> base.trimEnd('/') + "/v1/models"
            else -> base.trimEnd('/') + "/models"
        }
    }

    private fun officialBase(type: ProviderType): String? = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        else -> null
    }

    /** Friendly, actionable copy for every outcome. Never leaks raw bodies. */
    fun friendlyMessage(result: KeyValidationResult): String = when (result) {
        is KeyValidationResult.Valid ->
            if (result.modelCount >= 0) "Key works — ${result.modelCount} models available."
            else "Key works. The model list could not be read, but the key was accepted."
        KeyValidationResult.InvalidKey ->
            "That key was rejected. Check you pasted the full key, with no extra spaces."
        KeyValidationResult.Forbidden ->
            "The key was recognised but this endpoint refused it. Check the provider's plan or region for this key."
        KeyValidationResult.RateLimited ->
            "Too many attempts — wait a minute, then try again."
        is KeyValidationResult.ServerError ->
            "The provider's server had a problem (HTTP ${result.code}). Try again in a bit."
        KeyValidationResult.NetworkUnreachable ->
            "Could not reach the provider. Check your connection and try again."
        is KeyValidationResult.Unexpected ->
            "Unexpected response (HTTP ${result.code}). The key may still work — save it and try a chat."
    }

    /**
     * Validates [apiKey] against [type]'s live models endpoint.
     * Network on IO; never throws — failures map to result values.
     */
    suspend fun validate(
        type: ProviderType,
        apiKey: String,
        baseOverride: String? = null,
    ): KeyValidationResult = withContext(Dispatchers.IO) {
        val endpoint = endpointFor(type, baseOverride)
            ?: return@withContext KeyValidationResult.Unexpected(-1)
        val request = when (type) {
            ProviderType.gemini -> Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey.trim())
                .get()
                .build()
            ProviderType.anthropic -> Request.Builder()
                .url(endpoint)
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()
            else -> Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
        }
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> KeyValidationResult.Valid(countModels(response.body?.string()))
                    401 -> KeyValidationResult.InvalidKey
                    403 -> KeyValidationResult.Forbidden
                    429 -> KeyValidationResult.RateLimited
                    in 500..599 -> KeyValidationResult.ServerError(response.code)
                    else -> KeyValidationResult.Unexpected(response.code)
                }
            }
        } catch (_: UnknownHostException) {
            KeyValidationResult.NetworkUnreachable
        } catch (_: SocketTimeoutException) {
            KeyValidationResult.NetworkUnreachable
        } catch (_: java.io.IOException) {
            KeyValidationResult.NetworkUnreachable
        } catch (_: Exception) {
            KeyValidationResult.NetworkUnreachable
        }
    }

    /** Counts models in a models-list body; -1 when the shape is unrecognised. */
    internal fun countModels(body: String?): Int {
        if (body.isNullOrBlank()) return -1
        return try {
            val json = JSONObject(body)
            // OpenAI / Anthropic style: { "data": [ ... ] }; Gemini style: { "models": [ ... ] }
            val arr = when {
                json.has("data") -> json.optJSONArray("data")
                json.has("models") -> json.optJSONArray("models")
                else -> null
            }
            arr?.length() ?: -1
        } catch (_: Exception) {
            -1
        }
    }
}
