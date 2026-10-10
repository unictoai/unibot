package ai.unicto.unibot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Document citation parsing and linkification ("Ask my documents").
 */
class DocCitationLinkifierTest {

    @Test
    fun `linkifies a simple file-line citation`() {
        val out = DocCitationLinkifier.linkify("See notes/todo.md:42 for details.")
        assertEquals(
            "See [notes/todo.md:42](unibot-doc:/notes/todo.md?line=42) for details.",
            out,
        )
    }

    @Test
    fun `linkifies a line range`() {
        val out = DocCitationLinkifier.linkify("Quoted in notes/todo.md:42-48.")
        assertEquals(
            "Quoted in [notes/todo.md:42-48](unibot-doc:/notes/todo.md?line=42&end=48).",
            out,
        )
    }

    @Test
    fun `linkifies multiple citations`() {
        val out = DocCitationLinkifier.linkify("a.md:1 and b/c.txt:2-3")
        assertTrue(out.contains("[a.md:1](unibot-doc:/a.md?line=1)"))
        assertTrue(out.contains("[b/c.txt:2-3](unibot-doc:/b/c.txt?line=2&end=3)"))
    }

    @Test
    fun `ignores times`() {
        val inText = "Meet at 10:30 tomorrow."
        assertSame(inText, DocCitationLinkifier.linkify(inText))
    }

    @Test
    fun `ignores version numbers`() {
        val inText = "Fixed in v1.5:2 of the spec."
        assertSame(inText, DocCitationLinkifier.linkify(inText))
    }

    @Test
    fun `ignores bare urls with ports`() {
        val inText = "See https://example.com:8080/x for the dashboard."
        assertSame(inText, DocCitationLinkifier.linkify(inText))
    }

    @Test
    fun `does not linkify inside fenced code`() {
        val inText = "```\nnotes/todo.md:42\n```\nReal cite: a.md:1"
        val out = DocCitationLinkifier.linkify(inText)
        assertTrue(out.contains("```\nnotes/todo.md:42\n```"))
        assertTrue(out.contains("[a.md:1](unibot-doc:/a.md?line=1)"))
    }

    @Test
    fun `does not linkify inside inline code`() {
        val inText = "Use `notes/todo.md:42` literally."
        assertSame(inText, DocCitationLinkifier.linkify(inText))
    }

    @Test
    fun `does not linkify inside existing markdown links`() {
        val inText = "Open [the doc](https://example.com/notes/todo.md:42) now."
        assertSame(inText, DocCitationLinkifier.linkify(inText))
    }

    @Test
    fun `linkify is idempotent`() {
        val once = DocCitationLinkifier.linkify("See notes/todo.md:42-48.")
        val twice = DocCitationLinkifier.linkify(once)
        assertEquals(once, twice)
    }

    @Test
    fun `returns same instance when nothing matches`() {
        val inText = "No citations here."
        assertSame(inText, DocCitationLinkifier.linkify(inText))
    }

    @Test
    fun `citation url round-trips a relative path`() {
        val c = DocCitationLinkifier.Citation("notes/todo.md", 42, 48)
        val url = DocCitationLinkifier.citationUrl(c)
        assertEquals("unibot-doc:/notes/todo.md?line=42&end=48", url)
        assertEquals(c, DocCitationLinkifier.parseCitationUrl(url))
    }

    @Test
    fun `citation url round-trips an absolute path`() {
        val c = DocCitationLinkifier.Citation("/var/minis/shared/report.pdf", 3, 3)
        val url = DocCitationLinkifier.citationUrl(c)
        assertEquals("unibot-doc:///var/minis/shared/report.pdf?line=3", url)
        assertEquals(c, DocCitationLinkifier.parseCitationUrl(url))
    }

    @Test
    fun `citation url encodes spaces and keeps plus literal`() {
        val c = DocCitationLinkifier.Citation("my notes/a+b.md", 7, 7)
        val url = DocCitationLinkifier.citationUrl(c)
        assertTrue(url.contains("my%20notes/a+b.md"))
        assertEquals(c, DocCitationLinkifier.parseCitationUrl(url))
    }

    @Test
    fun `parseCitationUrl rejects traversal and bad lines`() {
        assertNull(DocCitationLinkifier.parseCitationUrl("unibot-doc:/../secret.md?line=1"))
        assertNull(DocCitationLinkifier.parseCitationUrl("unibot-doc:/a.md?line=0"))
        assertNull(DocCitationLinkifier.parseCitationUrl("unibot-doc:/a.md"))
        assertNull(DocCitationLinkifier.parseCitationUrl("https://example.com/a.md?line=1"))
    }

    @Test
    fun `reversed range clamps to start`() {
        val out = DocCitationLinkifier.linkify("notes/todo.md:48-42")
        assertTrue(out.contains("?line=48"))
        assertTrue(!out.contains("end="))
    }
}
