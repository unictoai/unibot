package ai.unicto.unibot.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Item 41 — tracking params are stripped from web-search result URLs
 * before they reach the model or the chat UI.
 */
class LocalWebSearchTrackingParamsTest {

    @Test
    fun `utm and click ids are stripped, real params kept`() {
        val url = "https://example.com/article?utm_source=google&utm_medium=cpc" +
            "&gclid=abc123&fbclid=xyz&page=2"
        assertEquals("https://example.com/article?page=2", LocalWebSearch.stripTrackingParams(url))
    }

    @Test
    fun `url without query is untouched`() {
        val url = "https://example.com/plain"
        assertEquals(url, LocalWebSearch.stripTrackingParams(url))
    }

    @Test
    fun `all params tracked leaves a clean url`() {
        val url = "https://example.com/?utm_source=x&msclkid=y"
        assertEquals("https://example.com/", LocalWebSearch.stripTrackingParams(url))
    }

    @Test
    fun `fragment is preserved`() {
        val url = "https://example.com/a?UTM_CAMPAIGN=x&b=1#section"
        assertEquals("https://example.com/a?b=1#section", LocalWebSearch.stripTrackingParams(url))
    }

    @Test
    fun `matching is case-insensitive`() {
        val url = "https://example.com/?Gclid=1&ID=5"
        assertEquals("https://example.com/?ID=5", LocalWebSearch.stripTrackingParams(url))
    }

    @Test
    fun `parseResults strips tracking params end to end`() {
        val html = "<a class=\"result__a\" href=\"https://example.com/x?utm_source=ddg&amp;id=7\">" +
            "Some Title</a><a class=\"result__snippet\">snip</a>"
        val results = LocalWebSearch.parseResults(html, 4)
        assertEquals(1, results.size)
        assertEquals("https://example.com/x?id=7", results[0].url)
    }
}
