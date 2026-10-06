package ai.unicto.unibot.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 46 — JVM tests for the one-tap "add to calendar" natural-language
 * parser. Fixed `now` keeps weekday expectations deterministic.
 */
class CalendarQuickAddParserTest {

    // Tue 2026-10-06 09:00 local.
    private val now = CalendarQuickAddParser.toMs(2026, 10, 6, 9, 0)

    @Test
    fun `tomorrow with hour`() {
        val e = CalendarQuickAddParser.parse("Dentist tomorrow 3pm", now)!!
        assertEquals("Dentist", e.title)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 7, 15, 0), e.startMs)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 7, 16, 0), e.endMs)
    }

    @Test
    fun `today with colon time`() {
        val e = CalendarQuickAddParser.parse("Standup today 10:30", now)!!
        assertEquals("Standup", e.title)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 6, 10, 30), e.startMs)
    }

    @Test
    fun `weekday resolves forward`() {
        val e = CalendarQuickAddParser.parse("Team sync Friday 10am", now)!!
        // Fri 2026-10-09, 10:00.
        assertEquals("Team sync", e.title)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 9, 10, 0), e.startMs)
    }

    @Test
    fun `time range sets both ends`() {
        val e = CalendarQuickAddParser.parse("Workshop tomorrow 2-4pm", now)!!
        assertEquals("Workshop", e.title)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 7, 14, 0), e.startMs)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 7, 16, 0), e.endMs)
    }

    @Test
    fun `month name day`() {
        val e = CalendarQuickAddParser.parse("Lunch with Sara on Oct 8 at 1pm", now)!!
        assertEquals("Lunch with Sara", e.title)
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 8, 13, 0), e.startMs)
    }

    @Test
    fun `default duration is one hour`() {
        val e = CalendarQuickAddParser.parse("Call mom on 2026-10-07 at 18:00", now)!!
        assertEquals("Call mom", e.title)
        assertEquals(e.startMs + 60L * 60_000, e.endMs)
    }

    @Test
    fun `no date or time returns null`() {
        assertNull(CalendarQuickAddParser.parse("Buy some milk", now))
        assertNull(CalendarQuickAddParser.parse("", now))
    }

    @Test
    fun `no title returns null`() {
        assertNull(CalendarQuickAddParser.parse("tomorrow 3pm", now))
    }

    @Test
    fun `meridiem defaults sensibly`() {
        // Bare "3" without am/pm is afternoon for morning/afternoon hours.
        val e = CalendarQuickAddParser.parse("Gym tomorrow 3", now)!!
        assertEquals(CalendarQuickAddParser.toMs(2026, 10, 7, 15, 0), e.startMs)
    }

    @Test
    fun `parsed event always ends after it starts`() {
        val inputs = listOf(
            "Dentist tomorrow 3pm", "Team sync Friday 10-11am",
            "Lunch on Oct 8 at 1pm", "Standup today 9am",
        )
        for (input in inputs) {
            val e = CalendarQuickAddParser.parse(input, now)
            assertTrue("end > start for \"$input\"", e != null && e.endMs > e.startMs)
        }
    }
}
