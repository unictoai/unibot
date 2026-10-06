package ai.unicto.unibot.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

class KnowledgeStoreTest {

    private lateinit var root: File
    private lateinit var store: KnowledgeStore

    @Before
    fun setUp() {
        root = File.createTempFile("kb-store", "").apply { delete(); mkdirs() }
        store = KnowledgeStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun chunk(id: String, text: String, index: Int = 0): StoredChunk =
        StoredChunk(
            docId = id,
            index = index,
            page = null,
            text = text,
            embeddingB64 = Base64.getEncoder().encodeToString(HashedEmbedding.embed(text)),
        )

    private fun doc(id: String, title: String, atMs: Long = System.currentTimeMillis()) =
        KnowledgeDoc(id, title, KnowledgeKind.DOCUMENT, "text/plain", null, atMs, 1)

    @Test
    fun `put and list roundtrip`() = runBlocking {
        store.putDoc(doc("d1", "First", atMs = 1000), "bytes".toByteArray(), listOf(chunk("d1", "hello")))
        store.putDoc(doc("d2", "Second", atMs = 2000), null, listOf(chunk("d2", "world")))
        val docs = store.listDocs()
        assertEquals(2, docs.size)
        // Newest first.
        assertEquals("d2", docs[0].id)
        // Content bytes persisted.
        assertEquals("bytes", store.contentFile("d1").readText())
    }

    @Test
    fun `remove deletes doc chunks and content`() = runBlocking {
        store.putDoc(doc("d1", "First"), "bytes".toByteArray(), listOf(chunk("d1", "hello")))
        assertTrue(store.removeDoc("d1"))
        assertFalse(store.removeDoc("d1"))
        assertTrue(store.listDocs().isEmpty())
        assertTrue(store.snapshot().search("hello").isEmpty())
        assertFalse(store.contentFile("d1").exists())
    }

    @Test
    fun `rename updates title`() = runBlocking {
        store.putDoc(doc("d1", "Old"), null, listOf(chunk("d1", "hello")))
        assertTrue(store.renameDoc("d1", "New"))
        assertEquals("New", store.listDocs().single().title)
        assertFalse(store.renameDoc("missing", "X"))
    }

    @Test
    fun `index survives a fresh store instance`() = runBlocking {
        store.putDoc(doc("d1", "Persistent"), null, listOf(chunk("d1", "unforgettable content")))
        val fresh = KnowledgeStore(root)
        val hits = fresh.snapshot().search("unforgettable content")
        assertEquals(1, hits.size)
        assertEquals("Persistent", hits[0].doc.title)
    }

    @Test
    fun `search ranks the relevant chunk first`() = runBlocking {
        store.putDoc(
            doc("d1", "Passwords"),
            null,
            listOf(chunk("d1", "to reset your password open settings and tap account")),
        )
        store.putDoc(
            doc("d2", "Cooking"),
            null,
            listOf(chunk("d2", "photosynthesis converts sunlight into glucose for plants")),
        )
        val hits = store.snapshot().search("how do I reset my password")
        assertTrue(hits.isNotEmpty())
        assertEquals("Passwords", hits[0].doc.title)
    }

    @Test
    fun `empty query and empty index search clean`() = runBlocking {
        assertTrue(store.snapshot().search("").isEmpty())
        assertTrue(store.snapshot().search("anything").isEmpty())
    }
}
