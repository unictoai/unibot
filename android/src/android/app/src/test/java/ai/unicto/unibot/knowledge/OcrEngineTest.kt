package ai.unicto.unibot.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineTest {

    @Test
    fun `noop engine reports unavailability honestly`() {
        val availability = NoopOcrEngine.availability
        assertTrue(availability is OcrAvailability.Unavailable)
        assertTrue((availability as OcrAvailability.Unavailable).reason.isNotBlank())
    }

    @Test
    fun `noop recognize returns empty without throwing`() = runBlocking {
        val result = NoopOcrEngine.recognize(byteArrayOf(1, 2, 3))
        assertEquals("", result.text)
    }
}
