package ai.unicto.unibot.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for HelpArticles.search — the help center's offline search must
 * stay purely local and match titles, categories, and bodies.
 */
class HelpArticlesTest {

    @Test
    fun `blank query returns everything`() {
        assertEquals(HelpArticles.all.size, HelpArticles.search("").size)
        assertEquals(HelpArticles.all.size, HelpArticles.search("   ").size)
    }

    @Test
    fun `search matches titles`() {
        val results = HelpArticles.search("swarm")
        assertTrue(results.isNotEmpty())
        // Search matches titles, categories AND body text by design, so
        // articles that merely mention the swarm (e.g. what-is-unibot) may
        // also match. What matters: the swarm articles are found.
        assertTrue(results.any { it.category == HelpArticles.CAT_SWARM })
    }

    @Test
    fun `search matches body text`() {
        val results = HelpArticles.search("biometrics")
        assertTrue(results.any { it.id == "privacy-controls" })
    }

    @Test
    fun `search is case insensitive`() {
        assertEquals(HelpArticles.search("SWARM").size, HelpArticles.search("swarm").size)
    }

    @Test
    fun `no match returns an empty list`() {
        assertTrue(HelpArticles.search("xyzzy-no-such-topic").isEmpty())
    }

    @Test
    fun `every article has a non blank id title and body`() {
        for (article in HelpArticles.all) {
            assertTrue(article.id.isNotBlank())
            assertTrue(article.title.isNotBlank())
            assertTrue(article.body.isNotBlank())
        }
        val ids = HelpArticles.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }
}
