# Roadmap

Where unibot is headed. This is a living document — it changes when the
direction changes, not on a calendar.

## Shipped

- **v1.2** — the biggest release: ~60 features. 10 new first-class free-tier
  providers (Groq, Cerebras, Mistral, GitHub Models, SambaNova, NVIDIA NIM,
  DeepSeek, Z.AI, Nebius, Chutes), browser power-ups (tabs, bookmarks, reading
  mode, ad-block), chat power (editing, regeneration variants, templates,
  LaTeX), privacy tools (traffic viewer, auto-lock, encrypted export), voice
  upgrades, on-device AI (benchmark, sampler settings), widgets, and a
  never-give-up agent.
- **v1.1** — voice mode: fully on-device speech-to-text and text-to-speech,
  animated voice conversations, tap-to-interrupt. Built for phones with no
  system speech service.
- **v1.0** — the foundation: 31 connectors, privacy core (local-only gate,
  kill-switch, traffic log, chat lock), scheduled agents, creator studio,
  on-device models, full animation pass.

## Next

- **Provider robustness** — live model-list refresh (no more stale catalogs),
  human-readable provider errors with one-tap recovery (new chat / change
  model). *In progress.*
- **More free providers** — wherever a generous free tier appears and the API
  is OpenAI-compatible, it gets a first-class row with a FREE TIER badge.
- **Animation polish** — every screen keeps getting smoother; nothing ships
  that stutters on low-RAM phones.

## Standing principles (not a backlog)

- **Privacy first, always.** On-device first, bring-your-own-key, no account,
  no tracking. A feature that needs your data to leave the phone doesn't ship.
- **No dead buttons.** Every control does something or it doesn't exist.
- **No Play Store until it's ready.** No listing, no monetization talk until
  the app is powerful, useful, and privacy-focused enough to deserve it.
- **Free-first.** Everything must work without spending money — free provider
  tiers, optional on-device models, no paid gates.

## Exploring (no promises, no dates)

- SMS-based expense tracking from bank alerts (fully on-device parsing)
- Deeper on-device model support as phone hardware allows
- iOS — the codebase carries the iOS app source; no active development yet

## Have a say

The fastest way to move something from "exploring" to "next" is a concrete
user story: open an issue at
<https://github.com/unictoai/unibot/issues> describing what you'd do with it.
