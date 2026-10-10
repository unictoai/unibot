package ai.unicto.unibot.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException

/**
 * Zip-bomb caps for the provider-import flow: entry count, per-entry size and
 * total size are bounded, mirroring SkillRepository's guard.
 */
class ProviderImportZipTest {

    @Test
    fun `small entries copy fine and byte count is returned`() {
        val data = ByteArray(100) { it.toByte() }
        val out = ByteArrayOutputStream()
        val copied = ProviderImportZip.copyBounded(
            ByteArrayInputStream(data), out, "a.json", 0L,
        )
        assertEquals(100L, copied)
        assertTrue(out.toByteArray().contentEquals(data))
    }

    @Test
    fun `entry exceeding per-entry cap throws`() {
        val out = ByteArrayOutputStream()
        // An 11MB stream in 1KB chunks: must trip the 10MB per-entry cap.
        val chunked = object : java.io.InputStream() {
            var chunksLeft = 11 * 1024
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (chunksLeft-- <= 0) return -1
                return 1024
            }
        }
        try {
            ProviderImportZip.copyBounded(chunked, out, "evil.bin", 0L)
            fail("expected ZipException")
        } catch (e: ZipException) {
            assertTrue(e.message!!.contains("exceeds size limits"))
        }
    }

    @Test
    fun `total across entries exceeding cap throws`() {
        val out = ByteArrayOutputStream()
        try {
            // totalSoFar already near the cap: the next chunk must trip it.
            ProviderImportZip.copyBounded(
                ByteArrayInputStream(ByteArray(9000)),
                out,
                "b.json",
                ProviderImportZip.MAX_ZIP_TOTAL_BYTES - 1000,
            )
            fail("expected ZipException")
        } catch (e: ZipException) {
            assertTrue(e.message!!.contains("exceeds size limits"))
        }
    }

    @Test
    fun `checkZipBudget accepts inside limits and rejects outside`() {
        // Inside: no throw.
        ProviderImportZip.checkZipBudget("a.json", 1024, 2048)
        // Per-entry breach.
        try {
            ProviderImportZip.checkZipBudget("a.json", ProviderImportZip.MAX_ZIP_ENTRY_BYTES + 1, 0)
            fail("expected ZipException")
        } catch (_: ZipException) {
        }
        // Total breach with small entries.
        try {
            ProviderImportZip.checkZipBudget("a.json", 10, ProviderImportZip.MAX_ZIP_TOTAL_BYTES + 1)
            fail("expected ZipException")
        } catch (_: ZipException) {
        }
    }

    @Test
    fun `caps mirror SkillRepository`() {
        assertEquals(2_000, ProviderImportZip.MAX_ZIP_ENTRIES)
        assertEquals(10L * 1024 * 1024, ProviderImportZip.MAX_ZIP_ENTRY_BYTES)
        assertEquals(50L * 1024 * 1024, ProviderImportZip.MAX_ZIP_TOTAL_BYTES)
    }
}
