package ai.unicto.unibot.ui.sessions

import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.db.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v128-duplicate-rollback] Duplicating a session must survive a
 * mid-copy failure (e.g. SQLiteFullException on a full disk): no exception
 * escapes, the half-created copy is rolled back, and the user gets a message.
 */
class SessionDuplicateTest {

    private fun session(id: String, title: String? = "Chat") = ChatSessionEntity(
        id = id,
        title = title,
        modelId = "openai/gpt-oss-20b",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun message(id: String, sessionId: String, role: String) = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = role,
        partsJson = """[{"type":"text","value":"hi"}]""",
        createdAt = 1L,
        sortOrder = 0,
    )

    /** Hand-rolled fake: fails appends after [failAfterAppends] successful ones. */
    private class FakeOps(
        val failAfterAppends: Int = Int.MAX_VALUE,
    ) : SessionDuplicateOps {
        val sessions = mutableMapOf<String, ChatSessionEntity>()
        val messages = mutableMapOf<String, MutableList<MessageEntity>>()
        val deletedSessionIds = mutableListOf<String>()
        var appendCount = 0

        override suspend fun getSession(id: String) = sessions[id]
        override suspend fun loadMessages(id: String) = messages[id].orEmpty()
        override suspend fun createSession(modelId: String, title: String): ChatSessionEntity {
            val e = ChatSessionEntity(
                id = "copy-${sessions.size}",
                title = title,
                modelId = modelId,
                createdAt = 2L,
                updatedAt = 2L,
            )
            sessions[e.id] = e
            messages[e.id] = mutableListOf()
            return e
        }
        override suspend fun appendMessage(
            sessionId: String, role: String, partsJson: String,
            tokenUsage: String?, reasoningContent: String?,
        ): MessageEntity {
            // Note: android.database.sqlite.SQLiteFullException cannot be
            // instantiated in JVM unit tests (stubbed android.jar); the
            // production catch handles any Exception, so RuntimeException
            // exercises the same rollback path.
            if (appendCount >= failAfterAppends) throw RuntimeException("simulated disk-full")
            appendCount++
            val e = MessageEntity(
                id = "m-$appendCount",
                sessionId = sessionId,
                role = role,
                partsJson = partsJson,
                createdAt = 3L,
                tokenUsage = tokenUsage,
                sortOrder = appendCount,
                reasoningContent = reasoningContent,
            )
            messages.getOrPut(sessionId) { mutableListOf() }.add(e)
            return e
        }
        override suspend fun deleteSession(id: String) {
            deletedSessionIds.add(id)
            sessions.remove(id)
            messages.remove(id)
        }
    }

    @Test
    fun `happy path copies all messages`() = runTest {
        val ops = FakeOps()
        ops.sessions["s1"] = session("s1")
        ops.messages["s1"] = mutableListOf(
            message("a", "s1", "user"),
            message("b", "s1", "assistant"),
        )
        var error: String? = null

        duplicateSessionWithRollback(ops, "s1") { error = it }

        assertNull(error)
        assertTrue(ops.deletedSessionIds.isEmpty())
        val copy = ops.sessions.values.first { it.id != "s1" }
        assertEquals("Chat (Copy)", copy.title)
        assertEquals(2, ops.messages[copy.id]?.size)
    }

    @Test
    fun `disk-full mid-copy rolls back the partial copy and reports`() = runTest {
        val ops = FakeOps(failAfterAppends = 1)
        ops.sessions["s1"] = session("s1")
        ops.messages["s1"] = mutableListOf(
            message("a", "s1", "user"),
            message("b", "s1", "assistant"),
            message("c", "s1", "user"),
        )
        var error: String? = null

        // Must not throw.
        duplicateSessionWithRollback(ops, "s1") { error = it }

        assertEquals("Couldn't duplicate chat — storage may be full.", error)
        // The half-created copy is gone.
        assertEquals(1, ops.deletedSessionIds.size)
        val partialId = ops.deletedSessionIds[0]
        assertFalse(ops.sessions.containsKey(partialId))
        // The original is untouched.
        assertEquals(3, ops.messages["s1"]?.size)
    }

    @Test
    fun `missing source session is a no-op`() = runTest {
        val ops = FakeOps()
        var error: String? = null

        duplicateSessionWithRollback(ops, "ghost") { error = it }

        assertNull(error)
        assertTrue(ops.sessions.isEmpty())
    }

    @Test
    fun `failure before any copy is created still reports`() = runTest {
        val ops = FakeOps(failAfterAppends = 0)
        ops.sessions["s1"] = session("s1")
        ops.messages["s1"] = mutableListOf(message("a", "s1", "user"))
        var error: String? = null

        duplicateSessionWithRollback(ops, "s1") { error = it }

        assertNotNull(error)
        // A session row was created before the first append failed, so it is
        // rolled back too.
        assertEquals(1, ops.deletedSessionIds.size)
    }
}
