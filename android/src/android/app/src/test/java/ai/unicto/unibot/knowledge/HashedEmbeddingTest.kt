package ai.unicto.unibot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashedEmbeddingTest {

    @Test
    fun `embed is deterministic`() {
        val a = HashedEmbedding.embed("the quick brown fox")
        val b = HashedEmbedding.embed("the quick brown fox")
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `embed is case-insensitive`() {
        val a = HashedEmbedding.embed("Hello World")
        val b = HashedEmbedding.embed("hello world")
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `empty text yields zero vector`() {
        val v = HashedEmbedding.embed("   ")
        assertTrue(v.all { it == 0.toByte() })
        assertEquals(0f, HashedEmbedding.cosine(v, HashedEmbedding.embed("something")), 0.0001f)
    }

    @Test
    fun `identical texts score 1`() {
        val v = HashedEmbedding.embed("knowledge base retrieval")
        assertEquals(1f, HashedEmbedding.cosine(v, v.copyOf()), 0.001f)
    }

    @Test
    fun `similar texts outscore dissimilar texts`() {
        val query = HashedEmbedding.embed("how do I reset my password")
        val close = HashedEmbedding.embed("to reset your password open settings")
        val far = HashedEmbedding.embed("photosynthesis converts sunlight to glucose")
        assertTrue(
            "close=${HashedEmbedding.cosine(query, close)} far=${HashedEmbedding.cosine(query, far)}",
            HashedEmbedding.cosine(query, close) > HashedEmbedding.cosine(query, far),
        )
    }

    @Test
    fun `cosine is bounded`() {
        val a = HashedEmbedding.embed("alpha beta gamma")
        val b = HashedEmbedding.embed("delta epsilon zeta")
        val s = HashedEmbedding.cosine(a, b)
        assertTrue(s in -1f..1f)
    }

    @Test
    fun `dimension is 128`() {
        assertEquals(128, HashedEmbedding.embed("x").size)
        assertEquals(128, HashedEmbedding.DIM)
    }
}
