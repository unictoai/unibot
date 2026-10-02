package ai.unicto.unibot.sync

import android.content.Context
import ai.unicto.unibot.cloud.UnibotCloud
import ai.unicto.unibot.data.DeviceIdentity
import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.db.MessageEntity
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device session sync (P8) — orchestration.
 *
 * Opt-in (off by default), rides on the optional cloud relay connection, and
 * keeps everything end-to-end encrypted: the passphrase you enter derives the
 * key ([SyncCrypto]) and is held in memory only — never written to disk.
 *
 * Current transport reality: [StubRelaySyncTransport] — the relay has no
 * session-sync endpoint, so [syncNow] builds and seals the snapshot, attempts
 * the push, and honestly reports [SyncOutcome.Failed] with the explanation.
 * The snapshot, the envelope and the merge logic ([applySnapshot]) are all
 * real and ready; when the relay grows an endpoint only the transport changes.
 */
object SessionSyncEngine {
    private const val TAG = "SessionSync"
    private const val PREFS = "unibot_session_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_LAST_ERROR = "last_error"

    /** Bounds so one giant library cannot OOM the snapshot builder. */
    private const val MAX_SESSIONS_PER_SNAPSHOT = 500
    private const val MAX_MESSAGES_PER_SESSION = 2000
    private const val MESSAGE_PAGE = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The passphrase lives here and nowhere else: memory only, never persisted. */
    @Volatile
    private var passphrase: CharArray? = null

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clearPassphrase()
        AppLogger.info(TAG, "sync ${if (enabled) "enabled" else "disabled"}")
    }

    fun hasPassphrase(): Boolean = passphrase != null

    fun setPassphrase(pw: CharArray?) {
        clearPassphrase()
        passphrase = pw
    }

    private fun clearPassphrase() {
        passphrase?.fill('\u0000')
        passphrase = null
    }

    fun lastSyncAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC_AT, 0)

    fun lastError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)

    private fun transport(): SyncTransport = StubRelaySyncTransport()

    /**
     * One sync run: build the snapshot of this phone's sessions, seal it with
     * the passphrase, and push it through the relay connection. Blocking —
     * call off the main thread.
     */
    suspend fun syncNow(context: Context, chatRepository: ChatRepository): SyncOutcome =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext SyncOutcome.NotReady("Sync is turned off")
            if (!UnibotCloud.isSignedIn(context)) {
                return@withContext SyncOutcome.NotReady("Not signed in to unibot Cloud")
            }
            val pw = passphrase
                ?: return@withContext SyncOutcome.NotReady("Enter a sync passphrase first")

            val device = SyncDeviceInfo(
                deviceId = DeviceIdentity.deviceId(context),
                deviceName = DeviceIdentity.deviceName(context),
                accountId = UnibotCloud.account(context)?.accountId.orEmpty(),
            )
            return@withContext try {
                val (snapshotJson, sessionCount) = buildSnapshot(chatRepository, device)
                val sealed = SyncCrypto.seal(pw, snapshotJson.toString().toByteArray(Charsets.UTF_8))
                val accepted = transport().push(device, listOf(sealed))
                prefs(context).edit()
                    .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
                    .remove(KEY_LAST_ERROR)
                    .apply()
                SyncOutcome.Done(sessionCount, "Relay accepted $accepted snapshot(s)")
            } catch (e: TransportUnavailable) {
                AppLogger.info(TAG, "sync unavailable: ${e.message}")
                prefs(context).edit().putString(KEY_LAST_ERROR, e.message).apply()
                SyncOutcome.Failed(e.message ?: "The relay has no session-sync endpoint yet")
            } catch (e: SyncCrypto.CryptoException) {
                AppLogger.info(TAG, "sync crypto failed: ${e.message}")
                SyncOutcome.Failed(e.message ?: "Encryption failed")
            } catch (e: Exception) {
                AppLogger.info(TAG, "sync failed: ${e.message}")
                prefs(context).edit().putString(KEY_LAST_ERROR, e.message).apply()
                SyncOutcome.Failed(e.message ?: "Sync failed")
            }
        }

    /**
     * Merge one decrypted snapshot into the local database (last-writer-wins
     * per session on `updatedAt`; tombstones delete). Used by pull when a real
     * transport exists — today it is only exercised by future code paths.
     */
    suspend fun applySnapshot(context: Context, chatRepository: ChatRepository, snapshot: JSONObject) =
        withContext(Dispatchers.IO) {
            val sessions = snapshot.optJSONArray("sessions") ?: return@withContext
            for (i in 0 until sessions.length()) {
                val entry = sessions.optJSONObject(i) ?: continue
                val s = entry.optJSONObject("session") ?: continue
                val id = s.optString("id").takeIf { it.isNotBlank() } ?: continue
                val local = chatRepository.getSession(id)
                val remoteUpdated = s.optLong("updated_at", 0)
                if (s.optBoolean("deleted", false)) {
                    if (local != null && remoteUpdated >= local.updatedAt) {
                        chatRepository.deleteSession(id)
                    }
                    continue
                }
                if (local != null && remoteUpdated <= local.updatedAt) continue
                val entity = ChatSessionEntity(
                    id = id,
                    title = s.optString("title").ifBlank { null },
                    modelId = s.optString("model_id").ifBlank { local?.modelId ?: "" },
                    createdAt = s.optLong("created_at", System.currentTimeMillis()),
                    updatedAt = remoteUpdated,
                    category = s.optString("category").ifBlank { null },
                    source = "sync",
                )
                chatRepository.dao.insertSession(entity)
                chatRepository.deleteMessagesAfter(id, 0)
                val messages = entry.optJSONArray("messages") ?: JSONArray()
                for (m in 0 until messages.length()) {
                    val msg = messages.optJSONObject(m) ?: continue
                    chatRepository.dao.insertMessage(
                        MessageEntity(
                            id = msg.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            sessionId = id,
                            role = msg.optString("role").ifBlank { "user" },
                            partsJson = msg.optString("parts_json", "[]"),
                            createdAt = msg.optLong("created_at", System.currentTimeMillis()),
                            sortOrder = msg.optInt("sort_order", m),
                            reasoningContent = msg.optString("reasoning_content").ifBlank { null },
                        ),
                    )
                }
            }
            Unit
        }

    // -- snapshot -----------------------------------------------------------------------------

    private suspend fun buildSnapshot(
        chatRepository: ChatRepository,
        device: SyncDeviceInfo,
    ): Pair<JSONObject, Int> {
        val sessions = chatRepository.observeSessions().first()
            .sortedByDescending { it.updatedAt }
            .take(MAX_SESSIONS_PER_SNAPSHOT)
        val arr = JSONArray()
        var truncated = false
        for (session in sessions) {
            val (messages, cut) = loadMessages(chatRepository, session.id)
            if (cut) truncated = true
            arr.put(
                JSONObject()
                    .put("session", JSONObject()
                        .put("id", session.id)
                        .put("title", session.title ?: "")
                        .put("category", session.category ?: "")
                        .put("model_id", session.modelId)
                        .put("created_at", session.createdAt)
                        .put("updated_at", session.updatedAt)
                        .put("deleted", false))
                    .put("messages", messages),
            )
        }
        val snapshot = JSONObject()
            .put("v", 1)
            .put("meta", JSONObject()
                .put("device_id", device.deviceId)
                .put("device_name", device.deviceName)
                .put("created_at", System.currentTimeMillis())
                .put("truncated", truncated))
            .put("sessions", arr)
        return snapshot to sessions.size
    }

    private suspend fun loadMessages(
        chatRepository: ChatRepository,
        sessionId: String,
    ): Pair<JSONArray, Boolean> {
        val arr = JSONArray()
        var offset = 0
        var cut = false
        while (offset < MAX_MESSAGES_PER_SESSION) {
            val batch = chatRepository.loadMessagePageRaw(sessionId, offset, MESSAGE_PAGE)
            if (batch.isEmpty()) break
            for (msg in batch) {
                arr.put(
                    JSONObject()
                        .put("id", msg.id)
                        .put("role", msg.role)
                        .put("parts_json", msg.partsJson)
                        .put("created_at", msg.createdAt)
                        .put("sort_order", msg.sortOrder)
                        .put("reasoning_content", msg.reasoningContent ?: ""),
                )
            }
            offset += batch.size
            if (batch.size < MESSAGE_PAGE) break
        }
        if (offset >= MAX_MESSAGES_PER_SESSION) cut = true
        return arr to cut
    }
}
