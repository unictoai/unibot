package ai.unicto.unibot.ui.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * [v12-B] One-shot handoff for chat templates.
 *
 * The templates screen stages starter text keyed by the fresh draft session
 * id it is about to open; [ChatViewModel] consumes it in `init` so the
 * composer opens prefilled. In-memory only — a process restart before the
 * chat opens loses the staged text, which is acceptable (the user just taps
 * the template again). The entry is consumed, so a VM re-creation never
 * re-applies stale text.
 */
object ChatStarterPrefill {
    private val pending = ConcurrentHashMap<String, String>()

    fun stage(draftSessionId: String, text: String) {
        pending[draftSessionId] = text
    }

    fun consume(draftSessionId: String): String? = pending.remove(draftSessionId)
}
