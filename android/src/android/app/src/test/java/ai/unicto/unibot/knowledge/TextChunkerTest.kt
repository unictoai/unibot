package ai.unicto.unibot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `short text is one chunk`() {
        val chunks = TextChunker.chunk("hello world")
        assertEquals(1, chunks.size)
        assertEquals("hello world", chunks[0].text)
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(TextChunker.chunk("   \n  ").isEmpty())
    }

    @Test
    fun `long text splits with overlap and sane offsets`() {
        val sentence = "The personal knowledge base stores documents on device. "
        val text = sentence.repeat(200) // ~11k chars
        val chunks = TextChunker.chunk(text, maxChars = 2000, overlap = 200)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size >= 5)
        for (c in chunks) {
            assertTrue("chunk too long: ${c.text.length}", c.text.length <= 2000)
            assertTrue(c.startChar < c.endChar)
        }
        // Coverage: first chunk starts at 0, last chunk reaches the end.
        assertEquals(0, chunks.first().startChar)
        assertEquals(text.trim().length, chunks.last().endChar)
        // Overlap: consecutive chunks share text.
        for (i in 1 until chunks.size) {
            assertTrue(
                "chunks $i overlap",
                chunks[i].startChar < chunks[i - 1].endChar,
            )
        }
    }

    @Test
    fun `chunks join back to roughly the source`() {
        val text = (1..50).joinToString("\n\n") { "Paragraph $it explains something useful about topic $it." }
        val chunks = TextChunker.chunk(text, maxChars = 500, overlap = 100)
        val joined = chunks.joinToString(" ") { it.text }
        for (i in 1..50) {
            assertTrue("missing paragraph $i", joined.contains("Paragraph $i"))
        }
    }
}
