package ai.unicto.unibot.speech

/**
 * Voice theme item 31: fully offline voice chat — on-device STT + local LLM +
 * on-device TTS, zero network.
 *
 * The three legs already exist independently (whisper.cpp STT, llama.cpp via
 * [ai.unicto.unibot.local.LlamaModelManager], sherpa TTS); what was missing
 * was making the offline path EXPLICIT — one place that answers "can this
 * conversation run fully offline right now, and if not, which leg is missing?"
 * — and TESTABLE, so the routing decision is pinned by unit tests instead of
 * being scattered across the view-model.
 *
 * This object is that place. It is pure: callers supply the three readiness
 * probes (which themselves touch engines/managers), and it returns the
 * per-leg status the UI renders as a checklist.
 */
data class OfflineVoiceLeg(
    /** Stable id: "stt", "llm", "tts". */
    val id: String,
    /** User-facing leg name, e.g. "Voice recognition". */
    val label: String,
    /** True when this leg can run on-device right now. */
    val ready: Boolean,
    /** Plain-language recovery hint shown when [ready] is false. */
    val hint: String,
)

data class OfflineVoiceStatus(val legs: List<OfflineVoiceLeg>) {
    /** True only when every leg is on-device ready — zero network needed. */
    val allReady: Boolean get() = legs.isNotEmpty() && legs.all { it.ready }
    val readyCount: Int get() = legs.count { it.ready }
}

object OfflineVoiceRoute {

    /**
     * Evaluate the offline route. Pure — pass the already-computed readiness
     * of each leg:
     *
     * @param sttReady on-device STT usable (whisper engine selected/available
     *   with its model downloaded, or the system engine in offline mode).
     * @param llmReady an on-device LLM is downloaded and selected, so
     *   [ai.unicto.unibot.ui.chat.ChatViewModel.sendMessage] routes locally.
     * @param ttsReady on-device TTS usable (sherpa voice downloaded).
     */
    fun evaluate(sttReady: Boolean, llmReady: Boolean, ttsReady: Boolean): OfflineVoiceStatus =
        OfflineVoiceStatus(
            listOf(
                OfflineVoiceLeg(
                    id = "stt",
                    label = "Voice recognition",
                    ready = sttReady,
                    hint = "Download the offline voice model",
                ),
                OfflineVoiceLeg(
                    id = "llm",
                    label = "On-device model",
                    ready = llmReady,
                    hint = "Download an on-device model in Settings",
                ),
                OfflineVoiceLeg(
                    id = "tts",
                    label = "Voice",
                    ready = ttsReady,
                    hint = "Download a voice in Read-aloud settings",
                ),
            ),
        )
}
