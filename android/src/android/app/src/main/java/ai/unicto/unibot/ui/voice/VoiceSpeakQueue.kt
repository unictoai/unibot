package ai.unicto.unibot.ui.voice

import ai.unicto.unibot.speech.SpeechSentenceSplitter

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
 * Threading: the driver enqueues on the main thread while the TTS engine's
 * completion callback (`markSpoken` → next pump) runs on
 * Dispatchers.Default, so every method touching the shared state is
 * `@Synchronized`. [drainRemaining] is the exception: it only touches the
 * caller-owned buffer, which must stay confined to the caller's thread.
 *
 * Contract pinned by tests:
 *  - sentences speak in enqueue order, never scrambled;
 *  - [takeNext] returns null while an utterance is in-flight — the driver
 *    must only pump on utterance completion, never preempt a playing
 *    sentence (the engine flushes the current utterance on a new speak(),
 *    which cut sentences off mid-speech);
 *  - [clear] (interrupt / stop / error) drops everything, including the
 *    in-flight marker, so a stale completion callback can never resume a
 *    dead turn;
 *  - [isDrained] is true only when the queue is empty AND nothing is marked
 *    in-flight, which is the turn-completion signal;
 *  - [drainRemaining] flushes the sentence buffer at stream end, including
 *    a final fragment with no trailing punctuation (which the streaming
 *    loop would otherwise never speak).
 */
class VoiceSpeakQueue {

    private val queue = ArrayDeque<String>()
    private var inFlight = false

    /** Enqueue completed sentences; blanks are dropped (never spoken). */
    @Synchronized
    fun enqueueSentences(sentences: List<String>) {
        for (s in sentences) {
            if (s.isNotBlank()) queue.addLast(s)
        }
    }

    /**
     * Take the next sentence to speak, marking it in-flight. Returns null
     * when the queue is empty OR when an utterance is already in-flight —
     * the driver only pumps on the in-flight utterance's completion, so a
     * mid-stream pump can never preempt (and cut off) the playing sentence.
     */
    @Synchronized
    fun takeNext(): String? {
        if (inFlight) return null
        val next = queue.removeFirstOrNull() ?: return null
        inFlight = true
        return next
    }

    /** The in-flight utterance finished (or was stopped) — ready for the next. */
    @Synchronized
    fun markSpoken() {
        inFlight = false
    }

    /** Drop everything: interrupt, stop, error. Stale callbacks must no-op after this. */
    @Synchronized
    fun clear() {
        queue.clear()
        inFlight = false
    }

    /**
     * End-of-stream flush for the sentence buffer: extract every complete
     * sentence left in [buffer] (with streaming=false, so a trailing
     * decimal-point look-ahead commits instead of waiting for more text)
     * plus the remaining unterminated fragment, then empty [buffer]. The
     * buffer must stay confined to the caller's thread. Feed the result to
     * [enqueueSentences] — blanks are dropped there, never spoken.
     */
    fun drainRemaining(buffer: StringBuilder): List<String> {
        val sentences = SpeechSentenceSplitter
            .extractCompleteSentences(buffer, streaming = false)
            .toMutableList()
        val tail = buffer.toString().trim()
        buffer.clear()
        if (tail.isNotEmpty()) sentences.add(tail)
        return sentences
    }

    @get:Synchronized
    val pendingCount: Int get() = queue.size

    /** True while an utterance is taken but not yet marked spoken. */
    @get:Synchronized
    val hasInFlight: Boolean get() = inFlight

    /** True when nothing is queued and nothing is in-flight: the turn is fully spoken. */
    @get:Synchronized
    val isDrained: Boolean get() = queue.isEmpty() && !inFlight
}
