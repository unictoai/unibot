package ai.unicto.unibot.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Import-secret decoding must never silently mis-decode: only values with
 * strict base64 shape are decoded; everything else is kept as plaintext and
 * flagged so the caller can log it.
 *
 * Note: android.util.Base64 is stubbed on the JVM, so these tests pin the
 * shape gate (the part that prevents mis-decoding), not the decode itself.
 */
class DecodeImportSecretTest {

    @Test
    fun `plaintext with non-alphabet chars stays plaintext and is flagged`() {
        val (value, wasBase64) = decodeImportSecret("sk-ant-my-real-key-123")
        assertEquals("sk-ant-my-real-key-123", value)
        assertFalse(wasBase64)
    }

    @Test
    fun `wrong length stays plaintext`() {
        val (value, wasBase64) = decodeImportSecret("abc")
        assertEquals("abc", value)
        assertFalse(wasBase64)
    }

    @Test
    fun `empty stays plaintext`() {
        val (value, wasBase64) = decodeImportSecret("")
        assertEquals("", value)
        assertFalse(wasBase64)
    }

    @Test
    fun `whitespace disqualifies base64 shape`() {
        val (value, wasBase64) = decodeImportSecret("aGVs bG8=")
        assertEquals("aGVs bG8=", value)
        assertFalse(wasBase64)
    }
}
