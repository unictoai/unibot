package ai.unicto.unibot.ui.chat

// [v12-B] Chat export formats: styled HTML + plain TXT, extending the Wave 8
// export (Markdown share + PDF print) that lives in [ChatExport]. Same
// FileProvider share-sheet plumbing — no new dependencies, no network.

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ai.unicto.unibot.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatExportV12 {
    private const val TAG = "ChatExportV12"

    /** Plain-text export: readable in any editor. */
    fun toTxt(title: String, messages: List<ChatExport.ExportMessage>): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return buildString {
            appendLine(title.ifBlank { "Chat export" })
            appendLine("Exported from unibot · $date")
            appendLine()
            appendLine("=".repeat(40))
            for (m in messages) {
                if (m.text.isBlank()) continue
                val who = when (m.role.lowercase()) {
                    "user" -> "You"
                    "assistant" -> "unibot"
                    else -> "System"
                }
                appendLine()
                appendLine("$who:")
                appendLine("-".repeat(40))
                appendLine(m.text.trim())
            }
        }
    }

    /** Styled, self-contained HTML export (all CSS inline, zero external resources). */
    fun toHtml(title: String, messages: List<ChatExport.ExportMessage>): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val body = messages.filter { it.text.isNotBlank() }.joinToString("\n") { m ->
            val who = when (m.role.lowercase()) {
                "user" -> "You"
                "assistant" -> "unibot"
                else -> "System"
            }
            val cls = if (m.role.lowercase() == "user") "user" else "bot"
            val time = if (m.timestampMs > 0) {
                val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timestampMs))
                """<span class="time">$t</span>"""
            } else ""
            """<div class="msg $cls"><div class="who">$who$time</div><div class="text">${escapeHtml(m.text.trim()).replace("\n", "<br>")}</div></div>"""
        }
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:light}
body{font-family:-apple-system,system-ui,"Segoe UI",sans-serif;margin:0;padding:24px;color:#1c1c1e;background:#fafafa}
header{margin-bottom:20px}
h1{font-size:22px;margin:0 0 4px}
.sub{color:#8e8e93;font-size:13px}
.msg{margin:12px 0;padding:12px 16px;border-radius:16px;max-width:88%;box-shadow:0 1px 2px rgba(0,0,0,.06)}
.user{background:#7c5cff;color:#fff;margin-left:auto}
.bot{background:#fff;border:1px solid #e5e5ea}
.who{font-weight:600;font-size:12px;margin-bottom:6px;opacity:.75}
.time{font-weight:400;margin-left:8px;font-size:11px}
.text{font-size:15px;line-height:1.55;white-space:pre-wrap;word-break:break-word}
footer{margin-top:24px;color:#8e8e93;font-size:12px;text-align:center}
</style></head><body>
<header><h1>${escapeHtml(title.ifBlank { "Chat export" })}</h1>
<div class="sub">Exported from unibot · $date</div></header>
$body
<footer>unibot · your data stays yours</footer>
</body></html>"""
    }

    /** Share the plain-text export via the system share sheet. */
    fun shareTxt(context: Context, title: String, messages: List<ChatExport.ExportMessage>) {
        shareFile(context, title, "txt", "text/plain", toTxt(title, messages))
    }

    /** Share the styled HTML export via the system share sheet. */
    fun shareHtml(context: Context, title: String, messages: List<ChatExport.ExportMessage>) {
        shareFile(context, title, "html", "text/html", toHtml(title, messages))
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun shareFile(
        context: Context,
        title: String,
        ext: String,
        mime: String,
        content: String,
    ) {
        try {
            val safe = title.ifBlank { "chat" }.replace(Regex("[^a-zA-Z0-9-_]"), "_").take(40)
            val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
            val out = File(dir, "$safe.$ext")
            out.writeText(content)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export chat"))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "share $ext failed: ${t.message}")
        }
    }
}
