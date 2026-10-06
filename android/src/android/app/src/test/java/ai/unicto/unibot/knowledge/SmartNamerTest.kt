package ai.unicto.unibot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartNamerTest {

    @Test
    fun `markdown heading wins`() {
        val title = SmartNamer.suggestTitle(
            "notes.md", "text/markdown",
            "# Trip to the mountains\n\nWe left on Friday.",
        )
        assertEquals("Trip to the mountains", title)
    }

    @Test
    fun `code prefers filename stem`() {
        val title = SmartNamer.suggestTitle(
            "NetworkManager.kt", "text/x-kotlin",
            "package ai.unicto.unibot\n\nimport okhttp3.OkHttpClient",
        )
        assertEquals("NetworkManager", title)
    }

    @Test
    fun `first sentence for plain prose`() {
        val title = SmartNamer.suggestTitle(
            null, "text/plain",
            "The meeting notes from Tuesday cover the launch plan. Second sentence here.",
        )
        assertEquals("The meeting notes from Tuesday cover the launch plan", title)
    }

    @Test
    fun `filename stem when no usable text`() {
        val title = SmartNamer.suggestTitle("invoice-2026.pdf", "application/pdf", null)
        assertEquals("invoice-2026", title)
    }

    @Test
    fun `never blank`() {
        assertEquals("Untitled document", SmartNamer.suggestTitle(null, null, null))
        assertEquals("Untitled document", SmartNamer.suggestTitle(null, "text/plain", "   "))
        val img = SmartNamer.suggestTitle(null, "image/png", null)
        assertEquals("Scanned image", img)
    }

    @Test
    fun `title capped at 80 chars`() {
        val long = "A".repeat(200)
        val title = SmartNamer.suggestTitle(null, "text/plain", long)
        assertEquals(80, title.length)
    }
}
