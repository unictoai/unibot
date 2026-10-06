package ai.unicto.unibot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfTextExtractorTest {

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun pdf(vararg pageStreams: Pair<String, Boolean>): ByteArray {
        // pageStreams: (content stream text, flateCompressed)
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val pageCount = pageStreams.size
        val kids = (1..pageCount).map { "${2 * it + 1} 0 R" }.joinToString(" ")
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        sb.append("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $pageCount >>\nendobj\n")
        val out = ByteArrayOutputStream()
        fun emit(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        emit(sb.toString())
        pageStreams.forEachIndexed { i, (content, flate) ->
            val pageObj = 2 * i + 3
            val streamObj = 2 * i + 4
            emit("$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /Contents $streamObj 0 R >>\nendobj\n")
            val payload = if (flate) deflate(content) else content.toByteArray(Charsets.ISO_8859_1)
            val filter = if (flate) " /Filter /FlateDecode" else ""
            emit("$streamObj 0 obj\n<< /Length ${payload.size}$filter >>\nstream\n")
            out.write(payload)
            emit("\nendstream\nendobj\n")
        }
        emit("trailer\n<< /Root 1 0 R >>\n")
        return out.toByteArray()
    }

    @Test
    fun `extracts plain pages in order`() {
        val data = pdf(
            "BT /F1 12 Tf 72 720 Td (Hello page one) Tj ET" to false,
            "BT /F1 12 Tf 72 720 Td (Second page here) Tj ET" to false,
        )
        val pages = PdfTextExtractor.extract(data)
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].number)
        assertTrue(pages[0].text.contains("Hello page one"))
        assertEquals(2, pages[1].number)
        assertTrue(pages[1].text.contains("Second page here"))
    }

    @Test
    fun `extracts flate-decoded streams`() {
        val data = pdf(
            "BT (Compressed page text) Tj ET" to true,
        )
        val pages = PdfTextExtractor.extract(data)
        assertEquals(1, pages.size)
        assertTrue("got: '${pages[0].text}'", pages[0].text.contains("Compressed page text"))
    }

    @Test
    fun `extracts TJ arrays and hex strings`() {
        val hex = "Hello".toByteArray().joinToString("") { "%02x".format(it) }
        val data = pdf(
            "BT [(Paren) 120 (thesis)] TJ ET" to false,
            "BT <$hex> Tj ET" to false,
        )
        val pages = PdfTextExtractor.extract(data)
        assertEquals(2, pages.size)
        assertTrue("got: '${pages[0].text}'", pages[0].text.contains("Parenthesis"))
        assertTrue("got: '${pages[1].text}'", pages[1].text.contains("Hello"))
    }

    @Test
    fun `line breaks survive Td operators`() {
        val data = pdf(
            "BT (Line one) Tj ET BT 0 -20 Td (Line two) Tj ET" to false,
        )
        val pages = PdfTextExtractor.extract(data)
        assertEquals(1, pages.size)
        assertTrue(pages[0].text.contains("Line one"))
        assertTrue(pages[0].text.contains("Line two"))
    }

    @Test
    fun `garbage returns empty, never throws`() {
        assertTrue(PdfTextExtractor.extract(ByteArray(0)).isEmpty())
        assertTrue(PdfTextExtractor.extract("not a pdf at all".toByteArray()).isEmpty())
        assertTrue(PdfTextExtractor.extract("%PDF-1.4\ntrailer\n".toByteArray()).isEmpty())
    }

    @Test
    fun `winansi high bytes decode`() {
        // 0x93/0x94 are windows-1252 smart quotes.
        val decoded = PdfTextExtractor.decodePdfString(byteArrayOf(0x93.toByte(), 0x41, 0x94.toByte()))
        assertEquals("\u201cA\u201d", decoded)
    }

    @Test
    fun `utf16be bom decodes`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x48, 0x00, 0x69)
        assertEquals("Hi", PdfTextExtractor.decodePdfString(bytes))
    }
}
