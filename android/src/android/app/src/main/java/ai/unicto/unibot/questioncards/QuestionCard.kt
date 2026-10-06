package ai.unicto.unibot.questioncards

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Item 93 — in-chat question cards: multi-question wizards whose state is
 * persisted, so they survive app restarts and are resumable.
 *
 * A card is a small ordered questionnaire. The agent (or the user, from the
 * Question cards screen) starts one; the user answers step by step; when the
 * last step is answered the card completes and [QuestionCardEngine.summary]
 * compiles the Q&A into text the agent can use.
 *
 * Persistence: JSON via [toJson]/[fromJson], stored in [QuestionCardStore]
 * (SharedPreferences). Additive fields only.
 */
enum class CardStepKind { TEXT, CHOICE, MULTI_CHOICE, CONFIRM, SCALE }

data class CardStep(
    val id: String = UUID.randomUUID().toString(),
    val kind: CardStepKind = CardStepKind.TEXT,
    val prompt: String,
    /** CHOICE / MULTI_CHOICE options. */
    val options: List<String> = emptyList(),
    val required: Boolean = true,
    val placeholder: String = "",
    /** SCALE range, inclusive. */
    val scaleMin: Int = 1,
    val scaleMax: Int = 5,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("prompt", prompt)
        if (options.isNotEmpty()) put("options", JSONArray(options))
        put("required", required)
        if (placeholder.isNotEmpty()) put("placeholder", placeholder)
        put("scaleMin", scaleMin)
        put("scaleMax", scaleMax)
    }

    companion object {
        fun fromJson(o: JSONObject): CardStep = CardStep(
            id = o.optString("id", UUID.randomUUID().toString()),
            kind = runCatching { CardStepKind.valueOf(o.optString("kind", "TEXT")) }
                .getOrDefault(CardStepKind.TEXT),
            prompt = o.optString("prompt", ""),
            options = o.optJSONArray("options")?.let { arr ->
                buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
            } ?: emptyList(),
            required = o.optBoolean("required", true),
            placeholder = o.optString("placeholder", ""),
            scaleMin = o.optInt("scaleMin", 1),
            scaleMax = o.optInt("scaleMax", 5),
        )
    }
}

sealed class AnswerValue {
    data class Text(val text: String) : AnswerValue()
    data class Choice(val option: String) : AnswerValue()
    data class MultiChoice(val options: List<String>) : AnswerValue()
    data class Confirm(val confirmed: Boolean) : AnswerValue()
    data class Scale(val value: Int) : AnswerValue()

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@AnswerValue) {
            is Text -> { put("type", "text"); put("value", text) }
            is Choice -> { put("type", "choice"); put("value", option) }
            is MultiChoice -> { put("type", "multi"); put("value", JSONArray(options)) }
            is Confirm -> { put("type", "confirm"); put("value", confirmed) }
            is Scale -> { put("type", "scale"); put("value", value) }
        }
    }

    fun displayText(): String = when (this) {
        is Text -> text
        is Choice -> option
        is MultiChoice -> options.joinToString(", ")
        is Confirm -> if (confirmed) "Yes" else "No"
        is Scale -> value.toString()
    }

    companion object {
        fun fromJson(o: JSONObject): AnswerValue? = when (o.optString("type")) {
            "text" -> Text(o.optString("value", ""))
            "choice" -> Choice(o.optString("value", ""))
            "multi" -> MultiChoice(buildList {
                val arr = o.optJSONArray("value")
                if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
            })
            "confirm" -> Confirm(o.optBoolean("value", false))
            "scale" -> Scale(o.optInt("value", 1))
            else -> null
        }
    }
}

enum class QuestionCardStatus { IN_PROGRESS, COMPLETED, DISMISSED }

/** A reusable wizard definition (no answers) — the template a card starts from. */
data class QuestionCardDef(
    val title: String,
    val description: String = "",
    val steps: List<CardStep>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("description", description)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): QuestionCardDef = QuestionCardDef(
            title = o.optString("title", ""),
            description = o.optString("description", ""),
            steps = o.optJSONArray("steps")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(CardStep.fromJson(it)) }
                    }
                }
            } ?: emptyList(),
        )
    }
}

data class QuestionCard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val steps: List<CardStep> = emptyList(),
    val answers: Map<String, AnswerValue> = emptyMap(),
    /** Index of the step currently shown. Always valid while IN_PROGRESS. */
    val currentStep: Int = 0,
    val status: QuestionCardStatus = QuestionCardStatus.IN_PROGRESS,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Chat session that spawned the card, if any (for the in-chat host). */
    val sessionId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        put("answers", JSONObject().apply {
            answers.forEach { (k, v) -> put(k, v.toJson()) }
        })
        put("currentStep", currentStep)
        put("status", status.name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (sessionId != null) put("sessionId", sessionId)
    }

    companion object {
        fun fromJson(o: JSONObject): QuestionCard {
            val answers = mutableMapOf<String, AnswerValue>()
            o.optJSONObject("answers")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    obj.optJSONObject(k)?.let { AnswerValue.fromJson(it)?.let { v -> answers[k] = v } }
                }
            }
            return QuestionCard(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title", ""),
                description = o.optString("description", ""),
                steps = o.optJSONArray("steps")?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { add(CardStep.fromJson(it)) }
                        }
                    }
                } ?: emptyList(),
                answers = answers,
                currentStep = o.optInt("currentStep", 0),
                status = runCatching {
                    QuestionCardStatus.valueOf(o.optString("status", "IN_PROGRESS"))
                }.getOrDefault(QuestionCardStatus.IN_PROGRESS),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                sessionId = if (o.has("sessionId")) o.optString("sessionId", null) else null,
            )
        }
    }
}
