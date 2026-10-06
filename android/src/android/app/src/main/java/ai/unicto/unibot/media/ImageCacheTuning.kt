package ai.unicto.unibot.media

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * v1.4.0 item 87 — image pipeline sized for 4GB devices.
 *
 * Coil 2's defaults scale the memory cache from the app's memory class,
 * which on a 4GB phone still allows tens of MB of bitmap pixels — on top
 * of the chat's own caches (markdown, KaTeX) that already fight for the
 * same heap. These caps keep image memory bounded and predictable:
 *
 * - Memory cache: 24 MB fixed. Markdown/attachment images are
 *   re-decodable from disk in milliseconds; the memory cache is a scroll
 *   smoothness buffer, not storage.
 * - Disk cache: 96 MB under cacheDir (system-may-clear). Bounded so a
 *   long-lived install can't accumulate gigabytes of thumbnails.
 *
 * [memoryCacheBytes] is a pure function of nothing — the fixed budget —
 * kept separate so unit tests can pin the budget without an Android
 * Context. `UnibotApp.newImageLoader` applies it via [applyUnibotTuning].
 */
object ImageCacheTuning {
    /** Fixed memory-cache budget: 24 MB. */
    const val MEMORY_CACHE_BYTES: Long = 24L * 1024 * 1024

    /** Fixed disk-cache budget: 96 MB. */
    const val DISK_CACHE_BYTES: Long = 96L * 1024 * 1024

    /** Disk-cache directory name under [Context.getCacheDir]. */
    const val DISK_CACHE_DIR_NAME: String = "unibot_image_cache"

    fun memoryCacheBytes(): Long = MEMORY_CACHE_BYTES

    fun diskCacheBytes(): Long = DISK_CACHE_BYTES
}

/**
 * Applies the 4GB-device tuning to a Coil [ImageLoader.Builder].
 * Memory cache is capped at [ImageCacheTuning.MEMORY_CACHE_BYTES];
 * disk cache at [ImageCacheTuning.DISK_CACHE_BYTES] under cacheDir.
 */
fun ImageLoader.Builder.applyUnibotTuning(context: Context): ImageLoader.Builder =
    memoryCache {
        MemoryCache.Builder(context)
            .maxSizeBytes(ImageCacheTuning.memoryCacheBytes())
            .build()
    }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(ImageCacheTuning.DISK_CACHE_DIR_NAME))
                .maxSizeBytes(ImageCacheTuning.diskCacheBytes())
                .build()
        }
