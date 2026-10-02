package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.notion.NotionConnector
import ai.unicto.unibot.connectors.reddit.RedditConnector
import ai.unicto.unibot.connectors.rss.RssConnector
import ai.unicto.unibot.connectors.spotify.SpotifyConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

private fun w2Title(argsJson: String, fallback: String): String =
    JSONObject(argsJson).optString("tool_title", fallback)

private fun w2Param(argsJson: String, key: String): String =
    JSONObject(argsJson).optString(key, "").trim()

/** Spotify connector agent tools (OAuth, gated on connection). */
object SpotifyTool {

    const val NOW_PLAYING_NAME = "spotify_now_playing"
    const val CONTROL_NAME = "spotify_control"
    const val PLAYLISTS_NAME = "spotify_playlists"

    fun isConnected(context: Context): Boolean = SpotifyConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = NOW_PLAYING_NAME,
            description = "What is currently playing on the user's Spotify (track, artists, playing/paused).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = CONTROL_NAME,
            description = "Control Spotify playback: play, pause, next, previous. Needs an active Spotify device (phone/desktop app open). Only control playback when the user explicitly asked.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "command" to AgentToolParam("string", "One of: play, pause, next, previous."),
            ),
            required = listOf("tool_title", "command"),
            propertyOrdering = listOf("tool_title", "command"),
        ),
        AgentToolDefinition(
            name = PLAYLISTS_NAME,
            description = "List the user's Spotify playlists with track counts.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
    )

    suspend fun executeNowPlaying(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, NOW_PLAYING_NAME)
        return when (val r = SpotifyConnector.nowPlaying(context)) {
            is SpotifyConnector.ApiResult.Ok -> {
                val t = r.value ?: return ToolExecutionResult("Nothing is playing on Spotify right now.", true, toolTitle = title)
                ToolExecutionResult(
                    "${if (t.isPlaying) "▶" else "⏸"} ${t.title} — ${t.artists} (${t.album})",
                    true, toolTitle = title,
                )
            }
            is SpotifyConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is SpotifyConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeControl(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, CONTROL_NAME)
        val cmd = w2Param(argsJson, "command")
        if (cmd.isEmpty()) return ToolExecutionResult("Error: 'command' is required", false, toolTitle = title)
        return when (val r = SpotifyConnector.control(context, cmd)) {
            is SpotifyConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is SpotifyConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is SpotifyConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executePlaylists(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, PLAYLISTS_NAME)
        return when (val r = SpotifyConnector.playlists(context)) {
            is SpotifyConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No Spotify playlists found.", true, toolTitle = title)
                val out = buildString {
                    appendLine("Spotify playlists:")
                    r.value.forEach { appendLine("- ${it.name} (${it.trackCount} tracks)") }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is SpotifyConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is SpotifyConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}

/** Notion connector agent tools (integration token, gated on connection). */
object NotionTool {

    const val SEARCH_NAME = "notion_search"
    const val READ_NAME = "notion_read"
    const val APPEND_NAME = "notion_append"

    fun isConnected(context: Context): Boolean = NotionConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search the user's Notion pages shared with the integration.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Search query."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query"),
        ),
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read a Notion page's content as text (page id from notion_search).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "page_id" to AgentToolParam("string", "The Notion page id."),
            ),
            required = listOf("tool_title", "page_id"),
            propertyOrdering = listOf("tool_title", "page_id"),
        ),
        AgentToolDefinition(
            name = APPEND_NAME,
            description = "Append a paragraph to a Notion page. Only write when the user explicitly asked to save something there.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "page_id" to AgentToolParam("string", "The Notion page id."),
                "text" to AgentToolParam("string", "Text to append."),
            ),
            required = listOf("tool_title", "page_id", "text"),
            propertyOrdering = listOf("tool_title", "page_id", "text"),
        ),
    )

    suspend fun executeSearch(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, SEARCH_NAME)
        val q = w2Param(argsJson, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = title)
        return when (val r = NotionConnector.search(context, q)) {
            is NotionConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No Notion pages matched.", true, toolTitle = title)
                val out = buildString {
                    appendLine("Notion pages:")
                    r.value.forEach { appendLine("- ${it.title} (id: ${it.id})") }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is NotionConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is NotionConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeRead(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, READ_NAME)
        val id = w2Param(argsJson, "page_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'page_id' is required", false, toolTitle = title)
        return when (val r = NotionConnector.readPage(context, id)) {
            is NotionConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is NotionConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is NotionConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeAppend(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, APPEND_NAME)
        val id = w2Param(argsJson, "page_id")
        val text = w2Param(argsJson, "text")
        if (id.isEmpty() || text.isEmpty()) {
            return ToolExecutionResult("Error: 'page_id' and 'text' are required", false, toolTitle = title)
        }
        return when (val r = NotionConnector.appendBlock(context, id, text)) {
            is NotionConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is NotionConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is NotionConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}

/**
 * Reddit agent tools (public read, no account — enabled by default in
 * Settings → Connectors).
 */
object RedditTool {

    const val SEARCH_NAME = "reddit_search"
    const val TOP_NAME = "reddit_top"

    fun isConnected(context: Context): Boolean = RedditConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search public Reddit posts. Returns title, subreddit, score, comment count and link.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Search query."),
                "limit" to AgentToolParam("integer", "How many posts to return (default 10, max 25)."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "limit"),
        ),
        AgentToolDefinition(
            name = TOP_NAME,
            description = "Top posts of a subreddit (hot now, or top of the day).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "subreddit" to AgentToolParam("string", "Subreddit name, e.g. android."),
                "sort" to AgentToolParam("string", "hot (default) or top."),
                "limit" to AgentToolParam("integer", "How many posts to return (default 10, max 25)."),
            ),
            required = listOf("tool_title", "subreddit"),
            propertyOrdering = listOf("tool_title", "subreddit", "sort", "limit"),
        ),
    )

    private fun formatPosts(posts: List<RedditConnector.Post>): String = buildString {
        posts.forEach {
            appendLine("- ${it.title}")
            appendLine("  r/${it.subreddit} · ⬆ ${it.score} · 💬 ${it.comments}")
            if (it.url.isNotBlank()) appendLine("  ${it.url}")
        }
    }

    suspend fun executeSearch(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = w2Title(argsJson, SEARCH_NAME)
        val q = w2Param(argsJson, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = title)
        return when (val r = RedditConnector.search(context, q, args.optInt("limit", 10))) {
            is RedditConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No Reddit posts found.", true, toolTitle = title)
                ToolExecutionResult("Reddit results:\n" + formatPosts(r.value), true, toolTitle = title)
            }
            is RedditConnector.ApiResult.Disabled ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is RedditConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeTop(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = w2Title(argsJson, TOP_NAME)
        val sub = w2Param(argsJson, "subreddit")
        if (sub.isEmpty()) return ToolExecutionResult("Error: 'subreddit' is required", false, toolTitle = title)
        return when (val r = RedditConnector.top(context, sub, w2Param(argsJson, "sort").ifBlank { "hot" }, args.optInt("limit", 10))) {
            is RedditConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No posts in r/$sub.", true, toolTitle = title)
                ToolExecutionResult("Top of r/$sub:\n" + formatPosts(r.value), true, toolTitle = title)
            }
            is RedditConnector.ApiResult.Disabled ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is RedditConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}

/** RSS feeds agent tools (no account — feeds managed in Settings → Connectors). */
object RssTool {

    const val FEEDS_NAME = "rss_feeds"
    const val LATEST_NAME = "rss_latest"

    fun isConnected(context: Context): Boolean = RssConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = FEEDS_NAME,
            description = "List the user's RSS feeds (add more in Settings → Connectors → RSS feeds).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = LATEST_NAME,
            description = "Latest headlines from the user's RSS feeds (or one specific feed URL).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "feed_url" to AgentToolParam("string", "Specific feed URL (optional — omit for all feeds)."),
                "limit" to AgentToolParam("integer", "How many headlines (default 10, max 30)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "feed_url", "limit"),
        ),
    )

    suspend fun executeFeeds(argsJson: String, context: Context): ToolExecutionResult {
        val title = w2Title(argsJson, FEEDS_NAME)
        val feeds = RssConnector.feeds(context)
        if (feeds.isEmpty()) {
            return ToolExecutionResult(
                "No RSS feeds added yet. The user can add some in Settings → Connectors → RSS feeds.",
                true, toolTitle = title,
            )
        }
        val out = buildString {
            appendLine("RSS feeds:")
            feeds.forEachIndexed { i, f -> appendLine("${i + 1}. $f") }
        }
        return ToolExecutionResult(out, true, toolTitle = title)
    }

    suspend fun executeLatest(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = w2Title(argsJson, LATEST_NAME)
        val url = w2Param(argsJson, "feed_url").ifBlank { null }
        return when (val r = RssConnector.latest(context, url, args.optInt("limit", 10))) {
            is RssConnector.ApiResult.Ok -> {
                val out = buildString {
                    appendLine("Latest headlines:")
                    r.value.forEach {
                        appendLine("- ${it.title}")
                        if (it.link.isNotBlank()) appendLine("  ${it.link}")
                    }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is RssConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}
