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
 * Three passes, in order:
 *  1. Tool-pairing repair — strip [AgentContentPart.ToolUse] parts whose id
 *     is never answered by a [AgentContentPart.ToolResult] before the next
 *     assistant turn (orphaned tool_calls 400 on every strict endpoint).
 *  2. Drop empty assistant messages (blank content, no text/image/audio/tool
 *     parts).
 *  3. Merge consecutive same-role messages (content concatenated, parts
 *     appended) — merging never crosses a role boundary, so tool pairing
 *     established in pass 1 is preserved.
 */
object MessageNormalizer {

    fun normalize(messages: List<LLMMessage>): List<LLMMessage> {
        if (messages.size < 2) return messages.filterNot(::isEmptyAssistant)
        val paired = repairToolPairing(messages)
        val nonEmpty = paired.filterNot(::isEmptyAssistant)
        if (nonEmpty.size < 2) return nonEmpty
        return mergeConsecutiveSameRole(nonEmpty)
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
