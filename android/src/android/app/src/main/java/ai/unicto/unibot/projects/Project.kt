package ai.unicto.unibot.projects

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A project workspace: an easy bundle of a project's chats, its files, and its
 * custom instructions. Everything stays on-device (file-based store, like goals).
 *
 * - [sessionIds]: chat sessions filed into this project. Creating a chat from the
 *   project detail screen files the new session automatically.
 * - [files]: files copied into the project's own dir
 *   (`filesDir/minis-global/unibot/project_files/<projectId>/`), stored here as
 *   relative names so the project survives app updates.
 * - [instructions]: custom instructions prepended to the system prompt of every
 *   chat in the project (see [ProjectPrompt]).
 */
data class ProjectFile(
    val name: String,
    val sizeBytes: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("sizeBytes", sizeBytes)

    companion object {
        fun fromJson(o: JSONObject) = ProjectFile(
            name = o.optString("name", ""),
            sizeBytes = o.optLong("sizeBytes", 0L),
        )
    }
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val instructions: String = "",
    val colorIndex: Int = 0,
    val sessionIds: List<String> = emptyList(),
    val files: List<ProjectFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    /** Accent palette for project rows — violet-first, all dark-mode safe. */
    fun accentColor(): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(COLORS[colorIndex.mod(COLORS.size)])

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("instructions", instructions)
        put("colorIndex", colorIndex)
        put("sessionIds", JSONArray().apply { sessionIds.forEach { put(it) } })
        put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        val COLORS = longArrayOf(
            0xFF6D28D9, 0xFF0A66E4, 0xFF047857, 0xFFB45309, 0xFFBE123C, 0xFF4D7C0F,
        )

        fun fromJson(o: JSONObject): Project {
            val sessionIds = mutableListOf<String>()
            o.optJSONArray("sessionIds")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i)?.let { sessionIds += it }
            }
            val files = mutableListOf<ProjectFile>()
            o.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { files += ProjectFile.fromJson(it) }
            }
            return Project(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                instructions = o.optString("instructions", ""),
                colorIndex = o.optInt("colorIndex", 0),
                sessionIds = sessionIds,
                files = files,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}
