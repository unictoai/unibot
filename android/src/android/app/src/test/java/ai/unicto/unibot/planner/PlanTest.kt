package ai.unicto.unibot.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state-machine tests for the general plan model. No Android needed. */
class PlanTest {

    private fun plan() = AgentPlan(
        id = "p1",
        title = "Test plan",
        steps = listOf(
            PlanStep("s1", "First step"),
            PlanStep("s2", "Second step"),
            PlanStep("s3", "Third step"),
        ),
    )

    @Test
    fun `steps move pending to doing to done`() {
        val p = plan().startStep("s1").completeStep("s1")
        assertEquals(PlanStepState.DONE, p.steps[0].state)
        assertEquals(PlanStepState.PENDING, p.steps[1].state)
        assertEquals(1, p.finishedCount)
    }

    @Test
    fun `transitions only apply from the right state`() {
        val p = plan()
        // complete from pending is a no-op
        assertEquals(PlanStepState.PENDING, p.completeStep("s1").steps[0].state)
        // fail from pending is a no-op
        assertEquals(PlanStepState.PENDING, p.failStep("s1").steps[0].state)
        // done then fail is a no-op
        val done = p.startStep("s1").completeStep("s1")
        assertEquals(PlanStepState.DONE, done.failStep("s1").steps[0].state)
    }

    @Test
    fun `skip works from pending and doing`() {
        val p = plan().startStep("s1")
        assertEquals(PlanStepState.SKIPPED, p.skipStep("s1").steps[0].state)
        assertEquals(PlanStepState.SKIPPED, p.skipStep("s2").steps[1].state)
        // skipped counts as finished
        assertEquals(2, p.skipStep("s1").skipStep("s2").finishedCount)
        // done steps cannot be skipped
        val done = p.completeStep("s1")
        assertEquals(PlanStepState.DONE, done.skipStep("s1").steps[0].state)
    }

    @Test
    fun `retry revives a failed step`() {
        val p = plan().startStep("s1").failStep("s1")
        assertEquals(PlanStepState.FAILED, p.steps[0].state)
        assertEquals(PlanStepState.PENDING, p.retryStep("s1").steps[0].state)
        // retry on a non-failed step is a no-op
        assertEquals(PlanStepState.PENDING, p.retryStep("s2").steps[1].state)
    }

    @Test
    fun `edit renames but never blanks`() {
        val p = plan().editStep("s1", "Renamed step")
        assertEquals("Renamed step", p.steps[0].text)
        assertEquals("First step", plan().editStep("s1", "   ").steps[0].text)
        // unknown id leaves the plan untouched
        assertEquals(plan(), plan().editStep("nope", "x"))
    }

    @Test
    fun `pause and resume toggle`() {
        val p = plan()
        assertEquals(PlanLifecycle.PAUSED, p.pause().lifecycle)
        assertEquals(PlanLifecycle.RUNNING, p.pause().resume().lifecycle)
        // pausing a terminal plan is a no-op
        val done = p.copy(lifecycle = PlanLifecycle.DONE)
        assertEquals(PlanLifecycle.DONE, done.pause().lifecycle)
        assertTrue(done.isTerminal)
        assertFalse(p.isTerminal)
    }

    @Test
    fun `transitions are pure`() {
        val p = plan()
        p.startStep("s1")
        // the original is untouched
        assertEquals(PlanStepState.PENDING, p.steps[0].state)
    }
}
