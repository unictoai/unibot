package ai.unicto.unibot.questioncards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 93 — wizard engine: validation, advance, resume-from-persisted-state.
 */
class QuestionCardEngineTest {

    private fun def() = QuestionCardDef(
        title = "Trip planner",
        steps = listOf(
            CardStep(id = "dest", kind = CardStepKind.TEXT, prompt = "Where to?"),
            CardStep(
                id = "style", kind = CardStepKind.CHOICE, prompt = "Travel style?",
                options = listOf("Chill", "Adventure"),
            ),
            CardStep(
                id = "extras", kind = CardStepKind.MULTI_CHOICE, prompt = "Extras?",
                options = listOf("Insurance", "Lounge"), required = false,
            ),
            CardStep(id = "ok", kind = CardStepKind.CONFIRM, prompt = "Book it?"),
        ),
    )

    @Test
    fun `start puts the card on the first step`() {
        val card = QuestionCardEngine.start(def())
        assertEquals(0, card.currentStep)
        assertEquals(QuestionCardStatus.IN_PROGRESS, card.status)
    }

    @Test
    fun `blank answer to required text step is rejected`() {
        val card = QuestionCardEngine.start(def())
        val outcome = QuestionCardEngine.answer(card, "dest", AnswerValue.Text("  "))
        assertTrue(outcome is QuestionCardEngine.AnswerOutcome.Rejected)
    }

    @Test
    fun `full walkthrough advances and completes`() {
        var card = QuestionCardEngine.start(def())
        assertFalse(QuestionCardEngine.canAdvance(card))

        card = (QuestionCardEngine.answer(card, "dest", AnswerValue.Text("Kyoto"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        assertTrue(QuestionCardEngine.canAdvance(card))

        card = QuestionCardEngine.next(card)
        assertEquals(1, card.currentStep)

        // Wrong option rejected.
        val bad = QuestionCardEngine.answer(card, "style", AnswerValue.Choice("Nope"))
        assertTrue(bad is QuestionCardEngine.AnswerOutcome.Rejected)

        card = (QuestionCardEngine.answer(card, "style", AnswerValue.Choice("Chill"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        card = QuestionCardEngine.next(card)
        assertEquals(2, card.currentStep)

        // Optional step: advance without answering.
        assertTrue(QuestionCardEngine.canAdvance(card))
        card = QuestionCardEngine.next(card)
        assertEquals(3, card.currentStep)

        card = (QuestionCardEngine.answer(card, "ok", AnswerValue.Confirm(true))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        card = QuestionCardEngine.next(card)
        assertEquals(QuestionCardStatus.COMPLETED, card.status)
    }

    @Test
    fun `summary compiles questions and answers`() {
        var card = QuestionCardEngine.start(def())
        card = (QuestionCardEngine.answer(card, "dest", AnswerValue.Text("Kyoto"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        val summary = QuestionCardEngine.summary(card)
        assertTrue(summary.contains("Where to?"))
        assertTrue(summary.contains("Kyoto"))
        assertTrue(summary.contains("(skipped)"))
    }

    @Test
    fun `card survives a persist restore round trip mid-wizard`() {
        var card = QuestionCardEngine.start(def())
        card = (QuestionCardEngine.answer(card, "dest", AnswerValue.Text("Kyoto"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        card = QuestionCardEngine.next(card)

        // Simulate the app being killed: serialize + restore.
        val restored = QuestionCard.fromJson(card.toJson())
        assertEquals(1, restored.currentStep)
        assertEquals("Kyoto", (restored.answers["dest"] as AnswerValue.Text).text)
        assertTrue(QuestionCardEngine.canAdvance(restored))

        // And the wizard continues from there.
        val continued = (QuestionCardEngine.answer(restored, "style", AnswerValue.Choice("Adventure"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        assertEquals(1, continued.currentStep)
    }

    @Test
    fun `progress tracks required steps only`() {
        var card = QuestionCardEngine.start(def())
        assertEquals(0f, QuestionCardEngine.progress(card))
        card = (QuestionCardEngine.answer(card, "dest", AnswerValue.Text("Kyoto"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        // 1 of 3 required answered (extras is optional).
        assertEquals(1f / 3f, QuestionCardEngine.progress(card))
    }

    @Test
    fun `back moves to the previous step`() {
        var card = QuestionCardEngine.start(def())
        card = (QuestionCardEngine.answer(card, "dest", AnswerValue.Text("Kyoto"))
            as QuestionCardEngine.AnswerOutcome.Ok).card
        card = QuestionCardEngine.next(card)
        card = QuestionCardEngine.back(card)
        assertEquals(0, card.currentStep)
    }
}
