package ai.unicto.unibot.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * In-memory view of the knowledge index, safe to query synchronously from
 * the chat send path (pure CPU: embedding one query + cosine over ≤3000
 * chunks is single-digit milliseconds).
 */
class KnowledgeIndex(
    val docs: List<KnowledgeDoc>,
    private val chunks: List<StoredChunk>,
) {
    private val embeddings: List<ByteArray> =
        chunks.map { runCatching { Base64.getDecoder().decode(it.embeddingB64) }.getOrDefault(ByteArray(HashedEmbedding.DIM)) }
    private val docsById: Map<String, KnowledgeDoc> = docs.associateBy { it.id }

    /**
     * Top-[topK] chunks by cosine similarity. Title chunks (index -1) get a
     * small boost so documents are findable by name.
     */
    fun search(query: String, topK: Int = 6, onlyDocId: String? = null): List<KnowledgeHit> {
        if (query.isBlank() || chunks.isEmpty()) return emptyList()
        val q = HashedEmbedding.embed(query)
        val scored = ArrayList<Triple<StoredChunk, KnowledgeDoc, Float>>(chunks.size)
        for (i in chunks.indices) {
            val c = chunks[i]
            if (onlyDocId != null && c.docId != onlyDocId) continue
            val doc = docsById[c.docId] ?: continue
            var score = HashedEmbedding.cosine(q, embeddings[i])
            if (c.index == TITLE_CHUNK_INDEX) score += 0.10f
            scored += Triple(c, doc, score)
        }
        return scored
            .filter { it.third >= MIN_SCORE }
            .sortedByDescending { it.third }
            .take(topK.coerceAtLeast(1))
            .map { (c, doc, score) ->
                KnowledgeHit(doc, c.index, c.page, c.text, score)
            }
    }

    companion object {
        /** Pseudo-chunk index for the synthetic title chunk every doc gets. */
        const val TITLE_CHUNK_INDEX = -1
        /** Below this the match is noise; the hook stays silent. */
        const val MIN_SCORE = 0.12f
    }
}

@Serializable
private data class IndexFile(
    val docs: List<KnowledgeDoc>,
    val chunks: List<StoredChunk>,
)

/**
 * File-backed knowledge store: `knowledge/index.json` (docs + quantized
 * chunk embeddings) plus `knowledge/docs/<id>.bin` (original bytes).
 *
 * Caps keep RAM disciplined on 4 GB phones: [MAX_DOCS] documents,
 * [MAX_CHUNKS] chunks total (~384 KB of vectors). When a cap trips, the
 * oldest documents are evicted first.
 *
 * Locking is plain `synchronized` (never a suspend Mutex) so the chat send
 * path can take a blocking snapshot via [snapshotSync] without deadlocking.
 * File I/O runs on Dispatchers.IO; no suspend call happens inside the lock.
 */
class KnowledgeStore(rootDir: File) {

    companion object {
        const val MAX_DOCS = 200
        const val MAX_CHUNKS = 3000
    }

    private val storeDir = File(rootDir, "knowledge")
    private val indexFile = File(storeDir, "index.json")
    private val docsDir = File(storeDir, "docs")
    private val lock = Any()
    private var docs = ArrayList<KnowledgeDoc>()
    private var chunks = ArrayList<StoredChunk>()
    private var loaded = false

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Original bytes for [docId], or null when absent. */
    fun contentFile(docId: String): File = File(docsDir, "$docId.bin")

    /** Blocking snapshot for the synchronous chat send hook. */
    fun snapshotSync(): KnowledgeIndex = synchronized(lock) {
        ensureLoadedLocked()
        KnowledgeIndex(docs.toList(), chunks.toList())
    }

    suspend fun snapshot(): KnowledgeIndex = withContext(Dispatchers.IO) {
        snapshotSync()
    }

    suspend fun listDocs(): List<KnowledgeDoc> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureLoadedLocked()
            docs.sortedByDescending { it.addedAtMs }
        }
    }

    /**
     * Insert or replace a document and its chunks. Returns the ids of any
     * documents evicted by the caps.
     */
    suspend fun putDoc(
        doc: KnowledgeDoc,
        content: ByteArray?,
        docChunks: List<StoredChunk>,
    ): List<String> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureLoadedLocked()
            removeDocLocked(doc.id)
            if (content != null) {
                docsDir.mkdirs()
                contentFile(doc.id).writeBytes(content)
            }
            docs += doc
            chunks += docChunks
            val evicted = enforceCapsLocked()
            saveLocked()
            evicted
        }
    }

    suspend fun removeDoc(id: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureLoadedLocked()
            val removed = removeDocLocked(id)
            if (removed) saveLocked()
            removed
        }
    }

    suspend fun renameDoc(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureLoadedLocked()
            val i = docs.indexOfFirst { it.id == id }
            if (i < 0) return@withContext false
            docs[i] = docs[i].copy(title = title)
            saveLocked()
            true
        }
    }

    private fun removeDocLocked(id: String): Boolean {
        val removed = docs.removeAll { it.id == id }
        chunks.removeAll { it.docId == id }
        runCatching { contentFile(id).delete() }
        return removed
    }

    private fun enforceCapsLocked(): List<String> {
        val evicted = ArrayList<String>()
        // Oldest first.
        docs.sortBy { it.addedAtMs }
        while ((docs.size > MAX_DOCS || chunks.size > MAX_CHUNKS) && docs.isNotEmpty()) {
            val victim = docs.removeAt(0)
            chunks.removeAll { it.docId == victim.id }
            runCatching { contentFile(victim.id).delete() }
            evicted += victim.id
        }
        return evicted
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!indexFile.exists()) return
        runCatching {
            val parsed = json.decodeFromString<IndexFile>(indexFile.readText())
            docs = ArrayList(parsed.docs)
            chunks = ArrayList(parsed.chunks)
        }
    }

    private fun saveLocked() {
        runCatching {
            storeDir.mkdirs()
            // Atomic-ish: write temp then rename so a killed process never
            // leaves a half-written index.
            val tmp = File(storeDir, "index.json.tmp")
            tmp.writeText(json.encodeToString(IndexFile(docs.toList(), chunks.toList())))
            tmp.renameTo(indexFile)
        }
    }
}
