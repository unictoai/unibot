package ai.unicto.unibot.ui.voice

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.unicto.unibot.speech.RecognitionError
import ai.unicto.unibot.speech.SherpaTtsEngine
import ai.unicto.unibot.speech.SpeechRecognitionManager
import ai.unicto.unibot.speech.VoiceTextSanitizer
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
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Thinking : State
        data object Speaking : State
        data class Error(val message: String) : State
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

        fun factory(
            chatViewModel: ChatViewModel,
            ensureMicPermission: suspend () -> Boolean,
            hasMicPermission: () -> Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VoiceConversationViewModel(chatViewModel, ensureMicPermission, hasMicPermission) as T
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
            startListening()
        }
    }

    /** Stop everything and park in [State.Idle]. */
    fun stopConversation() {
        conversationActive = false
        sendJob?.cancel()
        sendJob = null
        silenceJob?.cancel()
        silenceJob = null
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
                    State.Error(message ?: "Voice input failed ($error).")
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

    private fun sendToChat(userText: String) {
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val beforeIds = chatViewModel.messages.value.map { it.id }.toSet()
            _lastExchange.value = userText to (_lastExchange.value?.second.orEmpty())
            chatViewModel.sendMessage(userText)
            val reply: String = try {
                awaitAssistantReply(beforeIds)
            } catch (e: TimeoutCancellationException) {
                _state.value = State.Error("The reply took too long. Tap to try again.")
                return@launch
            } catch (e: VoiceReplyException) {
                _state.value = State.Error(e.message ?: "Couldn't get a reply.")
                return@launch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = State.Error("Couldn't get a reply: ${e.message}")
                return@launch
            }
            _lastExchange.value = userText to reply
            _turnsCompleted.value++
            // Speak the final text; sanitizer strips markdown/emoji for the ear.
            val spoken = VoiceTextSanitizer.sanitize(reply).trim()
            if (spoken.isEmpty()) {
                // Tool-only turn with nothing speakable — just keep listening.
                if (VoiceConversationPrefs.autoListen.value) startListening()
                else _state.value = State.Idle
                return@launch
            }
            _state.value = State.Speaking
            // onDone fires on a background thread — hop back to the scope.
            SherpaTtsEngine.speak(spoken) {
                viewModelScope.launch { onSpeakDone() }
            }
        }
    }

    /**
     * Wait for the new assistant turn: first a fresh assistant message with
     * content, then for streaming to settle (stable-quiet for 1 s, so tool
     * loops that pause between chunks don't cut the reply short).
     */
    private suspend fun awaitAssistantReply(beforeIds: Set<String>): String =
        withTimeout(REPLY_TIMEOUT_MS) {
            var replyId: String? = null
            while (replyId == null) {
                // A hard send failure (no provider, network down) surfaces on
                // the VM's error flow without ever producing a message — don't
                // sit out the full timeout on those.
                chatViewModel.error.value?.let { throw VoiceReplyException(it) }
                val msg: ChatMessage? = chatViewModel.messages.value.firstOrNull {
                    it.role == "assistant" && it.id !in beforeIds &&
                        it.content.isNotBlank() && !it.isInternalBridge
                }
                if (msg != null) {
                    replyId = msg.id
                } else {
                    delay(200)
                }
            }
            var quietSince = 0L
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!chatViewModel.isStreaming.value) {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince > 1_000L) break
                } else {
                    quietSince = 0L
                }
                delay(200)
            }
            chatViewModel.messages.value.firstOrNull { it.id == replyId }?.content
                ?: chatViewModel.messages.value.firstOrNull {
                    it.role == "assistant" && it.id !in beforeIds
                }?.content.orEmpty()
        }

    private fun onSpeakDone() {
        if (!conversationActive) return
        if (_state.value !is State.Speaking) return
        if (VoiceConversationPrefs.autoListen.value) startListening()
        else _state.value = State.Idle
    }

    override fun onCleared() {
        conversationActive = false
        sendJob?.cancel()
        silenceJob?.cancel()
        SpeechRecognitionManager.stopRecording()
        SherpaTtsEngine.stop()
    }

    /** A hard send failure surfaced on [ChatViewModel.error]. */
    private class VoiceReplyException(message: String) : Exception(message)
}
