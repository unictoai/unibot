package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.currency.CurrencyConnector
import ai.unicto.unibot.connectors.dictionary.DictionaryConnector
import ai.unicto.unibot.connectors.dropbox.DropboxConnector
import ai.unicto.unibot.connectors.gitlab.GitLabConnector
import ai.unicto.unibot.connectors.gnews.GNewsConnector
import ai.unicto.unibot.connectors.gtasks.GTasksConnector
import ai.unicto.unibot.connectors.hn.HnConnector
import ai.unicto.unibot.connectors.onedrive.OneDriveConnector
import ai.unicto.unibot.connectors.qr.QrConnector
import ai.unicto.unibot.connectors.stackoverflow.StackOverflowConnector
import ai.unicto.unibot.connectors.tmdb.TmdbConnector
import ai.unicto.unibot.connectors.todoist.TodoistConnector
import ai.unicto.unibot.connectors.translate.TranslateConnector
import ai.unicto.unibot.connectors.trello.TrelloConnector
import ai.unicto.unibot.connectors.weather.WeatherConnector
import ai.unicto.unibot.connectors.wiki.WikiConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

// [v1.0-wave7] Connector mega-batch agent tools. Same shape as Wave2ConnectorTools.

private fun w7Title(argsJson: String, fallback: String): String =
    JSONObject(argsJson).optString("tool_title", fallback)

private fun w7Param(argsJson: String, key: String): String =
    JSONObject(argsJson).optString(key, "").trim()

private fun w7Int(argsJson: String, key: String, def: Int): Int {
    val v = JSONObject(argsJson).optInt(key, def)
    return v
}

private const val TITLE_DESC = "A concise 5-10 word summary of what this tool call does, shown to the user."

/** Trello (API key + token, stored as key:token). */
object TrelloTool {
    const val BOARDS = "trello_boards"
    const val LISTS = "trello_lists"
    const val ADD_CARD = "trello_add_card"

    fun isConnected(c: Context): Boolean = TrelloConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(BOARDS, "List the user's Trello boards.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC)),
            listOf("tool_title"), listOf("tool_title")),
        AgentToolDefinition(LISTS, "List the lists of a Trello board.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "board_id" to AgentToolParam("string", "Board id from trello_boards.")),
            listOf("tool_title", "board_id"), listOf("tool_title", "board_id")),
        AgentToolDefinition(ADD_CARD, "Add a card to a Trello list. Only add when the user explicitly asked.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "list_id" to AgentToolParam("string", "List id from trello_lists."),
                "name" to AgentToolParam("string", "Card title."),
                "desc" to AgentToolParam("string", "Card description (optional).")),
            listOf("tool_title", "list_id", "name"), listOf("tool_title", "list_id", "name", "desc")),
    )

    suspend fun executeBoards(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, BOARDS)
        return when (val r = TrelloConnector.boards(c)) {
            is TrelloConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No Trello boards found."
                else "Trello boards:\n" + r.value.joinToString("\n") { "- ${it.name} (id: ${it.id})" },
                true, toolTitle = t)
            is TrelloConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TrelloConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeLists(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, LISTS); val id = w7Param(a, "board_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'board_id' is required", false, toolTitle = t)
        return when (val r = TrelloConnector.lists(c, id)) {
            is TrelloConnector.ApiResult.Ok -> ToolExecutionResult(
                "Lists:\n" + r.value.joinToString("\n") { "- ${it.name} (id: ${it.id})" },
                true, toolTitle = t)
            is TrelloConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TrelloConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeAddCard(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, ADD_CARD)
        val id = w7Param(a, "list_id"); val name = w7Param(a, "name"); val desc = w7Param(a, "desc")
        if (id.isEmpty() || name.isEmpty()) return ToolExecutionResult("Error: 'list_id' and 'name' are required", false, toolTitle = t)
        return when (val r = TrelloConnector.addCard(c, id, name, desc)) {
            is TrelloConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is TrelloConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TrelloConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Todoist (API token). */
object TodoistTool {
    const val TASKS = "todoist_tasks"
    const val ADD = "todoist_add"
    const val COMPLETE = "todoist_complete"

    fun isConnected(c: Context): Boolean = TodoistConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(TASKS, "List the user's open Todoist tasks.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC)),
            listOf("tool_title"), listOf("tool_title")),
        AgentToolDefinition(ADD, "Add a Todoist task. Only add when the user explicitly asked.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "content" to AgentToolParam("string", "Task title."),
                "due" to AgentToolParam("string", "Due in natural language, e.g. 'tomorrow 9am' (optional).")),
            listOf("tool_title", "content"), listOf("tool_title", "content", "due")),
        AgentToolDefinition(COMPLETE, "Complete a Todoist task.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "task_id" to AgentToolParam("string", "Task id from todoist_tasks.")),
            listOf("tool_title", "task_id"), listOf("tool_title", "task_id")),
    )

    suspend fun executeTasks(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, TASKS)
        return when (val r = TodoistConnector.tasks(c)) {
            is TodoistConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No open Todoist tasks."
                else "Todoist tasks:\n" + r.value.joinToString("\n") {
                    "- ${it.content} (id: ${it.id}${if (it.due.isNotBlank()) ", due ${it.due}" else ""})"
                }, true, toolTitle = t)
            is TodoistConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TodoistConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeAdd(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, ADD)
        val content = w7Param(a, "content"); val due = w7Param(a, "due")
        if (content.isEmpty()) return ToolExecutionResult("Error: 'content' is required", false, toolTitle = t)
        return when (val r = TodoistConnector.add(c, content, due)) {
            is TodoistConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is TodoistConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TodoistConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeComplete(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, COMPLETE); val id = w7Param(a, "task_id")
        if (id.isEmpty()) return ToolExecutionResult("Error: 'task_id' is required", false, toolTitle = t)
        return when (val r = TodoistConnector.complete(c, id)) {
            is TodoistConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is TodoistConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TodoistConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** GitLab (personal access token). */
object GitLabTool {
    const val PROJECTS = "gitlab_projects"
    const val ISSUES = "gitlab_issues"

    fun isConnected(c: Context): Boolean = GitLabConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(PROJECTS, "List the user's GitLab projects.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC)),
            listOf("tool_title"), listOf("tool_title")),
        AgentToolDefinition(ISSUES, "List the user's GitLab issues.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "state" to AgentToolParam("string", "opened (default) or closed.")),
            listOf("tool_title"), listOf("tool_title", "state")),
    )

    suspend fun executeProjects(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, PROJECTS)
        return when (val r = GitLabConnector.projects(c)) {
            is GitLabConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No GitLab projects found."
                else "GitLab projects:\n" + r.value.joinToString("\n") { "- ${it.name}\n  ${it.url}" },
                true, toolTitle = t)
            is GitLabConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GitLabConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeIssues(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, ISSUES); val state = w7Param(a, "state").ifBlank { "opened" }
        return when (val r = GitLabConnector.issues(c, state)) {
            is GitLabConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No GitLab issues found."
                else "GitLab issues:\n" + r.value.joinToString("\n") { "- [${it.state}] ${it.title}\n  ${it.url}" },
                true, toolTitle = t)
            is GitLabConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GitLabConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** TMDB (free API key). */
object TmdbTool {
    const val SEARCH = "tmdb_search"
    const val TRENDING = "tmdb_trending"

    fun isConnected(c: Context): Boolean = TmdbConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(SEARCH, "Search movies and TV shows (title, year, rating, overview).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
        AgentToolDefinition(TRENDING, "Movies and shows trending this week.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC)),
            listOf("tool_title"), listOf("tool_title")),
    )

    private fun fmt(ts: List<TmdbConnector.Title>): String = ts.joinToString("\n") {
        "- ${it.name}${if (it.year.isNotBlank()) " (${it.year})" else ""} ★ ${it.rating}\n  ${it.overview}"
    }

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = TmdbConnector.search(c, q)) {
            is TmdbConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No titles found." else fmt(r.value), true, toolTitle = t)
            is TmdbConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TmdbConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeTrending(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, TRENDING)
        return when (val r = TmdbConnector.trending(c)) {
            is TmdbConnector.ApiResult.Ok -> ToolExecutionResult("Trending this week:\n" + fmt(r.value), true, toolTitle = t)
            is TmdbConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TmdbConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** GNews (free API key, 100 req/day). */
object GNewsTool {
    const val TOP = "news_top"
    const val SEARCH = "news_search"

    fun isConnected(c: Context): Boolean = GNewsConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(TOP, "Top news headlines, optionally by topic (world, business, technology, entertainment, sports, science, health).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "topic" to AgentToolParam("string", "Topic (optional).")),
            listOf("tool_title"), listOf("tool_title", "topic")),
        AgentToolDefinition(SEARCH, "Search news articles.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
    )

    private fun fmt(as_: List<GNewsConnector.Article>): String = as_.joinToString("\n") {
        "- ${it.title}${if (it.source.isNotBlank()) " (${it.source})" else ""}\n  ${it.url}"
    }

    suspend fun executeTop(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, TOP); val topic = w7Param(a, "topic")
        return when (val r = GNewsConnector.top(c, topic)) {
            is GNewsConnector.ApiResult.Ok -> ToolExecutionResult("Top headlines:\n" + fmt(r.value), true, toolTitle = t)
            is GNewsConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GNewsConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = GNewsConnector.search(c, q)) {
            is GNewsConnector.ApiResult.Ok -> ToolExecutionResult("News results:\n" + fmt(r.value), true, toolTitle = t)
            is GNewsConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GNewsConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Dropbox (OAuth). */
object DropboxTool {
    const val LIST = "dropbox_list"
    const val SEARCH = "dropbox_search"

    fun isConnected(c: Context): Boolean = DropboxConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(LIST, "List the user's Dropbox folder (empty path = root).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "path" to AgentToolParam("string", "Folder path (optional, default root).")),
            listOf("tool_title"), listOf("tool_title", "path")),
        AgentToolDefinition(SEARCH, "Search the user's Dropbox files by name.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
    )

    private fun fmt(es: List<DropboxConnector.Entry>): String = es.joinToString("\n") {
        "- ${if (it.isFolder) "📁" else "📄"} ${it.name}${if (!it.isFolder && it.size > 0) " (${it.size / 1024} KB)" else ""}"
    }

    suspend fun executeList(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, LIST); val p = w7Param(a, "path")
        return when (val r = DropboxConnector.list(c, p)) {
            is DropboxConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "Folder is empty." else fmt(r.value), true, toolTitle = t)
            is DropboxConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is DropboxConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = DropboxConnector.search(c, q)) {
            is DropboxConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No files matched." else fmt(r.value), true, toolTitle = t)
            is DropboxConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is DropboxConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** OneDrive (Microsoft OAuth). */
object OneDriveTool {
    const val LIST = "onedrive_list"
    const val SEARCH = "onedrive_search"

    fun isConnected(c: Context): Boolean = OneDriveConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(LIST, "List the user's OneDrive folder (empty path = root).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "path" to AgentToolParam("string", "Folder path (optional, default root).")),
            listOf("tool_title"), listOf("tool_title", "path")),
        AgentToolDefinition(SEARCH, "Search the user's OneDrive files by name.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
    )

    private fun fmt(es: List<OneDriveConnector.Entry>): String = es.joinToString("\n") {
        "- ${if (it.isFolder) "📁" else "📄"} ${it.name}${if (!it.isFolder && it.size > 0) " (${it.size / 1024} KB)" else ""}"
    }

    suspend fun executeList(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, LIST); val p = w7Param(a, "path")
        return when (val r = OneDriveConnector.list(c, p)) {
            is OneDriveConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "Folder is empty." else fmt(r.value), true, toolTitle = t)
            is OneDriveConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is OneDriveConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = OneDriveConnector.search(c, q)) {
            is OneDriveConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No files matched." else fmt(r.value), true, toolTitle = t)
            is OneDriveConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is OneDriveConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Google Tasks (Google OAuth, same client as Gmail). */
object GTasksTool {
    const val LISTS = "tasks_lists"
    const val LIST = "tasks_list"
    const val ADD = "tasks_add"

    fun isConnected(c: Context): Boolean = GTasksConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(LISTS, "List the user's Google Tasks lists.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC)),
            listOf("tool_title"), listOf("tool_title")),
        AgentToolDefinition(LIST, "List tasks in a Google Tasks list.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "list_id" to AgentToolParam("string", "List id from tasks_lists, or @default.")),
            listOf("tool_title"), listOf("tool_title", "list_id")),
        AgentToolDefinition(ADD, "Add a Google Task. Only add when the user explicitly asked.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "title" to AgentToolParam("string", "Task title."),
                "list_id" to AgentToolParam("string", "List id (optional, default list)."),
                "due" to AgentToolParam("string", "Due date RFC3339, e.g. 2026-10-04 (optional).")),
            listOf("tool_title", "title"), listOf("tool_title", "title", "list_id", "due")),
    )

    suspend fun executeLists(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, LISTS)
        return when (val r = GTasksConnector.lists(c)) {
            is GTasksConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No task lists found."
                else "Task lists:\n" + r.value.joinToString("\n") { "- ${it.title} (id: ${it.id})" },
                true, toolTitle = t)
            is GTasksConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GTasksConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeList(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, LIST); val id = w7Param(a, "list_id").ifBlank { "@default" }
        return when (val r = GTasksConnector.tasks(c, id)) {
            is GTasksConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No open tasks in this list."
                else "Tasks:\n" + r.value.joinToString("\n") {
                    "- ${it.title}${if (it.due.isNotBlank()) " (due ${it.due})" else ""}"
                }, true, toolTitle = t)
            is GTasksConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GTasksConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeAdd(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, ADD)
        val title = w7Param(a, "title"); val id = w7Param(a, "list_id").ifBlank { "@default" }
        val due = w7Param(a, "due")
        if (title.isEmpty()) return ToolExecutionResult("Error: 'title' is required", false, toolTitle = t)
        return when (val r = GTasksConnector.add(c, title, id, due)) {
            is GTasksConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is GTasksConnector.ApiResult.NotConnected -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is GTasksConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Weather (Open-Meteo, no key). */
object WeatherTool {
    const val NOW = "weather_now"
    const val FORECAST = "weather_forecast"

    fun isConnected(c: Context): Boolean = WeatherConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(NOW, "Current weather for a place (no account needed).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "place" to AgentToolParam("string", "Place name, e.g. Lahore.")),
            listOf("tool_title", "place"), listOf("tool_title", "place")),
        AgentToolDefinition(FORECAST, "Weather forecast for a place, up to 7 days.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "place" to AgentToolParam("string", "Place name."),
                "days" to AgentToolParam("integer", "Days ahead (default 3, max 7).")),
            listOf("tool_title", "place"), listOf("tool_title", "place", "days")),
    )

    suspend fun executeNow(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, NOW); val p = w7Param(a, "place")
        if (p.isEmpty()) return ToolExecutionResult("Error: 'place' is required", false, toolTitle = t)
        return when (val r = WeatherConnector.now(c, p)) {
            is WeatherConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is WeatherConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is WeatherConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeForecast(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, FORECAST); val p = w7Param(a, "place")
        if (p.isEmpty()) return ToolExecutionResult("Error: 'place' is required", false, toolTitle = t)
        return when (val r = WeatherConnector.forecast(c, p, w7Int(a, "days", 3))) {
            is WeatherConnector.ApiResult.Ok -> ToolExecutionResult(
                "Forecast for $p:\n" + r.value.joinToString("\n") { WeatherConnector.formatDay(it) },
                true, toolTitle = t)
            is WeatherConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is WeatherConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Currency (frankfurter.app, no key). */
object CurrencyTool {
    const val CONVERT = "currency_convert"
    const val RATES = "currency_rates"

    fun isConnected(c: Context): Boolean = CurrencyConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(CONVERT, "Convert money between currencies (ECB reference rates).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "amount" to AgentToolParam("string", "Amount, e.g. 100."),
                "from" to AgentToolParam("string", "3-letter code, e.g. USD."),
                "to" to AgentToolParam("string", "3-letter code, e.g. PKR.")),
            listOf("tool_title", "amount", "from", "to"),
            listOf("tool_title", "amount", "from", "to")),
        AgentToolDefinition(RATES, "Exchange rates of a base currency against majors.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "base" to AgentToolParam("string", "3-letter code (default USD).")),
            listOf("tool_title"), listOf("tool_title", "base")),
    )

    suspend fun executeConvert(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, CONVERT)
        val amt = w7Param(a, "amount").toDoubleOrNull()
            ?: return ToolExecutionResult("Error: 'amount' must be a number", false, toolTitle = t)
        val f = w7Param(a, "from"); val to = w7Param(a, "to")
        if (f.isEmpty() || to.isEmpty()) return ToolExecutionResult("Error: 'from' and 'to' are required", false, toolTitle = t)
        return when (val r = CurrencyConnector.convert(c, amt, f, to)) {
            is CurrencyConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is CurrencyConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is CurrencyConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeRates(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, RATES); val b = w7Param(a, "base").ifBlank { "USD" }
        return when (val r = CurrencyConnector.rates(c, b)) {
            is CurrencyConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is CurrencyConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is CurrencyConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Wikipedia (no key). */
object WikiTool {
    const val SEARCH = "wiki_search"
    const val SUMMARY = "wiki_summary"

    fun isConnected(c: Context): Boolean = WikiConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(SEARCH, "Search Wikipedia article titles.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
        AgentToolDefinition(SUMMARY, "Get a Wikipedia article summary.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "title" to AgentToolParam("string", "Article title.")),
            listOf("tool_title", "title"), listOf("tool_title", "title")),
    )

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = WikiConnector.search(c, q)) {
            is WikiConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No Wikipedia articles found."
                else "Wikipedia:\n" + r.value.joinToString("\n") { "- $it" }, true, toolTitle = t)
            is WikiConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is WikiConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeSummary(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SUMMARY); val title = w7Param(a, "title")
        if (title.isEmpty()) return ToolExecutionResult("Error: 'title' is required", false, toolTitle = t)
        return when (val r = WikiConnector.summary(c, title)) {
            is WikiConnector.ApiResult.Ok -> ToolExecutionResult(
                "${r.value.title}\n${r.value.summary}\n${r.value.url}", true, toolTitle = t)
            is WikiConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is WikiConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Hacker News (no key). */
object HnTool {
    const val TOP = "hn_top"
    const val SEARCH = "hn_search"

    fun isConnected(c: Context): Boolean = HnConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(TOP, "Hacker News front page stories.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "limit" to AgentToolParam("integer", "How many (default 10, max 30).")),
            listOf("tool_title"), listOf("tool_title", "limit")),
        AgentToolDefinition(SEARCH, "Search Hacker News stories.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
    )

    private fun fmt(ss: List<HnConnector.Story>): String = ss.joinToString("\n") {
        "- ${it.title} (▲${it.points}, ${it.comments} comments)\n  ${it.url}"
    }

    suspend fun executeTop(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, TOP)
        return when (val r = HnConnector.top(c, w7Int(a, "limit", 10))) {
            is HnConnector.ApiResult.Ok -> ToolExecutionResult("Hacker News:\n" + fmt(r.value), true, toolTitle = t)
            is HnConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is HnConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = HnConnector.search(c, q)) {
            is HnConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No stories found." else fmt(r.value), true, toolTitle = t)
            is HnConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is HnConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Dictionary (no key). */
object DictionaryTool {
    const val DEFINE = "define"

    fun isConnected(c: Context): Boolean = DictionaryConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(DEFINE, "Define an English word (part of speech, definitions, example).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "word" to AgentToolParam("string", "The word.")),
            listOf("tool_title", "word"), listOf("tool_title", "word")),
    )

    suspend fun executeDefine(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, DEFINE); val w = w7Param(a, "word")
        if (w.isEmpty()) return ToolExecutionResult("Error: 'word' is required", false, toolTitle = t)
        return when (val r = DictionaryConnector.define(c, w)) {
            is DictionaryConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is DictionaryConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is DictionaryConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Translate (MyMemory free tier, no key). */
object TranslateTool {
    const val TRANSLATE = "translate"

    fun isConnected(c: Context): Boolean = TranslateConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(TRANSLATE, "Translate text between languages (e.g. en→ur).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "text" to AgentToolParam("string", "Text to translate."),
                "target" to AgentToolParam("string", "Target language code, e.g. ur, es, fr."),
                "source" to AgentToolParam("string", "Source code or auto (default).")),
            listOf("tool_title", "text", "target"), listOf("tool_title", "text", "target", "source")),
    )

    suspend fun executeTranslate(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, TRANSLATE)
        val text = w7Param(a, "text"); val tgt = w7Param(a, "target"); val src = w7Param(a, "source")
        if (text.isEmpty() || tgt.isEmpty()) {
            return ToolExecutionResult("Error: 'text' and 'target' are required", false, toolTitle = t)
        }
        return when (val r = TranslateConnector.translate(c, text, tgt, src)) {
            is TranslateConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is TranslateConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is TranslateConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** Stack Overflow (no key). */
object StackOverflowTool {
    const val SEARCH = "so_search"

    fun isConnected(c: Context): Boolean = StackOverflowConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(SEARCH, "Search Stack Overflow questions (title, score, answers, link).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "query" to AgentToolParam("string", "Search query.")),
            listOf("tool_title", "query"), listOf("tool_title", "query")),
    )

    suspend fun executeSearch(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, SEARCH); val q = w7Param(a, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = t)
        return when (val r = StackOverflowConnector.search(c, q)) {
            is StackOverflowConnector.ApiResult.Ok -> ToolExecutionResult(
                if (r.value.isEmpty()) "No questions found."
                else r.value.joinToString("\n") { "- ${it.title} (▲${it.score}, ${it.answers} answers)\n  ${it.url}" },
                true, toolTitle = t)
            is StackOverflowConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is StackOverflowConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}

/** QR tools (on-device, no network). */
object QrTool {
    const val GENERATE = "qr_generate"
    const val DECODE = "qr_decode"

    fun isConnected(c: Context): Boolean = QrConnector.isConnected(c)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(GENERATE, "Generate a QR code PNG on-device (returns the file path).",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "text" to AgentToolParam("string", "Text or URL to encode (max 2000 chars).")),
            listOf("tool_title", "text"), listOf("tool_title", "text")),
        AgentToolDefinition(DECODE, "Decode a QR/barcode from an image file, on-device.",
            mapOf("tool_title" to AgentToolParam("string", TITLE_DESC),
                "image_path" to AgentToolParam("string", "Path to the image file.")),
            listOf("tool_title", "image_path"), listOf("tool_title", "image_path")),
    )

    suspend fun executeGenerate(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, GENERATE); val text = w7Param(a, "text")
        if (text.isEmpty()) return ToolExecutionResult("Error: 'text' is required", false, toolTitle = t)
        return when (val r = QrConnector.generate(c, text)) {
            is QrConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is QrConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is QrConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }

    suspend fun executeDecode(a: String, c: Context): ToolExecutionResult {
        val t = w7Title(a, DECODE); val p = w7Param(a, "image_path")
        if (p.isEmpty()) return ToolExecutionResult("Error: 'image_path' is required", false, toolTitle = t)
        return when (val r = QrConnector.decode(c, p)) {
            is QrConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = t)
            is QrConnector.ApiResult.Disabled -> ToolExecutionResult("Error: ${r.hint}", false, toolTitle = t)
            is QrConnector.ApiResult.Error -> ToolExecutionResult("Error: ${r.message}", false, toolTitle = t)
        }
    }
}
