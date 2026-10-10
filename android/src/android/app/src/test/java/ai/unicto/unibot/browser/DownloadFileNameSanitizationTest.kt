package ai.unicto.unibot.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A page-controlled filename (via the `__unibot__` JS bridge) must never
 * escape the session workspace directory. See sanitizeDownloadFileName.
 */
class DownloadFileNameSanitizationTest {

    @Test
    fun `plain filenames pass through unchanged`() {
        assertEquals("report.pdf", sanitizeDownloadFileName("report.pdf"))
        assertEquals("my file (1).png", sanitizeDownloadFileName("my file (1).png"))
    }

    @Test
    fun `parent traversals are stripped to the base name`() {
        assertEquals("evil.sh", sanitizeDownloadFileName("../../evil.sh"))
        assertEquals("evil.sh", sanitizeDownloadFileName("../../../usr/bin/evil.sh"))
        // Backslash is not a separator on Android/Linux: the name stays whole
        // but cannot traverse; it remains a single file inside the directory.
        assertEquals("..\\evil.sh", sanitizeDownloadFileName("..\\evil.sh"))
    }

    @Test
    fun `absolute paths are stripped to the base name`() {
        assertEquals("passwd", sanitizeDownloadFileName("/etc/passwd"))
    }

    @Test
    fun `dot segments become a safe default`() {
        assertEquals("download", sanitizeDownloadFileName("."))
        assertEquals("download", sanitizeDownloadFileName(".."))
        assertEquals("download", sanitizeDownloadFileName(""))
        assertEquals("download", sanitizeDownloadFileName("   "))
    }

    @Test
    fun `sanitized name cannot escape its directory`() {
        val dir = java.io.File("/tmp/workspace")
        val attacks = listOf("../../evil.sh", "../a/b", "..", "", "/etc/passwd")
        for (a in attacks) {
            val f = java.io.File(dir, sanitizeDownloadFileName(a))
            val canonical = f.canonicalPath
            assertFalse(
                "traversal: $a -> $canonical",
                canonical != dir.canonicalPath && !canonical.startsWith(dir.canonicalPath + "/"),
            )
        }
    }
}
