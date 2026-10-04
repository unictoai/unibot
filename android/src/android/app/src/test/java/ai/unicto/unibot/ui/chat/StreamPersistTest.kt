package ai.unicto.unibot.ui.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v128-stream-persist] The crash-safety placeholder row written at
 * stream start (and rewritten on each flush) must be a valid single-text-part
 * message that the normal message loader can read back after a process death.
 */
class StreamPersistTest {

    /** Minimal escaper mirroring ChatViewModel.escapeJson for the cases that matter. */
    private val escape: (String) -> String = { text ->
        buildString {
            append('"')
            for (c in text) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    @Test
    fun `placeholder is a single text part with the flushed content`() {
        val json = streamPlaceholderPartsJson("hello world", escape)
        val arr = JSONObject("{\"parts\":$json}").getJSONArray("parts")
        assertEquals(1, arr.length())
        assertEquals("text", arr.getJSONObject(0).getString("type"))
        assertEquals("hello world", arr.getJSONObject(0).getString("value"))
    }

    @Test
    fun `placeholder escapes quotes and newlines`() {
        val json = streamPlaceholderPartsJson("say \"hi\"\nbye", escape)
        val arr = JSONObject("{\"parts\":$json}").getJSONArray("parts")
        assertEquals("say \"hi\"\nbye", arr.getJSONObject(0).getString("value"))
    }

    @Test
    fun `placeholder handles empty content`() {
        val json = streamPlaceholderPartsJson("", escape)
        val arr = JSONObject("{\"parts\":$json}").getJSONArray("parts")
        assertEquals(1, arr.length())
        assertEquals("", arr.getJSONObject(0).getString("value"))
    }

    @Test
    fun `placeholder grows monotonically with accumulated text`() {
        val first = streamPlaceholderPartsJson("hello", escape)
        val second = streamPlaceholderPartsJson("hello world", escape)
        // A longer accumulated stream must produce a longer parts payload —
        // the write-guard keys off content length.
        assertTrue(second.length > first.length)
    }
}
