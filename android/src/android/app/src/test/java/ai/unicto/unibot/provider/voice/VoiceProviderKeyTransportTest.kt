package ai.unicto.unibot.provider.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-voice-key-url-bug8] Key transport for voice vendors.
 *
 * An external review flagged "keys in URLs" in this file. Verified in code:
 *   1. Gemini voice was NOT leaking — the earlier `?key=` → `x-goog-api-key`
 *      header fix already covers this call site; the URL carries no query at
 *      all and the key rides the header.
 *   2. The iFlytek (Xunfei) signed URL is vendor-mandated, not a bug: their
 *      spec REQUIRES the `authorization`/`date`/`host` triplet in the query
 *      string for /v2/iat and /v2/tts (no header alternative exists). What goes
 *      on the wire is the public APIKey *identifier* plus an HMAC-SHA256
 *      signature — the raw API secret never appears in the URL and cannot be
 *      derived from the signature.
 *
 * These tests pin both verdicts so a future reviewer (or refactor) cannot
 * "fix" either path into a broken one: Gemini must stay header-auth with a
 * query-free URL, and Xunfei must keep the signed-URL triplet while never
 * embedding the raw secret.
 */
class VoiceProviderKeyTransportTest {

    // -- Gemini voice ---------------------------------------------------------

    @Test
    fun `gemini voice key rides the x-goog-api-key header, never the URL`() {
        val key = "sk-gemini-voice-test-key"
        val req = GeminiVoiceProvider(
            "test-instance",
            "https://generativelanguage.googleapis.com",
            key,
        ).buildVoiceOutputRequest(VoiceOutputRequest(input = "hello"))

        val url = req.url.toString()
        assertNull("Gemini voice URL must carry no query string: $url", req.url.query)
        assertFalse("Gemini voice URL must not contain the key: $url", url.contains(key))
        assertEquals(key, req.header("x-goog-api-key"))
    }

    // -- iFlytek / Xunfei ------------------------------------------------------

    @Test
    fun `xunfei signed URL keeps the vendor-mandated auth triplet, never the raw secret`() {
        val secret = "xunfei-api-secret-value"
        val req = XunfeiVoiceProvider(
            providerId = "test-instance",
            appId = "test-app-id",
            apiKey = "test-api-key-id",
            apiSecret = secret,
        ).buildVoiceInputRequest(VoiceInputRequest(audioData = ByteArray(16)))

        val url = req.url.toString()
        // Vendor-mandated: iFlytek authenticates via this query triplet
        // (RFC-style HMAC signature over host/date/request-line). WebSocket and
        // HTTP endpoints alike require it IN the URL — moving it to a header
        // would break the integration.
        assertTrue("signed URL must carry authorization=: $url", url.contains("authorization="))
        assertTrue("signed URL must carry date=: $url", url.contains("date="))
        assertTrue("signed URL must carry host=: $url", url.contains("host="))

        // The raw secret is never embedded: only the HMAC-SHA256 signature
        // (an irreversible digest) goes on the wire. The "api_key" field
        // inside the authorization blob is the public key *identifier*,
        // not the secret — deliberately present, not a leak.
        assertFalse("raw API secret must never appear in the URL", url.contains(secret))
    }

    // -- Whole-file sweep guard: another header-auth vendor -------------------

    @Test
    fun `deepgram query strings carry model and language, never the key`() {
        val key = "dg-secret-key"
        val req = DeepgramVoiceProvider("test-instance", "https://api.deepgram.com", key)
            .buildVoiceInputRequest(
                VoiceInputRequest(
                    audioData = ByteArray(16),
                    model = "nova-2",
                    language = "en",
                ),
            )

        val url = req.url.toString()
        assertTrue("model stays a plain query param: $url", url.contains("model=nova-2"))
        assertTrue("language stays a plain query param: $url", url.contains("language=en"))
        assertFalse("Deepgram key must not leak into the URL: $url", url.contains(key))
        assertEquals("Token $key", req.header("Authorization"))
    }
}
