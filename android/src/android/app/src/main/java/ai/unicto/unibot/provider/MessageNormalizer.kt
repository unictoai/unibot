package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.AgentContentPart
import ai.unicto.unibot.data.model.LLMMessage

/**
 * Provider-agnostic message-list repair, applied at the top of every
 * provider's request builder — the last line of defense between the agent
 * loop's in-memory history and strict OpenAI-compatible endpoints.
 *
 * [T-android-v121-message-normalize] Field report (v1.2, Groq): the agent's
 * "never surrender" retry path produced a request whose message list tripped
 * `400 'messages.4': for 'role:assistant' the following must be satisfied`.
 * Strict providers reject (a) consecutive `assistant` messages, (b) empty
 * assistant messages (no content and no tool_calls), and (c) `tool_calls`
 * whose results never arrive. The history builders in ChatViewModel can't
 * cheaply guarantee all three across every retry/compact/fork path, so the
 * guarantee lives here, once, for every provider (OpenAI-compatible,
 * Anthropic, Gemini, Responses).
 *
 * [T-android-v122-400-diag] The v1.2.1 repair did not stop the 400, so the
 * passes below were broadened for the tool_call edge cases the first version
 * missed: ToolUse parts with empty arguments, ToolResult parts whose id
 * matches no assistant tool_call anywhere in the list, and tool-result
 * messages left dangling (first in list, or after a non-assistant message)
 * by earlier drops. Pass ORDER is load-bearing — each pass documents what it
 * assumes about the previous ones. The merge pass runs last, then the
 * dangling/empty drops run once more: merging two USER neighbours can
 * re-create a dangling adjacency, so the result is only valid after the
 * final sweep.
 *
 * Passes, in order:
 *  0. Drop [AgentContentPart.ToolUse] parts with empty arguments.
 *  Then a fixpoint loop (each pass only ever removes parts/messages, so it
 *  always stabilizes — capped at 4 iterations):
 *  1. Tool-pairing repair — strip [AgentContentPart.ToolUse] parts whose id
 *     is never answered by a [AgentContentPart.ToolResult] before the next
 *     assistant turn (orphaned tool_calls 400 on every strict endpoint).
 *  2. Drop [AgentContentPart.ToolResult] parts whose id matches NO assistant
 *     tool_call id anywhere in the list (orphaned tool results 400 the same
 *     way); drop a USER message left with nothing at all.
 *  3. Drop empty assistant messages (blank content, no text/image/audio/tool
 *     parts).
 *  4. Drop dangling tool-result messages — a tool-leading USER message that
 *     ends up first in the list or after a non-assistant, non-tool message.
 *     The OpenAI serializer always emits a user message's `tool` wire
 *     messages before its text, so such a message would put `tool` first or
 *     after `user` on the wire, both rejected by strict endpoints.
 *     Dropping here can orphan the tool_call that issued the result — which
 *     is why pass 1 re-runs inside the loop.
 *  Finally: merge consecutive same-role messages (content concatenated, parts
 *  appended) — merging never crosses a role boundary, so tool pairing is
 *  preserved — then one last dangling/empty sweep and merge so the result is
 *  valid.
 */
object MessageNormalizer {

    fun normalize(messages: List<LLMMessage>): List<LLMMessage> {
        if (messages.size < 2) return messages.filterNot(::isEmptyAssistant)
        // [T-android-v122-400-diag] Fixpoint: dropping a dangling tool message
        // can orphan the tool_call that issued it (pass 1 must re-run), and
        // stripping that call can empty its assistant (pass 3 must re-run).
        // Every pass only removes parts/messages — never adds or reorders —
        // so the loop always stabilizes; the cap is belt-and-braces.
        var cur = stripEmptyArgToolUse(messages)
        var guard = 0
        while (guard++ < 4) {
            val next = dropDanglingToolMessages(
                stripOrphanedToolResults(repairToolPairing(cur))
                    .filterNot(::isEmptyAssistant),
            )
            if (next == cur) break
            cur = next
        }
        if (cur.size < 2) return cur
        val merged = mergeConsecutiveSameRole(cur)
        // Merging only fuses same-role neighbours: a tool-leading USER message
        // keeps the assistant predecessor the dangling pass verified, so the
        // merged list is already valid — one final sweep keeps it that way.
        val swept = dropDanglingToolMessages(merged).filterNot(::isEmptyAssistant)
        return mergeConsecutiveSameRole(swept)
    }

    /** True when an assistant message would serialize to `{"role":"assistant"}`
     *  with neither content nor tool_calls — rejected by strict providers. */
    private fun isEmptyAssistant(msg: LLMMessage): Boolean {
        if (msg.role != LLMMessage.Role.ASSISTANT) return false
        if (msg.content.isNotBlank()) return false
        if (msg.imageParts.isNotEmpty() || msg.audioParts.isNotEmpty()) return false
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> if (part.text.isNotBlank()) return false
                is AgentContentPart.ToolUse,
                is AgentContentPart.ImageData -> return false
                is AgentContentPart.ToolResult -> return false // shouldn't happen; be safe
            }
        }
        return true
    }

    /**
     * [T-android-v122-400-diag] Drop ToolUse parts whose `arguments` are empty
     * or missing (blank `{}`). An empty-arguments tool_call serializes to
     * `"arguments": ""`/`"{}"` with no usable input and is rejected by strict
     * schema validation; a message left with nothing else becomes empty and is
     * dropped by the empty-assistant pass.
     */
    private fun stripEmptyArgToolUse(messages: List<LLMMessage>): List<LLMMessage> =
        messages.map { msg ->
            if (msg.role != LLMMessage.Role.ASSISTANT) return@map msg
            if (msg.contentParts.none { it is AgentContentPart.ToolUse && it.input.length() == 0 }) {
                return@map msg
            }
            msg.copy(
                contentParts = msg.contentParts.filterNot {
                    it is AgentContentPart.ToolUse && it.input.length() == 0
                },
            )
        }

    /**
     * A tool_call id emitted by the assistant message at [fromIndex] is
     * satisfied when a USER message before the next ASSISTANT message carries
     * a ToolResult with the same id. Unsatisfied ToolUse parts are stripped;
     * a message left with nothing else becomes empty and is dropped by the
     * next pass.
     */
    private fun repairToolPairing(messages: List<LLMMessage>): List<LLMMessage> {
        // Precompute, per message index, the ToolResult ids in USER messages.
        val toolResultIdsByIndex = messages.map { msg ->
            if (msg.role == LLMMessage.Role.USER) {
                msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                    .map { it.id }.toSet()
            } else emptySet()
        }
        return messages.mapIndexed { index, msg ->
            if (msg.role != LLMMessage.Role.ASSISTANT) return@mapIndexed msg
            val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) return@mapIndexed msg
            // Collect ToolResult ids from following USER messages up to (not
            // including) the next ASSISTANT message.
            val answered = mutableSetOf<String>()
            for (j in index + 1 until messages.size) {
                val next = messages[j]
                if (next.role == LLMMessage.Role.ASSISTANT) break
                answered += toolResultIdsByIndex[j]
            }
            val orphaned = toolUses.filter { it.id !in answered }
            if (orphaned.isEmpty()) return@mapIndexed msg
            msg.copy(contentParts = msg.contentParts - orphaned.toSet())
        }
    }

    /**
     * [T-android-v122-400-diag] The mirror image of [repairToolPairing]: a
     * ToolResult part whose id matches NO assistant tool_call id anywhere in
     * the list is an orphaned tool result — strict endpoints 400 on a `tool`
     * message that answers nothing. Strip the orphaned parts; a USER message
     * left with blank content and no remaining parts/attachments is dropped
     * outright (it would otherwise serialize to a content-less `user` shell
     * or, worse, a bare leading `tool` message).
     *
     * Runs AFTER [repairToolPairing] so the id set reflects the tool_calls
     * that will actually be sent (a call stripped as unanswered, or for empty
     * arguments, must not keep its result alive).
     */
    private fun stripOrphanedToolResults(messages: List<LLMMessage>): List<LLMMessage> {
        val liveToolCallIds = messages
            .asSequence()
            .filter { it.role == LLMMessage.Role.ASSISTANT }
            .flatMap { it.contentParts.asSequence() }
            .filterIsInstance<AgentContentPart.ToolUse>()
            .map { it.id }
            .toSet()
        val out = ArrayList<LLMMessage>(messages.size)
        for (msg in messages) {
            if (msg.role != LLMMessage.Role.USER ||
                msg.contentParts.none { it is AgentContentPart.ToolResult }
            ) {
                out.add(msg)
                continue
            }
            val kept = msg.contentParts.filterNot {
                it is AgentContentPart.ToolResult && it.id !in liveToolCallIds
            }
            if (kept == msg.contentParts) {
                out.add(msg)
                continue
            }
            if (kept.isEmpty() && msg.content.isBlank() &&
                msg.imageParts.isEmpty() && msg.audioParts.isEmpty()
            ) {
                continue // nothing left worth sending
            }
            out.add(msg.copy(contentParts = kept))
        }
        return out
    }

    /**
     * [T-android-v122-400-diag] Drop tool-leading USER messages left dangling
     * by earlier drops: first in the list, or after a message that is neither
     * an assistant turn nor another tool-leading message (two tool-leading
     * USER neighbours are fine — the merge pass fuses them and the wire order
     * stays `assistant, tool, tool`).
     *
     * "Tool-leading": the message carries [AgentContentPart.ToolResult] parts.
     * The OpenAI serializer emits a user message's `tool` wire messages BEFORE
     * its text, so even a tool-leading message WITH text would put `tool`
     * first on the wire here — hence the parts are stripped rather than the
     * message dropped when real text survives; a message left with nothing is
     * dropped outright.
     */
    private fun dropDanglingToolMessages(messages: List<LLMMessage>): List<LLMMessage> {
        if (messages.isEmpty()) return messages
        val out = ArrayList<LLMMessage>(messages.size)
        for (msg in messages) {
            if (!isToolLeading(msg)) {
                out.add(msg)
                continue
            }
            val prev = out.lastOrNull()
            val preceded = prev != null &&
                (prev.role == LLMMessage.Role.ASSISTANT || isToolLeading(prev))
            if (preceded) {
                out.add(msg)
                continue
            }
            val stripped = msg.copy(
                contentParts = msg.contentParts.filterNot { it is AgentContentPart.ToolResult },
            )
            val hasText = stripped.content.isNotBlank() ||
                stripped.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (!hasText && stripped.contentParts.none { it is AgentContentPart.ImageData } &&
                stripped.imageParts.isEmpty() && stripped.audioParts.isEmpty()
            ) {
                continue // tool-only message: drop outright
            }
            out.add(stripped)
        }
        return out
    }

    private fun isToolLeading(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER &&
            msg.contentParts.any { it is AgentContentPart.ToolResult }

    private fun mergeConsecutiveSameRole(messages: List<LLMMessage>): List<LLMMessage> {
        val out = ArrayList<LLMMessage>(messages.size)
        for (msg in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == msg.role) {
                out[out.lastIndex] = mergePair(last, msg)
            } else {
                out.add(msg)
            }
        }
        return out
    }

    private fun mergePair(a: LLMMessage, b: LLMMessage): LLMMessage {
        // isBlank (not isEmpty): whitespace-only content never survives a
        // merge — "  \n " collapses to the other side's content.
        val content = when {
            a.content.isBlank() -> b.content
            b.content.isBlank() -> a.content
            else -> a.content + "\n\n" + b.content
        }
        return a.copy(
            content = content,
            imageParts = a.imageParts + b.imageParts,
            audioParts = a.audioParts + b.audioParts,
            contentParts = a.contentParts + b.contentParts,
            reasoningContent = a.reasoningContent ?: b.reasoningContent,
            dbMessageId = a.dbMessageId ?: b.dbMessageId,
        )
    }
}
