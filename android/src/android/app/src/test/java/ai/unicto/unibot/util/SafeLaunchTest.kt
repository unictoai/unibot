package ai.unicto.unibot.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.4.0 item 78 — app-wide crash safety net.
 *
 * Pins the catching half: a throwing coroutine routes to onError (default:
 * [AppErrorBus]) instead of escaping, CancellationException still
 * propagates (cancellation must keep working), and AppErrorBus never
 * throws or blanks a message.
 */
class SafeLaunchTest {

    private fun testScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Unconfined + Job())

    @Test
    fun `launchCatching routes a throw to onError instead of escaping`() {
        val scope = testScope()
        val seen = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        scope.launchCatching(onError = {
            seen.set(it)
            done.countDown()
        }) {
            throw IllegalStateException("boom")
        }
        assertTrue("onError must fire", done.await(5, TimeUnit.SECONDS))
        assertEquals("boom", seen.get()?.message)
        scope.cancel()
    }

    @Test
    fun `launchCatching routes child coroutine failures to onError`() {
        val scope = testScope()
        val done = CountDownLatch(1)
        scope.launchCatching(onError = { done.countDown() }) {
            launch { throw IllegalArgumentException("child boom") }
        }
        assertTrue("child failure must reach onError", done.await(5, TimeUnit.SECONDS))
        scope.cancel()
    }

    @Test
    fun `launchCatching rethrows CancellationException`() {
        val scope = testScope()
        val done = CountDownLatch(1)
        val errors = mutableListOf<Throwable>()
        val job = scope.launchCatching(onError = { errors.add(it) }) {
            try {
                delay(60_000)
            } finally {
                done.countDown()
            }
        }
        job.cancel(CancellationException("user cancel"))
        runBlocking { job.join() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("cancellation must not route to onError", errors.isEmpty())
        scope.cancel()
    }

    @Test
    fun `launchCatching default posts to AppErrorBus`() {
        val scope = testScope()
        val received = AtomicReference<AppError?>()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            // Filter: AppErrorBus is process-wide, so ignore posts from
            // other tests sharing this JVM fork.
            AppErrorBus.errors.collect { if (it.message == "visible") received.set(it) }
        }
        scope.launchCatching(tag = "TestTag") { throw RuntimeException("visible") }
        val deadline = System.currentTimeMillis() + 5000
        while (received.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals("visible", received.get()?.message)
        assertEquals("TestTag", received.get()?.tag)
        collectJob.cancel()
        scope.cancel()
    }

    @Test
    fun `AppErrorBus never blanks a message`() {
        // A bare IOException() has a null message — the bus must fall back
        // to the class name, never "null".
        val received = AtomicReference<AppError?>()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            AppErrorBus.errors.collect { if (it.tag == "T") received.set(it) }
        }
        AppErrorBus.post(java.io.IOException(), tag = "T")
        val deadline = System.currentTimeMillis() + 5000
        while (received.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        val msg = received.get()?.message.orEmpty()
        assertTrue("message must not be blank/null", msg.isNotBlank())
        assertTrue(msg.contains("IOException"))
        collectJob.cancel()
    }

    @Test
    fun `AppErrorBus post of plain message is delivered`() {
        val received = AtomicReference<AppError?>()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            AppErrorBus.errors.collect { if (it.message == "dropped") received.set(it) }
        }
        AppErrorBus.post("Net", "dropped")
        val deadline = System.currentTimeMillis() + 5000
        while (received.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals("dropped", received.get()?.message)
        assertEquals("Net", received.get()?.tag)
        collectJob.cancel()
    }
}
