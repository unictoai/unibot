package ai.unicto.unibot.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * One page of extracted PDF text. [number] is 1-based.
 */
data class PdfPage(val number: Int, val text: String)

/**
 * Best-effort PDF text extraction with no dependencies — pure Kotlin/JVM on
 * java.util.zip for FlateDecode.
 *
 * Strategy: regex-scan every indirect object, decompress stream objects,
 * resolve the /Pages tree to order pages, then tokenize each page's content
 * stream for text-showing operators (Tj, TJ, ', ") and decode literal/hex
 * strings (WinAnsi / UTF-16BE).
 *
 * Handles: classic xref PDFs, FlateDecode content streams, /Contents as a
 * single ref or an array. Does NOT handle: object streams / compressed xref
 * (PDF 1.5+), encrypted PDFs, custom font encodings beyond WinAnsi/UTF-16BE.
 * Those return fewer (or zero) pages; callers treat empty as "could not
 * extract text" rather than failing.
 */
object PdfTextExtractor {

    fun extract(data: ByteArray): List<PdfPage> {
        if (data.size < 8) return emptyList()
        return runCatching { extractInternal(data) }.getOrDefault(emptyList())
    }

    private fun extractInternal(data: ByteArray): List<PdfPage> {
        // 1 byte = 1 char: parse structure as ISO-8859-1, decode text later.
        val raw = data.toString(Charsets.ISO_8859_1)
        if (!raw.startsWith("%PDF-")) return emptyList()

        // All indirect objects: "N M obj ... endobj".
        val objects = LinkedHashMap<String, String>()
        val objRe = Regex("""(\d+)\s+(\d+)\s+obj(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)
        for (m in objRe.findAll(raw)) {
            objects["${m.groupValues[1]} ${m.groupValues[2]}"] = m.groupValues[3]
        }
        if (objects.isEmpty()) return emptyList()

        // Stream payloads per object, decompressed.
        val streams = HashMap<String, ByteArray>()
        val streamRe = Regex("""stream\r?\n(.*?)endstream""", RegexOption.DOT_MATCHES_ALL)
        for ((key, body) in objects) {
            val sm = streamRe.find(body) ?: continue
            var payload = sm.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            if (body.contains("/FlateDecode")) {
                payload = inflate(payload) ?: continue
            }
            streams[key] = payload
        }

        // Page order from the /Pages tree.
        val pageKeys = pageOrder(objects)
        val pages = ArrayList<PdfPage>()
        if (pageKeys.isNotEmpty()) {
            var n = 1
            for (key in pageKeys) {
                val body = objects[key] ?: continue
                val contentKeys = contentRefs(body)
                val sb = StringBuilder()
                for (ck in contentKeys) {
                    val bytes = streams[ck] ?: continue
                    sb.append(extractText(bytes.toString(Charsets.ISO_8859_1)))
                    sb.append('\n')
                }
                val text = sb.toString().trim()
                if (text.isNotEmpty()) pages += PdfPage(n, text)
                n++
            }
        }
        // Fallback: no usable page tree — treat each text-bearing stream as a
        // page in object order.
        if (pages.isEmpty()) {
            var n = 1
            for ((_, bytes) in streams) {
                val text = extractText(bytes.toString(Charsets.ISO_8859_1)).trim()
                if (text.isNotEmpty()) pages += PdfPage(n++, text)
            }
        }
        return pages
    }

    private fun inflate(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** Depth-first page order from the /Pages catalog tree. */
    private fun pageOrder(objects: Map<String, String>): List<String> {
        val pagesObj = objects.entries.firstOrNull { (_, b) -> b.contains("/Type /Pages") }?.key
            ?: return emptyList()
        val out = ArrayList<String>()
        fun visit(key: String) {
            val body = objects[key] ?: return
            if (body.contains("/Type /Page") && !body.contains("/Type /Pages")) {
                out += key
                return
            }
            for (kid in refList(body, "/Kids")) visit(kid)
        }
        visit(pagesObj)
        return out
    }

    /** /Contents can be "5 0 R" or "[5 0 R 6 0 R]". */
    private fun contentRefs(pageBody: String): List<String> {
        val m = Regex("""/Contents\s*(\[.*?\]|\d+\s+\d+\s+R)""", RegexOption.DOT_MATCHES_ALL).find(pageBody)
            ?: return emptyList()
        val v = m.groupValues[1]
        if (v.startsWith("[")) return refList(v, "")
        val rm = Regex("""(\d+)\s+(\d+)\s+R""").find(v) ?: return emptyList()
        return listOf("${rm.groupValues[1]} ${rm.groupValues[2]}")
    }

    private fun refList(body: String, key: String): List<String> {
        val src = if (key.isEmpty()) body else {
            Regex("""$key\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?: return emptyList()
        }
        return Regex("""(\d+)\s+(\d+)\s+R""").findAll(src)
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList()
    }

    /**
     * Tokenize a content stream and collect shown text. Handles Tj (literal or
     * hex string), TJ (array of strings/numbers), ' and " (line-show ops).
     * Positioning ops (Td, TD, Tm, T*) insert line breaks so multi-line pages
     * don't collapse into one run.
     */
    internal fun extractText(content: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = content.length
        fun skipWs() { while (i < n && content[i].isWhitespace()) i++ }
        while (i < n) {
            skipWs()
            if (i >= n) break
            val c = content[i]
            when {
                c == '(' -> {
                    val (s, next) = readLiteral(content, i)
                    sb.append(decodePdfString(s))
                    i = next
                    skipWs()
                    i = readOperator(content, i).let { (op, ni) ->
                        if (op == "Tj" || op == "'" || op == "\"") sb.append(' ')
                        ni
                    }
                }
                c == '<' && i + 1 < n && content[i + 1] == '<' -> {
                    // dict — skip
                    i = skipBalanced(content, i, '<', '>')
                }
                c == '<' -> {
                    val (s, next) = readHex(content, i)
                    sb.append(decodePdfString(s))
                    i = next
                    skipWs()
                    i = readOperator(content, i).let { (op, ni) ->
                        if (op == "Tj") sb.append(' ')
                        ni
                    }
                }
                c == '[' -> {
                    val (parts, next) = readArray(content, i)
                    for (p in parts) sb.append(decodePdfString(p))
                    sb.append(' ')
                    i = next
                    skipWs()
                    i = readOperator(content, i).second
                }
                c == '/' || c == '%' -> {
                    // name or comment — skip token
                    if (c == '%') { while (i < n && content[i] != '\n') i++ }
                    else { while (i < n && !content[i].isWhitespace() && content[i] !in "()<>[]{}/%") i++ }
                }
                else -> {
                    val (op, ni) = readOperator(content, i)
                    when (op) {
                        "Td", "TD", "T*", "Tm" -> sb.append('\n')
                        "'", "\"" -> sb.append('\n')
                    }
                    // A bare number (operand) yields no operator — consume the
                    // token itself so the cursor always advances.
                    i = if (ni == i) skipToken(content, i) else ni
                }
            }
        }
        // Collapse whitespace runs but keep single newlines.
        return sb.toString()
            .replace(Regex("[ \\t\\u00a0\\u2000-\\u200a]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun readOperator(content: String, start: Int): Pair<String, Int> {
        var i = start
        val n = content.length
        while (i < n && content[i].isWhitespace()) i++
        val sb = StringBuilder()
        // Operators are letters, possibly with * or ' or ".
        while (i < n && (content[i].isLetter() || content[i] == '*' || content[i] == '\'' || content[i] == '"')) {
            sb.append(content[i]); i++
        }
        // Skip numeric operands that precede the operator we just read? No —
        // numbers were already consumed as tokens; just return.
        return sb.toString() to i
    }

    private fun skipToken(content: String, start: Int): Int {
        var i = start
        val delimiters = "()<>[]{}/%"
        while (i < content.length && !content[i].isWhitespace() && content[i] !in delimiters) i++
        // Never stall: always consume at least one char.
        if (i == start) i++
        return i
    }

    private fun readLiteral(content: String, start: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var i = start + 1
        val n = content.length
        var depth = 1
        while (i < n && depth > 0) {
            val c = content[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val e = content[i + 1]
                    when (e) {
                        'n' -> out.write('\n'.code); 'r' -> out.write('\r'.code)
                        't' -> out.write('\t'.code); 'b' -> out.write('\b'.code)
                        'f' -> out.write('\u000C'.code)
                        '(', ')', '\\' -> out.write(e.code)
                        in '0'..'9' -> {
                            var oct = ""
                            var j = i + 1
                            while (j < n && j < i + 4 && content[j].isDigit() && content[j] in '0'..'7') { oct += content[j]; j++ }
                            out.write(oct.toInt(8))
                            i = j - 1
                        }
                        else -> out.write(e.code)
                    }
                    i += 2
                }
                c == '(' -> { depth++; out.write(c.code); i++ }
                c == ')' -> { depth--; if (depth > 0) out.write(c.code); i++ }
                else -> { out.write(c.code); i++ }
            }
        }
        return out.toByteArray() to i
    }

    private fun readHex(content: String, start: Int): Pair<ByteArray, Int> {
        var i = start + 1
        val n = content.length
        val hex = StringBuilder()
        while (i < n && content[i] != '>') {
            if (!content[i].isWhitespace()) hex.append(content[i])
            i++
        }
        if (i < n) i++ // consume '>'
        var h = hex.toString()
        if (h.length % 2 == 1) h += "0"
        val out = ByteArray(h.length / 2)
        for (k in out.indices) out[k] = h.substring(k * 2, k * 2 + 2).toInt(16).toByte()
        return out to i
    }

    private fun readArray(content: String, start: Int): Pair<List<ByteArray>, Int> {
        val parts = ArrayList<ByteArray>()
        var i = start + 1
        val n = content.length
        while (i < n) {
            while (i < n && content[i].isWhitespace()) i++
            if (i >= n || content[i] == ']') { i++; break }
            when (content[i]) {
                '(' -> { val (s, ni) = readLiteral(content, i); parts += s; i = ni }
                '<' -> { val (s, ni) = readHex(content, i); parts += s; i = ni }
                else -> { // number / kern — skip token
                    while (i < n && !content[i].isWhitespace() && content[i] != ']') i++
                }
            }
        }
        return parts to i
    }

    private fun skipBalanced(content: String, start: Int, open: Char, close: Char): Int {
        var i = start
        var depth = 0
        while (i < content.length) {
            if (content[i] == open && i + 1 < content.length && content[i + 1] == open) { depth++; i += 2; continue }
            if (content[i] == close && i + 1 < content.length && content[i + 1] == close) { depth--; i += 2; if (depth == 0) break; continue }
            i++
        }
        return i
    }

    /**
     * Decode a PDF string's raw bytes: UTF-16BE when BOM-marked, else
     * WinAnsi (ASCII + windows-1252 for 0x80-0x9F + U+00A0-00FF above).
     */
    internal fun decodePdfString(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(winAnsi1252[v])
        }
        return sb.toString()
    }

    private val winAnsi1252: CharArray = CharArray(256) { i ->
        when (i) {
            in 0x00..0x7F -> i.toChar()
            // windows-1252 assignments for 0x80-0x9F; 0xA0-0xFF map to
            // U+00A0-U+00FF. Written as unicode escapes: several of these
            // are confusable quote/dash glyphs that do not survive
            // copy-paste as literals.
            0x80 -> '\u20AC'; 0x82 -> '\u201A'; 0x83 -> '\u0192'
            0x84 -> '\u201E'; 0x85 -> '\u2026'; 0x86 -> '\u2020'
            0x87 -> '\u2021'; 0x88 -> '\u02C6'; 0x89 -> '\u2030'
            0x8A -> '\u0160'; 0x8B -> '\u2039'; 0x8C -> '\u0152'
            0x8E -> '\u017D'; 0x91 -> '\u2018'; 0x92 -> '\u2019'
            0x93 -> '\u201C'; 0x94 -> '\u201D'; 0x95 -> '\u2022'
            0x96 -> '\u2013'; 0x97 -> '\u2014'; 0x98 -> '\u02DC'
            0x99 -> '\u2122'; 0x9A -> '\u0161'; 0x9B -> '\u203A'
            0x9C -> '\u0153'; 0x9E -> '\u017E'; 0x9F -> '\u0178'
            else -> i.toChar()
        }
    }
}
