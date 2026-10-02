package ai.unicto.unibot.sync

import org.json.JSONObject

/**
 * Who is pushing: the phone's stable device id, its friendly name and the
 * cloud account the relay knows (empty when BYOK-only and not signed in —
 * sync requires the relay connection, so the engine refuses before this).
 */
data class SyncDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val accountId: String,
)

/**
 * Push/pull of sealed session-snapshot envelopes. The envelopes are already
 * end-to-end encrypted by [SyncCrypto]; a transport only ever moves opaque
 * ciphertext — it must not need to read them.
 */
interface SyncTransport {
    /**
     * Push [envelopes] (each the JSON from [SyncCrypto.seal]). Returns how many
     * the backend accepted. Throws [TransportUnavailable] when the backend
     * cannot take session snapshots.
     */
    suspend fun push(device: SyncDeviceInfo, envelopes: List<JSONObject>): Int

    /** Envelopes written by other devices since [sinceMs] (unix millis). */
    suspend fun pull(device: SyncDeviceInfo, sinceMs: Long): List<JSONObject>
}

/** The backend has no session-sync endpoint. Not a network error — a missing API. */
class TransportUnavailable(message: String) : Exception(message)

/**
 * The relay connection as it actually exists (relay 0.5, surveyed 2026-10-02):
 *
 * - POST /v1/auth/{path} — sign-in, password, sign-out (auth only)
 * - `GET /v1/me`, `/v1/estimate` — allowance (chat billing)
 * - `GET/PUT /v1/me/profile` — the agent's name and look (see [ai.unicto.unibot.cloud.ProfileSync])
 * - `GET /v1/me/sessions`, `DELETE /v1/me/sessions/{prefix}` — sign-in *metadata*
 *   (which devices hold a key; revoking one) — not chat sessions
 * - `GET /v1/me/events` — the account's activity log
 * - `POST /v1/me/contribute`, `DELETE /v1/me/samples` — co-creation programme
 *
 * None of these accept or serve chat session snapshots, and stuffing chat data
 * through `/v1/me/profile` would corrupt the profile feature (name/look,
 * last-writer-wins, picture payloads) — so push/pull are honestly unavailable.
 * When the relay grows a sync endpoint, only this class changes: the data
 * model, the crypto and the engine stay exactly as they are.
 */
class StubRelaySyncTransport : SyncTransport {
    companion object {
        const val EXPLANATION =
            "The cloud relay does not offer a session-sync endpoint yet. " +
                "Its API covers chat, the agent profile, sign-in sessions and the activity log — " +
                "nothing that accepts or serves chat snapshots. Snapshots stay encrypted on this phone until it does."
    }

    override suspend fun push(device: SyncDeviceInfo, envelopes: List<JSONObject>): Int =
        throw TransportUnavailable(EXPLANATION)

    override suspend fun pull(device: SyncDeviceInfo, sinceMs: Long): List<JSONObject> =
        throw TransportUnavailable(EXPLANATION)
}
