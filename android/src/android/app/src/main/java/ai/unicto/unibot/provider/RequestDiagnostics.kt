package ai.unicto.unibot.provider

import ai.unicto.unibot.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shape-only diagnostics for HTTP 400s on chat requests.
 *
 * [T-android-v122-400-diag] The v1.2.1 [MessageNormalizer] repair did not stop
 * the user's Groq-backed agent from tripping
 * `400 'messages.N': for 'role:assistant' the following must be satisfied` —
 * so the normalizer is either not catching the real malformation or some
 * request path bypasses it. Rather than guess again, every provider's 400
 * path now logs a DIAGNOSTIC block describing ONLY the wire shape of the
 * rejected request: provider name/kind, message count, the role sequence,
 * and per-message flags (empty content, tool_call ids, tool_result ids, and
 * whether each tool result matched a prior tool_call id).
 *
 * PRIVACY: this helper NEVER logs message text, tool arguments, tool-result
 * content, image bytes, or any other payload. Ids are opaque correlation
 * tokens (`call_abc…`), safe to keep. The server's own error body is already
 * logged separately by the existing T321 error line; it is deliberately NOT
 * repeated here so this block stays shapes-only.
 *
 * The block is parsed from the already-serialized request body — i.e. exactly
 * what the server rejected — rather than from the [ai.unicto.unibot.data.model.LLMMessage]
 * list, so provider-specific wire expansions (OpenAI `tool` messages split
 * out of user messages, Responses `input` items, Anthropic content blocks,
 * Gemini `parts`) are reflected faithfully.
 */
object RequestDiagnostics {

    enum class Kind { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI }

    /** One wire message's structural summary — no payload, ever. */
    private data class MsgShape(
        val label: String,
        val emptyContent: Boolean,
        val toolCallIds: List<String> = emptyList(),
        val toolResultIds: List<String> = emptyList(),
        /** Parallel to [toolResultIds]: did the id match an earlier tool_call? */
        val resultMatchedPriorCall: List<Boolean> = emptyList(),
    )

    /**
     * Logs the diagnostic block to the app log (Settings → Logs) as
     * `[DIAG-400]`-prefixed error lines. Safe to call from any 400 path;
     * never throws — a diagnostic must not mask the real error.
     */
    fun logHttp400(tag: String, providerName: String, kind: Kind, body: JSONObject) {
        val lines = try {
            buildLines(providerName, kind, body)
        } catch (t: Throwable) {
            listOf("[DIAG-400] diagnostic build failed: ${t.javaClass.simpleName}")
        }
        for (line in lines) AppLogger.error(tag, line)
    }

    private fun buildLines(providerName: String, kind: Kind, body: JSONObject): List<String> {
        val shapes = when (kind) {
            Kind.OPENAI_CHAT -> parseOpenAiChat(body)
            Kind.OPENAI_RESPONSES -> parseOpenAiResponses(body)
            Kind.ANTHROPIC -> parseAnthropic(body)
            Kind.GEMINI -> parseGemini(body)
        }
        val lines = ArrayList<String>(shapes.size + 1)
        lines.add(
            "[DIAG-400] provider=$providerName kind=${kind.name.lowercase()} " +
                "messages=${shapes.size} roles=${shapes.joinToString(",") { it.label }}",
        )
        for ((i, s) in shapes.withIndex()) {
            val sb = StringBuilder("[DIAG-400] [$i] role=${s.label} emptyContent=${s.emptyContent}")
            if (s.toolCallIds.isNotEmpty()) {
                sb.append(" toolCalls=${s.toolCallIds.size} ids=${s.toolCallIds.joinToString(",", "[", "]") { shortId(it) }}")
            }
            if (s.toolResultIds.isNotEmpty()) {
                val matched = s.toolResultIds.mapIndexed { idx, id ->
                    "${shortId(id)}:${s.resultMatchedPriorCall.getOrNull(idx)}"
                }
                sb.append(" toolResults=${matched.joinToString(",", "[", "]")}")
            }
            lines.add(sb.toString())
        }
        return lines
    }

    /** Opaque ids stay useful for pairing diagnosis; cap length for log size. */
    private fun shortId(id: String): String =
        if (id.length <= 32) id else id.take(29) + "…"

    private fun isBlankContent(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> true
        is String -> value.isBlank()
        is JSONArray -> value.length() == 0
        else -> false
    }

    // ─── OpenAI Chat Completions: body.messages[] ────────────────────────────

    private fun parseOpenAiChat(body: JSONObject): List<MsgShape> {
        val arr = body.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<MsgShape>(arr.length())
        val seenCallIds = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "?").ifEmpty { "?" }
            val callIds = m.optJSONArray("tool_calls")?.let { tc ->
                (0 until tc.length()).mapNotNull {
                    tc.optJSONObject(it)?.optString("id", "")?.takeIf { id -> id.isNotEmpty() }
                }
            } ?: emptyList()
            val resultIds = if (role == "tool") {
                listOfNotNull(m.optString("tool_call_id", "").takeIf { it.isNotEmpty() })
            } else emptyList()
            out.add(
                MsgShape(
                    label = role,
                    emptyContent = isBlankContent(m.opt("content")),
                    toolCallIds = callIds,
                    toolResultIds = resultIds,
                    resultMatchedPriorCall = resultIds.map { it in seenCallIds },
                ),
            )
            if (role == "assistant") seenCallIds += callIds
        }
        return out
    }

    // ─── OpenAI Responses API: body.input[] ──────────────────────────────────

    private fun parseOpenAiResponses(body: JSONObject): List<MsgShape> {
        val arr = body.optJSONArray("input") ?: return emptyList()
        val out = ArrayList<MsgShape>(arr.length())
        val seenCallIds = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            when (item.optString("type", "")) {
                "function_call" -> {
                    val id = item.optString("call_id", "").ifEmpty { item.optString("id", "") }
                    out.add(
                        MsgShape(
                            label = "assistant[function_call]",
                            emptyContent = true,
                            toolCallIds = listOfNotNull(id.takeIf { it.isNotEmpty() }),
                        ),
                    )
                    if (id.isNotEmpty()) seenCallIds += id
                }
                "function_call_output" -> {
                    val id = item.optString("call_id", "").ifEmpty { item.optString("id", "") }
                    val ids = listOfNotNull(id.takeIf { it.isNotEmpty() })
                    out.add(
                        MsgShape(
                            label = "tool",
                            emptyContent = isBlankContent(item.opt("output")),
                            toolResultIds = ids,
                            resultMatchedPriorCall = ids.map { it in seenCallIds },
                        ),
                    )
                }
                "message" -> {
                    out.add(
                        MsgShape(
                            label = item.optString("role", "?").ifEmpty { "?" },
                            emptyContent = isBlankContent(item.opt("content")),
                        ),
                    )
                }
                else -> {
                    // reasoning items, etc. — shape only, no payload.
                    out.add(MsgShape(label = "item:${item.optString("type", "?")}", emptyContent = true))
                }
            }
        }
        return out
    }

    // ─── Anthropic: body.messages[] with content blocks ──────────────────────

    private fun parseAnthropic(body: JSONObject): List<MsgShape> {
        val arr = body.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<MsgShape>(arr.length())
        val seenCallIds = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "?").ifEmpty { "?" }
            val content = m.opt("content")
            val callIds = ArrayList<String>()
            val resultIds = ArrayList<String>()
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    when (block.optString("type", "")) {
                        "tool_use" -> block.optString("id", "").takeIf { it.isNotEmpty() }?.let(callIds::add)
                        "tool_result" -> block.optString("tool_use_id", "").takeIf { it.isNotEmpty() }?.let(resultIds::add)
                    }
                }
            }
            out.add(
                MsgShape(
                    label = role,
                    emptyContent = isBlankContent(content),
                    toolCallIds = callIds,
                    toolResultIds = resultIds,
                    resultMatchedPriorCall = resultIds.map { it in seenCallIds },
                ),
            )
            if (role == "assistant") seenCallIds += callIds
        }
        return out
    }

    // ─── Gemini: body.contents[] with parts[] ────────────────────────────────

    private fun parseGemini(body: JSONObject): List<MsgShape> {
        val arr = body.optJSONArray("contents") ?: return emptyList()
        val out = ArrayList<MsgShape>(arr.length())
        val seenCallNames = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "?").ifEmpty { "?" }
            val parts = m.optJSONArray("parts")
            val callNames = ArrayList<String>()
            val resultNames = ArrayList<String>()
            var hasNonBlankText = false
            if (parts != null) {
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    part.optJSONObject("functionCall")?.optString("name", "")
                        ?.takeIf { it.isNotEmpty() }?.let(callNames::add)
                    part.optJSONObject("functionResponse")?.optString("name", "")
                        ?.takeIf { it.isNotEmpty() }?.let(resultNames::add)
                    if (part.optString("text", "").isNotBlank()) hasNonBlankText = true
                }
            }
            out.add(
                MsgShape(
                    label = role,
                    // Gemini functionCall/functionResponse parts carry no
                    // "text"; a parts[] holding only those is non-empty.
                    emptyContent = parts == null || parts.length() == 0 ||
                        (!hasNonBlankText && callNames.isEmpty() && resultNames.isEmpty()),
                    toolCallIds = callNames,
                    toolResultIds = resultNames,
                    resultMatchedPriorCall = resultNames.map { it in seenCallNames },
                ),
            )
            if (role == "model") seenCallNames += callNames
        }
        return out
    }
}
