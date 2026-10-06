package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.discord.DiscordConnector
import ai.unicto.unibot.connectors.slack.SlackConnector
import ai.unicto.unibot.connectors.youtube.YouTubeConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

private fun titleOf(argsJson: String, fallback: String): String =
    JSONObject(argsJson).optString("tool_title", fallback)

private fun param(argsJson: String, key: String): String =
    JSONObject(argsJson).optString(key, "").trim()

/** YouTube connector agent tools (Google OAuth, gated on connection). */
object YouTubeTool {

    const val SEARCH_NAME = "youtube_search"
    const val CHANNEL_NAME = "youtube_channel"
    const val VIDEO_NAME = "youtube_video"
    const val TRANSCRIPT_NAME = "youtube_transcript"
    const val WATCHLATER_LIST_NAME = "youtube_watchlater_list"
    const val WATCHLATER_ADD_NAME = "youtube_watchlater_add"
    const val WATCHLATER_REMOVE_NAME = "youtube_watchlater_remove"

    fun isConnected(context: Context): Boolean = YouTubeConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search public YouTube videos. Returns video id, title, channel and publish date.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Search query."),
                "max_results" to AgentToolParam("integer", "Max videos to return (default 5, max 15)."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "max_results"),
        ),
        AgentToolDefinition(
            name = CHANNEL_NAME,
            description = "Show the connected user's own YouTube channel stats (name, subscribers, video count, total views).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = VIDEO_NAME,
            description = "Get details and stats for one YouTube video (title, channel, views, likes).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "video_id" to AgentToolParam("string", "The YouTube video id (from youtube_search)."),
            ),
            required = listOf("tool_title", "video_id"),
            propertyOrdering = listOf("tool_title", "video_id"),
        ),
        AgentToolDefinition(
            name = TRANSCRIPT_NAME,
            description = "Get a YouTube video's caption transcript as plain text, for summarizing the video. Works without extra permissions; fails when the video has no captions.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "video_id" to AgentToolParam("string", "The YouTube video id (from youtube_search)."),
            ),
            required = listOf("tool_title", "video_id"),
            propertyOrdering = listOf("tool_title", "video_id"),
        ),
        AgentToolDefinition(
            name = WATCHLATER_LIST_NAME,
            description = "List the user's YouTube Watch Later videos (newest-added first).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "max_results" to AgentToolParam("integer", "Max videos to return (default 15, max 25)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "max_results"),
        ),
        AgentToolDefinition(
            name = WATCHLATER_ADD_NAME,
            description = "Add a YouTube video to the user's Watch Later list. Only add videos the user asked to save.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "video_id" to AgentToolParam("string", "The YouTube video id to save."),
            ),
            required = listOf("tool_title", "video_id"),
            propertyOrdering = listOf("tool_title", "video_id"),
        ),
        AgentToolDefinition(
            name = WATCHLATER_REMOVE_NAME,
            description = "Remove a video from the user's Watch Later list. Only remove when the user explicitly asked.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "playlist_item_id" to AgentToolParam("string", "The playlist item id from youtube_watchlater_list."),
            ),
            required = listOf("tool_title", "playlist_item_id"),
            propertyOrdering = listOf("tool_title", "playlist_item_id"),
        ),
    )

    suspend fun executeSearch(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, SEARCH_NAME)
        val q = param(argsJson, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = title)
        val max = JSONObject(argsJson).optInt("max_results", 5)
        return when (val r = YouTubeConnector.search(context, q, max)) {
            is YouTubeConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No videos found.", true, toolTitle = title)
                val out = buildString {
                    appendLine("YouTube results:")
                    r.value.forEach {
                        appendLine("- ${it.title} (id: ${it.id}, ${it.channel}, ${it.publishedAt})")
                    }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeChannel(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, CHANNEL_NAME)
        return when (val r = YouTubeConnector.myChannel(context)) {
            is YouTubeConnector.ApiResult.Ok ->
                ToolExecutionResult(
                    "Channel: ${r.value.title}\nSubscribers: ${r.value.subscribers}\n" +
                        "Videos: ${r.value.videos}\nTotal views: ${r.value.views}",
                    true, toolTitle = title,
                )
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeVideo(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, VIDEO_NAME)
        val id = param(argsJson, "video_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'video_id' is required", false, toolTitle = title)
        return when (val r = YouTubeConnector.video(context, id)) {
            is YouTubeConnector.ApiResult.Ok ->
                ToolExecutionResult(
                    "${r.value.title}\nChannel: ${r.value.channel}\nPublished: ${r.value.publishedAt}\n" +
                        "Views: ${r.value.views}\nLikes: ${r.value.likes}\n" +
                        "https://www.youtube.com/watch?v=${r.value.id}",
                    true, toolTitle = title,
                )
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeTranscript(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, TRANSCRIPT_NAME)
        val id = param(argsJson, "video_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'video_id' is required", false, toolTitle = title)
        return when (val r = YouTubeConnector.transcript(context, id)) {
            is YouTubeConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = title)
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeWatchLaterList(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, WATCHLATER_LIST_NAME)
        val max = JSONObject(argsJson).optInt("max_results", 15)
        return when (val r = YouTubeConnector.watchLaterList(context, max)) {
            is YouTubeConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) {
                    ToolExecutionResult("Watch Later is empty.", true, toolTitle = title)
                } else {
                    val out = buildString {
                        appendLine("Watch Later (${r.value.size}):")
                        r.value.forEach {
                            appendLine("- ${it.title} (${it.channel})")
                            appendLine("  video id: ${it.videoId} | item id: ${it.playlistItemId}")
                        }
                    }
                    ToolExecutionResult(out, true, toolTitle = title)
                }
            }
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeWatchLaterAdd(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, WATCHLATER_ADD_NAME)
        val id = param(argsJson, "video_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'video_id' is required", false, toolTitle = title)
        return when (val r = YouTubeConnector.watchLaterAdd(context, id)) {
            is YouTubeConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = title)
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeWatchLaterRemove(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, WATCHLATER_REMOVE_NAME)
        val id = param(argsJson, "playlist_item_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'playlist_item_id' is required", false, toolTitle = title)
        return when (val r = YouTubeConnector.watchLaterRemove(context, id)) {
            is YouTubeConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = title)
            is YouTubeConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is YouTubeConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}

/** Discord connector agent tools (bot token, gated on connection). */
object DiscordTool {

    const val READ_NAME = "discord_read"
    const val SEND_NAME = "discord_send"

    fun isConnected(context: Context): Boolean = DiscordConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read recent messages from a Discord channel the bot can see.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "channel_id" to AgentToolParam("string", "The Discord channel id."),
                "limit" to AgentToolParam("integer", "How many recent messages to show (default 10)."),
            ),
            required = listOf("tool_title", "channel_id"),
            propertyOrdering = listOf("tool_title", "channel_id", "limit"),
        ),
        AgentToolDefinition(
            name = SEND_NAME,
            description = "Send a message to a Discord channel. Only send when the user explicitly asked for it.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "channel_id" to AgentToolParam("string", "The Discord channel id."),
                "text" to AgentToolParam("string", "Message text."),
            ),
            required = listOf("tool_title", "channel_id", "text"),
            propertyOrdering = listOf("tool_title", "channel_id", "text"),
        ),
    )

    suspend fun executeRead(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, READ_NAME)
        val ch = param(argsJson, "channel_id")
        if (ch.isEmpty()) return ToolExecutionResult("Error: 'channel_id' is required", false, toolTitle = title)
        val limit = JSONObject(argsJson).optInt("limit", 10)
        return when (val r = DiscordConnector.readChannel(context, ch, limit)) {
            is DiscordConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No messages in that channel.", true, toolTitle = title)
                val out = buildString {
                    appendLine("Recent Discord messages:")
                    r.value.forEach { appendLine("- ${it.author} [${it.timestamp}]: ${it.text}") }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is DiscordConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is DiscordConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeSend(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, SEND_NAME)
        val ch = param(argsJson, "channel_id")
        val text = param(argsJson, "text")
        if (ch.isEmpty() || text.isEmpty()) {
            return ToolExecutionResult("Error: 'channel_id' and 'text' are required", false, toolTitle = title)
        }
        return when (val r = DiscordConnector.send(context, ch, text)) {
            is DiscordConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is DiscordConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is DiscordConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}

/** Slack connector agent tools (bot token, gated on connection). */
object SlackTool {

    const val READ_NAME = "slack_read"
    const val SEND_NAME = "slack_send"

    fun isConnected(context: Context): Boolean = SlackConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read recent messages from a Slack channel (use the channel id, e.g. C0123456).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "channel_id" to AgentToolParam("string", "The Slack channel id."),
                "limit" to AgentToolParam("integer", "How many recent messages to show (default 10)."),
            ),
            required = listOf("tool_title", "channel_id"),
            propertyOrdering = listOf("tool_title", "channel_id", "limit"),
        ),
        AgentToolDefinition(
            name = SEND_NAME,
            description = "Send a message to a Slack channel. Only send when the user explicitly asked for it.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "channel_id" to AgentToolParam("string", "The Slack channel id."),
                "text" to AgentToolParam("string", "Message text."),
            ),
            required = listOf("tool_title", "channel_id", "text"),
            propertyOrdering = listOf("tool_title", "channel_id", "text"),
        ),
    )

    suspend fun executeRead(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, READ_NAME)
        val ch = param(argsJson, "channel_id")
        if (ch.isEmpty()) return ToolExecutionResult("Error: 'channel_id' is required", false, toolTitle = title)
        val limit = JSONObject(argsJson).optInt("limit", 10)
        return when (val r = SlackConnector.readChannel(context, ch, limit)) {
            is SlackConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No messages in that channel.", true, toolTitle = title)
                val out = buildString {
                    appendLine("Recent Slack messages:")
                    r.value.forEach { appendLine("- ${it.user}: ${it.text}") }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is SlackConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is SlackConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeSend(argsJson: String, context: Context): ToolExecutionResult {
        val title = titleOf(argsJson, SEND_NAME)
        val ch = param(argsJson, "channel_id")
        val text = param(argsJson, "text")
        if (ch.isEmpty() || text.isEmpty()) {
            return ToolExecutionResult("Error: 'channel_id' and 'text' are required", false, toolTitle = title)
        }
        return when (val r = SlackConnector.send(context, ch, text)) {
            is SlackConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is SlackConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is SlackConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}
