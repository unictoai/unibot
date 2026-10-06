package ai.unicto.unibot.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Item 92 — quiet hours + battery deferral policy.
 */
class SchedulePolicyTest {

    private fun task(
        respectQuietHours: Boolean = true,
        requireCharging: Boolean = false,
    ) = ScheduledTask(
        label = "t",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "hi",
        respectQuietHours = respectQuietHours,
        requireCharging = requireCharging,
    )

    private fun msAt(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val qh = QuietHours(enabled = true, startHour = 22, startMinute = 0, endHour = 7, endMinute = 0)
    private val healthyBattery = BatterySnapshot(isPowerSave = false, batteryPct = 80, isCharging = false)

    @Test
    fun `runs outside quiet hours`() {
        val decision = SchedulePolicy.decide(task(), msAt(10, 0), qh, healthyBattery)
        assertEquals(FireDecision.Run, decision)
    }

    @Test
    fun `defers inside quiet hours to window end`() {
        val now = msAt(23, 30)
        val decision = SchedulePolicy.decide(task(), now, qh, healthyBattery)
        assertTrue(decision is FireDecision.DeferUntil)
        val at = (decision as FireDecision.DeferUntil).atMs
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        // Next day — 23:30 + 7:30h.
        assertTrue(at - now in (7L * 3600_000L)..(8L * 3600_000L))
    }

    @Test
    fun `overnight window covers early morning`() {
        assertTrue(SchedulePolicy.inQuietHours(msAt(2, 0), qh))
        assertTrue(SchedulePolicy.inQuietHours(msAt(23, 59), qh))
        assertFalse(SchedulePolicy.inQuietHours(msAt(8, 0), qh))
        assertFalse(SchedulePolicy.inQuietHours(msAt(21, 59), qh))
    }

    @Test
    fun `daytime window works`() {
        val day = QuietHours(enabled = true, startHour = 13, startMinute = 0, endHour = 14, endMinute = 0)
        assertTrue(SchedulePolicy.inQuietHours(msAt(13, 30), day))
        assertFalse(SchedulePolicy.inQuietHours(msAt(15, 0), day))
    }

    @Test
    fun `task can opt out of quiet hours`() {
        val decision = SchedulePolicy.decide(task(respectQuietHours = false), msAt(23, 30), qh, healthyBattery)
        assertEquals(FireDecision.Run, decision)
    }

    @Test
    fun `critical battery defers even outside quiet hours`() {
        val low = BatterySnapshot(isPowerSave = false, batteryPct = 10, isCharging = false)
        val decision = SchedulePolicy.decide(task(respectQuietHours = false), msAt(10, 0), qh, low)
        assertTrue(decision is FireDecision.DeferUntil)
    }

    @Test
    fun `critical battery does not defer while charging`() {
        val lowCharging = BatterySnapshot(isPowerSave = false, batteryPct = 10, isCharging = true)
        val decision = SchedulePolicy.decide(task(respectQuietHours = false), msAt(10, 0), qh, lowCharging)
        assertEquals(FireDecision.Run, decision)
    }

    @Test
    fun `requireCharging defers when unplugged`() {
        val decision = SchedulePolicy.decide(task(requireCharging = true), msAt(10, 0), qh, healthyBattery)
        assertTrue(decision is FireDecision.DeferUntil)
        val plugged = healthyBattery.copy(isCharging = true)
        assertEquals(FireDecision.Run, SchedulePolicy.decide(task(requireCharging = true), msAt(10, 0), qh, plugged))
    }

    @Test
    fun `battery saver pause defers when power save is on`() {
        val saver = BatterySnapshot(isPowerSave = true, batteryPct = 60, isCharging = false)
        val decision = SchedulePolicy.decide(task(), msAt(10, 0), qh, saver, batterySaverPause = true)
        assertTrue(decision is FireDecision.DeferUntil)
        val noPause = SchedulePolicy.decide(task(), msAt(10, 0), qh, saver, batterySaverPause = false)
        assertEquals(FireDecision.Run, noPause)
    }

    @Test
    fun `disabled quiet hours never match`() {
        val off = qh.copy(enabled = false)
        assertFalse(SchedulePolicy.inQuietHours(msAt(23, 30), off))
    }
}
