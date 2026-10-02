package ai.unicto.unibot.tools

import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

private fun creatorTitleOf(argsJson: String, fallback: String): String =
    JSONObject(argsJson).optString("tool_title", fallback)

private fun creatorParam(argsJson: String, key: String): String =
    JSONObject(argsJson).optString(key, "").trim()

/**
 * Creator tools — template-based generators for Abdullah's creator businesses
 * (YouTube channel + clipping operation). Pure on-device Kotlin, no network,
 * no accounts: they turn a topic/description into post-ready captions, hooks,
 * replies, titles, hashtags and scripts using curated viral-format banks.
 *
 * The agent calls these and then polishes the output in its own words; the
 * chat UI renders the structured result as an animated card (see
 * ui/chat/CreatorCards.kt). Result wire format:
 *
 *     CREATOR_CARD:<kind>
 *     TITLE:<card title>
 *     SCORE:<0-100>            (optimize_title only)
 *     ISSUE:<one-line issue>   (optimize_title only, repeatable)
 *     TAGS:#a #b #c            (hashtags only)
 *     ITEM:<Label> — <body>    (repeatable; label optional)
 */
object CreatorTools {

    const val CAPTIONS_NAME = "clip_captions"
    const val HOOKS_NAME = "clip_hooks"
    const val REPLY_NAME = "draft_reply"
    const val TITLE_NAME = "optimize_title"
    const val HASHTAGS_NAME = "hashtags"
    const val SCRIPT_NAME = "write_script"

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = CAPTIONS_NAME,
            description = "Generate 3 post-ready captions for a clip/video. Call when the user wants " +
                "captions, a description, or posting text for TikTok/YouTube Shorts/Instagram. " +
                "Returns 3 caption options with hooks and hashtags.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "video_description" to AgentToolParam("string", "What the clip/video is about (1-2 sentences)."),
            ),
            required = listOf("tool_title", "video_description"),
            propertyOrdering = listOf("tool_title", "video_description"),
        ),
        AgentToolDefinition(
            name = HOOKS_NAME,
            description = "Generate 5 viral hook lines for a clip about a topic. Call when the user " +
                "wants opening lines, hooks, or first-3-seconds text for a short-form video.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "topic" to AgentToolParam("string", "The clip topic."),
            ),
            required = listOf("tool_title", "topic"),
            propertyOrdering = listOf("tool_title", "topic"),
        ),
        AgentToolDefinition(
            name = REPLY_NAME,
            description = "Draft 3 reply options to a YouTube/TikTok comment in the requested tone. " +
                "Call when the user pastes a comment and wants help replying.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "comment_text" to AgentToolParam("string", "The comment to reply to."),
                "tone" to AgentToolParam("string", "One of: funny, supportive, short."),
            ),
            required = listOf("tool_title", "comment_text"),
            propertyOrdering = listOf("tool_title", "comment_text", "tone"),
        ),
        AgentToolDefinition(
            name = TITLE_NAME,
            description = "Score a video title 0-100 (length, keyword front-loading, curiosity, " +
                "numbers, formatting) and return 3 rewritten options. Call when the user wants " +
                "a better title or asks if a title is good.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "title" to AgentToolParam("string", "The video title to analyze."),
            ),
            required = listOf("tool_title", "title"),
            propertyOrdering = listOf("tool_title", "title"),
        ),
        AgentToolDefinition(
            name = HASHTAGS_NAME,
            description = "Generate a platform-aware hashtag set for a topic. Call when the user " +
                "wants hashtags/tags for a post. Platforms: youtube, tiktok, instagram.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "topic" to AgentToolParam("string", "The post topic."),
                "platform" to AgentToolParam("string", "One of: youtube, tiktok, instagram (default tiktok)."),
            ),
            required = listOf("tool_title", "topic"),
            propertyOrdering = listOf("tool_title", "topic", "platform"),
        ),
        AgentToolDefinition(
            name = SCRIPT_NAME,
            description = "Write a short-form video script: hook + 3-act structure + CTA. Call when " +
                "the user wants a script/outline for a clip. Duration like \"30s\", \"60s\", \"5min\".",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "topic" to AgentToolParam("string", "The video topic."),
                "duration" to AgentToolParam("string", "Target length, e.g. 30s, 60s, 5min (default 60s)."),
            ),
            required = listOf("tool_title", "topic"),
            propertyOrdering = listOf("tool_title", "topic", "duration"),
        ),
    )

    // ─── caption banks ────────────────────────────────────────────────────

    private fun pickIndex(size: Int, seed: Int, salt: Int): Int {
        val h = (seed + salt).let { if (it == Int.MIN_VALUE) 0 else it }
        return (if (h < 0) -h else h) % size
    }

    private fun <T> pickOne(list: List<T>, seed: Int, salt: Int): T =
        list[pickIndex(list.size, seed, salt)]

    private val captionHooks = listOf(
        "Nobody is talking about this 👀",
        "This changed everything for me",
        "Wait for the end 😳",
        "POV: you finally get it",
        "Stop scrolling — watch this",
    )

    private val captionCtas = listOf(
        "Follow for more 👇",
        "Save this for later 📌",
        "Tag someone who needs this 👇",
        "Comment your thoughts 💬",
    )

    suspend fun executeCaptions(argsJson: String): ToolExecutionResult {
        val title = creatorTitleOf(argsJson, CAPTIONS_NAME)
        val desc = creatorParam(argsJson, "video_description")
        if (desc.isEmpty()) return ToolExecutionResult("Error: 'video_description' is required", false, toolTitle = title)
        val topic = desc.take(60)
        val tags = topicTags(topic).joinToString(" ")
        // Single-line ITEMs: the card parser treats each line independently,
        // so the hook, body, CTA and hashtags all live on one line.
        val out = buildString {
            appendLine("CREATOR_CARD:clip_captions")
            appendLine("TITLE:3 captions ready to post")
            appendLine("ITEM:${pickOne(captionHooks, desc.hashCode(), 1)} — $desc ${pickOne(captionCtas, desc.hashCode(), 2)} $tags")
            appendLine("ITEM:Engagement — $desc …agree or disagree? 👇 ${pickOne(captionCtas, desc.hashCode(), 5)}")
            appendLine("ITEM:Punchy — ${desc.replaceFirstChar { it.uppercase() }}. That's it. That's the post. 🔥 $tags")
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }

    // ─── hook banks ───────────────────────────────────────────────────────

    suspend fun executeHooks(argsJson: String): ToolExecutionResult {
        val title = creatorTitleOf(argsJson, HOOKS_NAME)
        val topic = creatorParam(argsJson, "topic")
        if (topic.isEmpty()) return ToolExecutionResult("Error: 'topic' is required", false, toolTitle = title)
        val templates = listOf(
            "Nobody talks about $topic like this…",
            "I tried $topic for 30 days — here's what happened",
            "Stop doing $topic wrong ❌ do THIS instead",
            "The $topic secret nobody wants you to know",
            "$topic in 15 seconds — save this 📌",
            "3 $topic mistakes killing your growth",
            "This $topic trick blew up my views overnight",
        )
        val start = pickIndex(templates.size, topic.hashCode(), 0)
        val out = buildString {
            appendLine("CREATOR_CARD:clip_hooks")
            appendLine("TITLE:5 viral hooks")
            repeat(5) { i -> appendLine("ITEM:${templates[(start + i) % templates.size]}") }
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }

    // ─── reply banks ──────────────────────────────────────────────────────

    private val funnyReplies = listOf(
        "Certified banger comment 🏆 this one's going in the hall of fame",
        "Bro really said it better than the video 😂",
        "Adding 'hired as my scriptwriter' to your resume as we speak",
    )
    private val supportiveReplies = listOf(
        "Thank you so much! Comments like this keep me going 🙏",
        "Really appreciate you watching — more coming soon!",
        "Glad it helped! Let me know what you want covered next ❤️",
    )
    private val shortReplies = listOf(
        "Facts 🔥",
        "Appreciate you! 🙏",
        "Real one 💯",
    )

    suspend fun executeReply(argsJson: String): ToolExecutionResult {
        val title = creatorTitleOf(argsJson, REPLY_NAME)
        val comment = creatorParam(argsJson, "comment_text")
        if (comment.isEmpty()) return ToolExecutionResult("Error: 'comment_text' is required", false, toolTitle = title)
        val tone = creatorParam(argsJson, "tone").lowercase().ifEmpty { "funny" }
        val bank = when (tone) {
            "supportive" -> supportiveReplies
            "short" -> shortReplies
            else -> funnyReplies
        }
        val label = tone.replaceFirstChar { it.uppercase() }
        val out = buildString {
            appendLine("CREATOR_CARD:draft_reply")
            appendLine("TITLE:3 reply drafts ($label)")
            bank.forEachIndexed { i, r -> appendLine("ITEM:Option ${i + 1} — $r") }
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }

    // ─── title scoring ────────────────────────────────────────────────────

    private val powerWords = listOf(
        "secret", "never", "shocking", "why", "how", "free", "best", "worst",
        "truth", "mistake", "insane", "ultimate", "proven", "stopped", "nobody",
    )

    suspend fun executeTitle(argsJson: String): ToolExecutionResult {
        val title = creatorTitleOf(argsJson, TITLE_NAME)
        val raw = creatorParam(argsJson, "title")
        if (raw.isEmpty()) return ToolExecutionResult("Error: 'title' is required", false, toolTitle = title)

        var score = 50
        val issues = mutableListOf<String>()
        val lower = raw.lowercase()

        // Length: 40-60 chars is the sweet spot for YouTube.
        when {
            raw.length in 40..60 -> score += 12
            raw.length < 30 -> { score -= 12; issues += "Only ${raw.length} characters — aim for 40-60 so it carries keywords." }
            raw.length > 70 -> { score -= 12; issues += "${raw.length} characters will truncate in search — trim under 60." }
            else -> score += 4
        }
        // Curiosity / power words.
        val hits = powerWords.filter { lower.contains(it) }
        if (hits.isNotEmpty()) score += 10 else { score -= 6; issues += "No curiosity trigger — add a word like \"why\", \"secret\" or \"never\"." }
        // Number present.
        if (raw.any { it.isDigit() }) score += 8 else { score -= 4; issues += "Numbers lift click-through (\"3 mistakes\" beats \"mistakes\")." }
        // Question?
        if ('?' in raw) score += 6
        // Keyword front-loading: first 4 words should carry substance (not filler).
        val firstWords = lower.split(" ").take(4)
        val filler = setOf("the", "a", "an", "my", "this", "that", "just", "so", "i")
        if (firstWords.all { it in filler }) { score -= 8; issues += "Front-load the keyword — don't open with filler words." }
        else score += 8
        // ALL CAPS / emoji spam.
        if (raw.count { it.isUpperCase() } > raw.length / 2) { score -= 8; issues += "ALL CAPS reads as spam — use it on 1-2 words max." }
        val emojiCount = raw.count { it.code > 0x2500 }
        if (emojiCount > 3) { score -= 6; issues += "$emojiCount emojis is clutter — 1-2 max." }
        else if (emojiCount in 1..2) score += 4

        score = score.coerceIn(5, 98)
        if (issues.isEmpty()) issues += "Solid title — the rewrites below push it further."

        val keyword = raw.split(" ").firstOrNull { it.length > 3 && it.lowercase() !in filler }
            ?.replaceFirstChar { it.uppercase() } ?: "This"
        val short = raw.take(48).trimEnd()
        val out = buildString {
            appendLine("CREATOR_CARD:optimize_title")
            appendLine("TITLE:Title score")
            appendLine("SCORE:$score")
            issues.take(4).forEach { appendLine("ISSUE:$it") }
            appendLine("ITEM:Keyword-first — $keyword: ${raw.replaceFirst(keyword, "", ignoreCase = true).trim().trimStart(':', '-', '—').take(45)}".trimEnd(' ', ':', '-', '—'))
            appendLine("ITEM:Curiosity — Why $keyword ${if ('?' in raw) "actually works" else "changes everything?"}")
            appendLine("ITEM:Short & punchy — $short 🔥")
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }

    // ─── hashtags ─────────────────────────────────────────────────────────

    private val platformPools = mapOf(
        "youtube" to listOf("#youtube", "#youtubeshorts", "#shorts", "#viralvideo", "#trending"),
        "tiktok" to listOf("#fyp", "#foryou", "#viral", "#tiktok", "#trending"),
        "instagram" to listOf("#reels", "#reelsinstagram", "#explore", "#explorepage", "#instagood"),
    )

    private fun topicTags(topic: String): List<String> {
        val words = topic.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 3 }
            .distinct()
            .take(4)
        return words.map { "#$it" }
    }

    suspend fun executeHashtags(argsJson: String): ToolExecutionResult {
        val title = creatorTitleOf(argsJson, HASHTAGS_NAME)
        val topic = creatorParam(argsJson, "topic")
        if (topic.isEmpty()) return ToolExecutionResult("Error: 'topic' is required", false, toolTitle = title)
        val platform = creatorParam(argsJson, "platform").lowercase().ifEmpty { "tiktok" }
        val pool = platformPools[platform] ?: platformPools["tiktok"]!!
        val tags = (topicTags(topic) + pool).distinct().let {
            when (platform) {
                "youtube" -> it.take(5)
                "instagram" -> it.take(10)
                else -> it.take(6)
            }
        }
        val out = buildString {
            appendLine("CREATOR_CARD:hashtags")
            appendLine("TITLE:Hashtags for $platform")
            appendLine("TAGS:${tags.joinToString(" ")}")
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }

    // ─── script writer ────────────────────────────────────────────────────

    suspend fun executeScript(argsJson: String): ToolExecutionResult {
        val title = creatorTitleOf(argsJson, SCRIPT_NAME)
        val topic = creatorParam(argsJson, "topic")
        if (topic.isEmpty()) return ToolExecutionResult("Error: 'topic' is required", false, toolTitle = title)
        val duration = creatorParam(argsJson, "duration").ifEmpty { "60s" }
        val hook = listOf(
            "Nobody talks about $topic like this…",
            "Stop doing $topic wrong — watch this",
            "$topic in ${duration.trim()} — here's everything",
        )[pickIndex(3, topic.hashCode(), 0)]
        val out = buildString {
            appendLine("CREATOR_CARD:write_script")
            appendLine("TITLE:$duration script: ${topic.take(40)}")
            appendLine("ITEM:HOOK (0-3s) — $hook")
            appendLine("ITEM:ACT 1 — SETUP — Frame the problem: why $topic matters right now, in one line.")
            appendLine("ITEM:ACT 2 — PAYOFF — Deliver the core value: the 2-3 things about $topic worth knowing.")
            appendLine("ITEM:ACT 3 — TWIST — End with the unexpected angle or result on $topic.")
            appendLine("ITEM:CTA — \"Follow for more on $topic 👇\" — say it, don't just caption it.")
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }
}
