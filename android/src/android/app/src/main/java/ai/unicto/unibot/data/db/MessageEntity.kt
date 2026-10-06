package ai.unicto.unibot.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["session_id", "sort_order"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "token_usage") val tokenUsage: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "stream_interrupt_count") val streamInterruptCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,        // milliseconds
    // [T-error-persist-android] Terminal error sticker for an assistant turn
    // (mirrors iOS messages.error_info / ChatMessage.error). Null for normal
    // rows; device-local, never synced to iCloud.
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    // v1.4.0 item 26 — machine-readable error kind beside [errorInfo]
    // (ChatMessage.errorKind). Persisted so a restored 404/413 never shows a
    // futile Retry again. Null = no special UI.
    @ColumnInfo(name = "error_kind") val errorKind: String? = null,
    // v1.4.0 item 93 (power-user handoff) — question card attached to this
    // assistant message (ChatMessage.questionCardId). The card itself lives
    // in QuestionCardStore; this keeps the in-chat host rendering after a
    // reload. Null for ordinary messages.
    @ColumnInfo(name = "question_card_id") val questionCardId: String? = null,
    // [T-token-attribution-snapshot] Which model actually produced this
    // message, captured AT WRITE TIME as an immutable snapshot.
    //
    // Why a snapshot and not a reference: the Usage page used to derive the
    // model by joining `sessions.model_id`, a single mutable column rewritten
    // on every model switch (including automatic failover). With no time
    // dimension in the join, a session's whole history was re-attributed to
    // whatever model it currently points at — switch to grok and 1B deepseek
    // tokens moved to grok; switch back and they moved back.
    //
    // These columns record what actually served the request, so past usage
    // stops depending on present configuration. `model_display_name` and
    // `provider_type` are stored too, not just the id, so a provider the user
    // later deletes still renders as "the model I used" instead of collapsing
    // into an Unknown bucket (deleting an instance also drops its
    // modelEntries, which is the only lookup source for a custom model).
    //
    // All four are nullable ON PURPOSE: rows written before this migration
    // have NULL, and that is exactly how the UI tells "measured" apart from
    // "estimated from the session" (see UsageStatsScreen's four states). A
    // NOT NULL + DEFAULT would erase that distinction.
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "model_display_name") val modelDisplayName: String? = null,
    /** `ProviderType` **rawValue** (e.g. `openAI`), never a display name. */
    @ColumnInfo(name = "provider_type") val providerType: String? = null,
    /** Diagnostics / disambiguation only — the UI never resolves through it. */
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String? = null,
)
