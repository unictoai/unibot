package ai.unicto.unibot.sync

/**
 * Cross-device session continuity (P8) — the local data model.
 *
 * A sync snapshot is the list of chat sessions on this phone plus their messages,
 * serialized to JSON, sealed with [SyncCrypto] (a key derived from the user's
 * passphrase — the relay never sees plaintext), and handed to a [SyncTransport].
 *
 * Transport reality (relay 0.5, checked 2026-10-02): the cloud relay exposes
 * chat, `/v1/me/profile` (the agent's name/look), `/v1/me/sessions` (sign-in
 * metadata) and the activity log — there is NO endpoint that accepts or serves
 * chat session snapshots. So the transport is stubbed ([StubRelaySyncTransport])
 * and the engine keeps snapshots encrypted on this phone until the relay grows
 * one. Nothing here invents a relay API.
 *
 * Conflict policy when a transport exists: last-writer-wins per session on
 * `updatedAt`; a `deleted` tombstone wins over any older content.
 */
data class SyncSessionRecord(
    val id: String,
    val title: String?,
    val category: String?,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Tombstone: the session was deleted on the writing device. */
    val deleted: Boolean = false,
)

data class SyncMessageRecord(
    val id: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val sortOrder: Int,
    val reasoningContent: String? = null,
)

/** One session and its messages, as carried inside a sealed envelope. */
data class SyncSessionPayload(
    val session: SyncSessionRecord,
    val messages: List<SyncMessageRecord>,
)

data class SyncSnapshotMeta(
    val deviceId: String,
    val deviceName: String,
    val createdAt: Long,
    /** True when at least one session's message list was cut short (see the engine's caps). */
    val truncated: Boolean = false,
)

/** The outcome of one [SessionSyncEngine.syncNow] run, for the UI. */
sealed interface SyncOutcome {
    /** The snapshot was built and handed to the transport. */
    data class Done(val sessions: Int, val note: String) : SyncOutcome
    /** Not attempted: sync off, not signed in, or no passphrase. */
    data class NotReady(val reason: String) : SyncOutcome
    /** Attempted but failed (today: the relay has no sync endpoint). */
    data class Failed(val reason: String) : SyncOutcome
}
