package ai.unicto.unibot.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [Wave 8] Chat export: Markdown (share sheet) and PDF (system print → save).
 *
 * Markdown: built from the message list, shared via ACTION_SEND — the user
 * picks where it goes. PDF: renders the same content as HTML in an offscreen
 * WebView and hands it to PrintManager, so "Save as PDF" uses the platform
 * dialog. No new dependencies, no network.
 */
object ChatExport {
    private const val TAG = "ChatExport"

    /** Minimal message shape for export — mapped from whatever the UI holds. */
    data class ExportMessage(
        val role: String, // "user" | "assistant" | "system"
        val text: String,
        val timestampMs: Long = 0L,
    )

    fun toMarkdown(title: String, messages: List<ExportMessage>): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("_Exported from unibot · $date_")
            appendLine()
            for (m in messages) {
                if (m.text.isBlank()) continue
                val who = when (m.role.lowercase()) {
                    "user" -> "**You**"
                    "assistant" -> "**unibot**"
                    else -> "**System**"
                }
                appendLine("$who:")
                appendLine()
                appendLine(m.text.trim())
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    /** Share the markdown via the system share sheet. */
    fun shareMarkdown(context: Context, title: String, messages: List<ExportMessage>) {
        val md = toMarkdown(title, messages)
        val safe = title.ifBlank { "chat" }.replace(Regex("[^a-zA-Z0-9-_]"), "_").take(40)
        val file = File(context.cacheDir, "exports").also { it.mkdirs() }
        val out = File(file, "$safe.md")
        out.writeText(md)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export chat"))
    }

    /**
     * Print the chat to PDF via the system print dialog ("Save as PDF").
     * Must be called on the main thread (WebView requirement).
     */
    suspend fun printToPdf(
        context: Context,
        title: String,
        messages: List<ExportMessage>,
    ) = withContext(Dispatchers.Main) {
        val html = toHtml(title, messages)
        val webView = WebView(context)
        try {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val printManager =
                        context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                            ?: return
                    val jobName = "${title.ifBlank { "chat" }}_unibot"
                    val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        view.createPrintDocumentAdapter(jobName)
                    } else {
                        @Suppress("DEPRECATION")
                        view.createPrintDocumentAdapter()
                    }
                    printManager.print(
                        jobName,
                        adapter,
                        PrintAttributes.Builder().build(),
                    )
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            // Give the page a moment to render before the Activity may go away.
            kotlinx.coroutines.delay(1500)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "printToPdf failed: ${t.message}")
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun toHtml(title: String, messages: List<ExportMessage>): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val body = messages.filter { it.text.isNotBlank() }.joinToString("\n") { m ->
            val who = when (m.role.lowercase()) {
                "user" -> "You"
                "assistant" -> "unibot"
                else -> "System"
            }
            val cls = if (m.role.lowercase() == "user") "user" else "bot"
            """<div class="msg $cls"><div class="who">$who</div><div class="text">${escapeHtml(m.text.trim()).replace("\n", "<br>")}</div></div>"""
        }
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
body{font-family:sans-serif;margin:24px;color:#111}
h1{font-size:20px} .sub{color:#666;font-size:12px;margin-bottom:16px}
.msg{margin:12px 0;padding:10px 14px;border-radius:12px;max-width:90%}
.user{background:#eef;margin-left:auto}
.bot{background:#f4f4f4}
.who{font-weight:bold;font-size:12px;margin-bottom:4px;color:#555}
.text{font-size:14px;line-height:1.5;white-space:pre-wrap}
</style></head><body>
<h1>${escapeHtml(title.ifBlank { "Chat export" })}</h1>
<div class="sub">Exported from unibot · $date</div>
$body</body></html>"""
    }
}
