package ai.unicto.unibot.questioncards

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

/**
 * Item 93 — SharedPreferences-backed store for [QuestionCard]s. Every engine
 * transition is written through here immediately, so a killed app resumes
 * exactly where the wizard left off.
 */
class QuestionCardStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _cards = MutableStateFlow<List<QuestionCard>>(emptyList())
    val cards: StateFlow<List<QuestionCard>> = _cards.asStateFlow()

    init {
        _cards.value = load()
    }

    fun all(): List<QuestionCard> = _cards.value

    fun get(cardId: String): QuestionCard? = _cards.value.firstOrNull { it.id == cardId }

    fun activeForSession(sessionId: String): QuestionCard? =
        _cards.value.firstOrNull {
            it.sessionId == sessionId && it.status == QuestionCardStatus.IN_PROGRESS
        }

    fun upsert(card: QuestionCard) {
        _cards.value = (_cards.value.filter { it.id != card.id } + card)
            .sortedByDescending { it.updatedAt }
        persist(_cards.value)
    }

    fun delete(cardId: String) {
        _cards.value = _cards.value.filter { it.id != cardId }
        persist(_cards.value)
    }

    /** Apply an engine transition and persist it in one step. */
    fun transition(cardId: String, fn: (QuestionCard) -> QuestionCard): QuestionCard? {
        val card = get(cardId) ?: return null
        val updated = fn(card)
        upsert(updated)
        return updated
    }

    fun observe(): Flow<List<QuestionCard>> = callbackFlow {
        trySend(_cards.value)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CARDS || key == null) trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun load(): List<QuestionCard> {
        val raw = prefs.getString(KEY_CARDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { QuestionCard.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed card row: ${it.message}") }
                }
            }.sortedByDescending { it.updatedAt }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load cards failed: ${t.message}")
            emptyList()
        }
    }

    private fun persist(cards: List<QuestionCard>) {
        val arr = JSONArray()
        cards.take(MAX_CARDS).forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CARDS, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "QuestionCardStore"
        private const val PREFS_NAME = "unibot_question_cards_prefs"
        private const val KEY_CARDS = "cards_json"
        const val MAX_CARDS = 200
    }
}
