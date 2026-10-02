package ai.unicto.unibot.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [P2-branching] Archived alternative ("sibling") versions of an assistant
 * turn.
 *
 * One row = one alternative response for the turn that followed the user
 * message [anchorUserMessageId]. The anchor is the USER message id (not the
 * assistant message id) on purpose: regenerating a turn deletes + rewrites
 * the assistant rows via the normal retry path, but the user message that
 * prompted the turn survives, so the variants stay attached across
 * regenerations.
 *
 * [partsJson] holds the turn's rows as a JSON ARRAY of {role, parts}
 * objects in turn (sort_order) order — positional, not id-keyed, because
 * regenerating a turn deletes its rows and mints new ids (see
 * [ai.unicto.unibot.data.repository.ChatRepository.archiveMessageVariant]).
 * Single-row turns just carry a one-entry array.
 *
 * The LIVE (currently shown) content always lives in the `messages` row
 * itself — variants are only the archived alternatives. Switching between
 * siblings rewrites the live row's parts_json (via
 * [ChatDao.updateMessageParts]) so the rest of the pipeline (flattening,
 * selection toolbar, export) keeps working unchanged.
 *
 * No foreign keys: variants must survive the retry-truncation that deletes
 * the assistant rows they were archived from (the anchor user row is never
 * deleted by that path, but a defensive no-FK keeps a variant readable even
 * if its anchor is ever removed by another code path).
 */
@Entity(
    tableName = "message_variants",
    indices = [Index(value = ["session_id", "anchor_user_message_id"])],
)
data class MessageVariantEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "anchor_user_message_id") val anchorUserMessageId: String,
    @ColumnInfo(name = "variant_index") val variantIndex: Int,
    /** JSON object: message entity id → parts_json snapshot. */
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "model_display_name") val modelDisplayName: String? = null,
)
