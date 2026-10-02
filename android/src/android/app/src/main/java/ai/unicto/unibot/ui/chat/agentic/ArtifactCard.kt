package ai.unicto.unibot.ui.chat.agentic

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.unicto.unibot.ui.home.MuseTones
import java.io.ByteArrayInputStream

/**
 * P4 generative/dynamic UI: renders a ```unibot-card fence as a WebView card
 * at full width inside the chat instead of raw code (v0.2.0).
 *
 * Sandbox rules:
 * - No JavaScript bridge to app internals — EVER (no addJavascriptInterface).
 * - JavaScript runs only when the fence declares `js=true` (default off).
 * - Network loads only when the fence declares `net=true` (default off);
 *   with net off, every http(s) subresource is blocked at
 *   shouldInterceptRequest. The card HTML itself is always local.
 * - No file/content access from the card.
 * - The card gets the current theme (data-ub-theme + color-scheme) so it can
 *   style for dark mode; body background defaults to transparent so it
 *   blends with the chat surface.
 *
 * Fence params (on the fence line, `k=v` space-separated):
 * - js=true|false — JavaScript (default false)
 * - net=true|false — network subresources (default false)
 * - height=NNN — card height in dp (default 320)
 */
@Composable
fun ArtifactCard(
    html: String,
    params: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val jsEnabled = params["js"] == "true"
    val netEnabled = params["net"] == "true"
    val heightDp = params["height"]?.toIntOrNull()?.coerceIn(120, 1200)?.dp ?: 320.dp
    // Theme-aware: read the ACTUAL surface luminance (respects the in-app
    // theme override, not just the system setting).
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val doc = remember(html, jsEnabled, dark) {
        buildString {
            append("<!DOCTYPE html><html data-ub-theme=\"")
            append(if (dark) "dark" else "light")
            append("\"><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<meta name=\"color-scheme\" content=\"dark light\">")
            append("<style>html,body{margin:0;padding:12px;background:transparent;")
            append("color:")
            append(if (dark) "#f2f2f7" else "#1c1c1e")
            append(";font-family:-apple-system,system-ui,sans-serif;}</style></head><body>")
            append(html)
            append("</body></html>")
        }
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // P4 sandbox: never a JS bridge; JS/DOM storage only with js=true.
                settings.javaScriptEnabled = jsEnabled
                settings.domStorageEnabled = jsEnabled
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = !netEnabled
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        // Belt and braces with blockNetworkLoads: with net
                        // off, refuse every remote subresource.
                        val url = request?.url?.toString().orEmpty()
                        if (!netEnabled && (url.startsWith("http://") || url.startsWith("https://"))) {
                            return WebResourceResponse(
                                "text/plain", "utf-8", 403, "Blocked",
                                mapOf("X-unibot-card" to "network-disabled"),
                                ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTag(TAG_DOC, doc)
                loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            // Re-set only if the doc actually changed (rotation / theme flip).
            if (view.getTag(TAG_DOC) != doc) {
                view.setTag(TAG_DOC, doc)
                view.settings.javaScriptEnabled = jsEnabled
                view.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(RoundedCornerShape(16.dp))
            .border(0.6.dp, MuseTones.hairline, RoundedCornerShape(16.dp)),
    )
}

private const val TAG_DOC = 0x7044_4152 // "p4AR"
