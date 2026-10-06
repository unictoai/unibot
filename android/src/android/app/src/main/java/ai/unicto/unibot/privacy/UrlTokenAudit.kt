package ai.unicto.unibot.privacy

/**
 * Privacy backlog item 64 — sweep share links and deep links for credential
 * leaks, and redact before any share or copy.
 *
 * What counts as a leak: a URL query or fragment parameter whose NAME looks
 * like a credential carrier (api_key, access_token, client_secret, password,
 * or anything ending in _token / _key / _secret / _signature). The VALUE is
 * never logged, returned, or stored anywhere: findLeaks reports parameter
 * names only, and the redact functions replace values with a fixed marker.
 *
 * Pure string logic with no Android dependency, so it is unit-tested on the
 * plain JVM. Wired in at:
 * - DeepLinkHandler.parse — incoming deep links are audited (parameter names
 *   logged at warning level, never values); the link itself is left intact
 *   because some first-party flows intentionally carry one-time codes.
 * - ClipboardGuard.copyWithAutoClear — every in-app copy is redacted first.
 */
object UrlTokenAudit {

    /** Replacement for a redacted credential value. Fixed string, never the value. */
    const val REDACTED = "REDACTED"

    private val SECRET_PARAM_NAMES = setOf(
        "api_key", "apikey", "api-key", "x-api-key",
        "token", "access_token", "refresh_token", "id_token",
        "auth_token", "authtoken", "auth", "authorization",
        "secret", "client_secret", "app_secret",
        "password", "passwd", "pwd",
        "private_key", "secret_key",
        "session_token", "sessionid", "session_id",
        "bearer", "sig", "signature", "hmac",
    )

    private fun isSecretParam(rawName: String): Boolean {
        val name = rawName.lowercase().trim()
        if (name.isEmpty()) return false
        if (name in SECRET_PARAM_NAMES) return true
        return name.endsWith("_token") || name.endsWith("_key") ||
            name.endsWith("_secret") || name.endsWith("_signature")
    }

    /**
     * Parameter names in [url] that look like credential carriers.
     * Returns names only — never values.
     */
    fun findLeaks(url: String): List<String> {
        val leaks = LinkedHashSet<String>()
        // Query and fragment parameters: ?name=value, &name=value, #name=value.
        val paramPattern = Regex("[?&#]([^=&#\\s;\"'<>]+)=([^&#\\s\"'<>]*)")
        for (match in paramPattern.findAll(url)) {
            val name = match.groupValues[1]
            if (isSecretParam(name)) leaks.add(name)
        }
        return leaks.toList()
    }

    /** True when [url] carries at least one credential-like parameter. */
    fun hasLeak(url: String): Boolean = findLeaks(url).isNotEmpty()

    /**
     * Returns [url] with every credential-like parameter value replaced by
     * [REDACTED]. Structure (scheme, host, path, parameter names) is kept so
     * the redacted link stays recognizable; only values are hidden.
     */
    fun redactUrl(url: String): String {
        val paramPattern = Regex("([?&#])([^=&#\\s;\"'<>]+)=([^&#\\s\"'<>]*)")
        return paramPattern.replace(url) { match ->
            val sep = match.groupValues[1]
            val name = match.groupValues[2]
            val value = match.groupValues[3]
            if (isSecretParam(name) && value.isNotEmpty()) "$sep$name=$REDACTED"
            else match.value
        }
    }

    private val urlInTextPattern =
        Regex("(https?://[^\\s<>\"']+|unibot://[^\\s<>\"']+|minis://[^\\s<>\"']+)")

    /**
     * Finds every URL inside free [text] and redacts credential-like
     * parameters in each. Trailing sentence punctuation (.,;:!?) and
     * closing brackets are kept outside the URL so they are never eaten.
     */
    fun redactUrlsInText(text: String): String {
        return urlInTextPattern.replace(text) { match ->
            var raw = match.value
            var tail = ""
            while (raw.isNotEmpty() && raw.last() in ".,;:!?)]}") {
                tail = raw.last() + tail
                raw = raw.dropLast(1)
            }
            redactUrl(raw) + tail
        }
    }

    /**
     * Audit helper for incoming deep links: the credential-like parameter
     * NAMES present in the raw URI string, for warning-level logging.
     * Values are never included.
     */
    fun auditDeepLink(uriString: String): List<String> = findLeaks(uriString)
}
