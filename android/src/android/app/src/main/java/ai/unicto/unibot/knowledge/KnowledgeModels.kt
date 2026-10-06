package ai.unicto.unibot.knowledge

import kotlinx.serialization.Serializable

/**
 * Kind of a knowledge-base entry.
 */
enum class KnowledgeKind {
    /** Plain text / markdown / pasted content. */
    DOCUMENT,
    /** PDF — text extracted per page, citations carry page numbers. */
    PDF,
    /** Short note saved from chat (item 53). */
    NOTE,
    /** OCR text captured from an image. */
    IMAGE_TEXT,
    /** Source code / config — indexed as-is. */
    CODE,
}

/**
 * One document in the personal knowledge base.
 */
@Serializable
data class KnowledgeDoc(
    val id: String,
    val title: String,
    val kind: KnowledgeKind,
    val mimeType: String?,
    val sourceFileName: String?,
    val addedAtMs: Long,
    val chunkCount: Int,
)

/**
 * One embedded chunk, persisted in the store's index file.
 *
 * @param embeddingB64 int8-quantized [HashedEmbedding.DIM]-dimensional vector,
 * Base64-encoded. 128 bytes per chunk keeps a 3,000-chunk index under half a
 * megabyte — cheap on a 4 GB phone.
 * @param page 1-based page number for PDFs, null otherwise.
 */
@Serializable
data class StoredChunk(
    val docId: String,
    val index: Int,
    val page: Int?,
    val text: String,
    val embeddingB64: String,
)

/**
 * One retrieval hit, with the source citation the chat layer shows the model
 * (and the user).
 */
data class KnowledgeHit(
    val doc: KnowledgeDoc,
    val chunkIndex: Int,
    val page: Int?,
    val text: String,
    val score: Float,
) {
    /** Human citation, e.g. "My Notes" or "Manual.pdf, p. 12". */
    fun citation(): String = buildString {
        append(doc.title)
        if (page != null) append(", p. $page")
    }
}
