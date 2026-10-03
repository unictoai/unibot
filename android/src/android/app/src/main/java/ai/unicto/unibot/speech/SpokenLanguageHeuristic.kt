package ai.unicto.unibot.speech

import java.lang.Character.UnicodeScript

/**
 * [unibot-voice-autodetect] Lightweight, fully on-device spoken-language heuristic.
 *
 * None of the STT engines expose a language-detection API: the system engine
 * ([SystemSpeechRecognitionEngine]) wraps android.speech.SpeechRecognizer
 * (no detection), the on-device whisper engine is an English-only tiny.en
 * model, and the provider engine is already language-agnostic/auto-detecting.
 * So detection runs on the transcript text itself, by Unicode script:
 * the dominant script of the letters in a final transcript maps to the
 * language family that owns that script.
 *
 * Deliberately conservative: it returns null (no switch) when the text is
 * too short, mixed, or Latin-ambiguous — Latin covers dozens of languages,
 * so a Latin transcript only suggests "en" when the current recognizer
 * language's typical script is NOT Latin (e.g. the recognizer was set to
 * Arabic and the user spoke English). Guessing wrong would flip the
 * recognizer under the user, which is worse than not switching.
 */
object SpokenLanguageHeuristic {

    /** Minimum letters needed before we trust a verdict. */
    private const val MIN_LETTERS = 4

    /** A script must own this share of the letters to win. */
    private const val DOMINANCE = 0.6

    /** ISO 639-1 of the language whose typical script is Latin. */
    private val LATIN_SCRIPT_LANGUAGES = setOf(
        "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "vi", "id", "ms",
        "sv", "no", "da", "fi", "cs", "sk", "ro", "hu", "ca", "eu", "gl",
    )

    /**
     * Detect the ISO 639-1 language of [text], or null when uncertain.
     * [currentLanguage] is the recognizer's current language (for the
     * Latin-ambiguity rule above).
     */
    fun detectLanguage(text: String, currentLanguage: String): String? {
        val counts = mutableMapOf<UnicodeScript, Int>()
        var letters = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            val script = UnicodeScript.of(cp)
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters < MIN_LETTERS) return null
        val (topScript, topCount) = counts.maxByOrNull { it.value } ?: return null
        if (topCount.toDouble() / letters < DOMINANCE) return null
        val language = scriptToLanguage(topScript) ?: return null
        if (language == "en" && currentLanguage in LATIN_SCRIPT_LANGUAGES) {
            // Latin text with a Latin-script recognizer: no information.
            return null
        }
        return language.takeIf { it != currentLanguage }
    }

    private fun scriptToLanguage(script: UnicodeScript): String? = when (script) {
        UnicodeScript.ARABIC -> "ar"
        UnicodeScript.DEVANAGARI -> "hi"
        UnicodeScript.BENGALI -> "bn"
        UnicodeScript.GURMUKHI -> "pa"
        UnicodeScript.GUJARATI -> "gu"
        UnicodeScript.ORIYA -> "or"
        UnicodeScript.TAMIL -> "ta"
        UnicodeScript.TELUGU -> "te"
        UnicodeScript.KANNADA -> "kn"
        UnicodeScript.MALAYALAM -> "ml"
        UnicodeScript.SINHALA -> "si"
        UnicodeScript.THAI -> "th"
        UnicodeScript.LAO -> "lo"
        UnicodeScript.TIBETAN -> "bo"
        UnicodeScript.MYANMAR -> "my"
        UnicodeScript.KHMER -> "km"
        UnicodeScript.HAN -> "zh"
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> "ja"
        UnicodeScript.HANGUL -> "ko"
        UnicodeScript.CYRILLIC -> "ru"
        UnicodeScript.GREEK -> "el"
        UnicodeScript.HEBREW -> "he"
        UnicodeScript.ARMENIAN -> "hy"
        UnicodeScript.GEORGIAN -> "ka"
        UnicodeScript.LATIN -> "en"
        else -> null
    }
}
