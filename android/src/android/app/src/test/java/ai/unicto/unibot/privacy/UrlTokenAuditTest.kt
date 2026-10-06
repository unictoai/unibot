package ai.unicto.unibot.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy item 64 — unit tests for the no-tokens-in-URLs audit.
 * Pure JVM: no Android framework involved.
 */
class UrlTokenAuditTest {

    @Test
    fun `findLeaks reports credential-like parameter names`() {
        val leaks = UrlTokenAudit.findLeaks(
            "https://example.com/callback?api_key=SECRET123&foo=bar"
        )
        assertEquals(listOf("api_key"), leaks)
    }

    @Test
    fun `findLeaks catches fragment tokens`() {
        val leaks = UrlTokenAudit.findLeaks(
            "https://example.com/#access_token=SECRET&token_type=bearer"
        )
        assertEquals(listOf("access_token"), leaks)
    }

    @Test
    fun `findLeaks is case-insensitive and catches suffix patterns`() {
        val leaks = UrlTokenAudit.findLeaks(
            "https://example.com/?API_KEY=x&my_custom_token=y&client_secret=z"
        )
        assertEquals(3, leaks.size)
        assertTrue(leaks.containsAll(listOf("API_KEY", "my_custom_token", "client_secret")))
    }

    @Test
    fun `findLeaks ignores benign parameters`() {
        assertTrue(
            UrlTokenAudit.findLeaks(
                "https://example.com/search?q=hello+world&page=2&lang=en"
            ).isEmpty()
        )
        // "monkey" ends in "key" but not "_key" — must not false-positive.
        assertTrue(UrlTokenAudit.findLeaks("https://example.com/?monkey=banana").isEmpty())
    }

    @Test
    fun `redactUrl hides values but keeps structure`() {
        val redacted = UrlTokenAudit.redactUrl(
            "https://example.com/cb?api_key=SECRET123&foo=bar"
        )
        assertEquals("https://example.com/cb?api_key=REDACTED&foo=bar", redacted)
        assertFalse(redacted.contains("SECRET123"))
    }

    @Test
    fun `redactUrl leaves benign URLs untouched`() {
        val url = "https://example.com/search?q=hello&page=2"
        assertEquals(url, UrlTokenAudit.redactUrl(url))
    }

    @Test
    fun `redactUrlsInText redacts embedded URLs and keeps punctuation`() {
        val out = UrlTokenAudit.redactUrlsInText(
            "see https://example.com/?token=ABC123, and also https://ok.com/a?x=1."
        )
        assertEquals(
            "see https://example.com/?token=REDACTED, and also https://ok.com/a?x=1.",
            out
        )
        assertFalse(out.contains("ABC123"))
    }

    @Test
    fun `redactUrlsInText handles unibot deep links`() {
        val out = UrlTokenAudit.redactUrlsInText(
            "open unibot://settings/environments?create_key=my_api_token&create_value=abc"
        )
        // create_key ends in _key -> redacted; create_value is not a secret name -> kept.
        assertTrue(out.contains("create_key=REDACTED"))
        assertTrue(out.contains("create_value=abc"))
    }

    @Test
    fun `hasLeak mirrors findLeaks`() {
        assertTrue(UrlTokenAudit.hasLeak("https://x.com/?password=hunter2"))
        assertFalse(UrlTokenAudit.hasLeak("https://x.com/?q=hunter2"))
    }

    @Test
    fun `values are never echoed by any API`() {
        val secret = "sk-super-secret-value-999"
        val url = "https://example.com/?api_key=$secret"
        assertFalse(UrlTokenAudit.findLeaks(url).joinToString().contains(secret))
        assertFalse(UrlTokenAudit.redactUrl(url).contains(secret))
        assertFalse(UrlTokenAudit.redactUrlsInText("go $url now").contains(secret))
        assertFalse(UrlTokenAudit.auditDeepLink(url).joinToString().contains(secret))
    }
}
