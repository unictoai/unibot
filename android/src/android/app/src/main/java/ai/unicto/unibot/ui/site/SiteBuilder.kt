package ai.unicto.unibot.ui.site

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PlayArrow
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * P5 content creation — one-prompt website builder.
 *
 * Agent contract (see `hidden_files/p5-content-creation-protocol.md`): on
 * "Build me a site for X" the coding agent generates a small static site
 * (HTML/CSS/JS, relative asset paths only) into the app's file store at
 * `filesDir/unibot-sites/<name>/`, then emits exactly one fenced block:
 *
 *     ```unibot-site
 *     {"title": "…", "dir": "<name>", "entry": "index.html"}
 *     ```
 *
 * The app ([SiteCard]) previews it in-app with the existing preview infra
 * ([WebViewHolder] pointed at the `file://` entry + [WebPreviewBottomSheet];
 * "Present" reuses [WebPreviewFullscreenScreen]), and exports it as a ZIP
 * through the system share sheet (existing FileProvider).
 *
 * Publishing: the optional cloud relay was checked (`ui/cloud/` —
 * sign-in/account/devices only) and offers NO static-hosting API, so there
 * is nothing to call. The card says relay publishing is a future step
 * instead of inventing an API. The ZIP export is the shippable path today.
 */
object SiteFlow {
    const val BLOCK = "unibot-site"
}

data class ParsedSite(val title: String, val dir: String, val entry: String)

fun parseSiteJson(json: String): ParsedSite? = runCatching {
    val o = JSONObject(json.trim())
    ParsedSite(
        title = o.optString("title", "My site"),
        dir = sanitizeSiteDir(o.optString("dir")),
        entry = o.optString("entry", "index.html").trim().ifEmpty { "index.html" },
    ).takeIf { it.dir.isNotEmpty() }
}.getOrNull()

/**
 * Only a single relative directory name survives: no "..", no absolute
 * paths, no separators — the agent can never escape `unibot-sites/`.
 */
fun sanitizeSiteDir(raw: String): String {
    val name = raw.trim().substringAfterLast('/').substringAfterLast('\\')
    if (name.isEmpty() || name == "." || name == "..") return ""
    return name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
}

fun siteDir(context: Context, dir: String): File =
    File(context.filesDir, "unibot-sites/$dir")

/** Inline card for a ```unibot-site fence. */
@Composable
fun SiteCard(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val site = remember(json) { parseSiteJson(json) }
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
        if (site == null) {
            Text("Couldn't read this site block — showing raw:", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(json, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        val dir = remember(site) { siteDir(context, site.dir) }
        val entryFile = remember(site) { File(dir, site.entry.removePrefix("/")) }
        val ready = dir.isDirectory && entryFile.isFile

        Text("WEBSITE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(site.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (ready) "${site.entry} · ${countSiteFiles(dir)} files"
            else "Still building — the site files aren't in place yet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (ready) {
            val fileUrl = remember(entryFile) { "file://${entryFile.absolutePath}" }
            val holder = ai.unicto.unibot.ui.preview.rememberWebViewHolder(fileUrl)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { previewing = true }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Preview")
                }
                TextButton(onClick = { presenting = true }) {
                    Icon(Icons.Outlined.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Present")
                }
                TextButton(onClick = { exportSiteZip(context, dir, site.title) }) {
                    Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Export ZIP")
                }
            }
            if (previewing) {
                WebPreviewBottomSheet(
                    holder = holder,
                    onDismiss = { previewing = false },
                    onExpandFullscreen = { previewing = false; presenting = true },
                    fallbackTitle = site.title,
                )
            }
            if (presenting) {
                WebPreviewFullscreenScreen(
                    holder = holder,
                    onDismiss = { presenting = false },
                    onCollapseToSheet = { presenting = false; previewing = true },
                    fallbackTitle = site.title,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null,
                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                "Publishing to the cloud relay isn't available yet — it doesn't offer static hosting. Export the ZIP to host it anywhere.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun countSiteFiles(dir: File): Int =
    runCatching { dir.walkTopDown().count { it.isFile } }.getOrDefault(0)

/** Zips the site directory into cacheDir/shared and fires the system share sheet. */
private fun exportSiteZip(context: Context, dir: File, title: String) {
    runCatching {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(48).ifEmpty { "site" }
        val zip = File(sharedDir, "$safe.zip").apply { if (exists()) delete() }
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val name = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
        AppLogger.info("Site", "exported ${zip.name} (${zip.length()} bytes)")
    }.onFailure {
        AppLogger.warning("Site", "ZIP export failed: ${it.message}")
        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}
