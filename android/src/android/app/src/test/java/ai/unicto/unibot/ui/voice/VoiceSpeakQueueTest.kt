package ai.unicto.unibot.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice theme item 27: the streaming-TTS queue contract — order, blanks,
 * interrupt-clearing, and the drained signal.
 */
class VoiceSpeakQueueTest {

    @Test
    fun speaksInEnqueueOrder() {
        val q = VoiceSpeakQueue()
        q.enqueueSentences(listOf("First.", "Second.", "Third."))
        assertEquals("First.", q.takeNext())
        q.markSpoken()
        assertEquals("Second.", q.takeNext())
        q.markSpoken()
        assertEquals("Third.", q.takeNext())
        q.markSpoken()
        assertTrue(q.isDrained)
    }

    @Test
    fun blanksAreNeverSpoken() {
        val q = VoiceSpeakQueue()
        q.enqueueSentences(listOf("  ", "", "Hello."))
        assertEquals(1, q.pendingCount)
        assertEquals("Hello.", q.takeNext())
    }

    @Test
    fun clearDropsQueueAndInFlight() {
        val q = VoiceSpeakQueue()
        q.enqueueSentences(listOf("One.", "Two."))
        q.takeNext() // in-flight
        q.clear()
        assertTrue(q.isDrained)
        assertEquals(0, q.pendingCount)
        // A stale completion callback after clear must not resurrect speech.
        q.markSpoken()
        assertTrue(q.isDrained)
        assertNull(q.takeNext())
    }

    @Test
    fun notDrainedWhileInFlight() {
        val q = VoiceSpeakQueue()
        q.enqueueSentences(listOf("One."))
        q.takeNext()
        assertFalse(q.isDrained)
        q.markSpoken()
        assertTrue(q.isDrained)
    }

    @Test
    fun takeNextOnEmptyIsNull() {
        val q = VoiceSpeakQueue()
        assertNull(q.takeNext())
        assertTrue(q.isDrained)
    }
}
