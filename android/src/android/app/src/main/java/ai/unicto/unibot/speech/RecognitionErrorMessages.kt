package ai.unicto.unibot.speech

/**
 * Voice theme item 30: friendly STT one-liners.
 *
 * The engines surface raw [RecognitionError] enums (and, on some ROMs, raw
 * engine strings like "ERROR_NETWORK_TIMEOUT") — neither is something a user
 * should ever read. This maps every kind to one plain sentence that says what
 * happened and what to do next. Pure function, no Context — unit-tested.
 *
 * Callers should prefer an engine-supplied message only when it is already
 * user-facing; otherwise fall back to [friendlyMessage].
 */
fun RecognitionError.friendlyMessage(): String = when (this) {
    RecognitionError.NO_MATCH ->
        "Didn't catch that — try speaking again."
    RecognitionError.TRANSCRIPTION_FAILED ->
        "Heard you, but couldn't make out the words. Try again a little slower or closer to the mic."
    RecognitionError.NETWORK ->
        "Voice recognition needs a connection right now. Check you're online and try again."
    RecognitionError.PERMISSION_DENIED ->
        "Microphone access is off. Allow it to use voice input."
    RecognitionError.OEM_NO_SERVICE ->
        "This phone has no built-in voice recognition. Download the offline voice model to talk on-device instead."
    RecognitionError.RECOGNIZER_BUSY ->
        "The voice service is busy. Wait a moment and try again."
    RecognitionError.LANGUAGE_UNSUPPORTED ->
        "That language isn't supported by the current voice engine."
    RecognitionError.AUDIO_ERROR ->
        "Couldn't open the microphone. Another app might be using it."
    RecognitionError.UNKNOWN ->
        "Voice input failed. Try again."
}

/**
 * True when an engine-supplied message is safe to show as-is. Raw engine
 * strings (ERROR_NETWORK_TIMEOUT, SCREAMING_ENUMS) fail this and the caller
 * falls back to [friendlyMessage].
 */
fun String.isUserFacingSttMessage(): Boolean {
    if (isBlank()) return false
    if (contains("ERROR_")) return false
    if (length > 4 && all { it.isUpperCase() || it == '_' || it.isDigit() || it.isWhitespace() }) return false
    return true
}
