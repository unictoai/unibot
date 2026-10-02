package ai.unicto.unibot.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.ui.theme.staggeredEntrance
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Photo grid for Google Photos tool results ([PhotosTool]).
 *
 * [content] is the tool output text: `![alt](file://…)` markdown lines for
 * each downloaded thumbnail plus caption lines. The markdown is parsed back
 * out here so the tool-detail sheet can show a real 2-column staggered grid
 * instead of generic text. Entries animate in with
 * [staggeredEntrance] (Motion's 40ms stagger step), so the grid unfolds
 * thumbnail by thumbnail.
 *
 * Files outside this grid's thumbnails still need the bearer token, so the
 * Photos tool pre-downloads them into the app cache dir — the grid loads
 * from local [File]s and never touches the network.
 */
@Composable
internal fun PhotosGridCard(
    content: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entries = rememberPhotosEntries(content)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        val chunks = entries.chunked(2)
        chunks.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { colIndex, entry ->
                    val file = entry.file
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(file)
                            .crossfade(true)
                            .build(),
                        contentDescription = entry.alt.ifBlank { "Google Photos thumbnail" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(0.5.dp, ChatColors.separator, RoundedCornerShape(10.dp))
                            .staggeredEntrance(rowIndex * 2 + colIndex),
                    )
                }
                // Keep the 2-column rhythm on a trailing odd row.
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (entries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
        }
        Text(
            text = entries.fold(content) { acc, e -> acc.replace(e.raw, "") }
                .lines()
                .filter { it.isNotBlank() }
                .joinToString("\n"),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = ChatColors.primaryText,
            lineHeight = 18.sp,
        )
    }
}

private data class PhotosEntry(val raw: String, val alt: String, val file: File)

/** Matches `![alt](file://…)` markdown image lines in tool output. */
private val photosMarkdownImageRegex = Regex("!\\[([^\\]]*)]\\((file://[^)\\s]+)\\)")

@Composable
private fun rememberPhotosEntries(content: String): List<PhotosEntry> {
    return remember(content) {
        photosMarkdownImageRegex.findAll(content).mapNotNull { m ->
            val path = Uri.parse(m.groupValues[2]).path ?: return@mapNotNull null
            val file = File(path)
            if (!file.exists() || !file.isFile) return@mapNotNull null
            PhotosEntry(raw = m.value, alt = m.groupValues[1], file = file)
        }.toList()
    }
}
