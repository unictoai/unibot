package ai.unicto.unibot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.4.0 item 16 — token_usage payload parsing. */
class ChatTokenStatsTest {

    @Test
    fun `parses input and output`() {
        val parsed = parseTokenUsage("""{"inputTokens":1200,"outputTokens":340}""")!!
        assertEquals(1200L, parsed.first)
        assertEquals(340L, parsed.second)
    }

    @Test
    fun `ignores cache and context keys`() {
        val parsed = parseTokenUsage(
            """{"inputTokens":10,"outputTokens":5,"cacheReadTokens":99,"latestContextTokens":42}""",
        )!!
        assertEquals(10L, parsed.first)
        assertEquals(5L, parsed.second)
    }

    @Test
    fun `null blank malformed and zero are null`() {
        assertNull(parseTokenUsage(null))
        assertNull(parseTokenUsage(""))
        assertNull(parseTokenUsage("   "))
        assertNull(parseTokenUsage("not json"))
        assertNull(parseTokenUsage("""{"inputTokens":0,"outputTokens":0}"""))
        assertNull(parseTokenUsage("""{}"""))
    }

    @Test
    fun `missing keys default to zero`() {
        val parsed = parseTokenUsage("""{"outputTokens":7}""")!!
        assertEquals(0L, parsed.first)
        assertEquals(7L, parsed.second)
    }
}
