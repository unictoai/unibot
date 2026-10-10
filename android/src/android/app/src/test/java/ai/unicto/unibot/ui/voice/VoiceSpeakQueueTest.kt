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

    // Bug 1: streaming TTS cut sentences off mid-speech. The driver pumped on
    // every new sentence and takeNext() ignored the in-flight flag, so the
    // engine's speak() flush (stop()) cancelled the playing sentence — the
    // cancelled utterance's onDone then cascaded into the next pump, so only
    // fragments survived. Now takeNext() refuses while in-flight and the pump
    // chains strictly on utterance completion: all 3 complete, in order.
    @Test
    fun takeNextRefusesWhileInFlight() {
        val q = VoiceSpeakQueue()
        q.enqueueSentences(listOf("First.", "Second.", "Third."))
        assertEquals("First.", q.takeNext())
        assertTrue(q.hasInFlight)
        // A new sentence arriving mid-utterance must NOT preempt it.
        q.enqueueSentences(listOf("Fourth."))
        assertNull(q.takeNext())
        assertTrue(q.hasInFlight)
        // Utterance completion chains the next pump, in order.
        q.markSpoken()
        assertFalse(q.hasInFlight)
        assertEquals("Second.", q.takeNext())
        q.markSpoken()
        assertEquals("Third.", q.takeNext())
        q.markSpoken()
        assertEquals("Fourth.", q.takeNext())
        q.markSpoken()
        assertTrue(q.isDrained)
    }

    // Bug 2: the last sentence was never spoken without final punctuation —
    // the old tail flush read liveText beyond consumedUpTo (always empty) and
    // the real leftover sat unflushed in the sentence buffer.
    @Test
    fun drainRemainingSpeaksUnterminatedTail() {
        val q = VoiceSpeakQueue()
        val buffer = StringBuilder("Done") // no trailing period
        assertEquals(listOf("Done"), q.drainRemaining(buffer))
        assertEquals(0, buffer.length)
    }

    @Test
    fun drainRemainingKeepsCompleteSentencesPlusTail() {
        val q = VoiceSpeakQueue()
        val buffer = StringBuilder("Hello there. How are you")
        assertEquals(listOf("Hello there.", "How are you"), q.drainRemaining(buffer))
        assertEquals(0, buffer.length)
    }

    @Test
    fun drainRemainingCommitsTrailingDecimalLookahead() {
        val q = VoiceSpeakQueue()
        // Mid-stream this trailing '.' is held back (it might be "28.98");
        // at the final flush nothing more is coming, so it commits.
        val buffer = StringBuilder("It costs 28.")
        assertEquals(listOf("It costs 28."), q.drainRemaining(buffer))
    }

    @Test
    fun drainRemainingOnEmptyBufferIsEmpty() {
        val q = VoiceSpeakQueue()
        val buffer = StringBuilder()
        assertTrue(q.drainRemaining(buffer).isEmpty())
    }

    // Bug 5: the queue is shared across threads — the driver enqueues on the
    // main thread while the engine's completion callback (markSpoken → next
    // pump) runs on Dispatchers.Default. All state is @Synchronized now; a
    // producer/consumer pair racing must never corrupt the deque or lose a
    // sentence.
    @Test
    fun concurrentEnqueueAndPumpStaysConsistent() {
        val q = VoiceSpeakQueue()
        val total = 500
        val producer = Thread {
            repeat(total) { q.enqueueSentences(listOf("s$it.")) }
        }
        val spoken = java.util.concurrent.atomic.AtomicInteger(0)
        val consumer = Thread {
            var idleSpins = 0
            while (spoken.get() < total && idleSpins < 1_000_000) {
                val s = q.takeNext()
                if (s == null) {
                    idleSpins++
                    Thread.yield()
                    continue
                }
                idleSpins = 0
                spoken.incrementAndGet()
                q.markSpoken()
            }
        }
        producer.start()
        consumer.start()
        producer.join(10_000)
        consumer.join(10_000)
        assertFalse(producer.isAlive)
        assertFalse(consumer.isAlive)
        assertEquals(total, spoken.get())
        assertTrue(q.isDrained)
    }
}
