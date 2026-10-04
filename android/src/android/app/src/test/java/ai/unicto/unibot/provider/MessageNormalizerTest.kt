package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.AgentContentPart
import ai.unicto.unibot.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the [MessageNormalizer] v1.2.2 broadenings
 * ([T-android-v122-400-diag]): orphaned ToolResult drops, dangling
 * tool-message drops, whitespace handling — and the deliberate decision that
 * no-arg (`{}`) ToolUse parts are NOT stripped (spec-valid; only unanswered
 * calls are dropped via pairing repair).
 */
class MessageNormalizerTest {

    private fun toolUse(id: String, args: JSONObject = JSONObject().put("path", "/x")) =
        AgentContentPart.ToolUse(id, "read_file", args)

    private fun toolResult(id: String) =
        AgentContentPart.ToolResult(id, "read_file", "file contents")

    private fun assistant(vararg parts: AgentContentPart, content: String = "") =
        LLMMessage(LLMMessage.Role.ASSISTANT, content, contentParts = parts.toList())

    private fun user(content: String = "", vararg parts: AgentContentPart) =
        LLMMessage(LLMMessage.Role.USER, content, contentParts = parts.toList())

    private fun toolUseIds(msg: LLMMessage) =
        msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }

    private fun toolResultIds(msg: LLMMessage) =
        msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id }

    @Test
    fun `unanswered no-arg tool call is dropped via pairing repair`() {
        val msgs = listOf(
            user("hi"),
            assistant(toolUse("c1", JSONObject())),
            user("done"),
        )
        val out = MessageNormalizer.normalize(msgs)
        // No result ever answered c1 -> pairing repair strips the call, the
        // assistant drops as empty, and the two user messages merge.
        assertEquals(1, out.size)
        assertEquals(LLMMessage.Role.USER, out[0].role)
        assertTrue(toolUseIds(out[0]).isEmpty())
    }

    @Test
    fun `answered no-arg tool call survives with its result`() {
        // Regression guard: "{}" arguments are spec-valid and must NOT be
        // stripped — only the pairing matters.
        val msgs = listOf(
            user("hi"),
            assistant(toolUse("c1", JSONObject())),
            user("", toolResult("c1")),
        )
        val out = MessageNormalizer.normalize(msgs)
        assertEquals(3, out.size)
        assertEquals(listOf("c1"), toolUseIds(out[1]))
        assertEquals(listOf("c1"), toolResultIds(out[2]))
    }

    @Test
    fun `toolUse with non-empty arguments is kept`() {
        val msgs = listOf(
            user("hi"),
            assistant(toolUse("c1"), content = "reading"),
            user("", toolResult("c1")),
        )
        val out = MessageNormalizer.normalize(msgs)
        assertEquals(listOf("c1"), toolUseIds(out[1]))
        assertEquals(listOf("c1"), toolResultIds(out[2]))
    }

    @Test
    fun `orphaned tool result is dropped, message collapses when empty`() {
        val msgs = listOf(
            user("hi"),
            assistant(toolUse("c1"), content = "reading"),
            user("", toolResult("c1"), toolResult("cZZZ")),
            user("thanks"),
        )
        val out = MessageNormalizer.normalize(msgs)
        val results = out.flatMap(::toolResultIds)
        assertEquals(listOf("c1"), results)
        assertTrue(results.none { it == "cZZZ" })
    }

    @Test
    fun `tool result first in list is dropped`() {
        val msgs = listOf(
            user("", toolResult("c1")),
            assistant(toolUse("c1"), content = "reading"),
            user("", toolResult("c1")),
        )
        val out = MessageNormalizer.normalize(msgs)
        // Leading dangling result dropped; the remaining pair is valid.
        assertEquals(2, out.size)
        assertEquals(LLMMessage.Role.ASSISTANT, out[0].role)
        assertEquals(listOf("c1"), toolResultIds(out[1]))
    }

    @Test
    fun `dangling tool message after user drops and fixpoint strips its orphaned call`() {
        val msgs = listOf(
            user("hello"),
            assistant(toolUse("c1"), content = "reading"),
            user("ok"),
            user("", toolResult("c1")),
        )
        val out = MessageNormalizer.normalize(msgs)
        // The tool result can't pair positionally (a user message intervenes),
        // so it drops; the fixpoint then strips the now-unanswered tool_call.
        assertTrue(out.flatMap(::toolResultIds).isEmpty())
        assertTrue(out.flatMap(::toolUseIds).isEmpty())
        // No wire-illegal "tool after user" adjacency survives.
        val roles = out.map { it.role }
        assertEquals(listOf(LLMMessage.Role.USER, LLMMessage.Role.ASSISTANT, LLMMessage.Role.USER), roles)
    }

    @Test
    fun `whitespace-only assistant content is treated as empty`() {
        val msgs = listOf(
            user("hi"),
            assistant(content = "  \n "),
            user("still there?"),
        )
        val out = MessageNormalizer.normalize(msgs)
        assertEquals(1, out.size)
        assertEquals("hi\n\nstill there?", out[0].content)
    }

    @Test
    fun `merge does not let whitespace survive`() {
        val msgs = listOf(
            user("a"),
            user("  "),
            user("b"),
        )
        val out = MessageNormalizer.normalize(msgs)
        assertEquals(1, out.size)
        assertEquals("a\n\nb", out[0].content)
    }

    @Test
    fun `valid tool pairing survives untouched`() {
        val msgs = listOf(
            user("read it"),
            assistant(toolUse("c1"), toolUse("c2"), content = "on it"),
            user("", toolResult("c1"), toolResult("c2")),
            user("thanks"),
        )
        val out = MessageNormalizer.normalize(msgs)
        // The trailing "thanks" user message merges with the tool-result
        // message (same role); pairing itself is untouched.
        assertEquals(3, out.size)
        assertEquals(listOf("c1", "c2"), toolUseIds(out[1]))
        assertEquals(listOf("c1", "c2"), toolResultIds(out[2]))
    }

    @Test
    fun `two tool-leading user messages after assistant merge cleanly`() {
        val msgs = listOf(
            assistant(toolUse("c1"), toolUse("c2"), content = "on it"),
            user("", toolResult("c1")),
            user("", toolResult("c2")),
        )
        val out = MessageNormalizer.normalize(msgs)
        // Both kept (second follows another tool message -> will merge), then
        // merged into one user message: wire order stays assistant, tool, tool.
        assertEquals(2, out.size)
        assertEquals(listOf("c1", "c2"), toolResultIds(out[1]))
    }
}
