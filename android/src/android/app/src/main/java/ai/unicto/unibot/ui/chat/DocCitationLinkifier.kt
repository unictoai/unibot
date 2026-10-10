package ai.unicto.unibot.ui.chat

/**
 * Document citations for "Ask my documents".
 *
 * The agent's `documents_search` tool returns chunks cited like
 * `notes/todo.md:42-48`, and the agent repeats those citations in its
 * answers. This object turns plain-text citations in rendered markdown into
 * tappable links of the form
 * `[notes/todo.md:42](unibot-doc:/notes/todo.md?line=42)`, which
 * [ChatLinkResolver] routes to an in-app file preview scrolled to the cited
 * line. Styling stays the standard muted chat-link style — no cards.
 *
 * [linkify] is pure and idempotent (already-linkified citations are inside
 * the skipped markdown-link regions), so it is safe to run on every parse
 * pass, including streaming ticks and the parser's nested block recursion.
 *
 * Deliberately conservative about what counts as a citation:
 *  - the path must contain a `/` or end in a letter-led extension, so
 *    `10:30` (time) and `v1.5:2` (version) never match;
 *  - fenced code blocks, inline code spans, existing markdown links/images
 *    and autolinks are left untouched.
 */
object DocCitationLinkifier {

    /** One parsed citation: document path plus 1-based line range. */
    data class Citation(val path: String, val startLine: Int, val endLine: Int)

    private const val SCHEME = "unibot-doc"

    /**
     * `notes/todo.md:42` or `notes/todo.md:42-48`. The lookbehind keeps the
     * match from starting mid-path or mid-URL (`https://host:8080/x` can
     * never match: after `https` comes `:` with no digits, and every later
     * start position is preceded by a path character). The trailing
     * `(?!\d)` keeps `file.md:420` from matching as line `42`.
     */
    private val CITATION_RE = Regex(
        """(?<![\w/.:@~-])([A-Za-z0-9_~][A-Za-z0-9_~./-]*):(\d{1,7})(?:-(\d{1,7}))?(?!\d)""",
    )

    /**
     * Regions where citations must NOT linkify. The fenced-code alternative
     * tolerates an unclosed fence (mid-stream) by running to end of input.
     */
    private val SKIP_RE = Regex(
        """```[\s\S]*?(?:```|$)""" + // fenced code
            """|`[^`\n]*`""" + // inline code
            """|!\[[^\]\n]*]\([^)\s]*\)""" + // images
            """|\[[^\]\n]*]\([^)\s]*\)""" + // links (also covers our own output: idempotent)
            """|<[^<>\n]*>""", // autolinks
    )

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Rewrite plain-text citations in [markdown] as `[text](unibot-doc:...)`
     * markdown links. Returns the input unchanged (same instance) when there
     * is nothing to linkify.
     */
    fun linkify(markdown: String): String {
        if (!markdown.contains(':')) return markdown
        val skips = SKIP_RE.findAll(markdown).map { it.range }.toList()
        fun isSkipped(range: IntRange): Boolean =
            skips.any { s -> s.first <= range.last && range.first <= s.last }

        var found = false
        for (m in CITATION_RE.findAll(markdown)) {
            if (parseMatch(m) != null && !isSkipped(m.range)) {
                found = true
                break
            }
        }
        if (!found) return markdown

        val out = StringBuilder(markdown.length + 64)
        var cursor = 0
        for (m in CITATION_RE.findAll(markdown)) {
            val citation = parseMatch(m) ?: continue
            if (isSkipped(m.range)) continue
            out.append(markdown, cursor, m.range.first)
            out.append('[').append(m.value).append("](")
                .append(citationUrl(citation)).append(')')
            cursor = m.range.last + 1
        }
        out.append(markdown, cursor, markdown.length)
        return out.toString()
    }

    /** Validate one regex match and build the [Citation]; null when the path is not file-like. */
    private fun parseMatch(m: MatchResult): Citation? {
        val path = m.groupValues[1]
        if (!isDocPath(path)) return null
        val start = m.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val end = m.groupValues[3].toIntOrNull()?.let { maxOf(it, start) } ?: start
        return Citation(path, start, end)
    }

    /**
     * A path is file-like when it contains a `/` (any extensionless file in
     * a directory) or ends in a letter-led extension (`todo.md`, `a.TXT` —
     * but not `v1.5`, whose "extension" is all digits).
     */
    private fun isDocPath(path: String): Boolean {
        if ('/' in path) return true
        val dot = path.lastIndexOf('.')
        if (dot < 0) return false
        val ext = path.substring(dot + 1)
        return ext.isNotEmpty() && ext.length <= 10 && ext[0].isLetter()
    }

    /**
     * Build the `unibot-doc:` URL for a citation.
     * Relative: `unibot-doc:/notes/todo.md?line=42`
     * Absolute: `unibot-doc:///var/minis/shared/x.md?line=3`
     */
    fun citationUrl(c: Citation): String {
        val encoded = encodePath(c.path)
        val target = if (c.path.startsWith("/")) "/$encoded" else encoded
        return buildString {
            append(SCHEME).append(":/").append(target)
            append("?line=").append(c.startLine)
            if (c.endLine > c.startLine) append("&end=").append(c.endLine)
        }
    }

    /**
     * Parse a `unibot-doc:` URL back into a [Citation]; null when malformed.
     * Hand-rolled rather than Uri.getQueryParameter so `+` in filenames is
     * never decoded to a space (paths are percent-decoded by hand, like
     * ChatLinkResolver.unibotPathCandidates).
     */
    fun parseCitationUrl(url: String): Citation? {
        if (!url.startsWith("$SCHEME:", ignoreCase = true)) return null
        val rest = url.substring(SCHEME.length + 1)
        val absolute = rest.startsWith("//")
        val withoutSlashes = rest.trimStart('/')
        if (withoutSlashes.isEmpty()) return null
        val path = decodePercent(withoutSlashes.substringBefore('?'))
        if (path.isEmpty() || path.split('/').any { it == ".." }) return null
        val query = withoutSlashes.substringAfter('?', "")
        val params = query.split('&').mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq < 0) null else p.substring(0, eq) to p.substring(eq + 1)
        }.toMap()
        val line = params["line"]?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val end = params["end"]?.toIntOrNull()?.let { maxOf(it, line) } ?: line
        return Citation(if (absolute) "/$path" else path, line, end)
    }

    /** Percent-encode a path, leaving `/`, `+` and the unreserved set intact. */
    private fun encodePath(path: String): String = buildString(path.length) {
        for (ch in path) {
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '/' || ch == '+') {
                append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    append('%')
                    append(HEX[(b.toInt() shr 4) and 0xF])
                    append(HEX[b.toInt() and 0xF])
                }
            }
        }
    }

    /** Percent-decode `%XX` only; `+` stays a literal plus. */
    private fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val byte = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
