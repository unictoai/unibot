package ai.unicto.unibot.ui.deck

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.preview.WebPreviewBottomSheet
import ai.unicto.unibot.ui.preview.WebPreviewFullscreenScreen
import ai.unicto.unibot.ui.preview.WebViewHolder
import ai.unicto.unibot.ui.theme.ChatColors
import java.io.File
import org.json.JSONObject

/**
 * P5 content creation — docs & slides generation.
 *
 * Agent contract (see `hidden_files/p5-content-creation-protocol.md`): on
 * "Make me a deck about X" the agent researches/writes the content with its
 * normal tools, then emits exactly one fenced block:
 *
 *     ```unibot-deck
 *     {"title": "…", "theme": "dark",
 *      "slides": [{"title": "…", "body": "line1\nline2"}, …]}
 *     ```
 *
 * The app ([DeckCard]) turns it into a self-contained single-file HTML slide
 * deck under `filesDir/unibot-decks/<id>/index.html`, rendered in-app with the
 * existing preview infra ([WebViewHolder] + [WebPreviewBottomSheet], "Present"
 * reuses [WebPreviewFullscreenScreen]).
 *
 * Export: PDF via the Android print framework
 * (`WebView.createPrintDocumentAdapter`, same pattern as the sandbox file
 * preview's `printFile`), and the raw HTML file via the system share sheet
 * (existing FileProvider). PPTX export is NOT available — no dependency-free
 * way to write .pptx on-device; the card says so honestly and the HTML
 * imports cleanly into Google Slides / PowerPoint as the workaround.
 */
object DeckFlow {
    const val BLOCK = "unibot-deck"
}

data class DeckSlide(val title: String, val body: String)
data class ParsedDeck(val title: String, val theme: String, val slides: List<DeckSlide>)

fun parseDeckJson(json: String): ParsedDeck? = runCatching {
    val o = JSONObject(json.trim())
    val slides = o.optJSONArray("slides")?.let { arr ->
        List(arr.length()) { i ->
            val s = arr.getJSONObject(i)
            DeckSlide(s.optString("title"), s.optString("body"))
        }
    }.orEmpty().filter { it.title.isNotBlank() || it.body.isNotBlank() }
    ParsedDeck(o.optString("title", "Untitled deck"), o.optString("theme", "dark"), slides)
        .takeIf { it.slides.isNotEmpty() }
}.getOrNull()

private fun esc(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); else -> append(c)
    }
}

/**
 * Builds the self-contained deck HTML: one full-viewport slide at a time,
 * click zones + arrow keys + on-screen arrows to page, counter, brand-violet
 * accents, print CSS that lays every slide on its own page (so the PDF
 * export comes out as one-slide-per-page).
 */
fun buildDeckHtml(title: String, slides: List<DeckSlide>, dark: Boolean): String {
    val bg = if (dark) "#000000" else "#FFFFFF"
    val card = if (dark) "#1C1C1E" else "#F2F2F7"
    val fg = if (dark) "#F2F2F7" else "#1C1C1E"
    val dim = if (dark) "#AEAEB2" else "#636366"
    val accent = if (dark) "#A78BFA" else "#6D28D9"
    val slideHtml = slides.mapIndexed { i, s ->
        val bullets = s.body.lines().filter { it.isNotBlank() }
            .joinToString("") { "<li>${esc(it.trim().removePrefix("-").removePrefix("•").trim())}</li>" }
        """
        <section class="slide" data-i="$i">
          <div class="inner">
            <div class="kicker">${esc(title)} · ${i + 1} / ${slides.size}</div>
            <h1>${esc(s.title)}</h1>
            ${if (bullets.isNotEmpty()) "<ul>$bullets</ul>" else ""}
          </div>
        </section>
        """.trimIndent()
    }.joinToString("\n")
    return """
    <!DOCTYPE html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${esc(title)}</title>
    <style>
      *{box-sizing:border-box}
      html,body{margin:0;padding:0;background:$bg;color:$fg;
        font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;height:100%;overflow:hidden}
      .slide{display:none;height:100vh;width:100vw;padding:24px}
      .slide.active{display:flex;align-items:center;justify-content:center;animation:fade .25s ease}
      @keyframes fade{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
      .inner{max-width:760px;width:100%;background:$card;border-radius:24px;padding:40px 36px}
      .kicker{font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:$accent;
        font-weight:700;margin-bottom:16px}
      h1{font-size:clamp(26px,5.5vw,44px);margin:0 0 20px;line-height:1.15}
      ul{margin:0;padding-left:22px;font-size:clamp(16px,3.4vw,22px);line-height:1.65;color:$fg}
      li{margin-bottom:10px}
      li::marker{color:$accent}
      .nav{position:fixed;bottom:18px;left:0;right:0;display:flex;justify-content:center;
        align-items:center;gap:18px;color:$dim;font-size:14px;user-select:none}
      .nav button{background:$card;color:$fg;border:1px solid $dim;border-radius:999px;
        width:44px;height:44px;font-size:20px;cursor:pointer}
      .counter{min-width:64px;text-align:center}
      @media print{
        html,body{overflow:visible;height:auto;background:#fff}
        .slide{display:flex !important;height:100vh;page-break-after:always;animation:none;padding:0}
        .nav{display:none}
        .inner{border-radius:0;background:#fff;max-width:none}
        h1,.kicker,ul{color:#000}
      }
    </style></head><body>
    $slideHtml
    <div class="nav">
      <button id="prev" aria-label="Previous">‹</button>
      <span class="counter"><span id="cur">1</span> / ${slides.size}</span>
      <button id="next" aria-label="Next">›</button>
    </div>
    <script>
      var i=0,slides=document.querySelectorAll('.slide'),cur=document.getElementById('cur');
      function show(n){i=Math.max(0,Math.min(slides.length-1,n));
        slides.forEach(function(s,k){s.classList.toggle('active',k===i)});
        cur.textContent=i+1;}
      document.getElementById('prev').onclick=function(e){e.stopPropagation();show(i-1)};
      document.getElementById('next').onclick=function(e){e.stopPropagation();show(i+1)};
      document.addEventListener('keydown',function(e){
        if(e.key==='ArrowRight'||e.key===' ')show(i+1);
        if(e.key==='ArrowLeft')show(i-1);});
      document.body.addEventListener('click',function(e){
        if(e.target.closest('.nav'))return;
        show(i+1);});
      show(0);
    </script></body></html>
    """.trimIndent()
}

/** Writes the deck HTML once and returns its file:// URL. Deterministic dir per (deck, theme) so re-staging overwrites instead of leaking directories. */
private fun stageDeckFile(context: Context, deck: ParsedDeck, dark: Boolean): String {
    val key = "deck-${(deck.title + deck.slides.hashCode().toString() + dark).hashCode()}"
    val dir = File(context.filesDir, "unibot-decks/$key").apply { mkdirs() }
    val html = buildDeckHtml(deck.title, deck.slides, dark)
    File(dir, "index.html").writeText(html)
    AppLogger.info("Deck", "staged '${deck.title.take(40)}' (${html.length} chars, ${deck.slides.size} slides)")
    return "file://${dir.absolutePath}/index.html"
}

/** Inline card for a ```unibot-deck fence. */
@Composable
fun DeckCard(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = ChatColors.isDark
    val deck = remember(json) { parseDeckJson(json) }
    var previewing by remember { mutableStateOf(false) }
    var presenting by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MuseTones.surface)
            .border(1.dp, MuseTones.hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (deck == null) {
            Text("Couldn't read this deck — showing raw:", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(json, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        // Stage the HTML once per (deck, theme).
        val fileUrl = remember(deck, dark) { stageDeckFile(context, deck, dark) }
        val holder = ai.unicto.unibot.ui.preview.rememberWebViewHolder(fileUrl)

        Text("SLIDE DECK", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(deck.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("${deck.slides.size} slides · ${if (dark) "dark" else "light"} theme",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { presenting = true }) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Present")
            }
            TextButton(onClick = { previewing = true }) {
                Icon(Icons.Outlined.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Preview")
            }
            TextButton(onClick = { exportDeckPdf(context, holder, deck.title) }) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("PDF")
            }
            TextButton(onClick = { shareDeckHtml(context, fileUrl, deck.title) }) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Share")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "PPTX export isn't available on-device yet — share the HTML (it imports cleanly into Google Slides / PowerPoint) or print to PDF.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (previewing) {
            WebPreviewBottomSheet(
                holder = holder,
                onDismiss = { previewing = false },
                onExpandFullscreen = { previewing = false; presenting = true },
                fallbackTitle = deck.title,
            )
        }
        if (presenting) {
            WebPreviewFullscreenScreen(
                holder = holder,
                onDismiss = { presenting = false },
                onCollapseToSheet = { presenting = false; previewing = true },
                fallbackTitle = deck.title,
            )
        }
    }
}

/** PDF export via the Android print framework — same pattern as the sandbox file preview. */
private fun exportDeckPdf(context: Context, holder: WebViewHolder, title: String) {
    runCatching {
        // Print from a dedicated off-screen WebView so the shared preview
        // holder keeps its state; the print CSS lays one slide per page.
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
        }
        var ref: WebView? = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "$title"
                printManager.print(
                    jobName,
                    view.createPrintDocumentAdapter(jobName),
                    PrintAttributes.Builder().build(),
                )
                AppLogger.info("Deck", "print job dispatched for '$title'")
                ref = null
            }
        }
        @Suppress("UNUSED_VALUE")
        ref = webView
        webView.loadUrl(holder.currentUrl)
    }.onFailure {
        AppLogger.warning("Deck", "PDF export failed: ${it.message}")
        Toast.makeText(context, "PDF export failed: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** Shares the staged single-file HTML via the system share sheet (existing FileProvider). */
private fun shareDeckHtml(context: Context, fileUrl: String, title: String) {
    runCatching {
        val src = File(fileUrl.removePrefix("file://"))
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(48).ifEmpty { "deck" }
        val dest = File(sharedDir, "$safe.html").apply { src.copyTo(this, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
        AppLogger.info("Deck", "shared ${dest.name}")
    }.onFailure {
        AppLogger.warning("Deck", "share failed: ${it.message}")
        Toast.makeText(context, "Share failed: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}
