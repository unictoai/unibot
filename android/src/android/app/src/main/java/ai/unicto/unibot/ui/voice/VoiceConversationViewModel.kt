package ai.unicto.unibot.ui.voice

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
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Thinking : State
        data object Speaking : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    /** Live STT text while listening (empty for one-shot engines until final). */
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _lastExchange = MutableStateFlow<Pair<String, String>?>(null)
    /** (what you said, what unibot said) for the most recent completed turn. */
    val lastExchange: StateFlow<Pair<String, String>?> = _lastExchange.asStateFlow()

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
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VoiceConversationViewModel(chatViewModel, ensureMicPermission) as T
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
                _state.value = State.Error("Microphone permission was not granted.")
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
                _state.value = State.Error(
                    "No voice engine is available. Download the offline voice model to talk on-device.",
                )
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
            is State.Idle, is State.Error -> startConversation(sessionId)
            // Thinking/Speaking: stop the whole conversation (halts TTS and
            // drops the in-flight reply wait; the agent turn itself keeps
            // running in the shared ChatViewModel).
            is State.Thinking, is State.Speaking -> stopConversation()
        }
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
                _state.value = State.Error(message ?: "Voice input failed ($error).")
            },
        )
        armSilenceFallback()
    }

    @Volatile
    private var lastSpeechAt: Long = 0L

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
        sendToChat(clean)
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
