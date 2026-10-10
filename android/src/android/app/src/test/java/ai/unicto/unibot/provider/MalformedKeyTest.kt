package ai.unicto.unibot.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key with interior whitespace can never be valid — and would make OkHttp throw. */
class MalformedKeyTest {

    @Test
    fun `clean keys pass`() {
        assertFalse(KeyValidator.isMalformedKey("AIzaSyD1234567890abcdef"))
        assertFalse(KeyValidator.isMalformedKey("sk-ant-123"))
    }

    @Test
    fun `keys with newlines or spaces are malformed`() {
        assertTrue(KeyValidator.isMalformedKey("AIzaSyD123\n456"))
        assertTrue(KeyValidator.isMalformedKey("key with spaces"))
        assertTrue(KeyValidator.isMalformedKey("trailing\n"))
    }
}
