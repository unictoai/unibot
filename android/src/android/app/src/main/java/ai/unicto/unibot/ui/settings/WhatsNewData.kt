package ai.unicto.unibot.ui.settings

/**
 * v1.4.0 item 72 — the bundled "What's new" changelog.
 *
 * Data, not code: one entry per shipped version, newest first, 3–6 plain
 * bullets each. The screen renders this verbatim; the one-shot dialog shows
 * the newest entry's highlights. Keep entries short — this ships in the APK.
 */
data class WhatsNewEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

val WHATS_NEW: List<WhatsNewEntry> = listOf(
    WhatsNewEntry(
        version = "v1.5.0",
        date = "2026-10-10",
        highlights = listOf(
            "Ask my documents: semantically search your files, with answers citing the exact file and line.",
            "Planner view: multi-step tasks as a live checklist — pause, edit a step, or skip ahead.",
            "Voice conversation fixed: no more cut-off speech, full replies on multi-step turns, mic-permission retry works.",
            "Swarm runs survive: leave the screen without losing progress, auto-retry on network blips, per-run token budget.",
            "Routine failure alerts, run history, and 20+ security hardening fixes across app and server.",
        ),
    ),
    WhatsNewEntry(
        version = "v1.4.0",
        date = "2026-10-06",
        highlights = listOf(
            "Swarm agents, leveled up: plan-approval gate, mission history with one-tap rerun, custom crews, mid-run steering, and completion notifications.",
            "Voice goes real-time: low-latency conversation, fully offline mode, voice notes that transcribe on send, and read-along highlighting.",
            "Chat gets friendlier errors, mid-chat model switching, chat folders, full-text search, branches, and one-tap export.",
            "Privacy blade: app lock, incognito chats, permission audit, and a kill-switch quick tile.",
            "UI polish pass: redesigned nav drawer, empty states everywhere, tap-to-expand errors, quick-ask home widget, and share-to-unibot.",
        ),
    ),
    WhatsNewEntry(
        version = "v1.3.5",
        date = "2026-10-06",
        highlights = listOf(
            "Swarm entry in the nav drawer, right below Devices — with a live status hint.",
            "UI optimization pass: centralized theme tokens, calmer motion, proper touch targets.",
            "Self-cleaning CI: stale bot issues now close automatically on green builds.",
        ),
    ),
    WhatsNewEntry(
        version = "v1.3.0",
        date = "2026-10-05",
        highlights = listOf(
            "Swarm agents: a hierarchical crew (planner, workers, verifier) that researches, writes, and verifies in parallel.",
            "Dedicated swarm space: mission composer, crew presets, live agent timeline, pause/resume/cancel.",
            "Checkpointing: a killed app resumes the mission as paused — no lost work.",
            "Per-agent token cost accounting, wired through the spending clamp.",
        ),
    ),
    WhatsNewEntry(
        version = "v1.2.9",
        date = "2026-10-04",
        highlights = listOf(
            "Fixed the 'request too large' failure on providers with oversized token budgets (e.g. Groq) — the clamp now self-heals.",
            "Provider repair: host-aware key handling for custom OpenAI-compatible endpoints.",
        ),
    ),
)
