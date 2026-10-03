package ai.unicto.unibot.ui.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [unibot-voice-conversation] Persisted preferences for the voice
 * conversation screen. Same SharedPreferences-object + StateFlow pattern as
 * [ai.unicto.unibot.speech.VoiceOutputState] and
 * [ai.unicto.unibot.ui.chat.voice.VoiceModePrefs].
 *
 * Voice speed deliberately reuses [ai.unicto.unibot.speech.VoiceOutputState.speed]
 * (0.5x–2.0x) — one speed for every TTS surface, so the slider in voice
 * settings and the one in read-aloud settings can never disagree.
 */
object VoiceConversationPrefs {

    private const val PREFS = "voice_conversation"
    private const val KEY_AUTO_LISTEN = "autoListen"
    private const val KEY_SPEAK_ONLY_IN_VOICE_MODE = "speakOnlyInVoiceMode"
    private const val KEY_VOICE_ID = "voiceId"
    private const val KEY_MIC_RATIONALE_SHOWN = "micRationaleShown"
    private const val KEY_AUTO_DETECT_LANGUAGE = "autoDetectLanguage"

    /** On-device voices offered by the voice conversation UI. The actual
     *  downloads are owned by the TTS voice manager (sibling work); these
     *  ids are the selection contract. */
    val VOICES: List<VoiceOption> = listOf(
        VoiceOption("amy", "Amy"),
        VoiceOption("ryan", "Ryan"),
        VoiceOption("lessac", "Lessac"),
        VoiceOption("joe", "Joe"),
        VoiceOption("alan", "Alan"),
    )

    data class VoiceOption(val id: String, val label: String)

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private val _autoListen = MutableStateFlow(true)
    /** Hands-free loop: after each reply finishes speaking, the mic re-arms. */
    val autoListen: StateFlow<Boolean> = _autoListen.asStateFlow()

    private val _speakOnlyInVoiceMode = MutableStateFlow(false)
    /**
     * When true, assistant replies are spoken ONLY inside the voice
     * conversation screen; the normal chat's read-aloud stays silent.
     * Default false — read-aloud behaves as before.
     */
    val speakOnlyInVoiceMode: StateFlow<Boolean> = _speakOnlyInVoiceMode.asStateFlow()

    private val _voiceId = MutableStateFlow<String?>(null)
    /** Selected on-device voice id ("amy"/"ryan"/"lessac"/"joe"/"alan"); null = default. */
    val voiceId: StateFlow<String?> = _voiceId.asStateFlow()

    private val _autoDetectLanguage = MutableStateFlow(false)
    /**
     * [unibot-voice-autodetect] When true, the voice conversation watches each final
     * transcript and switches the recognizer's language when the spoken
     * language is confidently different ([SpokenLanguageHeuristic]). Off by
     * default — flipping the recognizer under the user is opt-in.
     */
    val autoDetectLanguage: StateFlow<Boolean> = _autoDetectLanguage.asStateFlow()

    /** Idempotent; safe to call per composition. */
    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        _autoListen.value = sp.getBoolean(KEY_AUTO_LISTEN, true)
        _speakOnlyInVoiceMode.value = sp.getBoolean(KEY_SPEAK_ONLY_IN_VOICE_MODE, false)
        _voiceId.value = sp.getString(KEY_VOICE_ID, null)
        _autoDetectLanguage.value = sp.getBoolean(KEY_AUTO_DETECT_LANGUAGE, false)
    }

    fun setAutoListen(on: Boolean) {
        _autoListen.value = on
        prefs?.edit()?.putBoolean(KEY_AUTO_LISTEN, on)?.apply()
    }

    fun setSpeakOnlyInVoiceMode(only: Boolean) {
        _speakOnlyInVoiceMode.value = only
        prefs?.edit()?.putBoolean(KEY_SPEAK_ONLY_IN_VOICE_MODE, only)?.apply()
    }

    fun setVoiceId(id: String?) {
        _voiceId.value = id
        prefs?.edit()?.putString(KEY_VOICE_ID, id)?.apply()
    }

    fun setAutoDetectLanguage(on: Boolean) {
        _autoDetectLanguage.value = on
        prefs?.edit()?.putBoolean(KEY_AUTO_DETECT_LANGUAGE, on)?.apply()
    }

    /** True once the first-entry mic rationale dialog has been shown. */
    fun wasMicRationaleShown(context: Context): Boolean {
        init(context)
        return prefs?.getBoolean(KEY_MIC_RATIONALE_SHOWN, false) == true
    }

    fun markMicRationaleShown(context: Context) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_MIC_RATIONALE_SHOWN, true)?.apply()
    }
}
