package ai.unicto.unibot.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.db.MessageEntity
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.util.Date

/**
 * Self-contained HTML export of one chat session (P8 — shareable chats).
 *
 * The cloud relay has no public-blob endpoint, so a "share link" cannot be
 * minted server-side. Instead this builds a single `.html` file with all CSS
 * inline and zero external resources: it opens in any browser, works fully
 * offline, and is handed to the Android share sheet as a file attachment —
 * the person picks the destination themselves, every time. Nothing is
 * uploaded anywhere by unibot; there is no auto-share.
 *
 * Like [ChatExporter], messages are paged ([BATCH_SIZE] rows at a time) so
 * peak memory stays bounded no matter how long the chat is. Text parts are
 * rendered; image/video attachments are listed as placeholders rather than
 * embedded (embedding multi-megabyte media as data URIs would break the
 * memory bound and most share targets).
 */
object ChatHtmlExporter {

    private const val BATCH_SIZE = 50
    private const val LOG_CATEGORY = "ChatHtmlExporter"

    /**
     * Write [session] as a self-contained HTML file under `cacheDir/shared/`
     * and return its [FileProvider] content [Uri], suitable for
     * [Intent.ACTION_SEND] with type `"text/html"`.
     */
    suspend fun exportToHtml(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
    ): Uri = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeTitle = (session.title ?: "conversation")
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .take(64)
            .ifEmpty { "conversation" }
        val file = File(sharedDir, "${safeTitle}-${session.id.take(8)}.html")
        if (file.exists()) file.delete()

        val total = repository.messageCount(session.id)
        val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        var first: Long? = null
        var last: Long? = null
        var done = 0

        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { w ->
            w.write(htmlHead(escapeHtml(session.title ?: "Conversation")))
            w.write(
                """<header><h1>${escapeHtml(session.title ?: "Conversation")}</h1>""" +
                    """<p class="meta">$total messages</p></header><main>""",
            )
            var offset = 0
            while (offset < total) {
                val batch = repository.loadMessagePageRaw(session.id, offset, BATCH_SIZE)
                if (batch.isEmpty()) break
                for (msg in batch) {
                    if (first == null) first = msg.createdAt
                    last = msg.createdAt
                    w.write(renderMessage(msg, dateFmt))
                    done++
                }
                w.flush()
                offset += batch.size
                if (batch.size < BATCH_SIZE) break
            }
            val range = if (first != null && last != null) {
                "${dateFmt.format(Date(first!!))} – ${dateFmt.format(Date(last!!))}"
            } else ""
            w.write("</main>")
            w.write("""<footer><p>Exported from unibot · $done messages${if (range.isNotEmpty()) " · $range" else ""}</p></footer>""")
            w.write("</body></html>")
        }

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        AppLogger.info(LOG_CATEGORY, "exportToHtml ok: ${file.absolutePath} (${file.length()} bytes, $done msgs)")
        uri
    }

    private fun renderMessage(msg: MessageEntity, dateFmt: DateFormat): String {
        val isUser = msg.role == "user"
        val cls = if (isUser) "user" else "assistant"
        val who = if (isUser) "You" else "Assistant"
        val sb = StringBuilder()
        sb.append("""<article class="$cls"><div class="who">$who <span class="time">${dateFmt.format(Date(msg.createdAt))}</span></div>""")
        sb.append(renderParts(msg.partsJson))
        sb.append("</article>")
        return sb.toString()
    }

    /** Text parts as paragraphs; media parts as labelled placeholders. */
    private fun renderParts(partsJson: String): String {
        val sb = StringBuilder()
        try {
            val arr = JSONArray(partsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                when (obj.optString("type")) {
                    "text" -> {
                        val value = stripAttachmentInventory(obj.optString("value"))
                        if (value.isNotEmpty()) {
                            sb.append("<p>")
                            // Preserve line breaks without trusting any HTML in the text.
                            sb.append(escapeHtml(value).replace("\n", "<br>"))
                            sb.append("</p>")
                        }
                    }
                    "image", "image_url" -> sb.append("""<p class="media">[image attachment]</p>""")
                    "video", "video_url" -> sb.append("""<p class="media">[video attachment]</p>""")
                    "audio", "audio_url" -> sb.append("""<p class="media">[audio attachment]</p>""")
                    "file", "file_url" -> sb.append("""<p class="media">[file attachment]</p>""")
                }
            }
        } catch (_: Throwable) {
            sb.append("<p>").append(escapeHtml(partsJson)).append("</p>")
        }
        if (sb.isEmpty()) sb.append("""<p class="media">[empty message]</p>""")
        return sb.toString()
    }

    /**
     * Strip the persisted `<user-attached-files>` XML inventory from
     * human-readable output — model-facing metadata, not chat content
     * (same rule as [ChatExporter]'s text export).
     */
    private fun stripAttachmentInventory(value: String): String {
        var v = value
        val start = v.indexOf("<user-attached-files>")
        if (start >= 0) {
            val endTag = "</user-attached-files>"
            val end = v.indexOf(endTag, start)
            v = if (end >= 0) v.substring(0, start) + v.substring(end + endTag.length) else v.substring(0, start)
        }
        return v.trim()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * All styling inline, no external resources. Colours follow the OS theme
     * (`prefers-color-scheme`) so the file looks right wherever it is opened;
     * the accent is the unibot violet in both modes.
     */
    private fun htmlHead(title: String): String = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title</title><style>
:root{color-scheme:light dark}
*{box-sizing:border-box}
body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;padding:0 16px 48px;background:#ffffff;color:#111111;line-height:1.55}
header{max-width:720px;margin:0 auto;padding:28px 0 8px}
h1{font-size:22px;margin:0 0 4px}
.meta{color:#6b6b70;font-size:13px;margin:0}
main{max-width:720px;margin:0 auto;display:flex;flex-direction:column;gap:14px}
article{padding:2px 0}
article.user{align-self:flex-end;max-width:85%;background:#F0E6FF;border-radius:18px;padding:10px 14px}
.who{font-size:12px;font-weight:600;color:#6D28D9;margin-bottom:4px}
article.user .who{color:#6D28D9}
.time{font-weight:400;color:#8e8e93}
article p{margin:6px 0;font-size:15px;overflow-wrap:anywhere}
.media{color:#8e8e93;font-style:italic;font-size:13px}
footer{max-width:720px;margin:32px auto 0;color:#8e8e93;font-size:12px}
@media (prefers-color-scheme:dark){
body{background:#000000;color:#f2f2f7}
article.user{background:#2E1A4A}
.who{color:#A78BFA}
article.user .who{color:#A78BFA}
.meta,.time,.media,footer{color:#98989f}
}
</style></head><body>"""
}
