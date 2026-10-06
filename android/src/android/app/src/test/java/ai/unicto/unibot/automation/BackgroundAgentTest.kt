package ai.unicto.unibot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 91 — background agent model: JSON round-trip, work-spec mapping,
 * and the consent gate.
 */
class BackgroundAgentTest {

    @Test
    fun `json round trip preserves all fields`() {
        val agent = BackgroundAgent(
            name = "Morning brief",
            prompt = "Summarize my day.",
            modelBinding = """{"type":"group","groupId":"g1"}""",
            schedule = BackgroundAgentSchedule.INTERVAL,
            intervalMinutes = 30,
            requireCharging = true,
            requireUnmetered = true,
            batteryNotLow = true,
            enabled = true,
            consentGranted = true,
        )
        val restored = BackgroundAgent.fromJson(agent.toJson())
        assertEquals(agent, restored)
    }

    @Test
    fun `old rows read back with safe defaults`() {
        val restored = BackgroundAgent.fromJson(
            org.json.JSONObject().apply {
                put("name", "Legacy")
                put("prompt", "Hi")
            },
        )
        assertEquals(BackgroundAgentSchedule.MANUAL, restored.schedule)
        assertTrue(restored.batteryNotLow)
        assertFalse(restored.enabled)
        assertFalse(restored.consentGranted)
    }

    @Test
    fun `interval below workmanager minimum is clamped`() {
        val agent = BackgroundAgent(name = "x", prompt = "y", schedule = BackgroundAgentSchedule.INTERVAL, intervalMinutes = 5)
        val spec = BackgroundAgentScheduler.toWorkSpec(agent)
        assertEquals(15L, spec.repeatIntervalMinutes)
    }

    @Test
    fun `manual schedule maps to one-shot`() {
        val agent = BackgroundAgent(name = "x", prompt = "y", schedule = BackgroundAgentSchedule.MANUAL)
        val spec = BackgroundAgentScheduler.toWorkSpec(agent)
        assertNull(spec.repeatIntervalMinutes)
        assertEquals(0L, spec.initialDelayMinutes)
    }

    @Test
    fun `daily schedule maps to 24h periodic with positive initial delay`() {
        val agent = BackgroundAgent(name = "x", prompt = "y", schedule = BackgroundAgentSchedule.DAILY, dailyHour = 8, dailyMinute = 0)
        val spec = BackgroundAgentScheduler.toWorkSpec(agent)
        assertEquals(24L * 60L, spec.repeatIntervalMinutes)
        assertTrue(spec.initialDelayMinutes in 1..(24L * 60L))
    }

    @Test
    fun `unique names are stable per agent`() {
        val agent = BackgroundAgent(id = "abc", name = "x", prompt = "y")
        assertEquals("bgagent-abc", BackgroundAgentScheduler.toWorkSpec(agent).uniqueName)
    }

    @Test
    fun `run json round trip`() {
        val run = BackgroundAgentRun(
            agentId = "a1", agentName = "Brief",
            finishedAt = 123L, ok = true, preview = "Hello", sessionId = "s1",
        )
        val restored = BackgroundAgentRun.fromJson(run.toJson())
        assertEquals(run, restored)
    }
}
