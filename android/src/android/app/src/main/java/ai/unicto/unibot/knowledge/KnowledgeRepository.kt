package ai.unicto.unibot.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * High-level personal knowledge base (item 47).
 *
 * Accepts text, notes, files, and PDFs; chunks and embeds them into the
 * on-device [KnowledgeStore]; answers queries with cited excerpts for chat
 * (via [KnowledgeSendHook]) and the swarm tool surface (via [KnowledgeToolSpec]).
 *
 * Everything here is on-device: no network, no account, no upload.
 */
class KnowledgeRepository(
    private val store: KnowledgeStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /** Max excerpt characters injected into a chat turn. */
        const val DEFAULT_CONTEXT_CHARS = 3000
        /** Chars of a doc sampled for smart titling. */
        const val TITLE_SAMPLE_CHARS = 4000
        /** Docs larger than this are still indexed, but the raw bytes kept. */
        const val MAX_INDEX_BYTES = 4 * 1024 * 1024
    }

    suspend fun listDocs(): List<KnowledgeDoc> = store.listDocs()

    fun contentFile(docId: String): File = store.contentFile(docId)

    suspend fun deleteDoc(id: String): Boolean = store.removeDoc(id)

    suspend fun renameDoc(id: String, title: String): Boolean =
        store.renameDoc(id, title.trim().take(120))

    /**
     * Save an assistant reply (or any text) as a note (item 53). Title is
     * auto-derived from the content (item 54).
     */
    suspend fun addNote(text: String): KnowledgeDoc? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val title = SmartNamer.suggestTitle(null, "text/plain", clean.take(TITLE_SAMPLE_CHARS))
        return addText(title, clean, KnowledgeKind.NOTE, "text/plain")
    }

    suspend fun addText(
        title: String?,
        text: String,
        kind: KnowledgeKind = KnowledgeKind.DOCUMENT,
        mimeType: String? = "text/plain",
    ): KnowledgeDoc? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val resolvedTitle = title?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
            ?: SmartNamer.suggestTitle(null, mimeType, clean.take(TITLE_SAMPLE_CHARS))
        return indexDoc(
            title = resolvedTitle,
            kind = kind,
            mimeType = mimeType,
            sourceFileName = null,
            content = clean.toByteArray(Charsets.UTF_8),
            pages = listOf(null to clean),
        )
    }

    /**
     * Add an arbitrary file. Text/markdown/code are decoded as UTF-8; PDFs go
     * through [PdfTextExtractor] per page (item 48); images are OCR'd when an
     * [ocr] engine is supplied (item 50); anything else is stored with its
     * title indexed so it stays findable by name.
     */
    suspend fun addFile(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray,
        ocr: OcrEngine? = null,
    ): KnowledgeDoc = withContext(Dispatchers.Default) {
        val mime = (mimeType ?: guessMime(fileName)).lowercase()
        val kind: KnowledgeKind
        val pages: List<Pair<Int?, String>>
        val keepBytes: ByteArray? = bytes.takeIf { it.size <= MAX_INDEX_BYTES }

        when {
            mime == "application/pdf" || fileName.lowercase().endsWith(".pdf") -> {
                kind = KnowledgeKind.PDF
                val extracted = PdfTextExtractor.extract(bytes)
                pages = extracted.map { it.number to it.text }
            }
            mime.startsWith("image/") -> {
                kind = KnowledgeKind.IMAGE_TEXT
                val text = ocr?.let {
                    runCatching { it.recognize(bytes).text }.getOrDefault("")
                }.orEmpty().trim()
                pages = if (text.isNotEmpty()) listOf(null to text) else emptyList()
            }
            isTextLike(mime, fileName) -> {
                kind = if (isCodeLike(mime, fileName)) KnowledgeKind.CODE else KnowledgeKind.DOCUMENT
                val text = runCatching {
                    bytes.toString(Charsets.UTF_8)
                }.getOrDefault("").trim()
                pages = if (text.isNotEmpty()) listOf(null to text) else emptyList()
            }
            else -> {
                kind = KnowledgeKind.DOCUMENT
                pages = emptyList()
            }
        }
        val sample = pages.firstOrNull()?.second?.take(TITLE_SAMPLE_CHARS)
        val title = SmartNamer.suggestTitle(fileName, mime, sample)
        indexDoc(title, kind, mime, fileName, keepBytes, pages)
    }

    /** Cited excerpts for [query], newest-first within equal scores. */
    suspend fun search(
        query: String,
        topK: Int = 6,
        onlyDocId: String? = null,
    ): List<KnowledgeHit> = withContext(Dispatchers.Default) {
        store.snapshot().search(query, topK, onlyDocId)
    }

    /**
     * A prompt-ready block of cited excerpts for [query], capped at
     * [maxChars]. Empty string when nothing relevant matches — the send hook
     * then injects nothing.
     */
    suspend fun buildContextBlock(
        query: String,
        maxChars: Int = DEFAULT_CONTEXT_CHARS,
        onlyDocId: String? = null,
    ): String = withContext(Dispatchers.Default) {
        val hits = store.snapshot().search(query, topK = 6, onlyDocId = onlyDocId)
        if (hits.isEmpty()) return@withContext ""
        val sb = StringBuilder()
        sb.append("Relevant excerpts from the user's personal knowledge base. ")
        sb.append("When you use them, cite the source shown in brackets.\n")
        var used = 0
        for (hit in hits) {
            val excerpt = hit.text.take(1200).trim()
            if (excerpt.isEmpty()) continue
            val block = "---\n$excerpt\n[${hit.citation()}]\n"
            if (used + block.length > maxChars && used > 0) break
            sb.append(block)
            used += block.length
        }
        if (used == 0) "" else sb.toString().trim()
    }

    private suspend fun indexDoc(
        title: String,
        kind: KnowledgeKind,
        mimeType: String?,
        sourceFileName: String?,
        content: ByteArray?,
        pages: List<Pair<Int?, String>>,
    ): KnowledgeDoc {
        val id = UUID.randomUUID().toString()
        val docChunks = ArrayList<StoredChunk>()
        // Title chunk: every doc is findable by name.
        docChunks += StoredChunk(
            docId = id,
            index = KnowledgeIndex.TITLE_CHUNK_INDEX,
            page = null,
            text = title,
            embeddingB64 = b64(HashedEmbedding.embed(title)),
        )
        var chunkIndex = 0
        for ((page, pageText) in pages) {
            for (tc in TextChunker.chunk(pageText)) {
                docChunks += StoredChunk(
                    docId = id,
                    index = chunkIndex++,
                    page = page,
                    text = tc.text,
                    embeddingB64 = b64(HashedEmbedding.embed(tc.text)),
                )
            }
        }
        val doc = KnowledgeDoc(
            id = id,
            title = title,
            kind = kind,
            mimeType = mimeType,
            sourceFileName = sourceFileName,
            addedAtMs = clock(),
            chunkCount = docChunks.size,
        )
        store.putDoc(doc, content, docChunks)
        return doc
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun isTextLike(mime: String, fileName: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime == "application/json" || mime == "application/xml" ||
            mime == "application/javascript" || mime.endsWith("+json") || mime.endsWith("+xml")
        ) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTS
    }

    private fun isCodeLike(mime: String, fileName: String): Boolean {
        if (mime.startsWith("text/x-")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in CODE_EXTS
    }

    private fun guessMime(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private val TEXT_EXTS = setOf(
        "md", "markdown", "txt", "text", "rst", "org", "log", "csv", "tsv",
        "json", "jsonl", "xml", "html", "htm", "css", "yaml", "yml", "toml",
        "ini", "cfg", "conf", "properties", "gradle", "kt", "java", "py",
        "js", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "cc", "cs",
        "swift", "sh", "bash", "zsh", "sql", "r", "dart", "lua", "pl", "pm",
    )
    private val CODE_EXTS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h",
        "cpp", "cc", "cs", "swift", "sh", "bash", "zsh", "sql", "dart", "lua",
        "json", "xml", "yaml", "yml", "toml", "gradle",
    )
}

/**
 * Tool contract for the swarm theme (item 47 covers chat AND swarm).
 *
 * The swarm package must not be touched by this theme; this descriptor lets
 * the swarm worker register `knowledge_search` later with zero chat-side
 * changes — it calls [KnowledgeRepository.search] under the hood.
 */
object KnowledgeToolSpec {
    const val TOOL_NAME = "knowledge_search"
    const val DESCRIPTION =
        "Search the user's personal knowledge base (their uploaded documents, " +
            "PDFs, notes, and scanned text). Returns cited excerpts; always " +
            "cite the [Title] or [Title, p. N] source when you use them."
    const val INPUT_SCHEMA =
        """{"type":"object","properties":{"query":{"type":"string","description":"What to look up in the knowledge base"}},"required":["query"]}"""
}
