package ai.unicto.unibot.ui.voice

import ai.unicto.unibot.ui.chat.AssistantBlock
import ai.unicto.unibot.ui.chat.ChatMessage
import ai.unicto.unibot.ui.chat.StreamingDelta
import ai.unicto.unibot.ui.chat.ToolBlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 3: the voice loop locked the reply id to the FIRST new assistant
 * message of the turn, so when the agent loop sealed its bubble mid-turn
 * (in-loop compaction) and continued in a fresh bubble, the rest of the
 * reply never reached speech, lastExchange, or the returned text.
 * [resolveVoiceTurnSnapshot] tracks every new assistant message instead.
 *
 * Bug 7: the reply wait is bounded by activity, not a fixed deadline —
 * [isVoiceReplyStalled] only fires after a full window with no agent
 * activity, and [VoiceTurnSnapshot.isAgentActive] is the activity signal.
 */
class VoiceTurnSnapshotTest {

    private fun userMsg(id: String = "user_1") =
        ChatMessage(id = id, role = "user", content = "What is 40 + 2?")

    // Bug 3 repro: a tool-calling turn where the agent loop seals its first
    // bubble (tool already ran) and streams the final answer into a second
    // bubble. Old code: replyId locked to "assistant_sealed" → liveText was
    // only "Let me check." and "The answer is 42." was never spoken.
    @Test
    fun tracksSecondAssistantMessageAfterToolCall() {
        val beforeIds = setOf("user_1", "old_assistant")

        // First poll: only the sealed bubble exists, streaming its text.
        var snap = resolveVoiceTurnSnapshot(
            messages = listOf(
                userMsg(),
                ChatMessage(
                    id = "assistant_sealed",
                    role = "assistant",
                    content = "",
                    toolBlocks = listOf(
                        AssistantBlock(
                            id = "t1",
                            kind = "tool_use",
                            toolStatus = ToolBlockStatus.RUNNING,
                            toolName = "calculator",
                        ),
                    ),
                ),
            ),
            streamingById = mapOf(
                "assistant_sealed" to StreamingDelta("Let me check.", emptyList(), false),
            ),
            beforeIds = beforeIds,
            isStreaming = true,
            trackedIds = emptyList(),
        )
        assertEquals(listOf("assistant_sealed"), snap.replyIds)
        assertEquals("Let me check.", snap.liveText)
        assertTrue(snap.isAgentActive)

        // Later poll: the tool finished, the bubble was sealed, and the final
        // answer streams into a fresh bubble.
        snap = resolveVoiceTurnSnapshot(
            messages = listOf(
                userMsg(),
                ChatMessage(
                    id = "assistant_sealed",
                    role = "assistant",
                    content = "Let me check.",
                    toolBlocks = listOf(
                        AssistantBlock(
                            id = "t1",
                            kind = "tool_use",
                            toolStatus = ToolBlockStatus.SUCCESS,
                            toolName = "calculator",
                        ),
                    ),
                ),
                ChatMessage(id = "assistant_fresh", role = "assistant", content = ""),
            ),
            streamingById = mapOf(
                "assistant_fresh" to StreamingDelta("The answer is 42.", emptyList(), false),
            ),
            beforeIds = beforeIds,
            isStreaming = true,
            trackedIds = snap.replyIds,
        )
        assertEquals(listOf("assistant_sealed", "assistant_fresh"), snap.replyIds)
        assertEquals("Let me check. The answer is 42.", snap.liveText)
        assertTrue(snap.isAgentActive)
    }

    // No-regression: the ordinary single-bubble turn behaves exactly as the
    // old first-id logic did.
    @Test
    fun singleBubbleTurnBehavesAsBefore() {
        val snap = resolveVoiceTurnSnapshot(
            messages = listOf(
                userMsg(),
                ChatMessage(id = "a1", role = "assistant", content = ""),
            ),
            streamingById = mapOf(
                "a1" to StreamingDelta("Hello there.", emptyList(), false),
            ),
            beforeIds = setOf("user_1"),
            isStreaming = true,
            trackedIds = emptyList(),
        )
        assertEquals(listOf("a1"), snap.replyIds)
        assertEquals("Hello there.", snap.liveText)
        assertNull(snap.error)
        assertTrue(snap.isAgentActive)
    }

    // An error frame landing on the SECOND bubble must surface too, not just
    // on the first message.
    @Test
    fun errorOnSecondBubbleSurfaces() {
        val snap = resolveVoiceTurnSnapshot(
            messages = listOf(
                userMsg(),
                ChatMessage(id = "a1", role = "assistant", content = "Partial."),
                ChatMessage(
                    id = "a2",
                    role = "assistant",
                    content = "",
                    error = "boom",
                    errorKind = "auth",
                ),
            ),
            streamingById = emptyMap(),
            beforeIds = setOf("user_1"),
            isStreaming = false,
            trackedIds = listOf("a1"),
        )
        assertEquals(listOf("a1", "a2"), snap.replyIds)
        assertEquals("auth" to "boom", snap.error)
    }

    // Bug 7: a tool still RUNNING counts as agent activity even if the
    // isStreaming flag was cleared in a race — the turn must not time out.
    @Test
    fun agentActiveWhileToolRunning() {
        val snap = resolveVoiceTurnSnapshot(
            messages = listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "",
                    toolBlocks = listOf(
                        AssistantBlock(
                            id = "t1",
                            kind = "tool_use",
                            toolStatus = ToolBlockStatus.RUNNING,
                        ),
                    ),
                ),
            ),
            streamingById = emptyMap(),
            beforeIds = emptySet(),
            isStreaming = false,
            trackedIds = emptyList(),
        )
        assertTrue(snap.isAgentActive)
    }

    @Test
    fun agentIdleWhenLoopDoneAndToolsFinished() {
        val snap = resolveVoiceTurnSnapshot(
            messages = listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "Done.",
                    toolBlocks = listOf(
                        AssistantBlock(
                            id = "t1",
                            kind = "tool_use",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ),
                    ),
                ),
            ),
            streamingById = emptyMap(),
            beforeIds = emptySet(),
            isStreaming = false,
            trackedIds = emptyList(),
        )
        assertFalse(snap.isAgentActive)
    }

    // Bug 7: the stall predicate only fires after a FULL idle window — a
    // fixed deadline from turn start would kill long-but-live turns.
    @Test
    fun replyStallNeedsFullIdleWindow() {
        val timeout = 180_000L
        assertFalse(isVoiceReplyStalled(nowMs = 1_000L, lastActivityAtMs = 900L, timeoutMs = timeout))
        assertFalse(isVoiceReplyStalled(nowMs = 179_999L, lastActivityAtMs = 0L, timeoutMs = timeout))
        assertTrue(isVoiceReplyStalled(nowMs = 180_001L, lastActivityAtMs = 0L, timeoutMs = timeout))
        // Recent activity resets the window even late in the turn.
        assertFalse(isVoiceReplyStalled(nowMs = 1_000_000L, lastActivityAtMs = 900_000L, timeoutMs = timeout))
    }
}
