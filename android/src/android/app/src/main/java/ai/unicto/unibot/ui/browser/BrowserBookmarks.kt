package ai.unicto.unibot.ui.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A bookmark folder. The store never creates a default folder — a null
 *  [BrowserBookmark.folderId] means "no folder" and shows under All. */
data class BrowserBookmarkFolder(
    val id: String,
    val name: String,
    val createdAt: Long,
)

data class BrowserBookmark(
    val id: String,
    val url: String,
    val title: String,
    val folderId: String?,
    val createdAt: Long,
)

/**
 * On-device bookmark store for the in-app browser: bookmarks plus named
 * folders, persisted as JSON in the app's private files dir. Nothing leaves
 * the phone; there is no account, no sync.
 *
 * All public methods are synchronized and safe to call from the main thread
 * (the file is tiny — a few KB at most).
 */
class BrowserBookmarkStore private constructor(private val appContext: Context) {

    private val file: File get() = File(appContext.filesDir, FILE_NAME)

    private var folders: MutableList<BrowserBookmarkFolder> = mutableListOf()
    private var bookmarks: MutableList<BrowserBookmark> = mutableListOf()

    init {
        load()
    }

    // ── Queries ──

    @Synchronized
    fun getFolders(): List<BrowserBookmarkFolder> = folders.toList()

    /** [folderId] null → every bookmark, newest first. */
    @Synchronized
    fun getBookmarks(folderId: String? = null): List<BrowserBookmark> =
        bookmarks
            .filter { folderId == null || it.folderId == folderId }
            .sortedByDescending { it.createdAt }

    @Synchronized
    fun isBookmarked(url: String): Boolean =
        bookmarks.any { it.url == url.trim() }

    @Synchronized
    fun bookmarkFor(url: String): BrowserBookmark? =
        bookmarks.firstOrNull { it.url == url.trim() }

    // ── Mutations ──

    @Synchronized
    fun addBookmark(url: String, title: String, folderId: String?): BrowserBookmark {
        val cleanUrl = url.trim()
        require(cleanUrl.isNotEmpty())
        val existing = bookmarks.firstOrNull { it.url == cleanUrl }
        if (existing != null) return existing
        val resolvedFolder = folderId?.takeIf { fid -> folders.any { it.id == fid } }
        val bookmark = BrowserBookmark(
            id = UUID.randomUUID().toString(),
            url = cleanUrl,
            title = title.ifBlank { cleanUrl },
            folderId = resolvedFolder,
            createdAt = System.currentTimeMillis(),
        )
        bookmarks.add(bookmark)
        save()
        return bookmark
    }

    @Synchronized
    fun removeBookmark(id: String) {
        if (bookmarks.removeAll { it.id == id }) save()
    }

    @Synchronized
    fun removeBookmarkForUrl(url: String) {
        if (bookmarks.removeAll { it.url == url.trim() }) save()
    }

    @Synchronized
    fun moveBookmark(id: String, folderId: String?) {
        val index = bookmarks.indexOfFirst { it.id == id }
        if (index < 0) return
        val resolved = folderId?.takeIf { fid -> folders.any { it.id == fid } }
        bookmarks[index] = bookmarks[index].copy(folderId = resolved)
        save()
    }

    @Synchronized
    fun addFolder(name: String): BrowserBookmarkFolder {
        val clean = name.trim().take(MAX_FOLDER_NAME)
        require(clean.isNotEmpty())
        val folder = BrowserBookmarkFolder(
            id = UUID.randomUUID().toString(),
            name = clean,
            createdAt = System.currentTimeMillis(),
        )
        folders.add(folder)
        save()
        return folder
    }

    @Synchronized
    fun renameFolder(id: String, name: String) {
        val clean = name.trim().take(MAX_FOLDER_NAME)
        if (clean.isEmpty()) return
        val index = folders.indexOfFirst { it.id == id }
        if (index < 0) return
        folders[index] = folders[index].copy(name = clean)
        save()
    }

    /**
     * Deletes a folder; bookmarks it held move back to "no folder" rather
     * than being deleted — deleting a folder never deletes pages.
     */
    @Synchronized
    fun deleteFolder(id: String) {
        if (folders.removeAll { it.id == id }) {
            bookmarks = bookmarks.map {
                if (it.folderId == id) it.copy(folderId = null) else it
            }.toMutableList()
            save()
        }
    }

    // ── Persistence ──

    private fun load() {
        try {
            val f = file
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            folders = obj.optJSONArray("folders")?.toFolderList() ?: mutableListOf()
            bookmarks = obj.optJSONArray("bookmarks")?.toBookmarkList() ?: mutableListOf()
        } catch (_: Exception) {
            folders = mutableListOf()
            bookmarks = mutableListOf()
        }
    }

    private fun save() {
        try {
            val obj = JSONObject()
                .put("folders", JSONArray(folders.map { it.toJson() }))
                .put("bookmarks", JSONArray(bookmarks.map { it.toJson() }))
            file.writeText(obj.toString())
        } catch (_: Exception) {
            // Storage failures must never break browsing; the in-memory
            // lists still work for this session.
        }
    }

    private fun BrowserBookmarkFolder.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)

    private fun BrowserBookmark.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("title", title)
        .put("folderId", folderId)
        .put("createdAt", createdAt)

    private fun JSONArray.toFolderList(): MutableList<BrowserBookmarkFolder> {
        val out = mutableListOf<BrowserBookmarkFolder>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out.add(
                BrowserBookmarkFolder(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    name = o.optString("name").ifEmpty { "Folder" },
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            )
        }
        return out
    }

    private fun JSONArray.toBookmarkList(): MutableList<BrowserBookmark> {
        val out = mutableListOf<BrowserBookmark>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            out.add(
                BrowserBookmark(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    url = url,
                    title = o.optString("title").ifEmpty { url },
                    folderId = o.optString("folderId").ifEmpty { null },
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            )
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "browser_bookmarks.json"
        private const val MAX_FOLDER_NAME = 40

        @Volatile
        private var instance: BrowserBookmarkStore? = null

        fun getInstance(context: Context): BrowserBookmarkStore =
            instance ?: synchronized(this) {
                instance ?: BrowserBookmarkStore(context.applicationContext).also { instance = it }
            }
    }
}
