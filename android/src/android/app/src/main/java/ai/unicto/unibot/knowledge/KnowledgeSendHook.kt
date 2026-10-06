package ai.unicto.unibot.knowledge

import android.content.Context
import java.io.File

/**
 * Add-only integration between the chat send path and the personal knowledge
 * base (item 47).
 *
 * [augment] is called from ChatViewModel.sendMessage right after the memory
 * recall block. It prepends cited knowledge excerpts to the MODEL body only
 * (never the bubble). Rules:
 *
 * - Synchronous and total: any failure returns [modelBody] unchanged, so a
 *   broken index can never break a send.
 * - Silent when irrelevant: short prompts and low-similarity queries inject
 *   nothing.
 * - One-shot document scope: when DocumentScreen staged a scoped doc for this
 *   session ("ask about this doc"), the search is limited to that document.
 *
 * Token accounting: the injected block is prompt text; it flows through the
 * existing context policy and the v1.2.9 corruption-proof
 * `effectiveMaxOutputTokens` clamp like any other prompt text. No new
 * accounting is introduced here.
 */
object KnowledgeSendHook {

    @Volatile
    private var store: KnowledgeStore? = null

    private fun storeFor(context: Context): KnowledgeStore {
        return store ?: synchronized(this) {
            store ?: KnowledgeStore(File(context.applicationContext.filesDir, "knowledge"))
                .also { store = it }
        }
    }

    /**
     * Pre-warm the index off the main thread (called from Application
     * onCreate). After this, [augment]'s snapshot is in-memory.
     */
    fun prime(context: Context) {
        try {
            storeFor(context.applicationContext).snapshotSync()
        } catch (_: Throwable) {
            // Degrades to no injection; never break startup.
        }
    }

    /**
     * Test seam: replace the backing store.
     */
    fun setStoreForTests(testStore: KnowledgeStore?) {
        synchronized(this) { store = testStore }
    }

    fun augment(
        context: Context,
        sessionId: String,
        userText: String,
        modelBody: String,
    ): String {
        return try {
            augmentInternal(context, sessionId, userText, modelBody)
        } catch (_: Throwable) {
            modelBody
        }
    }

    private fun augmentInternal(
        context: Context,
        sessionId: String,
        userText: String,
        modelBody: String,
    ): String {
        val prefs = KnowledgePrefs(context)
        // Consume the one-shot document scope first so it can never go stale
        // (e.g. staged while retrieval was disabled).
        val scopedDocId = prefs.consumeScopedDoc(sessionId)
        if (!prefs.searchEnabled) return modelBody
        val query = userText.trim()
        if (query.length < 4) return modelBody
        // Skip retrieval for bare commands / tiny prompts — they're not
        // knowledge questions.
        if (query.startsWith("/") && query.length < 24) return modelBody

        val index = storeFor(context).snapshotSync()
        val hits = index.search(query, topK = 4, onlyDocId = scopedDocId)
        if (hits.isEmpty()) return modelBody

        val sb = StringBuilder()
        sb.append("Relevant excerpts from the user's personal knowledge base. ")
        sb.append("Use them when they answer the question; cite the source shown in brackets.\n")
        var used = 0
        for (hit in hits) {
            val excerpt = hit.text.take(1000).trim()
            if (excerpt.isEmpty()) continue
            val block = "---\n$excerpt\n[${hit.citation()}]\n"
            if (used + block.length > MAX_INJECT_CHARS && used > 0) break
            sb.append(block)
            used += block.length
        }
        if (used == 0) return modelBody
        return "${sb.toString().trim()}\n\n$modelBody"
    }

    private const val MAX_INJECT_CHARS = 2500
}
