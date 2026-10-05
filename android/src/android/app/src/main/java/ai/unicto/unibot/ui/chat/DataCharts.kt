package ai.unicto.unibot.ui.chat
import ai.unicto.unibot.ui.theme.UbColors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.ui.home.HomeBus
import ai.unicto.unibot.ui.home.MuseTones
import org.json.JSONObject

/**
 * P5 content creation — data analysis with charts.
 *
 * The agent-side contract (taught to the model via the content-creation
 * system prompt, see `hidden_files/p5-content-creation-protocol.md`):
 *
 * 1. The user uploads a CSV. The app shows an "Analyze" chip under the
 *    attachment ([CsvAnalyzeChips]); tapping it prefills the composer with
 *    an analysis request.
 * 2. The agent runs the analysis with its existing terminal/sandbox tools
 *    (no new app machinery — the coding/terminal tool path already exists).
 * 3. The agent emits findings as fenced ```chart blocks, which render here
 *    as native Compose-Canvas charts — no heavy chart library, cheap on GPU.
 *
 * Supported spec (JSON, or the loose `{type:bar, labels:[…], values:[…]}`
 * shorthand the prompt teaches):
 *
 *     ```chart
 *     {"type":"bar","title":"Signups by day","labels":["Mon","Tue","Wed"],"values":[12,19,7]}
 *     ```
 *
 * types: "bar" | "line" | "pie". For pie, labels+values are slices.
 * Anything unparseable falls back to a raw code view so data is never lost.
 */
data class ChartSpec(
    val type: String,
    val title: String,
    val labels: List<String>,
    val values: List<Float>,
)

fun parseChartSpec(code: String): ChartSpec? {
    val trimmed = code.trim()
    // 1) Strict JSON first.
    runCatching {
        val o = JSONObject(trimmed)
        return ChartSpec(
            type = o.optString("type", "bar").lowercase(),
            title = o.optString("title", ""),
            labels = o.optJSONArray("labels")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
            values = o.optJSONArray("values")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } }.orEmpty(),
        ).takeIf { it.values.isNotEmpty() }
    }
    // 2) Loose shorthand: {type:bar, labels:[a, b], values:[1, 2]}
    return runCatching {
        val body = trimmed.removePrefix("{").removeSuffix("}")
        val type = Regex("""type\s*:\s*["']?(\w+)""").find(body)?.groupValues?.get(1)?.lowercase() ?: "bar"
        val title = Regex("""title\s*:\s*["']([^"']*)["']""").find(body)?.groupValues?.get(1).orEmpty()
        fun listOf(key: String): List<String> =
            Regex(key + """\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(body)
                ?.groupValues?.get(1)?.split(",")
                ?.map { it.trim().trim('"', '\'') }
                ?.filter { it.isNotEmpty() }.orEmpty()
        val labels = listOf("labels")
        val values = listOf("values").map { it.toFloat() }
        ChartSpec(type, title, labels, values).takeIf { values.isNotEmpty() }
    }.getOrNull()
}

/** Renders a ```chart fence: native chart, or a raw fallback when the spec won't parse. */
@Composable
fun ChartCard(code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val spec = remember(code) { parseChartSpec(code) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MuseTones.surface)
            .border(1.dp, MuseTones.hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CHART",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (spec?.title?.isNotBlank() == true) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = spec.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            } else Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy chart data", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (spec == null) {
            Text(
                text = "Couldn't parse this chart spec — showing the raw data:",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = code, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppLogger.warning("DataCharts", "unparseable chart spec (${code.take(80)})")
        } else {
            ChartCanvas(spec)
            if (spec.type == "pie") PieLegend(spec)
        }
    }
}

@Composable
private fun ChartCanvas(spec: ChartSpec) {
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = onSurface.copy(alpha = 0.7f))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MuseTones.fill)
            .padding(8.dp),
    ) {
        when (spec.type) {
            "line" -> drawLineChart(spec, primary, grid, measurer, labelStyle)
            "pie" -> drawPieChart(spec, primary)
            else -> drawBarChart(spec, primary, grid, onSurface, measurer, labelStyle)
        }
    }
}

private val piePalette = listOf(
    Color(0xFF6D28D9), Color(0xFF0A84FF), UbColors.successDark, UbColors.warningDark,
    UbColors.errorDark, Color(0xFF64D2FF), Color(0xFFBF5AF2), Color(0xFFFFD60A),
)

private fun DrawScope.drawBarChart(
    spec: ChartSpec,
    accent: Color,
    grid: Color,
    onSurface: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle,
) {
    val n = spec.values.size
    if (n == 0) return
    val max = (spec.values.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
    val leftPad = 8f; val bottomPad = 34f; val topPad = 12f
    val plotW = size.width - leftPad * 2
    val plotH = size.height - topPad - bottomPad
    // gridlines
    repeat(4) { i ->
        val y = topPad + plotH * i / 3f
        drawLine(grid, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
    }
    val slot = plotW / n
    val barW = (slot * 0.62f).coerceAtMost(64f)
    spec.values.forEachIndexed { i, v ->
        val h = (v / max) * plotH
        val x = leftPad + slot * i + (slot - barW) / 2f
        drawRect(
            color = accent.copy(alpha = 0.85f),
            topLeft = Offset(x, topPad + plotH - h),
            size = Size(barW, h),
        )
        val label = spec.labels.getOrNull(i) ?: ""
        if (label.isNotEmpty()) {
            val layout = measurer.measure(AnnotatedString(label.take(8)), labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    (leftPad + slot * i + slot / 2f - layout.size.width / 2f)
                        .coerceIn(leftPad, leftPad + plotW - layout.size.width),
                    topPad + plotH + 8f,
                ),
            )
        }
    }
}

private fun DrawScope.drawLineChart(
    spec: ChartSpec,
    accent: Color,
    grid: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle,
) {
    val n = spec.values.size
    if (n == 0) return
    val max = (spec.values.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
    val min = (spec.values.minOrNull() ?: 0f).coerceAtMost(0f)
    val span = (max - min).coerceAtLeast(1e-6f)
    val leftPad = 8f; val bottomPad = 34f; val topPad = 12f
    val plotW = size.width - leftPad * 2
    val plotH = size.height - topPad - bottomPad
    repeat(4) { i ->
        val y = topPad + plotH * i / 3f
        drawLine(grid, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
    }
    fun pt(i: Int, v: Float): Offset {
        val x = if (n == 1) leftPad + plotW / 2f else leftPad + plotW * i / (n - 1)
        val y = topPad + plotH * (1f - (v - min) / span)
        return Offset(x, y)
    }
    val path = Path()
    spec.values.forEachIndexed { i, v ->
        val p = pt(i, v)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, accent, style = Stroke(width = 5f))
    spec.values.forEachIndexed { i, v ->
        val p = pt(i, v)
        drawCircle(accent, radius = 7f, center = p)
        drawCircle(Color.White, radius = 3f, center = p)
        if (n <= 12) {
            val label = spec.labels.getOrNull(i) ?: ""
            if (label.isNotEmpty()) {
                val layout = measurer.measure(AnnotatedString(label.take(8)), labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        (p.x - layout.size.width / 2f).coerceIn(leftPad, leftPad + plotW - layout.size.width),
                        topPad + plotH + 8f,
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawPieChart(spec: ChartSpec, accent: Color) {
    val total = spec.values.sum().coerceAtLeast(1e-6f)
    val diameter = size.width.coerceAtMost(size.height) * 0.92f
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    var start = -90f
    spec.values.forEachIndexed { i, v ->
        val sweep = 360f * (v / total)
        drawArc(
            color = piePalette[i % piePalette.size].let { if (i == 0) accent else it },
            startAngle = start,
            sweepAngle = sweep.coerceAtLeast(0.5f),
            useCenter = true,
            topLeft = topLeft,
            size = Size(diameter, diameter),
        )
        start += sweep
    }
}

@Composable
private fun PieLegend(spec: ChartSpec) {
    val total = spec.values.sum().coerceAtLeast(1e-6f)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        spec.values.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier.size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(piePalette[i % piePalette.size]),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = spec.labels.getOrNull(i) ?: "Slice ${i + 1}",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${((v / total) * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Analyze" affordance under CSV attachments. Tapping prefills the composer
 * with an analysis request; the agent does the work with its terminal tools
 * and answers with ```chart blocks rendered above. Rendered by
 * [UserAttachmentList] when any attached file ends in .csv.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CsvAnalyzeChips(fileNames: List<String>) {
    val csvNames = remember(fileNames) { fileNames.filter { it.lowercase().endsWith(".csv") } }
    if (csvNames.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        csvNames.forEach { name ->
            AssistChip(
                onClick = {
                    HomeBus.prefillComposer(
                        "Analyze the attached CSV \"$name\": load it with the terminal, " +
                            "give me the key stats and findings, and show the main " +
                            "numbers as ```chart blocks (bar/line/pie).",
                    )
                    AppLogger.info("DataCharts", "analyze requested for $name")
                },
                label = { Text("Analyze", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Outlined.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }
    }
}
