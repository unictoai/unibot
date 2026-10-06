package ai.unicto.unibot.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice theme item 30: every STT failure kind maps to a plain sentence —
 * never a raw enum name or engine string.
 */
class RecognitionErrorMessagesTest {

    @Test
    fun everyKindHasAFriendlyMessage() {
        for (kind in RecognitionError.entries) {
            val msg = kind.friendlyMessage()
            assertTrue("empty message for $kind", msg.isNotBlank())
        }
    }

    @Test
    fun messagesNeverLeakEnumNames() {
        for (kind in RecognitionError.entries) {
            val msg = kind.friendlyMessage()
            assertFalse(
                "raw enum leaked for $kind: $msg",
                msg.contains(kind.name) || msg.contains("ERROR_"),
            )
        }
    }

    @Test
    fun spotCheckOneLiners() {
        assertTrue(RecognitionError.NO_MATCH.friendlyMessage().contains("Didn't catch that"))
        assertTrue(RecognitionError.NETWORK.friendlyMessage().contains("connection"))
        assertTrue(
            RecognitionError.OEM_NO_SERVICE.friendlyMessage().contains("offline voice model"),
        )
        assertTrue(
            RecognitionError.PERMISSION_DENIED.friendlyMessage().contains("Microphone access"),
        )
    }
}
