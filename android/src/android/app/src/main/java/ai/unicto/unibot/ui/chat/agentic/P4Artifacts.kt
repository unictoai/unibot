package ai.unicto.unibot.ui.chat.agentic

import org.json.JSONObject

/**
 * P4 artifact fences (v0.2.0).
 *
 * The model emits fenced code blocks with a `unibot-*` language tag; the
 * chat renderer ([P4ArtifactHost], hooked into MarkdownBlock) turns them
 * into rich cards instead of raw code. Only COMPLETE fences render — a
 * partial fence mid-stream keeps rendering as a code block until closed.
 *
 * Fences:
 * - ```unibot-card [k=v ...]\n<html>…</html>\n``` → sandboxed WebView card
 * - ```unibot-todo\n{…}\n``` → checklist card (live to-do list)
 * - ```unibot-research\n{…}\n``` → research progress card
 * - ```unibot-checkpoint\n{…}\n``` → checkpoint card with Resume/Dismiss
 */
object P4Artifacts {

    sealed interface Artifact {
        /** HTML card. Params from the fence line: js, net, height. */
        data class Card(val html: String, val params: Map<String, String>) : Artifact

        data class TodoItem(val title: String, val done: Boolean)

        /** Checklist. [runId] groups re-emissions of the same plan so only the latest renders expanded. */
        data class Todo(val title: String, val items: List<TodoItem>, val runId: String) : Artifact

        data class Source(val title: String, val url: String)

        /** Research progress. [runId] groups updates of the same question. */
        data class Research(val question: String, val step: String, val sources: List<Source>, val runId: String) : Artifact

        /** Pause point. kind ∈ login, paywall, approval, other. */
        data class Checkpoint(val kind: String, val title: String, val detail: String) : Artifact
    }

    /**
     * Returns the artifact when [rawText] is exactly one complete
     * ```unibot-* fence, else null. Cheap: two string scans, no regex.
     */
    fun detect(rawText: String): Artifact? {
        val t = rawText.trim()
        if (!t.startsWith("```unibot-")) return null
        // Complete fence only: the text must end with a closing ``` fence.
        // A partial fence mid-stream keeps rendering as a code block.
        if (!t.endsWith("```")) return null
        val firstNl = t.indexOf('\n')
        if (firstNl < 0) return null
        val tagLine = t.substring(3, firstNl).trim() // e.g. "unibot-card js=true"
        val body = t.substring(firstNl + 1, t.length - 3).trim()
        if (body.isEmpty()) return null
        val tag = tagLine.substringBefore(' ')
        val params = tagLine.substringAfter(' ', "")
            .split(' ').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) kv[0].lowercase() to kv[1] else null
            }.toMap()
        return runCatching {
            when (tag) {
                "unibot-card" -> Artifact.Card(html = body, params = params)
                "unibot-todo" -> parseTodo(body)
                "unibot-research" -> parseResearch(body)
                "unibot-checkpoint" -> parseCheckpoint(body)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseTodo(body: String): Artifact.Todo? {
        val o = JSONObject(body)
        val title = o.optString("title", "To-do").ifBlank { "To-do" }
        val arr = o.optJSONArray("items") ?: return null
        val items = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = it.optString("t", it.optString("title", "")).trim()
            if (text.isEmpty()) null else Artifact.TodoItem(text, it.optBoolean("done", false))
        }
        if (items.isEmpty()) return null
        return Artifact.Todo(title, items, runId = "todo:${title.hashCode()}")
    }

    private fun parseResearch(body: String): Artifact.Research? {
        val o = JSONObject(body)
        val question = o.optString("question", "").trim()
        if (question.isEmpty()) return null
        val step = o.optString("step", "").trim()
        val arr = o.optJSONArray("sources")
        val sources = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = s.optString("url", "").trim()
            if (url.isEmpty()) null
            else Artifact.Source(s.optString("title", url).trim().ifEmpty { url }, url)
        }
        return Artifact.Research(question, step, sources, runId = "research:${question.hashCode()}")
    }

    private fun parseCheckpoint(body: String): Artifact.Checkpoint? {
        val o = JSONObject(body)
        val title = o.optString("title", "").trim()
        if (title.isEmpty()) return null
        val kind = o.optString("kind", "other").trim().lowercase()
            .takeIf { it in setOf("login", "paywall", "approval", "other") } ?: "other"
        return Artifact.Checkpoint(kind, title, o.optString("detail", "").trim())
    }
}
