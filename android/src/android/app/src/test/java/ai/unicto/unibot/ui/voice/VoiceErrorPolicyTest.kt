package ai.unicto.unibot.ui.voice

import ai.unicto.unibot.ui.chat.ERROR_KIND_INVALID_KEY
import ai.unicto.unibot.ui.chat.ERROR_KIND_MODEL_NOT_FOUND
import ai.unicto.unibot.ui.chat.ERROR_KIND_NETWORK
import ai.unicto.unibot.ui.chat.ERROR_KIND_OUTPUT_LIMIT
import ai.unicto.unibot.ui.chat.ERROR_KIND_RATE_LIMITED
import ai.unicto.unibot.ui.chat.ERROR_KIND_REQUEST_TOO_LARGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice theme item 29: error cards gate Retry by kind — auth failures offer
 * fixes, never a futile Retry.
 */
class VoiceErrorPolicyTest {

    @Test
    fun invalidKeyOffersUpdateKeyNotRetry() {
        val ui = VoiceErrorPolicy.forKind(ERROR_KIND_INVALID_KEY, "raw")
        assertEquals(VoiceErrorAction.UPDATE_KEY, ui.primaryAction)
        assertFalse(ui.allowRetry)
        assertNull(ui.secondaryAction)
        assertTrue(ui.message.contains("API key"))
    }

    @Test
    fun rateLimitedOffersSwitchProviderFirst() {
        val ui = VoiceErrorPolicy.forKind(ERROR_KIND_RATE_LIMITED, "raw")
        assertEquals(VoiceErrorAction.SWITCH_PROVIDER, ui.primaryAction)
        assertEquals(VoiceErrorAction.RETRY, ui.secondaryAction)
    }

    @Test
    fun deadModelAndOversizeNeverRetry() {
        for (kind in listOf(
            ERROR_KIND_REQUEST_TOO_LARGE,
            ERROR_KIND_MODEL_NOT_FOUND,
            ERROR_KIND_OUTPUT_LIMIT,
        )) {
            val ui = VoiceErrorPolicy.forKind(kind, "raw")
            assertEquals("kind=$kind", VoiceErrorAction.CHANGE_MODEL, ui.primaryAction)
            assertFalse("kind=$kind", ui.allowRetry)
        }
    }

    @Test
    fun networkKeepsRetry() {
        val ui = VoiceErrorPolicy.forKind(ERROR_KIND_NETWORK, "raw")
        assertEquals(VoiceErrorAction.RETRY, ui.primaryAction)
        assertTrue(ui.allowRetry)
    }

    @Test
    fun unknownKindFallsBackToMessageWithRetry() {
        val ui = VoiceErrorPolicy.forKind(null, "Something broke")
        assertEquals("Something broke", ui.message)
        assertEquals(VoiceErrorAction.RETRY, ui.primaryAction)
        assertTrue(ui.allowRetry)
    }

    @Test
    fun blankFallbackNeverShowsEmptyCard() {
        val ui = VoiceErrorPolicy.forKind("weird_kind", "")
        assertTrue(ui.message.isNotBlank())
    }
}
