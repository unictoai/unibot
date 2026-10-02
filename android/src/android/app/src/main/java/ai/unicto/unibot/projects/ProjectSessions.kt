package ai.unicto.unibot.projects

import ai.unicto.unibot.UnibotApp
import ai.unicto.unibot.data.MemoryGlobalPrefs

/**
 * "New chat in project": create a session exactly like a goal's own conversation
 * does ([ai.unicto.unibot.goals.GoalSessions]) — seed the row with a model from
 * the default group — then file it into the project.
 *
 * @return the new session id, or null when no credentialed model is available.
 */
object ProjectSessions {
    suspend fun create(app: UnibotApp, projectId: String, title: String? = null): String? {
        val providers = app.providerRepository
        val defaultGroupId = providers.defaultPrimaryGroupId
        val seedModelId = defaultGroupId
            ?.let { providers.group(it) }
            ?.let { g -> providers.availableMemberEntries(g).firstOrNull()?.model?.id }
            ?: providers.allVisibleEntries().firstOrNull()?.baseModel?.id
            ?: return null
        val session = app.chatRepository.createSession(
            modelId = seedModelId,
            title = title,
            memoryEnabled = MemoryGlobalPrefs.isGlobalEnabled(app),
        )
        if (defaultGroupId != null) {
            app.chatRepository.updateSessionBinding(
                session.id,
                """{"type":"group","groupId":"$defaultGroupId"}""",
                seedModelId,
            )
        }
        ProjectStore.get(app).addSession(projectId, session.id)
        return session.id
    }
}
