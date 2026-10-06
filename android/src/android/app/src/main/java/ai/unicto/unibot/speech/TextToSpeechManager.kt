package ai.unicto.unibot.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Manages Android TextToSpeech engine for reading assistant responses aloud.
 * Supports en-US and zh-CN with automatic language detection.
 */
class TextToSpeechManager : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeech"

        /**
         * [T-android-tts-silent-blackhole] Upper bound on waiting for the
         * engine to bind. Generous because low-end devices cold-start their
         * TTS service; a device with NO engine may never call onInit, and this
         * is what turns that into a detectable failure instead of a hang.
         */
        const val INIT_TIMEOUT_MS = 4_000L
        private val HAN_REGEX = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")
        // [T-android-tts-intranumber-guard] The sentence-boundary set moved to
        // SpeechSentenceSplitter.SENTENCE_ENDERS — a single source of truth
        // shared with ReadAloudPlayer, so the two TTS paths can't drift.

        /**
         * Voice theme item 34: explicit TTS language override from the voice
         * bar's language switch ([ai.unicto.unibot.ui.voice.VoiceLanguageSheet]).
         * When set, it wins over the per-utterance auto-detect below; a
         * picked system voice ([preferredVoiceName]) still wins over both.
         * Process-wide so every read-aloud player honors it.
         */
        @Volatile
        var preferredLanguageOverride: java.util.Locale? = null
    }

    // @Volatile: written on the binder thread in onInit / nulled on main in
    // shutdown(), and read from both — the posted pre-init replay relies on
    // seeing a torn-down engine rather than a stale non-null.
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    var isInitialized = false
        private set

    /**
     * [T-android-tts-silent-blackhole] True when the engine reported ERROR at
     * init — typically a device with no functional TTS engine installed
     * (common on Chinese ROMs without Google TTS, e.g. ColorOS). Callers can
     * use this + [awaitReady] to distinguish "not ready YET" from "will never
     * speak", instead of silently dropping text.
     */
    var initFailed = false
        private set

    /**
     * Resolves once [onInit] runs: true on SUCCESS, false on ERROR. Engine
     * binding is asynchronous, so the first utterances can easily arrive
     * before this settles — see [preInitQueue].
     */
    private val initResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /**
     * [T-android-tts-silent-blackhole] Utterances that arrived before init
     * settled. `speak()` used to DROP text whenever `!isInitialized` — since
     * init is async, the very first utterance after construction was silently
     * lost (LazyReadAloudPlayer constructs the engine on first tap, so the
     * first Read Aloud tap spoke nothing on every device). Buffered here and
     * replayed in order from [onInit] instead. Guarded by [queueLock];
     * `onInit` may arrive on a binder thread.
     */
    private val preInitQueue = mutableListOf<String>()
    private val queueLock = Any()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Voice theme item 33: karaoke word progress. The engine reports
     * onRangeStart (API 26+) with the character offset into the current
     * utterance; this flow carries the utterance text plus the spoken word's
     * char range, resolved via [utteranceTexts]. Null when nothing is being
     * spoken (or the engine never reports ranges — some OEM engines don't).
     */
    private val _spokenWord = MutableStateFlow<SpokenWord?>(null)
    val spokenWord: StateFlow<SpokenWord?> = _spokenWord.asStateFlow()

    /** Utterance id → spoken text, so onRangeStart offsets resolve to words. */
    private val utteranceTexts = mutableMapOf<String, String>()

    private var isPaused = false
    private var pendingTexts = mutableListOf<String>()
    private var pausedAtIndex = 0

    /**
     * Rolling buffer of partial-streaming text that hasn't hit a sentence
     * boundary yet. Cleared by [flush] or [stop]. See [appendText].
     */
    private val sentenceBuffer = StringBuilder()

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var speechPitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    var speechVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /**
     * Initializes the TTS engine. Must be called before speaking.
     */
    fun init(context: Context) {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.apply {
                setSpeechRate(speechRate)
                setPitch(speechPitch)
                setLanguage(Locale.US)
                setOnUtteranceProgressListener(createProgressListener())
            }
            Log.d(TAG, "TTS initialized successfully")
            // Replay whatever arrived while the engine was binding, in order.
            val queued = synchronized(queueLock) {
                val copy = preInitQueue.toList()
                preInitQueue.clear()
                copy
            }
            // [T-android-tts-init-thread] Post the replay to the MAIN thread.
            //
            // onInit arrives on a binder thread. speakQueued touches
            // pendingTexts (a plain MutableList that speak()/stop()/the
            // progress listener also mutate) and `tts` (which shutdown()
            // nulls), with no happens-before against any of them. Disposing the
            // ChatScreen inside the ~4 s init window — rotate the device, or
            // navigate away right after enabling read-replies — therefore
            // raced a ConcurrentModificationException on pendingTexts, or a
            // speak() against an already shut-down engine. Same lifecycle shape
            // as the VAD SIGSEGV fixed in 91c7b2944.
            //
            // Re-check liveness inside the post: shutdown() can land between
            // the binder callback and the main-thread turn.
            if (queued.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    if (tts != null && isInitialized) {
                        for (text in queued) speakQueued(text)
                    } else {
                        Log.d(TAG, "pre-init replay dropped — engine shut down during init")
                    }
                }
            }
        } else {
            initFailed = true
            synchronized(queueLock) { preInitQueue.clear() }
            _isSpeaking.value = false
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    /**
     * [T-android-tts-silent-blackhole] Suspend until init settles (bounded by
     * [timeoutMs] — a device with no engine may never call [onInit] at all).
     * @return true when the engine is usable.
     */
    suspend fun awaitReady(timeoutMs: Long = INIT_TIMEOUT_MS): Boolean {
        if (isInitialized) return true
        if (initFailed) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { initResult.await() } ?: false
    }

    /**
     * Speaks text immediately, interrupting any ongoing speech (QUEUE_FLUSH).
     * Automatically detects language from text content.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-silent-blackhole] Engine still binding (or dead).
            // Buffer instead of dropping; onInit replays in order. On a failed
            // init the queue is discarded there — nothing can ever speak it.
            if (!initFailed) {
                synchronized(queueLock) {
                    preInitQueue.clear() // speak() semantics: flush what came before
                    preInitQueue.add(text)
                }
                _isSpeaking.value = true
            }
            return
        }

        isPaused = false
        pendingTexts.clear()
        pendingTexts.add(text)
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)

        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] speak() returns ERROR synchronously when
        // the engine rejects the request (missing language data, engine died —
        // both real on OEM/AOSP engines without Google TTS). The result used
        // to be discarded, so isSpeaking stayed true with NO utterance in
        // flight and no progress callback ever coming — ReadAloudPlayer's
        // completion poll then spun forever and the whole read-aloud queue
        // wedged for the rest of the process.
        // Voice theme item 33: remember the utterance text for karaoke word
        // progress (onRangeStart only gives an id + offset).
        val utteranceId = generateUtteranceId()
        rememberUtteranceText(utteranceId, text)
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak rejected by engine (rc=$rc len=${text.length})")
            _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Adds text to the speech queue without interrupting (QUEUE_ADD).
     */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-diag] A field report of "read replies is on but
            // nothing is spoken" could not be diagnosed from the log: this
            // path emitted nothing at all, so whether text ever reached the
            // engine was unknowable. Queued-before-init is the one silent
            // outcome that looks identical to success from the caller's side.
            Log.i(TAG, "speakQueued deferred (init pending) len=${text.length} initFailed=$initFailed")
            if (!initFailed) {
                synchronized(queueLock) { preInitQueue.add(text) }
                _isSpeaking.value = true
            }
            return
        }
        Log.i(TAG, "speakQueued len=${text.length}")

        pendingTexts.add(text)
        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] Same rejected-enqueue guard as speak().
        // Voice theme item 33: remember the utterance text for karaoke word
        // progress (onRangeStart only gives an id + offset).
        val utteranceId = generateUtteranceId()
        rememberUtteranceText(utteranceId, text)
        val rc = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speakQueued rejected by engine (rc=$rc len=${text.length})")
            // Only clear the flag when nothing else is in flight — a QUEUE_ADD
            // rejection must not mark an ongoing earlier utterance as done.
            if (pendingTexts.isEmpty()) _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Feed a chunk of streaming AI output. Any complete sentences the chunk
     * produces (boundary characters: `。！？.!?\n` — same set as iOS
     * `extractNewSentences` in AIChatViewModel) are immediately enqueued for
     * TTS via [speakQueued], so the first sentence starts speaking the moment
     * its terminator arrives instead of waiting for the full response.
     *
     * Incomplete tail text stays in [sentenceBuffer] until the next call, or
     * until [flush] runs at stream end.
     *
     * Safe to call before [init]/onInit — chunks are still split; the TTS
     * engine simply drops them if not yet ready (speakQueued guards).
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        val sentences = extractCompleteSentences(sentenceBuffer)
        if (sentences.isEmpty()) return
        for (sentence in sentences) {
            speakQueued(sentence)
        }
    }

    /**
     * Emit the remaining buffered tail (a sentence fragment with no trailing
     * punctuation) as a final utterance. Call at the end of a streaming
     * response. Mirrors iOS's post-stream "flush remaining speech" logic.
     */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) {
            speakQueued(tail)
        }
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and [ReadAloudPlayer] apply the same
     * terminator set AND the same intra-number guards ("3.14" is never cut into
     * "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    /**
     * Stops all speech immediately.
     */
    fun stop() {
        tts?.stop()
        isPaused = false
        pendingTexts.clear()
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)
        // A stop while the engine is still binding must also drop the buffered
        // utterances, or they'd blurt out the moment onInit lands.
        synchronized(queueLock) { preInitQueue.clear() }
        _isSpeaking.value = false
        // Voice theme item 33: karaoke ends with the speech.
        _spokenWord.value = null
    }

    /**
     * Toggles pause/resume. Since Android TTS doesn't natively support pause,
     * this stops playback and tracks position for manual resume.
     */
    fun togglePause() {
        if (isPaused) {
            // Resume: replay remaining texts from where we paused
            isPaused = false
            if (pausedAtIndex < pendingTexts.size) {
                val remaining = pendingTexts.subList(pausedAtIndex, pendingTexts.size)
                remaining.forEachIndexed { index, text ->
                    autoDetectAndSetLanguage(text)
                    val params = buildSpeechParams()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(text, queueMode, params, generateUtteranceId())
                }
                _isSpeaking.value = true
            }
        } else {
            // Pause: stop and mark position
            isPaused = true
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    /**
     * Sets the TTS language explicitly.
     */
    fun setLanguage(locale: Locale) {
        if (!isInitialized) return
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: ${locale.toLanguageTag()}")
        } else {
            Log.d(TAG, "Language set to: ${locale.toLanguageTag()}")
        }
    }

    /**
     * Cleans up the TTS engine. Must be called when no longer needed.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shut down")
    }

    /**
     * Detects if text contains Han characters and sets language accordingly.
     */
    /**
     * [T-android-system-voice-catalog] Engine voice name to speak with; null =
     * auto (per-utterance language detection below). Set per utterance by
     * ReadAloudPlayer from the capsule's picked system voice. When the named
     * voice exists it wins over language auto-detection — same as iOS, where a
     * picked voice is used regardless of the reply's language.
     */
    @Volatile
    var preferredVoiceName: String? = null

    private fun autoDetectAndSetLanguage(text: String) {
        preferredVoiceName?.let { wanted ->
            val v = runCatching { tts?.voices?.firstOrNull { it.name == wanted } }.getOrNull()
            if (v != null) {
                val rc = tts?.setVoice(v)
                if (rc == TextToSpeech.SUCCESS) return
                Log.w(TAG, "setVoice($wanted) rejected (rc=$rc) — falling back to auto language")
            } else {
                Log.w(TAG, "preferred voice '$wanted' not found on this engine — falling back to auto language")
            }
        }
        // Voice theme item 34: explicit language override from the voice
        // bar's language switch beats the per-utterance heuristic.
        preferredLanguageOverride?.let { override ->
            tts?.setLanguage(override)
            return
        }
        val locale = if (HAN_REGEX.containsMatchIn(text)) {
            Locale.SIMPLIFIED_CHINESE
        } else {
            Locale.US
        }
        tts?.setLanguage(locale)
    }

    private fun buildSpeechParams(): android.os.Bundle {
        return android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
        }
    }

    private fun generateUtteranceId(): String = UUID.randomUUID().toString()

    /**
     * Voice theme item 33: bound the utterance-text map (one entry per
     * utterance; long read-aloud sessions would otherwise grow it forever).
     */
    private fun rememberUtteranceText(utteranceId: String, text: String) {
        if (utteranceTexts.size > 64) utteranceTexts.clear()
        utteranceTexts[utteranceId] = text
    }

    private fun createProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                // Voice theme item 33: publish the utterance text immediately
                // (word range follows via onRangeStart); the karaoke UI shows
                // the sentence even on engines that never report ranges.
                val text = utteranceId?.let { utteranceTexts[it] }
                Handler(Looper.getMainLooper()).post {
                    _spokenWord.value = text?.let { SpokenWord(it, null) }
                }
            }

            /**
             * Voice theme item 33: karaoke word progress (API 26+). The
             * engine reports the char offset of the spoken frame; resolve it
             * to the containing word via [WordBoundaryTracker].
             */
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
                val text = utteranceId?.let { utteranceTexts[it] } ?: return
                // Some engines report the frame END or a mid-word offset;
                // anchor on the frame start and resolve the containing word.
                val range = WordBoundaryTracker.wordAt(text, start.coerceIn(0, text.length - 1))
                    ?: return
                Handler(Looper.getMainLooper()).post {
                    _spokenWord.value = SpokenWord(text, range)
                }
            }

            override fun onDone(utteranceId: String?) {
                // [T-android-tts-init-thread] Progress callbacks arrive on a
                // binder thread. This used to bump pausedAtIndex and read
                // pendingTexts.size straight from there, while togglePause()
                // takes a subList VIEW of that same list on main — a
                // concurrent stop() clearing it makes the view throw. Confine
                // both to the main thread so pendingTexts/pausedAtIndex have a
                // single owner.
                Handler(Looper.getMainLooper()).post {
                    pausedAtIndex++
                    // Only mark as not speaking if nothing else is queued
                    if (pausedAtIndex >= pendingTexts.size) {
                        _isSpeaking.value = false
                        // Voice theme item 33: karaoke ends with the utterance.
                        _spokenWord.value = null
                    }
                }
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId, code: $errorCode")
                _isSpeaking.value = false
            }
        }
}
