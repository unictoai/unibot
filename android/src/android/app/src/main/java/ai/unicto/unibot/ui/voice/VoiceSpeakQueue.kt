package ai.unicto.unibot.ui.voice

/**
 * Voice theme item 27: sequencing for streaming TTS in the realtime voice
 * loop.
 *
 * The conversation speaks the reply sentence-by-sentence AS the model
 * streams: completed sentences are enqueued here, and the driver
 * ([ai.unicto.unibot.ui.voice.VoiceConversationViewModel]) feeds them one at
 * a time to the TTS engine, chaining on each utterance's completion
 * callback. The queue owns the ORDERING contract only — the engine calls stay
 * in the view-model so this stays pure and unit-testable.
 *
 * Contract pinned by tests:
 *  - sentences speak in enqueue order, never scrambled;
 *  - [clear] (interrupt / stop / error) drops everything, including the
 *    in-flight marker, so a stale completion callback can never resume a
 *    dead turn;
 *  - [isDrained] is true only when the queue is empty AND nothing is marked
 *    in-flight, which is the turn-completion signal.
 */
class VoiceSpeakQueue {

    private val queue = ArrayDeque<String>()
    private var inFlight = false

    /** Enqueue completed sentences; blanks are dropped (never spoken). */
    fun enqueueSentences(sentences: List<String>) {
        for (s in sentences) {
            if (s.isNotBlank()) queue.addLast(s)
        }
    }

    /**
     * Take the next sentence to speak, marking it in-flight. Returns null
     * when the queue is empty (the driver then waits for more sentences or
     * for stream end).
     */
    fun takeNext(): String? {
        val next = queue.removeFirstOrNull() ?: return null
        inFlight = true
        return next
    }

    /** The in-flight utterance finished (or was stopped) — ready for the next. */
    fun markSpoken() {
        inFlight = false
    }

    /** Drop everything: interrupt, stop, error. Stale callbacks must no-op after this. */
    fun clear() {
        queue.clear()
        inFlight = false
    }

    val pendingCount: Int get() = queue.size

    /** True when nothing is queued and nothing is in-flight: the turn is fully spoken. */
    val isDrained: Boolean get() = queue.isEmpty() && !inFlight
}
