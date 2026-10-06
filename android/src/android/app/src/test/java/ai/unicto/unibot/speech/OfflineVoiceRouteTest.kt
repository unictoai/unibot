package ai.unicto.unibot.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice theme item 31: the offline route evaluator is pure and explicit —
 * all three legs ready means zero network, and any missing leg is named.
 */
class OfflineVoiceRouteTest {

    @Test
    fun allReadyMeansFullyOffline() {
        val status = OfflineVoiceRoute.evaluate(sttReady = true, llmReady = true, ttsReady = true)
        assertTrue(status.allReady)
        assertEquals(3, status.readyCount)
    }

    @Test
    fun anyMissingLegBreaksOffline() {
        val status = OfflineVoiceRoute.evaluate(sttReady = true, llmReady = false, ttsReady = true)
        assertFalse(status.allReady)
        assertEquals(2, status.readyCount)
        val llm = status.legs.first { it.id == "llm" }
        assertFalse(llm.ready)
        assertTrue(llm.hint.isNotBlank())
    }

    @Test
    fun nothingReadyNamesEveryLeg() {
        val status = OfflineVoiceRoute.evaluate(sttReady = false, llmReady = false, ttsReady = false)
        assertFalse(status.allReady)
        assertEquals(0, status.readyCount)
        assertEquals(listOf("stt", "llm", "tts"), status.legs.map { it.id })
        assertTrue(status.legs.all { it.label.isNotBlank() && it.hint.isNotBlank() })
    }

    @Test
    fun legsAreStableAndOrdered() {
        val a = OfflineVoiceRoute.evaluate(true, true, true)
        val b = OfflineVoiceRoute.evaluate(true, true, true)
        assertEquals(a, b)
        assertNull(OfflineVoiceRoute.evaluate(true, true, true).legs.firstOrNull { it.id == "x" })
    }
}
