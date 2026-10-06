package ai.unicto.unibot.ui.swarm

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.print.PrintAttributes
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import ai.unicto.unibot.swarm.SwarmAgentState
import ai.unicto.unibot.swarm.SwarmMissionRecord
import ai.unicto.unibot.swarm.SwarmUiState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.4.0 items 5+8 — mission report and agent transcript export.
 *
 * The Markdown builders are pure JVM (unit-tested without Robolectric);
 * the file/share/PDF functions need a Context and live in the same file
 * so the whole export surface is in one place. Reports are written under
 * `cacheDir/shared/` (already declared in file_provider_paths.xml — the
 * chat exporter's zip flow uses the same directory) and handed to the
 * Android share sheet via FileProvider, so no storage permission is needed.
 */

// ─── Pure Markdown builders ─────────────────────────────────────────────────

private val EXPORT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/**
 * Builds the full mission report as Markdown: mission, crew, plan, the
 * stitched result, per-agent results with token costs, steering notes,
 * and attachments. Used for both the .md share and as the PDF's source
 * text.
 */
fun buildMissionReportMarkdown(state: SwarmUiState): String = buildString {
    appendLine("# Swarm mission report")
    appendLine()
    appendLine("_Exported ${EXPORT_DATE_FORMAT.format(Date())}_")
    appendLine()
    appendLine("## Mission")
    appendLine()
    appendLine(state.mission.ifBlank { "(no mission text)" })
    appendLine()
    val crew = state.crew
    if (crew != null) {
        appendLine("## Crew: ${crew.name}")
        appendLine()
        appendLine(crew.roles.joinToString(", ") { roleDisplayName(it) })
        appendLine()
    }
    val plan = state.proposedPlan
    if (plan.isNotEmpty()) {
        appendLine("## Plan")
        appendLine()
        plan.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
        appendLine()
    }
    if (state.stitchedResult.isNotBlank()) {
        appendLine("## Result")
        appendLine()
        appendLine(state.stitchedResult.trim())
        appendLine()
    }
    if (state.agents.isNotEmpty()) {
        appendLine("## Agent results")
        appendLine()
        state.agents.forEach { agent ->
            appendLine("### ${agentCodename(agent.id)} — ${roleDisplayName(agent.role)}")
            appendLine()
            appendLine("_Status: ${agent.status.label()} · ${formatTokens(agent.tokensUsed)} tokens_")
            appendLine()
            val clean = withoutIncompleteMarker(agent.result).ifBlank { "(no output)" }
            appendLine(clean)
            extractIncompleteNote(agent.result, agent.detail)?.let { note ->
                appendLine()
                appendLine("> Incomplete: $note")
            }
            appendLine()
        }
    }
    if (state.steeringNotes.isNotEmpty()) {
        appendLine("## Steering notes")
        appendLine()
        state.steeringNotes.forEachIndexed { i, note -> appendLine("${i + 1}. $note") }
        appendLine()
    }
    if (state.attachments.isNotEmpty()) {
        appendLine("## Attachments")
        appendLine()
        state.attachments.forEach { a -> appendLine("- ${a.name} (${a.text.length} chars)") }
        appendLine()
    }
    appendLine("## Cost")
    appendLine()
    appendLine("Total: ${formatTokens(state.totalTokens)} tokens — \$0 spent (your keys, your free tier).")
}.trimEnd() + "\n"

/**
 * Builds one agent's full transcript as Markdown (v1.4.0 item 8): the
 * step-by-step log plus the complete result — not just the summary the
 * card shows.
 */
fun buildAgentTranscriptMarkdown(state: SwarmUiState, agent: SwarmAgentState): String =
    buildString {
        appendLine("# Agent transcript — ${agentCodename(agent.id)} (${roleDisplayName(agent.role)})")
        appendLine()
        appendLine("_Mission: ${state.mission.take(120).ifBlank { "(no mission text)" }}_")
        appendLine()
        appendLine("_Status: ${agent.status.label()} · ${formatTokens(agent.tokensUsed)} tokens_")
        appendLine()
        if (agent.log.isNotEmpty()) {
            appendLine("## Step log")
            appendLine()
            agent.log.forEach { entry -> appendLine("- $entry") }
            appendLine()
        }
        appendLine("## Result")
        appendLine()
        appendLine(withoutIncompleteMarker(agent.result).ifBlank { "(no output)" })
        extractIncompleteNote(agent.result, agent.detail)?.let { note ->
            appendLine()
            appendLine("> Incomplete: $note")
        }
    }.trimEnd() + "\n"

/** One-line summary of a history record, for the share-sheet title. */
internal fun historyRecordTitle(record: SwarmMissionRecord): String =
    record.mission.take(60).ifBlank { "Swarm mission" }

// ─── File + share-sheet plumbing (needs Context) ────────────────────────────

private fun sharedDir(context: Context): File =
    File(context.cacheDir, "shared").apply { mkdirs() }

private fun reportFileName(state: SwarmUiState, extension: String): String {
    val slug = state.mission.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(30)
        .ifBlank { "mission" }
    return "swarm-report-$slug-${System.currentTimeMillis()}.$extension"
}

private fun contentUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Writes the mission report as a Markdown file and returns its shareable URI. */
fun exportMissionMarkdown(context: Context, state: SwarmUiState): Uri {
    val file = File(sharedDir(context), reportFileName(state, "md"))
    file.writeText(buildMissionReportMarkdown(state))
    return contentUri(context, file)
}

/** Writes one agent's transcript as a Markdown file and returns its shareable URI. */
fun exportAgentTranscript(context: Context, state: SwarmUiState, agent: SwarmAgentState): Uri {
    val file = File(
        sharedDir(context),
        "swarm-transcript-${agentCodename(agent.id).lowercase(Locale.US)}-${System.currentTimeMillis()}.md",
    )
    file.writeText(buildAgentTranscriptMarkdown(state, agent))
    return contentUri(context, file)
}

/**
 * Renders the mission report as a PDF via PrintedPdfDocument (no extra
 * dependencies — the Markdown source is laid out as plain text). Returns
 * the shareable URI.
 */
fun exportMissionPdf(context: Context, state: SwarmUiState): Uri {
    val text = buildMissionReportMarkdown(state)
    val printAttrs = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    val document = PrintedPdfDocument(context, printAttrs)
    val pageWidth = 595 // A4 @ 72dpi
    val pageHeight = 842
    val margin = 48
    val paint = TextPaint().apply {
        color = 0xFF1A1A1A.toInt()
        textSize = 11f
        typeface = Typeface.MONOSPACE
    }
    val usableWidth = pageWidth - margin * 2
    val usableHeight = pageHeight - margin * 2
    // StaticLayout pagination: lay out the whole text once, then draw each
    // page's line range. Builder for API 23+, deprecated constructor below
    // it (our minSdk is 26, so the Builder path always applies).
    val layout: Layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        StaticLayout.Builder.obtain(text, 0, text.length, paint, usableWidth)
            .setLineSpacing(4f, 1f)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(text, paint, usableWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 4f, false)
    }
    var firstLine = 0
    val lineCount = layout.lineCount
    while (firstLine < lineCount) {
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
            pageWidth, pageHeight, document.pages.size + 1,
        ).build()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(0xFFFFFFFF.toInt())
        canvas.save()
        canvas.translate(margin.toFloat(), margin.toFloat())
        // Find how many lines fit on this page.
        var lastLine = firstLine
        while (lastLine < lineCount &&
            layout.getLineBottom(lastLine) - layout.getLineTop(firstLine) <= usableHeight
        ) {
            lastLine++
        }
        if (lastLine == firstLine) lastLine = firstLine + 1 // never stall on one tall line
        canvas.translate(0f, -layout.getLineTop(firstLine).toFloat())
        // Clip to this page's lines: draw the layout, then the next page
        // continues from lastLine.
        canvas.save()
        canvas.clipRect(
            0f,
            layout.getLineTop(firstLine).toFloat(),
            usableWidth.toFloat(),
            layout.getLineBottom((lastLine - 1).coerceAtLeast(firstLine)).toFloat(),
        )
        layout.draw(canvas)
        canvas.restore()
        canvas.restore()
        document.finishPage(page)
        firstLine = lastLine
    }
    val file = File(sharedDir(context), reportFileName(state, "pdf"))
    FileOutputStream(file).use { out -> document.writeTo(out) }
    document.close()
    return contentUri(context, file)
}

/** Hands a file URI to the Android share sheet. */
fun shareFileUri(context: Context, uri: Uri, mimeType: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
