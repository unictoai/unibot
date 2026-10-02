package ai.unicto.unibot.ui.chat

// [P2-modes] Composer-mode behaviour (Study Mode + custom modes), extracted
// from ChatViewModel the same way ChatViewModelMentionExt was.
//
// The active mode is GLOBAL (not per-session) and owned by
// [PromptLibraryStore]: a mode stays on across chats until the user turns it
// off, and the slash menu, the library screen, and the system prompt all
// read the same source of truth. This extension only adds the chat-side
// verbs (toggle + prompt injection + user-facing notices).

import kotlinx.coroutines.flow.StateFlow

/**
 * Activate [preset] as the composer mode (or pass null to clear). The mode's
 * content is injected into the system prompt by [activeComposerModeSection]
 * for every subsequent turn, in every chat, until cleared.
 *
 * A short system notice is appended so the change is visible in the chat
 * itself — there is no persistent composer chip (ChatScreen.kt is frozen).
 */
internal fun ChatViewModel.setComposerMode(preset: PromptPreset?) {
    val current = PromptLibraryStore.activeModeNow()
    if (current?.id == preset?.id) return
    PromptLibraryStore.setActiveModeId(context, preset?.id)
    if (preset != null) {
        appendSystemInfo(
            text = "Mode on: ${preset.name}. ${preset.description.ifEmpty { "Its instructions now apply to every reply." }} " +
                "Turn it off from the / menu or the Prompt Library.",
            iconKind = "mode",
        )
    } else if (current != null) {
        appendSystemInfo(
            text = "Mode off: ${current.name}. Back to the default assistant.",
            iconKind = "mode",
        )
    }
}

/**
 * Toggle the built-in Study Mode. Used by the `/study` slash command and the
 * prompt library screen.
 */
internal fun ChatViewModel.toggleStudyMode() {
    val study = PromptLibraryStore.builtInPresets().firstOrNull { it.id == STUDY_MODE_PRESET_ID }
        ?: return
    if (PromptLibraryStore.activeModeNow()?.id == study.id) setComposerMode(null)
    else setComposerMode(study)
}

/**
 * Toggle any MODE preset by id: tapping the active one turns it off,
 * tapping another switches to it.
 */
internal fun ChatViewModel.toggleModeById(id: String) {
    if (PromptLibraryStore.activeModeNow()?.id == id) {
        setComposerMode(null)
        return
    }
    val preset = PromptLibraryStore.findById(id) ?: return
    if (preset.kind != PresetKind.MODE) return
    setComposerMode(preset)
}

/**
 * The system-prompt section for the active composer mode, or null when no
 * mode is active. Called from [ChatViewModel.buildSystemPrompt] — appended
 * near the end of the prompt (after memory/first-conversation addenda) so
 * the cache-friendly static prefix above stays byte-stable.
 *
 * Rendered as a clearly-labeled block the model treats as a hard behavioral
 * constraint; the user's latest message still wins on direct conflict (same
 * precedence rule as the SOUL personality block).
 */
internal fun ChatViewModel.activeComposerModeSection(): String? {
    val mode = PromptLibraryStore.activeModeNow() ?: return null
    val body = mode.content.trim()
    if (body.isEmpty()) return null
    return "Active mode: ${mode.name} — apply the following instructions to every " +
        "reply in this chat (the user's latest message takes precedence on direct conflict):\n$body"
}

/** Reactive view of the active mode id for the `/` menu subtitles. */
internal fun ChatViewModel.activeModeIdFlow(): StateFlow<String?> = PromptLibraryStore.activeModeId

/** Stable id of the built-in Study Mode preset. */
internal const val STUDY_MODE_PRESET_ID = "builtin-study-mode"
