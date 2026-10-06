package ai.unicto.unibot.knowledge

/**
 * One chunk of document text, with character offsets into the source.
 */
data class TextChunk(val text: String, val startChar: Int, val endChar: Int)

/**
 * Splits document text into overlapping chunks for the embedding index.
 *
 * Sliding window with sentence/line-boundary snapping: chunks end at a
 * paragraph break, sentence end, or newline when one is available in the back
 * half of the window, so chunks rarely cut mid-thought. Overlap keeps context
 * across boundaries.
 */
object TextChunker {
    const val MAX_CHARS = 2000
    const val OVERLAP = 200

    fun chunk(text: String, maxChars: Int = MAX_CHARS, overlap: Int = OVERLAP): List<TextChunk> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxChars) return listOf(TextChunk(clean, 0, clean.length))
        val out = ArrayList<TextChunk>()
        var start = 0
        while (start < clean.length) {
            var end = (start + maxChars).coerceAtMost(clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val snap = maxOf(
                    window.lastIndexOf("\n\n"),
                    window.lastIndexOf(". "),
                    window.lastIndexOf(".\n"),
                    window.lastIndexOf("!\n"),
                    window.lastIndexOf("?\n"),
                    window.lastIndexOf("\n"),
                )
                if (snap > maxChars / 2) end = start + snap + 1
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) out += TextChunk(piece, start, end)
            if (end >= clean.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out
    }
}
