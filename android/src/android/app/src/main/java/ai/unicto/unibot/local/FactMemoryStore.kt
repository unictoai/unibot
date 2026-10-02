package ai.unicto.unibot.local

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.util.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * [v0.5.0-agentic-core] Long-term fact memories ("remember that …").
 *
 * A small, user-controlled fact store — "remember that my bike's number is
 * LXN-1234" — separate from the agent's session memory system
 * (daily logs / GLOBAL.md via [ai.unicto.unibot.data.repository.MemoryRepository]).
 * Facts are things the user explicitly asked the app to keep, surfaced back
 * when a later message matches them.
 *
 * Privacy: facts live ONLY in [EncryptedSharedPreferences] on this device
 * (AES-256 via [EncryptedPrefsFactory]). They are never uploaded, never
 * leave the phone, and are wiped with app data. The Settings screen lists
 * every fact with delete.
 */
data class FactMemory(
    val id: String,
    val text: String,
    val createdAt: Long,
)

class FactMemoryStore(context: Context) {
    private val prefs: SharedPreferences =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_NAME)

    /** All facts, newest first. */
    fun getAll(): List<FactMemory> = readAll().sortedByDescending { it.createdAt }

    /** Save a fact; returns the stored entry. Blank text is ignored (null). */
    fun add(text: String): FactMemory? {
        val clean = text.trim().trimEnd('.', '!', '?').trim()
        if (clean.isEmpty()) return null
        // De-dupe: don't store the same fact twice.
        readAll().firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return it }
        val entry = FactMemory(
            id = "fact_${System.currentTimeMillis()}_${(0..9999).random()}",
            text = clean,
            createdAt = System.currentTimeMillis(),
        )
        writeAll(readAll() + entry)
        return entry
    }

    /** Delete by id. Returns true when something was removed. */
    fun delete(id: String): Boolean {
        val all = readAll()
        val kept = all.filterNot { it.id == id }
        if (kept.size == all.size) return false
        writeAll(kept)
        return true
    }

    /** Delete every fact whose text contains [text] (case-insensitive). */
    fun deleteMatching(text: String): Int {
        val all = readAll()
        val kept = all.filterNot { it.text.contains(text, ignoreCase = true) }
        val removed = all.size - kept.size
        if (removed > 0) writeAll(kept)
        return removed
    }

    /**
     * Find facts relevant to [query]: any significant token (≥4 chars,
     * not a stopword) shared between query and fact. Best matches first.
     */
    fun findMatches(query: String, limit: Int = 3): List<FactMemory> {
        val tokens = significantTokens(query)
        if (tokens.isEmpty()) return emptyList()
        return readAll()
            .mapNotNull { fact ->
                val factTokens = significantTokens(fact.text)
                val overlap = tokens.intersect(factTokens).size
                if (overlap > 0) fact to overlap else null
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // -- storage --

    private fun readAll(): List<FactMemory> {
        val raw = prefs.getString(KEY_FACTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                FactMemory(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    createdAt = o.optLong("createdAt"),
                )
            }.filter { it.id.isNotEmpty() && it.text.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(facts: List<FactMemory>) {
        val arr = JSONArray()
        facts.take(MAX_FACTS).forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("text", f.text)
                put("createdAt", f.createdAt)
            })
        }
        prefs.edit().putString(KEY_FACTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "fact_memories"
        private const val KEY_FACTS = "facts"
        private const val MAX_FACTS = 200

        private val STOPWORDS = setOf(
            "what", "whats", "when", "where", "which", "while", "with", "from",
            "that", "this", "these", "those", "there", "their", "have", "has",
            "will", "would", "should", "could", "about", "your", "mine", "remember",
            "tell", "know", "does", "whats", "number",
        )

        /** Lowercase alphanumeric tokens worth matching on. */
        fun significantTokens(text: String): Set<String> =
            text.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 4 && it !in STOPWORDS }
                .toSet()

        /**
         * "remember that my bike's number is LXN-1234" → "my bike's number is LXN-1234".
         * Also handles "remember my birthday is …". Null when not a remember command.
         */
        fun parseRememberCommand(text: String): String? {
            val m = Regex("^remember\\s+(that\\s+)?(.+)$", RegexOption.IGNORE_CASE)
                .find(text.trim()) ?: return null
            return m.groupValues[2].trim().takeIf { it.isNotEmpty() }
        }

        /**
         * "forget my bike number" / "forget that …" → the phrase to match
         * against stored facts. Null when not a forget command.
         */
        fun parseForgetCommand(text: String): String? {
            val m = Regex("^forget\\s+(that\\s+)?(.+)$", RegexOption.IGNORE_CASE)
                .find(text.trim()) ?: return null
            return m.groupValues[2].trim().takeIf { it.isNotEmpty() }
        }
    }
}
