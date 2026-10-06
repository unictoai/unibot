package ai.unicto.unibot.deeplink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 73 — the `unibot://ask` `new` query parameter.
 *
 * [DeepLinkHandler.isNewChatParam] is the pure predicate behind
 * `DeepLinkAction.Ask.newChat` (android.net.Uri is unavailable in unit
 * tests, so the predicate stays separate and testable).
 */
class DeepLinkAskParamTest {

    @Test
    fun `1 means new chat`() {
        assertTrue(DeepLinkHandler.isNewChatParam("1"))
    }

    @Test
    fun `true means new chat`() {
        assertTrue(DeepLinkHandler.isNewChatParam("true"))
        assertTrue(DeepLinkHandler.isNewChatParam("TRUE"))
    }

    @Test
    fun `anything else means main chat`() {
        assertFalse(DeepLinkHandler.isNewChatParam(null))
        assertFalse(DeepLinkHandler.isNewChatParam(""))
        assertFalse(DeepLinkHandler.isNewChatParam("0"))
        assertFalse(DeepLinkHandler.isNewChatParam("false"))
        assertFalse(DeepLinkHandler.isNewChatParam("yes"))
    }
}
