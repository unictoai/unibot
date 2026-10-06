package ai.unicto.unibot.speech

/**
 * Voice theme item 33: read-aloud with word highlighting (karaoke-style
 * follow-along for long replies).
 *
 * Android's TTS engine reports word progress via
 * [android.speech.tts.UtteranceProgressListener.onRangeStart] (API 26+) as a
 * character offset into the spoken utterance. [SpokenWord] pairs the utterance
 * text with the currently spoken word's character range; the UI highlights
 * that range.
 *
 * [WordBoundaryTracker] is the pure, unit-tested half: it maps a raw char
 * offset to the word that contains it. The engine half lives in
 * [TextToSpeechManager], which feeds offsets in.
 */
data class SpokenWord(
    /** The utterance text the range indexes into. */
    val text: String,
    /** Char range of the word being spoken, or null when unknown. */
    val wordRange: IntRange?,
)

object WordBoundaryTracker {

    /**
     * Char range of the word containing [offset] in [text], or null when the
     * offset is out of bounds or sits on whitespace/punctuation.
     *
     * Word characters are letters, digits, apostrophes and hyphens, so
     * "don't" and "on-device" highlight as single words.
     */
    fun wordAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty() || offset < 0 || offset >= text.length) return null
        if (!isWordChar(text[offset])) return null
        var start = offset
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = offset
        while (end < text.length - 1 && isWordChar(text[end + 1])) end++
        return start..end
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-'
}
