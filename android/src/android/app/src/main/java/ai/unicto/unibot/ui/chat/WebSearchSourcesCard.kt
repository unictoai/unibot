package ai.unicto.unibot.ui.chat

import ai.unicto.unibot.local.WebResult
import ai.unicto.unibot.ui.components.SkeletonLine
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [v0.5.0-agentic-core] Beautiful "Sources" card for web_search results.
 *
 * Renders under the answer whenever a `web_search` tool_use block carries
 * results (cloud agent path) or the on-device path injected live results
 * (synthetic block built by ChatViewModel from
 * [ai.unicto.unibot.local.LocalCapabilities.lastSearchResults]).
 *
 * Each row shows a favicon-ish colored dot (stable per domain), the title
 * and the domain. Tapping a row opens the URL through the chat's normal
 * link handler ([LocalMarkdownUrlClickHandler]). While the search is still
 * running, shimmer skeleton rows pulse instead.
 *
 * Results are parsed from [ai.unicto.unibot.local.LocalCapabilities.buildSearchBlock]
 * output — the numbered `N. Title / snippet / Source: url` format both the
 * tool and the local path produce.
 */
@Composable
internal fun WebSearchSourcesCard(
    block: AssistantBlock,
    modifier: Modifier = Modifier,
) {
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val results = if (isRunning) emptyList() else parseSearchBlock(block.content)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isRunning) "Searching the web…" else "Sources",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!isRunning && results.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${results.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (isRunning) {
            // Skeleton shimmer while results load (matches the SEARCHING stage).
            repeat(3) { i ->
                SkeletonLine(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
                if (i < 2) Spacer(Modifier.height(2.dp))
            }
        } else if (results.isEmpty()) {
            Text(
                text = "No sources returned.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val urlClick = LocalMarkdownUrlClickHandler.current
            val haptics = rememberHaptic()
            results.forEachIndexed { index, result ->
                SourceRow(
                    result = result,
                    index = index,
                    onClick = {
                        haptics.tap()
                        urlClick?.invoke(result.url)
                    },
                )
                if (index < results.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SourceRow(
    result: WebResult,
    index: Int,
    onClick: () -> Unit,
) {
    val domain = domainOf(result.url)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(domainColor(domain), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
            )
            Text(
                text = domain,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Stable pastel-ish color per domain so rows are scannable. */
private fun domainColor(domain: String): Color {
    val palette = listOf(
        Color(0xFF6D28D9), Color(0xFF0A84FF), Color(0xFF30B980),
        Color(0xFFFF9F0A), Color(0xFFFF375F), Color(0xFF64D2FF),
        Color(0xFFBF5AF2), Color(0xFFAC8E68),
    )
    val idx = (domain.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}

private fun domainOf(url: String): String = runCatching {
    java.net.URI(url).host?.removePrefix("www.") ?: url
}.getOrDefault(url)

/**
 * Parses [ai.unicto.unibot.local.LocalCapabilities.buildSearchBlock] output:
 * ```
 * 1. Title here
 *    snippet line
 *    Source: https://example.com/page
 * ```
 */
internal fun parseSearchBlock(content: String): List<WebResult> {
    val out = mutableListOf<WebResult>()
    val lines = content.lines()
    var i = 0
    while (i < lines.size) {
        val titleMatch = Regex("^\\d+\\.\\s+(.+)$").find(lines[i].trim())
        if (titleMatch != null) {
            val title = titleMatch.groupValues[1].trim()
            var snippet = ""
            var url = ""
            var j = i + 1
            while (j < lines.size) {
                val t = lines[j].trim()
                if (Regex("^\\d+\\.\\s+.+").matches(t)) break
                when {
                    t.startsWith("Source:", ignoreCase = true) ->
                        url = t.substringAfter("Source:", "").trim()
                    t.isNotEmpty() && snippet.isEmpty() -> snippet = t
                }
                j++
            }
            if (title.isNotBlank() && url.isNotBlank()) {
                out.add(WebResult(title = title, snippet = snippet, url = url))
            }
            i = j
        } else {
            i++
        }
    }
    return out
}
