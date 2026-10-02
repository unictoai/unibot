package ai.unicto.unibot.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the app-side capabilities behind on-device chat.
 * Only the pure functions are covered here (no Context, no network):
 * the search trigger heuristics, the DDG HTML parser and the prompt
 * block formatter.
 */
class LocalCapabilitiesTest {

    // ── needsWebSearch ──

    @Test
    fun `recency questions trigger search`() {
        assertTrue(LocalCapabilities.needsWebSearch("when solo leveling new movie will come"))
        assertTrue(LocalCapabilities.needsWebSearch("When will the new season release?"))
        assertTrue(LocalCapabilities.needsWebSearch("when is eid 2026"))
        assertTrue(LocalCapabilities.needsWebSearch("who won the match yesterday"))
        assertTrue(LocalCapabilities.needsWebSearch("latest news about AI"))
        assertTrue(LocalCapabilities.needsWebSearch("price of bitcoin today"))
        assertTrue(LocalCapabilities.needsWebSearch("what's the weather in karachi"))
        assertTrue(LocalCapabilities.needsWebSearch("how much does it cost"))
        assertTrue(LocalCapabilities.needsWebSearch("iPhone 18 release date"))
    }

    @Test
    fun `timeless questions do not trigger search`() {
        assertFalse(LocalCapabilities.needsWebSearch("write a poem about the sea"))
        assertFalse(LocalCapabilities.needsWebSearch("explain photosynthesis"))
        assertFalse(LocalCapabilities.needsWebSearch("hi"))
        assertFalse(LocalCapabilities.needsWebSearch("tell me a joke"))
        assertFalse(LocalCapabilities.needsWebSearch("how do I bake a cake"))
        assertFalse(LocalCapabilities.needsWebSearch("what is the capital of france"))
    }

    // ── LocalWebSearch.parseResults ──

    private val cannedHtml = """
        <div class="result">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsolo%2Dleveling&amp;rut=abc123">Solo Leveling <b>movie</b> announced</a>
        <a rel="nofollow" class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsolo%2Dleveling">The new Solo Leveling movie &amp; its release window revealed.</a>
        </div>
        <div class="result">
        <a rel="nofollow" class="result__a" href="https://direct.example.org/news">Direct link title</a>
        <a rel="nofollow" class="result__snippet" href="https://direct.example.org/news">Plain snippet here.</a>
        </div>
    """.trimIndent()

    @Test
    fun `parser unwraps ddg links and cleans text`() {
        val results = LocalWebSearch.parseResults(cannedHtml, 4)
        assertEquals(2, results.size)

        assertEquals("Solo Leveling movie announced", results[0].title)
        assertEquals("https://example.com/solo-leveling", results[0].url)
        assertEquals(
            "The new Solo Leveling movie & its release window revealed.",
            results[0].snippet,
        )

        assertEquals("Direct link title", results[1].title)
        assertEquals("https://direct.example.org/news", results[1].url)
    }

    @Test
    fun `parser respects maxResults`() {
        val results = LocalWebSearch.parseResults(cannedHtml, 1)
        assertEquals(1, results.size)
    }

    @Test
    fun `parser returns empty on garbage`() {
        assertTrue(LocalWebSearch.parseResults("<html>no results here</html>", 4).isEmpty())
        assertTrue(LocalWebSearch.parseResults("", 4).isEmpty())
    }

    // ── buildSearchBlock / currentDateTimeLine ──

    @Test
    fun `search block is labeled and honest`() {
        val block = LocalCapabilities.buildSearchBlock(
            listOf(WebResult("T", "S", "https://x.example")),
        )
        assertTrue(block.contains("Live web search results"))
        assertTrue(block.contains("1. T"))
        assertTrue(block.contains("https://x.example"))
        assertTrue(block.contains("say so honestly"))
    }

    @Test
    fun `date line is labeled`() {
        assertTrue(LocalCapabilities.currentDateTimeLine().startsWith("Current date and time: "))
    }
}
