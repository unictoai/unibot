package ai.unicto.unibot.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class KnowledgeRepositoryTest {

    private lateinit var root: File
    private lateinit var repo: KnowledgeRepository

    @Before
    fun setUp() {
        root = File.createTempFile("kb-repo", "").apply { delete(); mkdirs() }
        repo = KnowledgeRepository(KnowledgeStore(root))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `addText indexes and retrieves with citation`() = runBlocking {
        val doc = repo.addText(
            "Refund Policy",
            "Our refund policy: full refunds within 30 days of purchase. Contact support@example.com.",
        )
        assertNotNull(doc)
        val hits = repo.search("how do refunds work")
        assertTrue(hits.isNotEmpty())
        assertEquals("Refund Policy", hits[0].doc.title)
        assertEquals("Refund Policy", hits[0].citation())
        val block = repo.buildContextBlock("how do refunds work")
        assertTrue("block was: $block", block.contains("[Refund Policy]"))
        assertTrue(block.contains("30 days"))
    }

    @Test
    fun `addNote derives title from content`() = runBlocking {
        val note = repo.addNote("The wifi password for the office is correct-horse-battery.")
        assertNotNull(note)
        assertTrue("title was: ${note!!.title}", note.title.contains("wifi password", ignoreCase = true))
        assertEquals(KnowledgeKind.NOTE, note.kind)
        assertNull(repo.addNote("   "))
    }

    @Test
    fun `pdf addFile cites page numbers`() = runBlocking {
        // Minimal two-page PDF (uncompressed) built inline.
        fun pageObj(n: Int, text: String): String {
            val stream = "BT /F1 12 Tf 72 720 Td ($text) Tj ET"
            return "${2 * n + 1} 0 obj\n<< /Type /Page /Parent 2 0 R /Contents ${2 * n + 2} 0 R >>\nendobj\n" +
                "${2 * n + 2} 0 obj\n<< /Length ${stream.length} >>\nstream\n$stream\nendstream\nendobj\n"
        }
        val pdf = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>\nendobj\n")
            append(pageObj(1, "Cats are independent animals"))
            append(pageObj(2, "Dogs are loyal companions"))
            append("trailer\n<< /Root 1 0 R >>\n")
        }.toByteArray(Charsets.ISO_8859_1)

        val doc = repo.addFile("pets.pdf", "application/pdf", pdf)
        assertEquals(KnowledgeKind.PDF, doc.kind)
        val hits = repo.search("tell me about dogs")
        assertTrue(hits.isNotEmpty())
        val dogHit = hits.firstOrNull { it.text.contains("Dogs") }
        assertNotNull(dogHit)
        assertEquals(2, dogHit!!.page)
        assertEquals("${dogHit.doc.title}, p. 2", dogHit.citation())
        val block = repo.buildContextBlock("tell me about dogs")
        assertTrue("block was: $block", block.contains("p. 2"))
    }

    @Test
    fun `unrelated query returns empty block`() = runBlocking {
        repo.addText("Cooking", "Simmer the sauce for twenty minutes on low heat.")
        val block = repo.buildContextBlock("quantum field theory renormalization")
        assertTrue("block was: $block", block.isEmpty())
    }

    @Test
    fun `delete and rename work`() = runBlocking {
        val doc = repo.addText("Temp", "temporary content here")!!
        assertTrue(repo.renameDoc(doc.id, "Renamed"))
        assertEquals("Renamed", repo.listDocs().single().title)
        assertTrue(repo.deleteDoc(doc.id))
        assertTrue(repo.listDocs().isEmpty())
    }

    @Test
    fun `image without ocr is title-searchable`() = runBlocking {
        val doc = repo.addFile("whiteboard.png", "image/png", byteArrayOf(1, 2, 3), ocr = null)
        assertEquals(KnowledgeKind.IMAGE_TEXT, doc.kind)
        // Title chunk keeps it findable by name.
        val hits = repo.search("whiteboard")
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun `binary file is stored and title-searchable`() = runBlocking {
        val doc = repo.addFile("archive.zip", "application/zip", ByteArray(64) { it.toByte() })
        val hits = repo.search("archive")
        assertTrue(hits.isNotEmpty())
        assertEquals(doc.id, hits[0].doc.id)
    }
}
