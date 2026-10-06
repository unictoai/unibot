package ai.unicto.unibot.questioncards

/**
 * Item 93 — the wizard engine: start / answer / validate / advance /
 * complete. Pure logic, no Android — fully unit-tested. The store persists
 * every transition, so killing the app mid-wizard loses nothing.
 */
object QuestionCardEngine {

    /** Start a card from a definition. The first required step becomes current. */
    fun start(def: QuestionCardDef, sessionId: String? = null): QuestionCard =
        QuestionCard(
            title = def.title,
            description = def.description,
            steps = def.steps,
            sessionId = sessionId,
        ).coerceCurrent()

    /** Record an answer for [stepId]. Returns the updated card or an error. */
    sealed class AnswerOutcome {
        data class Ok(val card: QuestionCard) : AnswerOutcome()
        data class Rejected(val reason: String, val card: QuestionCard) : AnswerOutcome()
    }

    fun answer(card: QuestionCard, stepId: String, value: AnswerValue): AnswerOutcome {
        val step = card.steps.firstOrNull { it.id == stepId }
            ?: return AnswerOutcome.Rejected("Unknown step.", card)
        val problem = validateAnswer(step, value)
        if (problem != null) return AnswerOutcome.Rejected(problem, card)
        val updated = card.copy(
            answers = card.answers + (stepId to value),
            updatedAt = System.currentTimeMillis(),
        )
        return AnswerOutcome.Ok(updated)
    }

    /** Human-readable problem with [value] for [step], or null when valid. */
    fun validateAnswer(step: CardStep, value: AnswerValue): String? {
        // Type/kind agreement first — a wrong-typed answer is a bug, not input.
        val kindOk = when (step.kind) {
            CardStepKind.TEXT -> value is AnswerValue.Text
            CardStepKind.CHOICE -> value is AnswerValue.Choice
            CardStepKind.MULTI_CHOICE -> value is AnswerValue.MultiChoice
            CardStepKind.CONFIRM -> value is AnswerValue.Confirm
            CardStepKind.SCALE -> value is AnswerValue.Scale
        }
        if (!kindOk) return "That answer doesn't match the question type."
        if (!step.required) return null
        return when (value) {
            is AnswerValue.Text -> if (value.text.isBlank()) "Please type an answer." else null
            is AnswerValue.Choice ->
                if (value.option !in step.options) "Please pick one of the options." else null
            is AnswerValue.MultiChoice ->
                if (value.options.isEmpty()) "Please pick at least one option."
                else if (value.options.any { it !in step.options }) "Please pick from the listed options."
                else null
            is AnswerValue.Confirm -> null // an explicit No is still an answer
            is AnswerValue.Scale ->
                if (value.value !in step.scaleMin..step.scaleMax) {
                    "Please pick between ${step.scaleMin} and ${step.scaleMax}."
                } else null
        }
    }

    /** True when the current step is satisfied and the card may advance. */
    fun canAdvance(card: QuestionCard): Boolean {
        val step = card.steps.getOrNull(card.currentStep) ?: return false
        val answer = card.answers[step.id]
        if (answer == null) return !step.required
        return validateAnswer(step, answer) == null
    }

    /** Move to the next unanswered step (or past the end → auto-complete). */
    fun next(card: QuestionCard): QuestionCard {
        if (!canAdvance(card)) return card
        val at = card.currentStep + 1
        return if (at >= card.steps.size) {
            complete(card)
        } else {
            card.copy(currentStep = at, updatedAt = System.currentTimeMillis())
        }
    }

    fun back(card: QuestionCard): QuestionCard =
        card.copy(
            currentStep = (card.currentStep - 1).coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
        )

    fun complete(card: QuestionCard): QuestionCard =
        card.copy(status = QuestionCardStatus.COMPLETED, updatedAt = System.currentTimeMillis())

    fun dismiss(card: QuestionCard): QuestionCard =
        card.copy(status = QuestionCardStatus.DISMISSED, updatedAt = System.currentTimeMillis())

    /** 0..1 progress over required steps. */
    fun progress(card: QuestionCard): Float {
        val required = card.steps.filter { it.required }
        if (required.isEmpty()) return 1f
        val done = required.count { step ->
            card.answers[step.id]?.let { validateAnswer(step, it) == null } == true
        }
        return done.toFloat() / required.size
    }

    /**
     * Compile the Q&A into plain text for the agent — "the user answered the
     * wizard; here is what they said."
     */
    fun summary(card: QuestionCard): String = buildString {
        appendLine("Question card: ${card.title}")
        if (card.description.isNotBlank()) appendLine(card.description)
        for (step in card.steps) {
            val answer = card.answers[step.id]
            appendLine()
            appendLine("Q: ${step.prompt}")
            appendLine("A: ${answer?.displayText() ?: "(skipped)"}")
        }
    }.trimEnd()

    /** Clamp [QuestionCard.currentStep] into range (defensive after edits). */
    private fun QuestionCard.coerceCurrent(): QuestionCard {
        if (steps.isEmpty()) return copy(currentStep = 0)
        return copy(currentStep = currentStep.coerceIn(0, steps.size - 1))
    }
}
