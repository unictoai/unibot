package ai.unicto.unibot.ui.browser

import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** Main content pulled out of a page: the document title plus cleaned article text. */
data class BrowserPageContent(
    val title: String,
    val text: String,
)

/**
 * Readability-style article extraction shared by reading mode and
 * "Ask unibot about this page".
 *
 * The heuristic scores candidate containers (article/main first, then
 * div/section fallbacks) by text length minus link text, bonuses for
 * paragraph count, then strips scripts, nav, ads and forms from a clone
 * before reading its text. Pure on-device JS — the page never leaves the
 * phone beyond what the WebView already loaded.
 */
object BrowserPageText {

    /** Hard cap on extracted characters — keeps IPC and prompts sane. */
    const val MAX_CHARS = 60000

    /**
     * Runs the extraction inside [webView] and delivers the result on the
     * main thread. Never throws; [onResult] receives null when the page has
     * no readable text or extraction fails. Must be called on the main
     * thread (Compose event handlers already are).
     */
    fun extract(webView: WebView, onResult: (BrowserPageContent?) -> Unit) {
        try {
            webView.evaluateJavascript(READABILITY_JS) { raw ->
                onResult(parseResult(raw))
            }
        } catch (_: Exception) {
            onResult(null)
        }
    }

    private fun parseResult(raw: String?): BrowserPageContent? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            // evaluateJavascript JSON-encodes a returned string, so the
            // payload arrives wrapped in quotes — decode once to get the
            // JSON text, then parse the object.
            val inner = JSONTokener(raw).nextValue() as? String ?: return null
            val obj = JSONObject(inner)
            val text = obj.optString("text").trim()
            if (text.length < 120) return null
            BrowserPageContent(title = obj.optString("title").trim(), text = text)
        } catch (_: Exception) {
            null
        }
    }

    // NOTE: plain JS only — no template literals or `$` (Kotlin raw-string
    // interpolation) and no triple quotes.
    private const val READABILITY_JS = """(function(){
  function clean(t){ return (t || '').replace(/\s+/g, ' ').trim(); }
  function linkLen(el){
    var n = 0, links = el.getElementsByTagName('a');
    for (var i = 0; i < links.length; i++) n += clean(links[i].innerText).length;
    return n;
  }
  function score(el){
    var t = clean(el.innerText);
    if (t.length < 200) return -1;
    var p = el.getElementsByTagName('p').length;
    return t.length - linkLen(el) + p * 60;
  }
  var best = null, bestScore = -1, i, j, els, el, s;
  var sels = ['article', 'main', '[role="main"]'];
  for (i = 0; i < sels.length; i++){
    els = document.querySelectorAll(sels[i]);
    for (j = 0; j < els.length; j++){ el = els[j]; s = score(el); if (s > bestScore){ bestScore = s; best = el; } }
  }
  if (!best){
    els = document.querySelectorAll('div, section');
    for (i = 0; i < els.length; i++){ el = els[i]; s = score(el); if (s > bestScore){ bestScore = s; best = el; } }
  }
  var root = best || document.body;
  var clone = root.cloneNode(true);
  var junk = clone.querySelectorAll('script, style, nav, header, footer, aside, form, button, select, textarea, input, iframe, video, audio, .ad, .ads, .advertisement, .cookie, .popup, .modal, .share, .social');
  for (i = 0; i < junk.length; i++){ if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]); }
  var text = clean(clone.innerText);
  if (text.length > 60000) text = text.substring(0, 60000);
  return JSON.stringify({ title: document.title || '', text: text });
})()"""
}
