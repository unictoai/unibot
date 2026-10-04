# Troubleshooting

Real problems users have hit, and what fixes them. If your issue isn't here,
open an issue at <https://github.com/unictoai/unibot/issues> — a screenshot
plus one line of what you were doing is a complete bug report.

## Provider errors

### `Provider error: [400] ... 'messages.N': for 'role:assistant' ... must be satisfied`
The app sent the provider a field it rejects. This was a real bug: unibot echoed
a DeepSeek-only `reasoning_content` field to Groq/Cerebras/Mistral, whose strict
validators 400 on unknown fields. **Fixed in v1.2.3** — update the app. If you
see a 400 on a newer version, check **Settings → Logs** for the `[DIAG-400]`
block (it shows the request's shape, never your content) and attach it to your
issue.

### `Provider error: [404] The model '...' does not exist or you do not have access`
The model ID was retired by the provider (e.g. Groq decommissioned
`llama-3.3-70b-versatile` on 2026-08-16 — providers rotate lineups without
warning). **Fix:** open the provider in Settings → Providers and tap
**Refresh models** to pull the live list (v1.2.4+), then pick a current ID.
Current live IDs on Groq's free tier include `openai/gpt-oss-20b`,
`openai/gpt-oss-120b`, and `qwen/qwen3.6-27b`.

### `Provider error: [413] Request too large for model '...'`
Your conversation history no longer fits the model's free-tier quota — common
after long agent runs with thinking blocks and tool calls. Nothing is broken
and your messages are safe. **Fix:** start a **New chat**, or **Change model**
to one with a larger free quota (v1.2.4+ offers both right from the error).

### `Provider error: [401]` / `[429]`
401: your API key is wrong or revoked — re-paste it in Settings → Providers.
429: you've hit the provider's rate limit — wait a minute and retry. Free tiers
are rate-limited; adding a second provider's key spreads the load.

## Voice mode

### App crashes when voice mode starts (native crash)
On some devices two native libraries shipped conflicting builds of the same
engine file. **Fixed in v1.2.2** (the build now pins the correct copy and fails
CI loudly if it ever skews again). Update the app. If a native crash ever
recurs, **Settings → Logs** contains a `native-crash-*.log` with the abort
reason captured automatically — attach it to your issue.

### "RECORD_AUDIO required" / mic permission dead-end
The old permission flow showed a dead-end error. **Fixed in v1.1.1**: the app now
explains why it needs the mic, opens the system dialog, and deep-links to
system Settings if you deny it. Grant the permission when asked — audio never
leaves your phone.

### Voice downloads stall or the voice sounds wrong
Voices download from the `tts-voice-v1` GitHub release as ~60 MB files. If a
download was interrupted, delete the voice and re-download it — the app
validates files before loading them (v1.2.1+). A phone with no system speech
service (e.g. some Infinix devices) is fully supported: unibot falls back to
its own on-device speech engine automatically.

## Chat & UI

### "Auto (on-device)" model name renders vertically, one letter per line
A layout bug in the model picker. **Fixed in v1.1.1** — update the app.

### Tapping "Chat stats" does nothing
It was a dead menu item. **Fixed in v1.1.1** — it now opens the real stats
screen. Rule we hold ourselves to: no dead buttons ship.

### Google sign-in fails with a 400
The OAuth callback had a port race and double-decoded the auth code.
**Fixed in v1.0.3** — update the app. Note: Google sign-in stays in Testing
mode (up to 100 approved test accounts) because public Gmail-level verification
requires a paid security audit; it's only needed for the Gmail/Drive/Calendar/
YouTube connectors — everything else works without it.

### App lock crashes on enable
Missing biometric permission in early builds. **Fixed in v1.0.1**.

## General

### Something broke right after an update
Releases install over the previous version and keep your chats, providers, and
settings. If the new version misbehaves, the fastest signal is: which version
were you on before, and what were the exact last 2–3 taps before it broke.

### Battery / heat during long agent runs
Agent mode with thinking on is genuinely heavy work — big models, many
round-trips. Use **Battery saver** in Settings to calm animations and background
intervals, and prefer smaller models (e.g. `openai/gpt-oss-20b`) for long runs.
