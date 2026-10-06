package ai.unicto.unibot.ui.voice

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.unicto.unibot.speech.RecognitionError
import ai.unicto.unibot.speech.SherpaTtsEngine
import ai.unicto.unibot.speech.SpeechRecognitionManager
import ai.unicto.unibot.speech.VoiceTextSanitizer
import ai.unicto.unibot.speech.friendlyMessage
import ai.unicto.unibot.speech.isUserFacingSttMessage
import ai.unicto.unibot.ui.chat.ChatMessage
import ai.unicto.unibot.ui.chat.ChatViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * [unibot-voice-conversation] State machine for the flagship voice
 * conversation screen — the hands-free talk loop:
 *
 *   Listening → (final transcript) → Thinking → (assistant reply) →
 *   Speaking → (TTS done) → Listening …
 *
 * STT comes from [SpeechRecognitionManager] (system engine, or the
 * on-device whisper.cpp engine — forced when no system engine exists, see
 * [startConversation]). Replies go through the session's [ChatViewModel]
 * (`sendMessage` + watching `messages` for the new assistant turn), and
 * speech comes from [SherpaTtsEngine].
 *
 * Auto-send on silence: engines auto-finalize on silence themselves; on top
 * of that a 1.5 s silence fallback timer calls `stopRecording()` (this is
 * what makes the whisper engine — one-shot-on-stop — produce a transcript).
 */
class VoiceConversationViewModel(
    private val chatViewModel: ChatViewModel,
    private val ensureMicPermission: suspend () -> Boolean,
        // [v1.2] Synchronous "is the mic actually usable" probe (runtime grant
    // + AppOps). Lets the error path tell a *bogus* engine permission failure
    // (broken recognition service on ROMs with no real speech service)
    // apart from a genuinely missing grant.
    private val hasMicPermission: () -> Boolean,
    // Voice theme item 27: realtime voice degrades on low-RAM devices —
    // sentence-streaming TTS doubles peak native footprint (LLM + TTS
    // synthesizing the next sentence while the model still streams), so
    // low-RAM phones speak the full reply once instead.
    private val isLowRamDevice: () -> Boolean = { false },
    // Voice theme item 31: readiness probes for the fully-offline route
    // (on-device STT + local LLM + on-device TTS). Null = offline routing
    // unavailable; the badge simply never claims offline.
    private val offlineProbes: OfflineProbes? = null,
) : ViewModel() {

    /**
     * Voice theme item 31: the three on-device legs of the offline route.
     * Supplied by the screen (which owns the Context the managers need).
     */
    data class OfflineProbes(
        /** On-device STT usable right now (whisper model downloaded, or system engine offline). */
        val isSttReady: () -> Boolean,
        /** A local LLM is downloaded and selected, so sendMessage routes locally. */
        val isLlmReady: () -> Boolean,
        /** On-device TTS usable right now (sherpa voice downloaded). */
        val isTtsReady: () -> Boolean,
        /**
         * Make the local route active (select a downloaded model) when none
         * is. Returns true when the local route is (now) active.
         */
        val ensureLocalLlm: () -> Boolean,
    )

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Thinking : State
        data object Speaking : State
        // Voice theme item 29: the machine-readable kind travels with the
        // message so the error card can gate Retry by kind (auth failures
        // offer "Update key", not a futile Retry). Null for unkinded failures.
        data class Error(val message: String, val kind: String? = null) : State
        // [v1.1.2] Mic permission denied (or "don't ask again"). The UI shows
        // a graceful card with rationale + a Settings deep-link — never a raw
        // "RECORD_AUDIO required" dead-end.
        data object PermissionDenied : State
                // [v1.2] No speech-to-text model on the phone (and no usable system
        // engine). The UI shows a download card with real progress — the
        // recovery action, not just an error string.
        data object NoSttModel : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    /** Live STT text while listening (empty for one-shot engines until final). */
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _lastExchange = MutableStateFlow<Pair<String, String>?>(null)
    /** (what you said, what unibot said) for the most recent completed turn. */
    val lastExchange: StateFlow<Pair<String, String>?> = _lastExchange.asStateFlow()

    private val _turnsCompleted = MutableStateFlow(0)
    /** Completed voice turns this visit — drives the voice-history record. */
    val turnsCompleted: StateFlow<Int> = _turnsCompleted.asStateFlow()

    /** Latest mic level 0f..1f, from [SpeechRecognitionManager.audioLevels]. */
    val audioLevel: StateFlow<Float> = SpeechRecognitionManager.audioLevels
        .map { levels -> levels.lastOrNull() ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /**
     * True when the conversation is fully on-device: the whisper STT engine
     * is the active engine AND sherpa TTS is ready (native lib + voice).
     * [refreshBadge] re-evaluates it (e.g. when returning from downloads).
     */
    private val badgeTick = MutableStateFlow(0)
    val onDeviceBadge: StateFlow<Boolean> = combine(
        SpeechRecognitionManager.selectedEngineId,
        badgeTick,
    ) { engineId, _ ->
        engineId == WHISPER_ENGINE_ID && SherpaTtsEngine.isReady
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Re-evaluate [onDeviceBadge] (TTS readiness can change out-of-band). */
    fun refreshBadge() {
        badgeTick.value++
        refreshOfflineStatus()
    }

    // ─── Voice theme item 31: explicit offline route ──────────────────────

    private val _offlineStatus =
        MutableStateFlow(ai.unicto.unibot.speech.OfflineVoiceStatus(emptyList()))
    /**
     * Per-leg readiness of the fully-offline route (on-device STT + local
     * LLM + on-device TTS). Empty legs when [offlineProbes] is null. The UI
     * renders this as a checklist so the offline path is explicit, and the
     * view-model gates [VoiceConversationPrefs.offlineMode] on
     * [ai.unicto.unibot.speech.OfflineVoiceStatus.allReady].
     */
    val offlineStatus: StateFlow<ai.unicto.unibot.speech.OfflineVoiceStatus> =
        _offlineStatus.asStateFlow()

    private fun refreshOfflineStatus() {
        val probes = offlineProbes ?: return
        _offlineStatus.value = ai.unicto.unibot.speech.OfflineVoiceRoute.evaluate(
            sttReady = runCatching { probes.isSttReady() }.getOrDefault(false),
            llmReady = runCatching { probes.isLlmReady() }.getOrDefault(false),
            ttsReady = runCatching { probes.isTtsReady() }.getOrDefault(false),
        )
    }

    // ─── Voice theme item 27: streaming TTS queue + background discipline ──

    /**
     * Sentence queue for realtime speech: completed sentences are spoken as
     * the model streams them, instead of waiting for the full reply. Owned
     * by the turn in [sendToChat]; [interrupt]/[stopConversation]/errors
     * clear it so a stale utterance can never resume a dead turn.
     */
    private val ttsQueue = VoiceSpeakQueue()

    /**
     * Turn generation for the TTS pump: [interrupt]/[stopConversation] bump
     * it, so a pump call or utterance callback from a dead turn no-ops
     * instead of speaking into the new turn (the enqueue→pump window is
     * small but real).
     */
    private var ttsGen = 0

    /**
     * Battery discipline: the realtime loop must never hold the mic or play
     * audio in the background. On background we stop the whole conversation
     * (the agent turn itself keeps running in the shared ChatViewModel); on
     * foreground we re-arm the mic only if the loop was active.
     */
    private var pausedForBackground = false

    fun onBackgrounded() {
        if (!conversationActive) return
        pausedForBackground = true
        stopConversation()
    }

    fun onForegrounded() {
        if (!pausedForBackground) return
        pausedForBackground = false
        if (VoiceConversationPrefs.autoListen.value) startConversation(sessionId)
    }

    /** True while a downloaded TTS voice is ready to speak. */
    val ttsReady: Boolean get() = SherpaTtsEngine.isReady

    val autoListen: StateFlow<Boolean> = VoiceConversationPrefs.autoListen

    private var sessionId: String = ""
    private var sendJob: Job? = null
    private var silenceJob: Job? = null
    private var conversationActive = false

    companion object {
        const val WHISPER_ENGINE_ID = "whisper-offline"
        const val SYSTEM_ENGINE_ID = "system"

        /** Silence fallback: stop the capture this long after speech stops. */
        private const val SILENCE_FALLBACK_MS = 1_500L
        /** Minimum capture length before the fallback may fire. */
        private const val MIN_LISTEN_MS = 2_500L
        /** Level above which the mic counts as hearing speech. */
        private const val QUIET_THRESHOLD = 0.12f
        /** Max wait for the assistant's reply before surfacing an error. */
        private const val REPLY_TIMEOUT_MS = 180_000L
        /**
         * Voice theme item 28: error/turn poll cadence. 150 ms keeps LLM
         * errors surfacing in a blink without hot-spinning the flow reads.
         */
        private const val POLL_MS = 150L
        /** Stream-quiet window before a turn counts as finished. */
        private const val STREAM_QUIET_MS = 1_000L

        fun factory(
            chatViewModel: ChatViewModel,
            ensureMicPermission: suspend () -> Boolean,
            hasMicPermission: () -> Boolean,
            isLowRamDevice: () -> Boolean = { false },
            offlineProbes: OfflineProbes? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VoiceConversationViewModel(
                    chatViewModel, ensureMicPermission, hasMicPermission,
                    isLowRamDevice, offlineProbes,
                ) as T
        }
    }

    // ─── Entry ────────────────────────────────────────────────────────────

    /**
     * Start the hands-free loop for [sessionId]: mic permission → engine
     * selection → listening. Safe to call again after [stopConversation] or
     * an [State.Error].
     */
    fun startConversation(sessionId: String) {
        if (conversationActive && _state.value != State.Idle && _state.value !is State.Error) return
        this.sessionId = sessionId
        conversationActive = true
        viewModelScope.launch {
            if (!ensureMicPermission()) {
                // [v1.1.2] Denied -> the graceful PermissionDenied card, not
                // a dead-end error string.
                _state.value = State.PermissionDenied
                return@launch
            }
            // [unibot-voice-conversation] Give every engine a fresh start, then
            // FORCE the on-device whisper engine when no system engine exists
            // (Abdullah's Infinix ships no system speech service). Without
            // this the conversation would sit in an error state on exactly the
            // phones the offline path was built for.
            SpeechRecognitionManager.clearDegradationAndRefresh()
            val engines = SpeechRecognitionManager.availableEngines()
            val systemOk = engines.any { it.id == SYSTEM_ENGINE_ID && it.isAvailable }
            val whisper = engines.firstOrNull { it.id == WHISPER_ENGINE_ID }
            if (!systemOk && whisper != null && whisper.isAvailable) {
                SpeechRecognitionManager.selectEngine(WHISPER_ENGINE_ID)
            }
            if (SpeechRecognitionManager.availableEngines().none { it.isAvailable }) {
                // [v1.2] Dedicated state (not a bare Error string) so the UI
                // can offer the real recovery: downloading the offline model.
                _state.value = State.NoSttModel
                return@launch
            }
            // Voice theme item 31: explicit offline mode. When the user asked
            // for fully-offline voice, every leg must be ready — starting a
            // cloud turn here would silently break the "zero network" promise.
            // The UI renders offlineStatus as a checklist with the real
            // recovery (download the missing leg); Retry re-checks.
            val probes = offlineProbes
            if (VoiceConversationPrefs.offlineMode.value && probes != null) {
                refreshOfflineStatus()
                val status = _offlineStatus.value
                if (!status.allReady) {
                    val missing = status.legs.filterNot { it.ready }
                        .joinToString(", ") { it.label }
                    _state.value = State.Error(
                        "Offline voice isn't fully ready yet — missing: $missing.",
                    )
                    return@launch
                }
                // Pin the loop to the on-device legs: whisper STT + the local
                // LLM route (sendMessage auto-routes once a local model is
                // active) + sherpa TTS.
                SpeechRecognitionManager.availableEngines()
                    .firstOrNull { it.id == WHISPER_ENGINE_ID && it.isAvailable }
                    ?.let { SpeechRecognitionManager.selectEngine(WHISPER_ENGINE_ID) }
                if (!runCatching { probes.ensureLocalLlm() }.getOrDefault(false)) {
                    _state.value = State.Error(
                        "Couldn't activate the on-device model. Download one first.",
                    )
                    return@launch
                }
            }
            startListening()
        }
    }

    /** Stop everything and park in [State.Idle]. */
    fun stopConversation() {
        conversationActive = false
        ttsGen++
        sendJob?.cancel()
        sendJob = null
        silenceJob?.cancel()
        silenceJob = null
        // Voice theme item 27: drop queued sentences too — a stale pump
        // callback must never resume speech after the turn died.
        ttsQueue.clear()
        SpeechRecognitionManager.stopRecording()
        SherpaTtsEngine.stop()
        _partialTranscript.value = ""
        _state.value = State.Idle
    }

    /** Mic toggle button: stop when listening, (re)start otherwise. */
    fun toggleListening() {
        when (_state.value) {
            is State.Listening -> stopConversation()
            // [v1.1.2] PermissionDenied re-runs the permission flow (the
            // screen's launcher + rationale), not a blind retry. NoSttModel
            // re-probes engines (a download may have finished elsewhere).
            is State.Idle, is State.Error, is State.PermissionDenied, is State.NoSttModel ->
                startConversation(sessionId)
            // Thinking/Speaking: stop the whole conversation (halts TTS and
            // drops the in-flight reply wait; the agent turn itself keeps
            // running in the shared ChatViewModel).
            is State.Thinking, is State.Speaking -> stopConversation()
        }
    }

    // [v1.1.2] Called when the system mic-permission dialog was denied
    // (including "don't ask again"). Shows the graceful denied card.
    fun onPermissionDenied() {
        _state.value = State.PermissionDenied
    }

    /**
     * Tap-to-interrupt: stop TTS immediately and re-arm the mic (when
     * auto-listen is on). The in-flight reply wait is cancelled — the
     * interrupted turn is simply dropped.
     */
    fun interrupt() {
        // Voice theme item 27: bump the pump generation first so an
        // in-flight utterance's completion callback finds nothing to pump,
        // then clear the queue and stop the engine.
        ttsGen++
        ttsQueue.clear()
        SherpaTtsEngine.stop()
        sendJob?.cancel()
        sendJob = null
        if (!conversationActive) return
        if (VoiceConversationPrefs.autoListen.value) startListening()
        else _state.value = State.Idle
    }

    // ─── Listening ────────────────────────────────────────────────────────

    private fun startListening() {
        if (!conversationActive) return
        // A previous TTS tail must never leak into the new capture.
        SherpaTtsEngine.stop()
        _partialTranscript.value = ""
        _state.value = State.Listening
        SpeechRecognitionManager.startRecording(
            onPartialOrFinal = { text, isFinal ->
                if (isFinal) onFinalTranscript(text)
                else {
                    _partialTranscript.value = text
                    lastSpeechAt = android.os.SystemClock.elapsedRealtime()
                }
            },
            onError = { error: RecognitionError, message: String? ->
                silenceJob?.cancel()
                if (!conversationActive) return@startRecording
                // [v1.2] A runtime permission denial (e.g. revoked mid-session)
                // lands on the graceful denied card, never a raw engine string.
                // BUT: when the app verifiably holds the mic permission, a
                // PERMISSION_DENIED from the engine is a *bogus* failure — the
                // recognition service itself is broken (seen on ROMs with no
                // real speech service, which also lie on the availability
                // probe). The engine already degraded itself; fall over to the
                // next engine instead of sending the user to Settings for a
                // permission they already granted.
                if (error == RecognitionError.PERMISSION_DENIED && hasMicPermission()) {
                    Log.w(
                        "VoiceConversation",
                        "engine reported PERMISSION_DENIED with mic granted — trying fallback engine",
                    )
                    if (tryFallbackEngine()) return@startRecording
                    _state.value = State.Error(
                        "The system voice service isn't working on this phone. " +
                            "Download the offline voice model below to talk on-device instead.",
                    )
                    return@startRecording
                }
                _state.value = if (error == RecognitionError.PERMISSION_DENIED) {
                    State.PermissionDenied
                } else {
                    // Voice theme item 30: friendly one-liners — raw engine
                    // strings and NETWORK enums become plain sentences.
                    State.Error(
                        message?.takeIf { it.isUserFacingSttMessage() }
                            ?: error.friendlyMessage(),
                    )
                }
            },
        )
        armSilenceFallback()
    }

    @Volatile
    private var lastSpeechAt: Long = 0L

        // [v1.2] Switch to the next available engine after the current one
    // failed spuriously, then restart listening. Returns false when there is
    // nothing to fall back to. Loop-safe: the failed engine already marked
    // itself degraded, and we only ever move to a *different* engine.
    private fun tryFallbackEngine(): Boolean {
        val current = SpeechRecognitionManager.selectedEngineId.value
        val next = SpeechRecognitionManager.availableEngines()
            .firstOrNull { it.isAvailable && it.id != current }
            ?: return false
        Log.i("VoiceConversation", "falling back from engine $current to ${next.id}")
        SpeechRecognitionManager.selectEngine(next.id)
        startListening()
        return true
    }

    /**
     * 1.5 s silence fallback: the engines auto-finalize on silence, but the
     * one-shot whisper engine only transcribes on stop — so if it hears
     * silence for 1.5 s we stop the capture for it, which delivers the final
     * transcript. No-op once the engine already finalized (the manager
     * guards `stopRecording` by state).
     */
    private fun armSilenceFallback() {
        silenceJob?.cancel()
        lastSpeechAt = android.os.SystemClock.elapsedRealtime()
        silenceJob = viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            while (_state.value is State.Listening) {
                delay(250)
                val now = android.os.SystemClock.elapsedRealtime()
                val level = SpeechRecognitionManager.audioLevels.value.maxOrNull() ?: 0f
                if (level > QUIET_THRESHOLD) lastSpeechAt = now
                if (now - startedAt > MIN_LISTEN_MS && now - lastSpeechAt > SILENCE_FALLBACK_MS) {
                    SpeechRecognitionManager.stopRecording()
                    break
                }
            }
        }
    }

    private fun onFinalTranscript(text: String) {
        silenceJob?.cancel()
        if (!conversationActive) return
        val clean = text.trim()
        if (clean.isEmpty()) {
            // Nothing said — re-arm so the loop doesn't die on a quiet take.
            if (VoiceConversationPrefs.autoListen.value) startListening()
            else _state.value = State.Idle
            return
        }
        _state.value = State.Thinking
        _partialTranscript.value = ""
        // [unibot-voice-autodetect] First-utterance language detection: watch
        // the transcript and move the recognizer to the spoken language.
        maybeAutoDetectLanguage(clean)
        sendToChat(clean)
    }

    /**
     * [unibot-voice-autodetect] When the toggle is on, run the on-device
     * script heuristic over the final transcript and switch the recognizer
     * locale when the spoken language is confidently different. No-ops for
     * engines where a switch is meaningless: the provider engine is
     * language-agnostic (auto-detects natively) and the offline whisper model
     * is English-only.
     */
    private fun maybeAutoDetectLanguage(text: String) {
        if (!VoiceConversationPrefs.autoDetectLanguage.value) return
        val engineId = SpeechRecognitionManager.selectedEngineId.value
        if (engineId == WHISPER_ENGINE_ID) return
        val current = SpeechRecognitionManager.locale.value
        val detected = ai.unicto.unibot.speech.SpokenLanguageHeuristic
            .detectLanguage(text, current.language) ?: return
        val supported = SpeechRecognitionManager.supportedLocales.value
        // Only switch to a locale the engine actually supports; with an
        // unknown list (provider engine) the recognizer ignores locale anyway.
        val target = supported.firstOrNull { it.language == detected } ?: return
        if (target.toLanguageTag() == current.toLanguageTag()) return
        Log.i("VoiceConversation", "auto-detect: ${current.toLanguageTag()} -> ${target.toLanguageTag()}")
        SpeechRecognitionManager.selectLocale(target)
    }

    // ─── Thinking → Speaking ─────────────────────────────────────────────
    // Voice theme item 27: realtime voice. The reply is spoken
    // sentence-by-sentence AS the model streams (via ttsQueue + the sherpa
    // engine chained on utterance completion), so the first sentence is
    // audible long before generation finishes. On low-RAM devices the queue
    // stays parked until the turn ends and the full reply speaks once —
    // cheaper peak footprint, same result.
    //
    // Voice theme item 28: LLM errors surface INSTANTLY. The old code waited
    // for a fresh assistant message with content; an error frame on an
    // otherwise empty message never satisfied that, so the UI sat on
    // "thinking" until the 180 s timeout. Now every poll checks the
    // top-level error flow AND the fresh message's error/kind, and throws
    // immediately — the voice card appears in ~150 ms with the kind attached
    // (item 29).

    private fun sendToChat(userText: String) {
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val beforeIds = chatViewModel.messages.value.map { it.id }.toSet()
            _lastExchange.value = userText to (_lastExchange.value?.second.orEmpty())
            ttsQueue.clear()
            val gen = ++ttsGen
            // Low-RAM degrade: park sentence streaming, speak once at the end.
            val streamTts = !runCatching { isLowRamDevice() }.getOrDefault(false)
            chatViewModel.sendMessage(userText)
            val reply: String = try {
                awaitReplyStreaming(beforeIds, streamTts, gen)
            } catch (e: TimeoutCancellationException) {
                ttsQueue.clear()
                _state.value = State.Error("The reply took too long. Tap to try again.")
                return@launch
            } catch (e: VoiceReplyException) {
                ttsQueue.clear()
                _state.value = State.Error(e.message ?: "Couldn't get a reply.", e.kind)
                return@launch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ttsQueue.clear()
                _state.value = State.Error("Couldn't get a reply: ${e.message}")
                return@launch
            }
            _lastExchange.value = userText to reply
            _turnsCompleted.value++
            if (!streamTts) {
                // Low-RAM path: the turn streamed silently; speak it once now.
                speakFullReply(reply)
                return@launch
            }
            // Streaming path: the queue spoke every sentence. If nothing was
            // ever speakable (tool-only turn), the state never left Thinking —
            // re-arm the mic instead of stranding the loop.
            if (_state.value is State.Speaking) {
                onSpeakDone()
            } else if (VoiceConversationPrefs.autoListen.value) {
                startListening()
            } else {
                _state.value = State.Idle
            }
        }
    }

    /**
     * Pump the TTS queue: speak the next queued sentence, chaining on the
     * utterance completion callback. No-op when the turn died (generation
     * bumped or queue cleared) or the conversation stopped — a stale callback
     * can never resume speech. Runs on whatever thread the engine callback
     * uses; StateFlow writes are thread-safe.
     */
    private fun pumpTtsQueue(gen: Int) {
        if (gen != ttsGen || !conversationActive) {
            ttsQueue.clear()
            return
        }
        val next = ttsQueue.takeNext() ?: return
        _state.value = State.Speaking
        // SherpaTtsEngine.speak sanitizes (no spoken markdown/emoji) and
        // flushes any in-flight utterance; we only ever call it from here,
        // strictly after the previous onDone, so ordering is exact.
        SherpaTtsEngine.speak(next) {
            ttsQueue.markSpoken()
            pumpTtsQueue(gen)
        }
    }

    /** Low-RAM fallback (item 27): speak the whole reply once, like before. */
    private fun speakFullReply(reply: String) {
        val spoken = VoiceTextSanitizer.sanitize(reply).trim()
        if (spoken.isEmpty()) {
            // Tool-only turn with nothing speakable — just keep listening.
            if (VoiceConversationPrefs.autoListen.value) startListening()
            else _state.value = State.Idle
            return
        }
        _state.value = State.Speaking
        // onDone fires on a background thread — hop back to the scope.
        SherpaTtsEngine.speak(spoken) {
            viewModelScope.launch { onSpeakDone() }
        }
    }

    /**
     * Wait for the assistant's reply, feeding [ttsQueue] sentence-by-sentence
     * from the live stream when [streamTts] is on.
     *
     * The reply id is resolved from the streaming side-channel first
     * (per-token text rides `streamingById`, keyed by message id — the
     * canonical list stays static mid-turn), falling back to the canonical
     * message list. Completion = the chat is quiet for 1 s (so tool loops
     * that pause between chunks don't cut the reply short) AND the TTS queue
     * drained.
     *
     * @throws VoiceReplyException the moment an LLM error surfaces — on the
     *   top-level error flow or on the fresh message — carrying its kind.
     */
    private suspend fun awaitReplyStreaming(beforeIds: Set<String>, streamTts: Boolean, gen: Int): String =
        withTimeout(REPLY_TIMEOUT_MS) {
            val sentenceBuffer = StringBuilder()
            var consumedUpTo = 0
            var replyId: String? = null
            var quietSince = 0L

            while (true) {
                // Item 28: surface errors the moment they land — never sit on
                // "thinking" after the chat already failed.
                chatViewModel.error.value?.let { text ->
                    throw VoiceReplyException(chatViewModel.lastErrorKind.value, text)
                }
                val canonical: ChatMessage? = chatViewModel.messages.value.firstOrNull {
                    it.role == "assistant" && it.id !in beforeIds && !it.isInternalBridge
                }
                // An error frame on an otherwise empty message: the old code
                // waited here until the 180 s timeout. Throw now, with kind.
                canonical?.error?.let { text ->
                    throw VoiceReplyException(canonical.errorKind, text)
                }
                if (replyId == null) {
                    replyId = chatViewModel.streamingById.value.keys
                        .firstOrNull { it !in beforeIds }
                        ?: canonical?.id
                }
                val liveText: String = replyId?.let { id ->
                    chatViewModel.streamingById.value[id]?.content
                        ?: chatViewModel.messages.value.firstOrNull { it.id == id }?.content
                }.orEmpty()

                if (liveText.length > consumedUpTo) {
                    val delta = liveText.substring(consumedUpTo)
                    consumedUpTo = liveText.length
                    if (streamTts) {
                        sentenceBuffer.append(delta)
                        val sentences =
                            ai.unicto.unibot.speech.SpeechSentenceSplitter
                                .extractCompleteSentences(sentenceBuffer, streaming = true)
                        if (sentences.isNotEmpty()) {
                            ttsQueue.enqueueSentences(sentences)
                            pumpTtsQueue(gen)
                        }
                    }
                }

                // Completion: the agent loop is quiet AND (streaming path)
                // every queued sentence finished speaking.
                val now = android.os.SystemClock.elapsedRealtime()
                if (!chatViewModel.isStreaming.value) {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince > STREAM_QUIET_MS) {
                        // Flush the tail (a fragment with no terminator).
                        val tail = liveText.substring(consumedUpTo.coerceAtMost(liveText.length))
                        if (streamTts && tail.isNotBlank()) {
                            ttsQueue.enqueueSentences(listOf(tail))
                            pumpTtsQueue(gen)
                        }
                        // Wait for the spoken queue to drain (bounded by the
                        // outer timeout).
                        while (streamTts && !ttsQueue.isDrained) {
                            if (!conversationActive) throw VoiceReplyException(
                                null, "Conversation stopped.",
                            )
                            delay(100)
                        }
                        break
                    }
                } else {
                    quietSince = 0L
                }
                delay(POLL_MS)
            }

            val id = replyId
            val full: String = id?.let { rid ->
                chatViewModel.messages.value.firstOrNull { it.id == rid }?.content
            }.orEmpty()
            // A mid-stream error could have landed after the last poll: never
            // hand an errored turn back as a successful reply.
            chatViewModel.messages.value.firstOrNull { it.id == id }?.error?.let { text ->
                val kind = chatViewModel.messages.value.firstOrNull { it.id == id }?.errorKind
                throw VoiceReplyException(kind, text)
            }
            full
        }

    private fun onSpeakDone() {
        if (!conversationActive) return
        if (_state.value !is State.Speaking) return
        if (VoiceConversationPrefs.autoListen.value) startListening()
        else _state.value = State.Idle
    }

    override fun onCleared() {
        conversationActive = false
        ttsGen++
        ttsQueue.clear()
        sendJob?.cancel()
        silenceJob?.cancel()
        SpeechRecognitionManager.stopRecording()
        SherpaTtsEngine.stop()
    }

    /**
     * A failed turn. [kind] is the chat error-kind taxonomy value (may be
     * null) so the UI can gate Retry vs fix actions.
     */
    private class VoiceReplyException(val kind: String?, message: String) : Exception(message)
}
