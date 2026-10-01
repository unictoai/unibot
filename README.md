<p align="center">
  <img src="https://raw.githubusercontent.com/unictoai/unibot/main/assets/brand/unibot-cover.svg" alt="unibot — an open-source personal agent for every device you own">
</p>

<p align="center">
  <a href="https://github.com/unictoai/unibot/blob/main/README.md">English</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_zh.md">简体中文</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_zh-TW.md">繁體中文</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_es.md">Español</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_fr.md">Français</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_id.md">Bahasa Indonesia</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_ja.md">日本語</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_ko.md">한국어</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_ru.md">Русский</a> |
  <a href="https://github.com/unictoai/unibot/blob/main/README_vi.md">Tiếng Việt</a>
</p>

<p align="center">
  <a href="https://github.com/unictoai/unibot/stargazers"><img src="https://img.shields.io/github/stars/unictoai/unibot?style=flat&label=stars" alt="GitHub stars"></a>
  <a href="https://github.com/unictoai/unibot/releases"><img src="https://img.shields.io/github/downloads/unictoai/unibot/total?label=downloads" alt="Downloads"></a>
  <a href="https://github.com/unictoai/unibot/actions/workflows/ci.yml"><img src="https://github.com/unictoai/unibot/actions/workflows/ci.yml/badge.svg?branch=main" alt="Test Suite"></a>
  <a href="https://github.com/unictoai/unibot/blob/main/LICENSE"><img src="https://img.shields.io/github/license/unictoai/unibot?label=license" alt="GPL-3.0-or-later"></a>
</p>

> [!IMPORTANT]
> **Free, open source, non-profit — bring your own key.** unibot has no account and no sign-up: install it, paste an API key from any provider, and start talking. Your key and your words go straight to the provider you chose — nothing passes through our servers, because there are none. Your data stays on your phone.

unibot is an open-source personal agent for Android: one agent with a name and a look of its own that **does things instead of answering questions**. The whole agent runs **on the phone** — a Linux root file system, a shell, a browser, MCP, skills and scheduled tasks inside the APK — with hands for the apps that never had an API (the phone's own screen, with your permission). It keeps working while the app is closed, remembers you, and stops to ask before anything you could not undo.

unibot is a friendly fork of [unibot](https://github.com/unictoai/unibot) (which itself grew out of [OpenMinis](https://github.com/OpenMinis/OpenMinis)), rebranded and refocused on bring-your-own-key: any model, any provider, your key. GPL-3.0-or-later, like upstream.

## Get it

Download the APK from [the latest release](https://github.com/unictoai/unibot/releases/latest) (`unibot-<version>-arm64.apk`), open it on your phone, and allow the install. Updates install over previous releases.

1. Open unibot. The welcome screen asks for a provider key — paste it (or skip and add one later).
2. Pick a model. Say something. It answers — and it can act: files, shell, browser, scheduled routines.

Any OpenAI-compatible endpoint works: OpenAI, Anthropic, DeepSeek, OpenRouter, xAI, Alibaba Bailian, Moonshot, Zhipu — or your own vLLM / Ollama box on the local network. Add more under Settings → Providers.

## What's new in unibot

- **v0.1.0** — the first unibot release: full rebrand (new name, violet theme, new icon), bring-your-own-key onboarding with no account, update checks against this repo, and signed release APKs built by GitHub Actions.

## Build it yourself

See [docs/android.md](docs/android.md). In short: install the Android SDK (API 36, NDK r27c), then `./gradlew :app:assembleDebug` under `android/src/android`. Release builds sign with the project key when `android/keystore.properties` exists, otherwise the debug key.

## Privacy

Everything the agent does happens on your device; the only network traffic is yours — to the model provider whose key you pasted. See [docs/privacy.md](docs/privacy.md).

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). unibot is a fork of unibot by the unibot contributors, which builds on OpenMinis; their copyright notices and license terms are preserved.
