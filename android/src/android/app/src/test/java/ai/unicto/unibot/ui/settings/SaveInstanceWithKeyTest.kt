package ai.unicto.unibot.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The wizard's provider save must be atomic: key first, then the instance;
 * a failed instance write rolls the orphaned key back.
 */
class SaveInstanceWithKeyTest {

    @Test
    fun `happy path runs key then instance in order`() {
        val order = mutableListOf<String>()
        saveInstanceWithKey(
            saveKey = { order += "key" },
            addInstance = { order += "instance" },
            deleteKey = { order += "delete" },
        )
        assertEquals(listOf("key", "instance"), order)
    }

    @Test
    fun `failed instance write deletes the orphaned key and rethrows`() {
        val order = mutableListOf<String>()
        val boom = RuntimeException("db exploded")
        try {
            saveInstanceWithKey(
                saveKey = { order += "key" },
                addInstance = { order += "instance"; throw boom },
                deleteKey = { order += "delete" },
            )
            fail("expected the original exception")
        } catch (e: RuntimeException) {
            assertTrue(e === boom)
        }
        assertEquals(listOf("key", "instance", "delete"), order)
    }

    @Test
    fun `failed key write never touches the instance`() {
        val order = mutableListOf<String>()
        try {
            saveInstanceWithKey(
                saveKey = { order += "key"; throw RuntimeException("prefs exploded") },
                addInstance = { order += "instance" },
                deleteKey = { order += "delete" },
            )
            fail("expected the original exception")
        } catch (_: RuntimeException) {
        }
        assertEquals(listOf("key"), order)
    }

    @Test
    fun `rollback failure does not mask the original error`() {
        val boom = RuntimeException("db exploded")
        try {
            saveInstanceWithKey(
                saveKey = {},
                addInstance = { throw boom },
                deleteKey = { throw RuntimeException("rollback also failed") },
            )
            fail("expected the original exception")
        } catch (e: RuntimeException) {
            assertTrue(e === boom)
        }
    }
}
