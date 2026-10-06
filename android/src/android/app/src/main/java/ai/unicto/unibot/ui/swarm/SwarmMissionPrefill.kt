package ai.unicto.unibot.ui.swarm

/**
 * One-shot mission prefill for the swarm screen (v1.4.0 item 12 — "swarm
 * from chat").
 *
 * Chat's "Deep-dive this" action stashes the mission text here and
 * navigates to the swarm route; the swarm destination consumes it exactly
 * once (take()) into the mission composer. A simple volatile holder is
 * enough — it is written on the UI thread and read on the next
 * composition, and a stale value is harmless (it just prefills a text
 * field the user can edit).
 */
object SwarmMissionPrefill {
    @Volatile
    private var pending: String? = null

    /** Stash a mission for the next swarm-screen visit. */
    fun set(mission: String) {
        pending = mission
    }

    /** Take the pending mission, clearing it. Null when nothing is pending. */
    fun take(): String? {
        val value = pending
        pending = null
        return value?.takeIf { it.isNotBlank() }
    }
}
