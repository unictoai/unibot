package ai.unicto.unibot.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 87 — image pipeline sized for 4GB devices.
 *
 * Pins the fixed cache budgets: the memory cache must stay small enough
 * to leave headroom for the chat's own caches on a 4GB phone, and the
 * disk cache must stay bounded so a long-lived install can't accumulate
 * gigabytes of thumbnails.
 */
class ImageCacheTuningTest {

    @Test
    fun `memory budget is fixed at 24 MB`() {
        assertEquals(24L * 1024 * 1024, ImageCacheTuning.memoryCacheBytes())
        assertEquals(ImageCacheTuning.MEMORY_CACHE_BYTES, ImageCacheTuning.memoryCacheBytes())
    }

    @Test
    fun `memory budget leaves headroom on a 4GB phone`() {
        // A 4GB device typically gives the app a 256 MB heap; the image
        // memory cache must be a small fraction of that.
        val heap256Mb = 256L * 1024 * 1024
        assertTrue(
            "memory cache must be <= 1/8 of a 256MB heap",
            ImageCacheTuning.memoryCacheBytes() <= heap256Mb / 8,
        )
    }

    @Test
    fun `disk budget is fixed at 96 MB`() {
        assertEquals(96L * 1024 * 1024, ImageCacheTuning.diskCacheBytes())
    }

    @Test
    fun `disk cache dir name is stable`() {
        // The FileProvider `share` root and any cleanup jobs key on it.
        assertEquals("unibot_image_cache", ImageCacheTuning.DISK_CACHE_DIR_NAME)
    }
}
