package ai.unicto.unibot.browser

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Item 40 — JVM tests for the hand-over wait state machine.
 */
class HandoverWaitControllerTest {

    @Test
    fun `resolve resumes the wait`() = runTest {
        val c = HandoverWaitController()
        val job = async { c.awaitUser("Sign in", "https://example.com", timeoutMs = 5_000) }
        delay(50)
        // Banner state is visible while waiting.
        assertEquals("Sign in", c.request.value?.message)
        assertEquals("https://example.com", c.request.value?.pageUrl)
        c.resolve()
        assertEquals(HandoverWaitController.Outcome.RESUMED, job.await())
        assertNull(c.request.value)
    }

    @Test
    fun `cancel ends the wait without error`() = runTest {
        val c = HandoverWaitController()
        val job = async { c.awaitUser("Pay", null, timeoutMs = 5_000) }
        delay(50)
        c.cancel()
        assertEquals(HandoverWaitController.Outcome.CANCELLED, job.await())
        assertNull(c.request.value)
    }

    @Test
    fun `timeout ends the wait`() = runTest {
        val c = HandoverWaitController()
        val outcome = c.awaitUser("x", null, timeoutMs = 100)
        assertEquals(HandoverWaitController.Outcome.TIMED_OUT, outcome)
        assertNull(c.request.value)
    }

    @Test
    fun `blank message falls back to default`() = runTest {
        val c = HandoverWaitController()
        val job = async { c.awaitUser("  ", null, timeoutMs = 5_000) }
        delay(50)
        assertEquals(HandoverWaitController.DEFAULT_MESSAGE, c.request.value?.message)
        c.cancel()
        job.await()
    }

    @Test
    fun `second wait cancels the first`() = runTest {
        val c = HandoverWaitController()
        val first = async { c.awaitUser("first", null, timeoutMs = 5_000) }
        delay(50)
        val second = async { c.awaitUser("second", null, timeoutMs = 5_000) }
        delay(50)
        assertEquals(HandoverWaitController.Outcome.CANCELLED, first.await())
        assertEquals("second", c.request.value?.message)
        c.resolve()
        assertEquals(HandoverWaitController.Outcome.RESUMED, second.await())
    }
}
