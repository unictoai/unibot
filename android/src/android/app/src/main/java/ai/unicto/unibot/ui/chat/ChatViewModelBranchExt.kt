package ai.unicto.unibot.ui.chat

// [P2-branching] Sibling-variant ("1/3" switcher) behaviour, extracted from
// ChatViewModel the same way ChatViewModelMentionExt was: state fields live
// on the class as `internal`, the behaviour lives here.
//
// Data model recap (see MessageVariantEntity):
// - Variants are anchored to the USER message id that prompted the turn —
//   the user row survives the retry-truncation that regeneration performs,
//   so siblings stay attached across regenerations.
// - A variant's parts_json is a JSON array of {role, parts} snapshots in
//   turn order, covering the WHOLE turn range (assistant rows + interleaved
//   tool_result user rows), so switching restores tool outputs faithfully.
//   Snapshots are positional (not id-keyed) because regeneration mints new
//   row ids; on switch they zip onto the CURRENT live rows by position.
// - The live content always lives in the `messages` rows; variants are only
//   the archived alternatives.

import ai.unicto.unibot.data.db.MessageEntity
import ai.unicto.unibot.logging.AppLogger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * (Re)load all archived variants for the current session into
 * [_branchVariants] and clamp [_branchSelection]. Call on session load.
 */
internal fun ChatViewModel.refreshBranchVariants() {
    viewModelScope.launch { loadBranchVariants() }
}

internal suspend fun ChatViewModel.loadBranchVariants() {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return
    val all = withContext(Dispatchers.IO) { chatRepository.listMessageVariants(sid) }
    _branchVariants.value = all.groupBy { it.anchorUserMessageId }
    _branchSelection.value = _branchSelection.value.mapNotNull { (anchor, sel) ->
        val count = _branchVariants.value[anchor]?.size ?: 0
        if (count == 0) null else anchor to sel.coerceIn(0, count)
    }.toMap()
    _branchLiveCache.keys.removeAll { it !in _branchVariants.value }
}

/**
 * The DB rows belonging to the turn that follows [anchor] in _messages:
 * everything after the anchor's sort_order up to (not including) the next
 * visible user message. Covers assistant rows AND the interleaved
 * tool_result user rows so a switched variant restores tool outputs too.
 *
 * Internal so the fork path (ChatViewModelForkExt) can resolve an assistant
 * UI bubble — whose in-memory id is a temp `assistant_…` id, NOT the DB row
 * id — to its DB rows.
 */
internal suspend fun ChatViewModel.turnEntities(
    sid: String,
    anchor: ChatMessage,
    anchorIdx: Int,
): List<MessageEntity> {
    val entities = withContext(Dispatchers.IO) { chatRepository.loadMessages(sid) }
    val anchorEntity = entities.firstOrNull { it.id == anchor.id } ?: return emptyList()
    val messages = _messages.value
    val nextUserSort = messages.subList(anchorIdx + 1, messages.size)
        .firstOrNull { it.role == "user" }
        ?.let { next -> entities.firstOrNull { it.id == next.id }?.sortOrder }
        ?: Int.MAX_VALUE
    return entities.filter { it.sortOrder > anchorEntity.sortOrder && it.sortOrder < nextUserSort }
}

/** Snapshot the turn rows positionally: (role → parts_json) in turn order. */
private fun snapshotTurn(rows: List<MessageEntity>): List<Pair<String, String>> =
    rows.map { it.role to it.partsJson }

/**
 * The index range of the assistant-message run starting at the first
 * assistant bubble after [anchorIdx] (stops at the first non-assistant).
 * A turn with tool calls is ONE live bubble but rebuilds from the DB as
 * TWO (the merge in toChatMessages only folds CONSECUTIVE assistant rows —
 * an interleaved tool_result user row breaks it), so the splice must cover
 * the whole run, not a single bubble.
 */
private fun assistantRunAfter(
    messages: List<ChatMessage>,
    anchorIdx: Int,
): IntRange? {
    val startOffset = messages.subList(anchorIdx + 1, messages.size)
        .indexOfFirst { it.role == "assistant" }
    if (startOffset < 0) return null
    var end = anchorIdx + 1 + startOffset
    while (end + 1 < messages.size && messages[end + 1].role == "assistant") end++
    return (anchorIdx + 1 + startOffset)..end
}

/**
 * Write [snapshots] onto [liveRows] positionally (index-aligned). When the
 * shapes differ (e.g. the archived turn had no tools but the live one does),
 * only the overlapping prefix is written — a graceful degradation rather
 * than a corrupt interleave.
 */
private suspend fun ChatViewModel.writeSnapshots(
    liveRows: List<MessageEntity>,
    snapshots: List<Pair<String, String>>,
) {
    val n = minOf(liveRows.size, snapshots.size)
    if (n <= 0) return
    withContext(Dispatchers.IO) {
        for (i in 0 until n) {
            val (role, parts) = snapshots[i]
            if (role.isNotEmpty() && role != liveRows[i].role) {
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Branch] role drift at position $i (snapshot=$role live=${liveRows[i].role}) — writing anyway",
                )
            }
            chatRepository.updateMessageParts(liveRows[i].id, parts)
        }
    }
}

/**
 * Regenerate the assistant turn for [assistantMessageId] as a new sibling:
 * archive the current turn's rows as a variant, then re-run the turn through
 * the existing retry path. The fresh response becomes the live content;
 * the pager ("1/2", "2/3", …) appears under the turn.
 */
fun ChatViewModel.regenerateBranch(assistantMessageId: String) {
    if (_isStreaming.value) return
    val messages = _messages.value
    val idx = messages.indexOfFirst { it.id == assistantMessageId }
    if (idx < 0) return
    val msg = messages[idx]
    if (msg.role != "assistant") return
    val anchorIdx = messages.subList(0, idx).indexOfLast { it.role == "user" }
    if (anchorIdx < 0) return
    val anchor = messages[anchorIdx]
    viewModelScope.launch {
        val sid = realSessionId.ifEmpty { sessionId }
        // Archive the current turn before the retry truncation deletes it.
        val turnRows = turnEntities(sid, anchor, anchorIdx)
        if (turnRows.isNotEmpty()) {
            val lastAssistant = turnRows.lastOrNull { it.role == "assistant" }
            withContext(Dispatchers.IO) {
                chatRepository.archiveMessageVariant(
                    sessionId = sid,
                    anchorUserMessageId = anchor.id,
                    rows = snapshotTurn(turnRows),
                    modelId = lastAssistant?.modelId,
                    modelDisplayName = lastAssistant?.modelDisplayName,
                )
            }
            loadBranchVariants()
        }
        // Point the pager at the live (about-to-be-regenerated) sibling.
        val count = _branchVariants.value[anchor.id]?.size ?: 0
        _branchSelection.value = _branchSelection.value + (anchor.id to count)
        _branchLiveCache.remove(anchor.id)
        AppLogger.info(ChatViewModel.TAG, "[Branch] regenerate anchored at ${anchor.id} (archived ${turnRows.size} row(s))")
        // Reuse the battle-tested retry path: truncates after the anchor,
        // rebuilds agentHistory, streams the new turn.
        retryFromMessage(anchor.id)
    }
}

/**
 * Show sibling [index] of the turn anchored at [anchorUserMessageId].
 * `index == variants.size` addresses the live content.
 */
fun ChatViewModel.selectBranchVariant(anchorUserMessageId: String, index: Int) {
    val variants = _branchVariants.value[anchorUserMessageId] ?: return
    if (index !in 0..variants.size) return
    if (_isStreaming.value) return
    viewModelScope.launch {
        val sid = realSessionId.ifEmpty { sessionId }
        val messages = _messages.value
        val anchorIdx = messages.indexOfFirst { it.id == anchorUserMessageId }
        if (anchorIdx < 0) return@launch
        val anchor = messages[anchorIdx]
        val liveRows = turnEntities(sid, anchor, anchorIdx)
        if (liveRows.isEmpty()) return@launch

        val snapshots: List<Pair<String, String>> = if (index == variants.size) {
            // Back to live — restore what was stashed on the first switch
            // away. Absent (e.g. after a process restart) means the rows
            // already hold the live content; nothing to do.
            _branchLiveCache[anchorUserMessageId] ?: return@launch
        } else {
            // Stash the live rows once, before the first switch-away, so
            // "back to live" can restore them.
            if (!_branchLiveCache.containsKey(anchorUserMessageId)) {
                _branchLiveCache[anchorUserMessageId] = snapshotTurn(liveRows)
            }
            chatRepository.unpackMessageVariant(variants[index])
        }
        if (snapshots.isEmpty()) return@launch
        writeSnapshots(liveRows, snapshots)

        // Rebuild the affected UI bubble(s) from the rewritten rows. Match
        // by POSITION (assistant run after the anchor), not by id: a
        // freshly-streamed bubble still carries its temp `assistant_…`
        // in-memory id, which never equals the DB row id — id-matching would
        // silently no-op on turns from this session lifetime. The anchor is
        // a user message whose in-memory id IS the DB id, so the anchor
        // lookup is stable in both lists. The whole assistant RUN is
        // spliced (a tool-using turn rebuilds as two bubbles where the live
        // list has one).
        val rebuiltRun = withContext(Dispatchers.IO) {
            val rebuilt = chatRepository.loadMessages(sid).toChatMessages()
            val rAnchorIdx = rebuilt.indexOfFirst { it.id == anchorUserMessageId }
            if (rAnchorIdx < 0) null
            else assistantRunAfter(rebuilt, rAnchorIdx)
                ?.let { range -> rebuilt.subList(range.first, range.last + 1).toList() }
        }
        val liveRun = assistantRunAfter(messages, anchorIdx)
        if (!rebuiltRun.isNullOrEmpty() && liveRun != null) {
            val patched = messages.toMutableList()
            patched.subList(liveRun.first, liveRun.last + 1).clear()
            patched.addAll(liveRun.first, rebuiltRun)
            _messages.value = patched
        }
        _branchSelection.value = _branchSelection.value + (anchorUserMessageId to index)
        AppLogger.info(ChatViewModel.TAG, "[Branch] selected sibling $index/${variants.size} at $anchorUserMessageId")
    }
}
