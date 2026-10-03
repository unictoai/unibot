# Contributing to unibot

unibot is an open-source personal AI agent for Android (building on [OpenMinis](https://github.com/OpenMinis/OpenMinis) for the on-device runtime), refocused on bring-your-own-key. GPL-3.0-or-later — see [LICENSE](LICENSE) and [NOTICE](NOTICE); by contributing you agree your contribution is licensed the same way.

## How to help

- Use unibot for a real task, report what broke, then pick something focused. Issues and pull requests are welcome; for anything larger than a fix, open an issue first so we can agree on the shape.
- The flagship is the **Android app** (`android/src/android`, application ID `ai.unicto.unibot`). It builds with `./gradlew :app:assembleDebug` (Android SDK, API 36, NDK r27c).
- Attribution to unibot and OpenMinis in the About screen and NOTICE is required and stays — don't strip it.

## Ground rules

- Keep it bring-your-own-key: no hosted services, no hard-coded servers, no phoning home. The relay client under `cloud/` exists so anyone can self-host a relay; the app ships with no default relay.
- One accent hue (violet) — see `docs/brand.md`.
- Don't reintroduce the unibot / OpenMinis names or logos as unibot's own identity.

## Translations

The English README is authoritative; the translated READMEs are currently stubs. If you can translate, PRs are very welcome.
