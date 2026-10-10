package ai.unicto.unibot.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 6 (v1.5/voice): the silence fallback compared the mic level against a
 * fixed `QUIET_THRESHOLD = 0.12f`. A fixed level cannot tell a quiet speaker
 * in a quiet room from a loud room's noise floor, so it failed both ways:
 * quiet-but-real speech sat under 0.12 and was cut ~2.5 s in, while steady
 * background noise sat above 0.12 and held the one-shot whisper capture open
 * until its 120 s cap. These tests drive [AdaptiveSilenceGate] — the
 * adaptive noise-floor replacement — through both failure shapes plus the
 * floor-tracking invariants, with small windows (1 s fallback, 0.5 s minimum
 * listen) and 250 ms samples matching the production poll cadence.
 */
class AdaptiveSilenceGateTest {

    /** Feed [levels] at [stepMs] from [startMs]; returns the fire timestamp or -1. */
    private fun feedLevels(
        gate: AdaptiveSilenceGate,
        levels: List<Float>,
        startMs: Long = 0L,
        stepMs: Long = 250L,
    ): Long {
        gate.start(startMs)
        var t = startMs
        for (level in levels) {
            if (gate.onSample(level, t)) return t
            t += stepMs
        }
        return -1L
    }

    /** Feed every sample, ignoring fire results (for floor-convergence probes). */
    private fun drainLevels(
        gate: AdaptiveSilenceGate,
        levels: List<Float>,
        startMs: Long = 0L,
        stepMs: Long = 250L,
    ) {
        gate.start(startMs)
        var t = startMs
        for (level in levels) {
            gate.onSample(level, t)
            t += stepMs
        }
    }

    @Test
    fun `quiet speaker in quiet room is not cut off`() {
        // 0.05–0.08 speech with dips to ambient 0.02, over 5 s. The old fixed
        // 0.12 threshold never saw any of it as speech and cut at ~2.5 s.
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        val speech = List(20) { i -> if (i % 4 == 3) 0.02f else 0.06f + (i % 3) * 0.01f }
        assertEquals(-1L, feedLevels(gate, speech))
    }

    @Test
    fun `sustained background noise does not hold the capture open`() {
        // Steady fan-level noise at 0.3: above the old 0.12 threshold, so the
        // old code refreshed the deadline forever. The adaptive floor absorbs
        // it and the fallback fires one fallback window after the minimum.
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        assertEquals(1000L, feedLevels(gate, List(16) { 0.3f }))
    }

    @Test
    fun `speech then silence fires one fallback window after speech stops`() {
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        val levels = listOf(0.5f, 0.2f, 0.55f, 0.15f, 0.5f, 0.18f) + List(10) { 0.02f }
        // Last speech sample lands at t=1000; silence from t=1250 on.
        assertEquals(2000L, feedLevels(gate, levels))
    }

    @Test
    fun `floor converges to sustained noise and falls fast toward quiet`() {
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        gate.start(0L)
        var t = 0L
        repeat(40) { gate.onSample(0.3f, t); t += 250 }
        assertEquals(0.3f, gate.currentFloor(), 0.001f)
        // Sudden quiet: the fast downward rate re-learns the room in ~2 s.
        repeat(8) { gate.onSample(0.05f, t); t += 250 }
        assertTrue(gate.currentFloor() < 0.15f)
    }

    @Test
    fun `speech does not drag the floor up`() {
        // The VAD's AGC invariant, mirrored here: loud speech must not raise
        // the noise floor, or each following frame reads quieter by comparison
        // and the gate starves itself.
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        drainLevels(gate, List(4) { 0.02f } + List(20) { 0.6f })
        assertTrue(gate.currentFloor() < 0.05f)
    }

    @Test
    fun `never fires before the minimum listen time`() {
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 500L, minListenMs = 2000L)
        // Dead silence from t=0: the fallback window (500 ms) elapses long
        // before the 2 s minimum, but the gate must hold.
        assertEquals(-1L, feedLevels(gate, List(8) { 0f }))
        // …and then fire exactly at the minimum.
        assertEquals(2000L, feedLevels(gate, List(10) { 0f }))
    }

    @Test
    fun `partial transcript refreshes the deadline without touching the floor`() {
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        gate.start(0L)
        var t = 0L
        repeat(4) { gate.onSample(0.02f, t); t += 250 } // seed: t = 0..750
        // The engine emits a partial at t=1000: without it the gate would fire
        // at t=1000; with it the deadline moves a full fallback window out.
        gate.onSpeechHeard(1000L)
        var firedAt = -1L
        while (t <= 3000L) {
            if (gate.onSample(0.02f, t)) {
                firedAt = t
                break
            }
            t += 250
        }
        assertEquals(2000L, firedAt)
        // …and the floor still reads ambient, not speech.
        assertTrue(gate.currentFloor() < 0.05f)
    }

    @Test
    fun `capture opening mid utterance converges and keeps speech alive`() {
        // First sample is already loud speech: the min-seed lands on the
        // first dip, and from then on peaks keep refreshing the deadline.
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        val levels = listOf(
            0.5f, 0.45f, 0.55f, 0.12f,
            0.5f, 0.15f, 0.55f, 0.10f,
            0.5f, 0.14f, 0.52f, 0.11f,
            0.5f, 0.16f, 0.53f, 0.10f,
            0.5f, 0.15f, 0.50f, 0.12f,
        )
        assertEquals(-1L, feedLevels(gate, levels))
    }

    @Test
    fun `samples before start never fire`() {
        val gate = AdaptiveSilenceGate(silenceFallbackMs = 1000L, minListenMs = 500L)
        assertFalse(gate.onSample(0f, 60_000L))
    }
}
