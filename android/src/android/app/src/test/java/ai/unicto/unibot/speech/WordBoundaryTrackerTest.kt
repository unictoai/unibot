package ai.unicto.unibot.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voice theme item 33: word-boundary mapping for karaoke highlighting.
 */
class WordBoundaryTrackerTest {

    @Test
    fun wordAtMidWord() {
        assertEquals(0..4, WordBoundaryTracker.wordAt("hello world", 2))
        assertEquals(6..10, WordBoundaryTracker.wordAt("hello world", 8))
    }

    @Test
    fun wordAtEdges() {
        assertEquals(0..4, WordBoundaryTracker.wordAt("hello world", 0))
        assertEquals(0..4, WordBoundaryTracker.wordAt("hello world", 4))
        assertEquals(6..10, WordBoundaryTracker.wordAt("hello world", 10))
    }

    @Test
    fun whitespaceAndPunctuationAreNotWords() {
        assertNull(WordBoundaryTracker.wordAt("hello world", 5))
        assertNull(WordBoundaryTracker.wordAt("hi, there", 2))
    }

    @Test
    fun contractionsAndHyphensStayWhole() {
        assertEquals(0..4, WordBoundaryTracker.wordAt("don't stop", 2))
        assertEquals(0..8, WordBoundaryTracker.wordAt("on-device llm", 4))
    }

    @Test
    fun outOfBoundsIsNull() {
        assertNull(WordBoundaryTracker.wordAt("", 0))
        assertNull(WordBoundaryTracker.wordAt("hi", -1))
        assertNull(WordBoundaryTracker.wordAt("hi", 2))
    }
}
