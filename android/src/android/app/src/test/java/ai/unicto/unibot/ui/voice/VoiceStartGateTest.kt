package ai.unicto.unibot.ui.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 4 (v1.5/voice): granting the mic permission after a denial never
 * started the conversation — the dead attempt left the loop marked active,
 * so the startConversation entry guard swallowed the Allow retry (and the
 * NoSttModel post-download kick-off). These tests drive [VoiceStartGate]
 * through the exact event sequence [VoiceConversationViewModel] uses and
 * pin the contract: terminal failures park the loop, retries proceed, and
 * a live loop still blocks a double-start.
 */
class VoiceStartGateTest {

    private val gate = VoiceStartGate()

    @Test
    fun deniedPermissionRetryProceeds() {
        // First attempt starts from Idle.
        assertTrue(gate.canStart(VoiceConversationViewModel.State.Idle))
        gate.onAttemptStarted()
        assertTrue(gate.active)

        // The mic check denies: the terminal failure parks the loop.
        gate.onAttemptFailedTerminally()
        assertFalse(gate.active)

        // The Allow retry (launcher granted -> startConversation) is no
        // longer swallowed by the entry guard, and begins a new attempt.
        assertTrue(gate.canStart(VoiceConversationViewModel.State.PermissionDenied))
        gate.onAttemptStarted()
        assertTrue(gate.active)
    }

    @Test
    fun noSttModelRetryProceeds() {
        assertTrue(gate.canStart(VoiceConversationViewModel.State.Idle))
        gate.onAttemptStarted()

        // No engine available: terminal failure parks the loop.
        gate.onAttemptFailedTerminally()
        assertFalse(gate.active)

        // The download card's onDownloaded kick-off starts a fresh attempt.
        assertTrue(gate.canStart(VoiceConversationViewModel.State.NoSttModel))
        gate.onAttemptStarted()
        assertTrue(gate.active)
    }

    @Test
    fun liveConversationBlocksDoubleStart() {
        gate.onAttemptStarted()
        assertFalse(gate.canStart(VoiceConversationViewModel.State.Listening))
        assertFalse(gate.canStart(VoiceConversationViewModel.State.Thinking))
        assertFalse(gate.canStart(VoiceConversationViewModel.State.Speaking))
    }

    @Test
    fun idleAndErrorRetryStillAllowedWhileActive() {
        // Preserved behavior: an attempt accepted but not yet past Idle, or
        // a turn that ended in Error, may start again.
        gate.onAttemptStarted()
        assertTrue(gate.canStart(VoiceConversationViewModel.State.Idle))
        assertTrue(gate.canStart(VoiceConversationViewModel.State.Error("boom")))
    }

    @Test
    fun freshGateIsInactiveAndAllowsStart() {
        assertFalse(gate.active)
        assertTrue(gate.canStart(VoiceConversationViewModel.State.Idle))
    }
}
