package ai.unicto.unibot.knowledge

/**
 * Auto-titles for attachments and knowledge documents (item 54).
 *
 * Priority: markdown heading → code filename stem (first lines of code are
 * imports, not titles) → first sentence → filename stem → mime-based fallback.
 * Never blank: worst case "Untitled document".
 */
object SmartNamer {
    private const val MAX_TITLE = 80

    private val codeMimes = setOf(
        "application/json", "application/javascript", "application/x-sh",
        "text/x-java-source", "text/x-python", "text/x-kotlin",
    )
    private val codeExts = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h",
        "cpp", "cs", "swift", "sh", "json", "xml", "yaml", "yml", "toml", "gradle",
    )

    fun suggestTitle(
        fileName: String?,
        mimeType: String?,
        textSample: String?,
    ): String {
        val stem = fileName
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        val isCode = (mimeType != null && (mimeType in codeMimes || mimeType.startsWith("text/x-"))) ||
            (ext != null && ext in codeExts)
        val text = textSample?.trim().orEmpty()

        // Markdown heading wins for prose.
        if (!isCode) {
            val heading = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("#") }
                ?.trimStart('#', ' ', '\t')
                ?.trim()
                ?.take(MAX_TITLE)
            if (!heading.isNullOrBlank()) return heading
        }

        // Code: the filename is the honest title; the first line is an import.
        if (isCode && stem != null) return stem.take(MAX_TITLE)

        // First sentence of the first non-blank line.
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (!firstLine.isNullOrBlank()) {
            val sentence = firstLine
                .split(Regex("[.!?。\n]"))
                .firstOrNull()?.trim()
                ?.take(MAX_TITLE)
            if (!sentence.isNullOrBlank() && sentence.length >= 4) return sentence
        }

        if (stem != null) return stem.take(MAX_TITLE)

        return when {
            mimeType?.startsWith("image/") == true -> "Scanned image"
            mimeType == "application/pdf" -> "PDF document"
            else -> "Untitled document"
        }
    }
}
