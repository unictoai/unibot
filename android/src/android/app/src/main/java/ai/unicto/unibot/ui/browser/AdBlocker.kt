package ai.unicto.unibot.ui.browser

import android.content.Context
import java.net.URI

/**
 * On-device ad/tracker host blocklist for the in-app browser.
 *
 * The list is curated in code — nothing is downloaded, no filter-list
 * updates phone home, and the on/off choice lives in the app's own
 * preferences. Matching is host-based: a request is blocked when its host
 * equals a listed host or is a subdomain of one.
 *
 * ENFORCEMENT (agent-power browser-backend worker): this file ships the
 * list, the toggle state, and [shouldBlock]. The actual interception lives
 * in BrowserUseManager's WebViewClient — add at the top of
 * `shouldInterceptRequest`, before the unibot:// check:
 *
 *     if (AdBlocker.isEnabled(view.context) && !request.isForMainFrame &&
 *         AdBlocker.shouldBlock(url.toString())) {
 *         return WebResourceResponse("text/plain", "utf-8", 204, "No Content",
 *             mutableMapOf(), ByteArray(0).inputStream())
 *     }
 *
 * Subresources only (`!isForMainFrame`) so a user who navigates directly to
 * a listed host still sees the page instead of a blank screen.
 */
object AdBlocker {
    private const val PREFS = "browser_prefs"
    private const val KEY_ENABLED = "adblock_enabled"

    /** Default off — blocking changes page rendering, so it's opt-in. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** True when [url]'s host is (or is a subdomain of) a blocked host. */
    fun shouldBlock(url: String): Boolean {
        val host = try {
            URI(url).host
        } catch (_: Exception) {
            null
        }?.lowercase()?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false
        for (blocked in BLOCKED_HOSTS) {
            if (host == blocked || host.endsWith(".$blocked")) return true
        }
        return false
    }

    /**
     * Curated ad / tracker / analytics hosts. Deliberately host-granular
     * (no TLD wildcards) so first-party CDNs and login flows keep working.
     */
    private val BLOCKED_HOSTS = setOf(
        // Google ads & analytics
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagmanager.com",
        "googletagservices.com",
        "google-analytics.com",
        "analytics.google.com",
        "adservice.google.com",
        // Major ad networks / exchanges
        "advertising.com",
        "adnxs.com",
        "adsrvr.org",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "criteo.com",
        "criteo.net",
        "outbrain.com",
        "taboola.com",
        "revcontent.com",
        "mgid.com",
        "zergnet.com",
        "nativo.com",
        "sharethrough.com",
        "triplelift.com",
        "indexww.com",
        "yieldmo.com",
        "media.net",
        "adtech.de",
        "bidswitch.net",
        "mathtag.com",
        "moatads.com",
        "2mdn.net",
        "amazon-adsystem.com",
        // Social / platform trackers
        "connect.facebook.net",
        "pixel.facebook.com",
        "ads-twitter.com",
        "ads.linkedin.com",
        "snap.licdn.com",
        "bat.bing.com",
        "ads.pinterest.com",
        "ct.pinterest.com",
        // Analytics / session replay / fingerprinting
        "hotjar.com",
        "fullstory.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "amplitude.com",
        "newrelic.com",
        "nr-data.net",
        "scorecardresearch.com",
        "quantserve.com",
        "chartbeat.com",
        "crazyegg.com",
        "luckyorange.com",
        "mouseflow.com",
        "inspectlet.com",
        "pardot.com",
        "marketo.net",
        "hs-analytics.net",
        "hubspot.com",
        "intercom.io",
        "drift.com",
        "zendesk.com",
        "adform.net",
        "adform.com",
        "rlcdn.com",
        "crwdcntrl.net",
        "demdex.net",
        "everesttech.net",
        "agkn.com",
        "rfp.io",
        "lijit.com",
        "sovrn.com",
        "undertone.com",
        "stickyadstv.com",
        "smartadserver.com",
        "teads.tv",
    )
}
