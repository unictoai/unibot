package ai.unicto.unibot.projects

import android.content.Context
import android.net.Uri
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * `minis-global/unibot/projects.json` — every project, newest first.
 * File-based like [ai.unicto.unibot.goals.GoalStore]: small file, read once,
 * written whole on every change; the flow is what the Projects screens observe.
 *
 * Project files live under `minis-global/unibot/project_files/<projectId>/`.
 * [copyIntoProject] copies an inbound file (camera photo, picked document) there
 * and records it on the project, so the bundle stays self-contained on-device.
 */
class ProjectStore private constructor(private val appContext: Context) {
    private val file = File(appContext.filesDir, "minis-global/unibot/projects.json")
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<Project>> = _projects

    fun all(): List<Project> = _projects.value

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    /** The project a chat session is filed into, if any. */
    fun projectForSession(sessionId: String): Project? =
        _projects.value.firstOrNull { sessionId in it.sessionIds }

    @Synchronized
    fun upsert(project: Project) {
        val stamped = project.copy(updatedAt = System.currentTimeMillis())
        val rest = _projects.value.filterNot { it.id == project.id }
        _projects.value = (listOf(stamped) + rest).sortedByDescending { it.createdAt }
        save()
    }

    @Synchronized
    fun delete(id: String) {
        _projects.value = _projects.value.filterNot { it.id == id }
        save()
        // Remove the project's file dir too — a deleted project leaves nothing behind.
        runCatching { projectDir(id).deleteRecursively() }
    }

    @Synchronized
    fun addSession(projectId: String, sessionId: String) {
        val p = get(projectId) ?: return
        if (sessionId in p.sessionIds) return
        upsert(p.copy(sessionIds = p.sessionIds + sessionId))
    }

    @Synchronized
    fun removeSession(projectId: String, sessionId: String) {
        val p = get(projectId) ?: return
        upsert(p.copy(sessionIds = p.sessionIds - sessionId))
    }

    /** Copy [sourceUri] into the project's dir and record it. Returns the recorded entry or null. */
    fun copyIntoProject(projectId: String, sourceUri: Uri, displayName: String): ProjectFile? {
        val p = get(projectId) ?: return null
        val dir = projectDir(projectId).also { it.mkdirs() }
        val safeName = displayName.ifBlank { "file_${System.currentTimeMillis()}" }
        val target = File(dir, uniqueName(dir, safeName))
        return runCatching {
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val entry = ProjectFile(name = target.name, sizeBytes = target.length())
            synchronized(this) {
                val cur = get(projectId) ?: return null
                upsert(cur.copy(files = cur.files + entry))
            }
            entry
        }.onFailure { AppLogger.warning(TAG, "copyIntoProject failed: ${it.message}") }
            .getOrNull()
    }

    fun projectDir(projectId: String): File =
        File(appContext.filesDir, "minis-global/unibot/project_files/$projectId")

    fun fileFor(projectId: String, entry: ProjectFile): File =
        File(projectDir(projectId), entry.name)

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var n = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}"
                else "$name ($n)"
            n++
        }
        return candidate
    }

    private fun load(): List<Project> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(Project.fromJson(it)) } }
        }.onFailure { AppLogger.warning(TAG, "projects.json unreadable: ${it.message}") }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONArray().apply { _projects.value.forEach { put(it.toJson()) } }.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { AppLogger.warning(TAG, "projects.json write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Projects"

        @Volatile private var instance: ProjectStore? = null

        fun get(context: Context): ProjectStore =
            instance ?: synchronized(this) {
                instance ?: ProjectStore(context.applicationContext).also { instance = it }
            }
    }
}
