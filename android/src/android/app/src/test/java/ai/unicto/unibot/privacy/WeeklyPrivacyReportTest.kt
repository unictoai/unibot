package ai.unicto.unibot.privacy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Privacy item 56 — unit tests for the on-device weekly privacy report.
 * Pure JVM: persistence is exercised through the toJson/loadJson seam,
 * never through Android SharedPreferences.
 */
class WeeklyPrivacyReportTest {

    @After
    fun tearDown() {
        WeeklyPrivacyReport.clearForTest()
    }

    @Test
    fun `weekStartMs is the Monday 00-00 of the containing week`() {
        // 2026-10-06 is a Tuesday (build week).
        val tuesdayNoon = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 6, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = WeeklyPrivacyReport.weekStartMs(tuesdayNoon)
        val cal = Calendar.getInstance().apply { timeInMillis = weekStart }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertTrue(weekStart <= tuesdayNoon)
        assertTrue(tuesdayNoon - weekStart < 7 * 24 * 3_600_000L)
        // Idempotent.
        assertEquals(weekStart, WeeklyPrivacyReport.weekStartMs(weekStart))
    }

    @Test
    fun `weekStartMs groups a whole week together`() {
        val monday = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 5, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sunday = monday + 6 * 24 * 3_600_000L + 12 * 3_600_000L
        val nextMonday = monday + 7 * 24 * 3_600_000L
        val week = WeeklyPrivacyReport.weekStartMs(monday)
        assertEquals(week, WeeklyPrivacyReport.weekStartMs(sunday))
        assertFalse(week == WeeklyPrivacyReport.weekStartMs(nextMonday))
    }

    @Test
    fun `isKnownTracker matches analytics hosts only`() {
        assertTrue(WeeklyPrivacyReport.isKnownTracker("www.google-analytics.com"))
        assertTrue(WeeklyPrivacyReport.isKnownTracker("stats.doubleclick.net"))
        assertTrue(WeeklyPrivacyReport.isKnownTracker("telemetry.example.com"))
        assertFalse(WeeklyPrivacyReport.isKnownTracker("api.openai.com"))
        assertFalse(WeeklyPrivacyReport.isKnownTracker("api.telegram.org"))
        assertFalse(WeeklyPrivacyReport.isKnownTracker("duckduckgo.com"))
    }

    @Test
    fun `record classifies requests into the weekly bucket`() {
        WeeklyPrivacyReport.record("api.openai.com", 100, 200)
        WeeklyPrivacyReport.record("api.telegram.org", 50, 60)
        WeeklyPrivacyReport.record("duckduckgo.com", 10, 20)
        WeeklyPrivacyReport.record("unknown-host.example", 5, 5)
        WeeklyPrivacyReport.record("www.google-analytics.com", 7, 8)

        val report = WeeklyPrivacyReport.currentReport()
        assertEquals(5, report.requests)
        assertEquals(172, report.bytesUp)
        assertEquals(293, report.bytesDown)
        assertEquals(1, report.providerRequests)
        assertEquals(1, report.connectorRequests)
        assertEquals(1, report.updateSearchRequests)
        assertEquals(1, report.otherRequests)
        // Tracker bytes counted separately, never hidden.
        assertEquals(15, report.trackerBytes)
        assertFalse(report.isEmpty)
    }

    @Test
    fun `currentReport is empty before any traffic`() {
        val report = WeeklyPrivacyReport.currentReport()
        assertTrue(report.isEmpty)
        assertEquals(0, report.requests)
        assertEquals(0, report.trackerBytes)
    }

    @Test
    fun `toJson and loadJson round-trip buckets`() {
        WeeklyPrivacyReport.record("api.openai.com", 100, 200)
        WeeklyPrivacyReport.record("www.google-analytics.com", 7, 8)
        val json = WeeklyPrivacyReport.toJson()

        WeeklyPrivacyReport.clearForTest()
        assertTrue(WeeklyPrivacyReport.currentReport().isEmpty)

        WeeklyPrivacyReport.loadJson(json)
        val restored = WeeklyPrivacyReport.currentReport()
        assertEquals(2, restored.requests)
        assertEquals(1, restored.providerRequests)
        assertEquals(15, restored.trackerBytes)
        assertEquals(107, restored.bytesUp)
        assertEquals(208, restored.bytesDown)
    }

    @Test
    fun `loadJson tolerates garbage without crashing`() {
        WeeklyPrivacyReport.loadJson("not json at all {{{")
        assertTrue(WeeklyPrivacyReport.currentReport().isEmpty)
        WeeklyPrivacyReport.loadJson("")
        assertTrue(WeeklyPrivacyReport.currentReport().isEmpty)
    }
}
