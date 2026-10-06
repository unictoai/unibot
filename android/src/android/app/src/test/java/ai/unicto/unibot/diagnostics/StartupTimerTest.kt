package ai.unicto.unibot.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 86 — cold-start measurement.
 *
 * StartupTimer must be safe to call from anywhere (never throws, cheap)
 * and must actually record the phases UnibotApp.onCreate marks, so a
 * before/after comparison is one logcat line away on the device.
 */
class StartupTimerTest {

    @Test
    fun `mark records a phase`() {
        StartupTimer.mark("test-phase")
        val names = StartupTimer.snapshotForTest().map { it.first }
        assertTrue(names.contains("test-phase"))
    }

    @Test
    fun `marks are non-decreasing in elapsed time`() {
        StartupTimer.mark("order-a")
        Thread.sleep(5)
        StartupTimer.mark("order-b")
        val snap = StartupTimer.snapshotForTest()
        val a = snap.last { it.first == "order-a" }.second
        val b = snap.last { it.first == "order-b" }.second
        assertTrue("elapsed times must be non-decreasing", b >= a)
    }

    @Test
    fun `report never throws`() {
        // Must be safe at the end of onCreate even under memory pressure.
        StartupTimer.report()
    }
}
