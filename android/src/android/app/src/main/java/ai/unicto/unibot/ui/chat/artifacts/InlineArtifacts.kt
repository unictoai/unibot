package ai.unicto.unibot.ui.chat.artifacts

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.SkeletonCard
import ai.unicto.unibot.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * P5 content creation — inline artifacts.
 *
 * Detects artifact fenced blocks in assistant messages and renders them live,
 * full width, inside the chat:
 *
 * - ```mermaid  → rendered via WebView + the mermaid.js CDN (graceful offline
 *   fallback card when there is no connectivity or the CDN fails).
 * - ```svg      → rendered natively by the WebView's SVG engine.
 * - ```html     → rendered in a sandboxed WebView: JavaScript enabled (demos
 *   need it) but NO app JS bridge (`addJavascriptInterface` is never called),
 *   no file/content access, and `loadDataWithBaseURL(null, …)` so the page
 *   has no origin to reach out from.
 * - ```chart    → Compose-Canvas charts, see DataCharts.kt.
 * - ```unibot-canvas / ```unibot-deck / ```unibot-site → app-protocol cards,
 *   see CanvasPane.kt / SlideDeck.kt / SiteBuilder.kt.
 *
 * Wired into both markdown renderers (StreamingMarkdownText + MarkdownText)
 * via [ArtifactRender.isArtifactLanguage]; the card itself is [ArtifactBlock].
 *
 * Streaming safety: while the model is still appending tokens the fence
 * content changes on every recomposition. [settledCode] only updates after
 * 800 ms of quiet, so the WebView loads once per settled edit instead of
 * per token; a skeleton shows meanwhile. This keeps the streaming path cheap.
 */
object ArtifactRender {
    private val languages = setOf(
        "mermaid", "svg", "html",
        "chart",
        "unibot-canvas", "unibot-canvas-edit", "unibot-deck", "unibot-site",
    )

    /** True when a fenced-code language tag should render as an artifact, not code. */
    fun isArtifactLanguage(language: String): Boolean {
        val tag = language.trim().lowercase().substringBefore(' ').substringBefore('{')
        return tag in languages
    }

    /** Normalized tag for dispatch (lowercased, no params). */
    fun tagOf(language: String): String =
        language.trim().lowercase().substringBefore(' ').substringBefore('{')
}

/** Renders one artifact fence. Dispatches to the chart/canvas/deck/site cards or a live WebView. */
@Composable
fun ArtifactBlock(language: String, code: String, modifier: Modifier = Modifier) {
    when (ArtifactRender.tagOf(language)) {
        "chart" -> ai.unicto.unibot.ui.chat.ChartCard(code = code, modifier = modifier)
        "unibot-canvas" -> ai.unicto.unibot.ui.chat.CanvasCard(json = code, modifier = modifier)
        "unibot-canvas-edit" -> ai.unicto.unibot.ui.chat.CanvasEditCard(json = code, modifier = modifier)
        "unibot-deck" -> ai.unicto.unibot.ui.deck.DeckCard(json = code, modifier = modifier)
        "unibot-site" -> ai.unicto.unibot.ui.site.SiteCard(json = code, modifier = modifier)
        else -> WebArtifactCard(language = ArtifactRender.tagOf(language), code = code, modifier = modifier)
    }
}

/** Debounces fast-changing code (live streaming) so WebViews load once per settled edit. */
@Composable
private fun settledCode(code: String, quietMs: Long = 800): String {
    var settled by remember { mutableStateOf(code) }
    LaunchedEffect(code) {
        delay(quietMs)
        settled = code
    }
    return settled
}

private fun escapeHtml(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        else -> append(c)
    }
}

private const val MERMAID_CDN = "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"

private fun mermaidHtml(source: String, dark: Boolean): String {
    val bg = if (dark) "#1C1C1E" else "#FFFFFF"
    val fg = if (dark) "#F2F2F7" else "#1C1C1E"
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          html,body{margin:0;padding:0;background:$bg;color:$fg;}
          body{padding:12px;font-family:-apple-system,system-ui,sans-serif;}
          .mermaid{display:flex;justify-content:center;}
          .mermaid svg{max-width:100%;height:auto;}
          #merr-err{display:none;padding:12px;border:1px solid #FF453A;border-radius:8px;
                    color:#FF453A;font-size:13px;white-space:pre-wrap;word-break:break-word;}
        </style>
        <script src="$MERMAID_CDN"></script>
        </head><body>
        <pre class="mermaid">${escapeHtml(source)}</pre>
        <div id="merr-err"></div>
        <script>
          try {
            mermaid.initialize({ startOnLoad: true, theme: '${if (dark) "dark" else "default"}',
              securityLevel: 'strict', maxEdges: 500 });
          } catch (e) {
            var d = document.getElementById('merr-err');
            d.style.display = 'block';
            d.textContent = 'Could not render this diagram: ' + e;
          }
        </script>
        </body></html>
    """.trimIndent()
}

private fun svgHtml(source: String, dark: Boolean): String {
    val bg = if (dark) "#1C1C1E" else "#FFFFFF"
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;padding:0;background:$bg;}
        body{display:flex;justify-content:center;align-items:flex-start;padding:12px;}
        svg{max-width:100%;height:auto;}</style>
        </head><body>$source</body></html>
    """.trimIndent()
}

private fun htmlShell(source: String, dark: Boolean): String {
    // Served with a null base URL: the snippet gets no origin, no file
    // access, and no app bridge (sandboxed — see [sandboxedWebView]).
    val bg = if (dark) "#000000" else "#FFFFFF"
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;padding:0;background:$bg;}</style>
        </head><body>$source</body></html>
    """.trimIndent()
}

/**
 * A WebView for untrusted model-generated snippets: JS on (demos/mermaid need
 * it) but file/content access off, no universal file-URL access, and — most
 * importantly — no `addJavascriptInterface`, so page JS can never reach the app.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun sandboxedWebView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
}

private fun isOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(false)

@Composable
private fun WebArtifactCard(language: String, code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dark = ChatColors.isDark
    val settled = settledCode(code)
    val streaming = settled != code
    var loadFailed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    val label = when (language) {
        "mermaid" -> "Diagram"
        "svg" -> "SVG"
        "html" -> "HTML"
        else -> language
    }
    val needsNetwork = language == "mermaid"
    val offline = needsNetwork && !isOnline(context)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MuseTones.surface)
            .border(1.dp, MuseTones.hairline, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.OpenInFull, contentDescription = "Expand", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    AppLogger.info("Artifacts", "copied $language block (${code.length} chars)")
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy source", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        when {
            streaming -> SkeletonCard(lines = 4, modifier = Modifier.padding(horizontal = 0.dp))
            offline || loadFailed -> ArtifactOfflineFallback(
                label = label,
                code = code,
                offline = offline,
                onRetry = { loadFailed = false; retryTick++ },
            )
            else -> {
                val html = remember(settled, dark, retryTick) {
                    when (language) {
                        "mermaid" -> mermaidHtml(settled, dark)
                        "svg" -> svgHtml(settled, dark)
                        else -> htmlShell(settled, dark)
                    }
                }
                // Tag-guard: only load when the settled source actually changed.
                // The retry tick is part of the key so "Retry" really reloads.
                val loadKey = remember(html, retryTick) { "$retryTick::$html" }
                AndroidView(
                    factory = { ctx -> sandboxedWebView(ctx) },
                    update = { view ->
                        if (view.tag != loadKey) {
                            view.tag = loadKey
                            view.webViewClient = object : WebViewClient() {
                                @Deprecated("legacy")
                                override fun onReceivedError(
                                    v: WebView, errorCode: Int, description: String?, failingUrl: String?,
                                ) { loadFailed = true }

                                override fun onReceivedError(
                                    v: WebView, request: WebResourceRequest, error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) loadFailed = true
                                }
                            }
                            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (language == "html") 320.dp else 280.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
    }

    if (expanded) {
        ArtifactExpandedDialog(label = label, onDismiss = { expanded = false }) {
            ExpandedArtifactBody(language = language, code = code, onLoadFailed = { loadFailed = true })
        }
    }
}

/** Graceful fallback when the artifact can't render (offline CDN, blocked load). */
@Composable
private fun ArtifactOfflineFallback(
    label: String,
    code: String,
    offline: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MuseTones.fill)
            .padding(14.dp),
    ) {
        Text(
            text = if (offline)
                "$label needs internet to render (diagram library loads from a CDN). Connect, then retry — your source is safe below."
            else
                "$label couldn't load just now. Your source is safe below — retry or copy it.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Retry")
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = code,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArtifactExpandedDialog(
    label: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun ExpandedArtifactBody(language: String, code: String, onLoadFailed: () -> Unit) {
    val dark = ChatColors.isDark
    val html = remember(code, dark) {
        when (language) {
            "mermaid" -> mermaidHtml(code, dark)
            "svg" -> svgHtml(code, dark)
            else -> htmlShell(code, dark)
        }
    }
    AndroidView(
        factory = { ctx ->
            sandboxedWebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) onLoadFailed()
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
