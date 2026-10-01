# The Android app

`android/` is unibot for the phone: [OpenMinis](https://github.com/OpenMinis/OpenMinis)
1.13 (GPL-3.0) — a native app whose agent runs **on the phone**, with a Linux shell, a
browser, MCP servers, skills and scheduled tasks inside the APK — with unibot's own
identity, design and features on top. Everything of unibot's lives in
`ai.unicto.unibot.*`; edits inside OpenMinis files are marked `// unibot:`. The
history of this choice is in [roadmap.md](roadmap.md); what came before it (a WebView
around a Python server, two APK flavours) is kept as a design record in
[archive/android-python-line.md](archive/android-python-line.md) and is not what you
download today.

One APK, one architecture: `unibot-<version>-arm64.apk` (arm64-v8a, Android 8.0 / API 26
or newer, `targetSdk` 35). Every release is signed with the same key, so a newer APK
installs over the old one and keeps its data. No Play Store listing: the app checks this
repository's GitHub Releases for a newer version and offers the download (*Settings →
About*).

## Install

1. Download `unibot-<version>-arm64.apk` from the
   [latest release](https://github.com/unictoai/unibot/releases/latest). Verify with
   `sha256sum -c unibot-<version>-arm64.apk.sha256` if you like.
2. Open it. Android asks once to allow installs from your browser or file manager.
3. **Sign in.** The first screen is the account: a phone number or an e-mail address,
   a code — or a password once you have set one. It is what lets your devices work as one
   ([hub.md](hub.md)) and brings a model to start with ([cloud.md](cloud.md): ¥10 free per
   account, ¥5 more per friend invited, ¥10 more for joining the co-creation programme; then [your own key](own-key.md)). Then choose which model answers: the account's
   own, or a key of your own (any OpenAI-compatible endpoint, or the OAuth sign-ins the
   app ships with). Running everything yourself with no account at all is the runtime's
   `cloud.required = false`; the phone app asks for the account.
4. Optional: the two permissions that let the agent use your phone's apps (skippable and
   revocable), and *Settings → Image & video models* for a look that can change and move.

## What the phone does

| | |
| --- | --- |
| **The agent, on the phone** | A sandboxed Alpine Linux, a real shell, files, a browser, MCP servers, skills in the Agent Skills format, memory, scheduled tasks; approvals before deleting, sending or paying. The model is the account's or your own; nothing you say passes through the project's relay when you use your own key. |
| **Hands** (`hands/`) | With *Hands* on and the accessibility service enabled, the agent looks at the phone's screen and taps, types and swipes in its apps — the last rung after APIs, fetches and the browser. A stage over the app shows where it is about to tap and a capsule with the step and **Stop**. Passwords and codes are always yours to type. [gui.md](gui.md) is the design record; the app's own hands are in `ai.unicto.unibot.hands`. |
| **Reach** (`reach/`) | Your computers, from the phone: "say it on the phone, it gets done there" — a shell command, a file, the computer's screen, or a whole task for the unibot running there. The way in is the hub (next row): install unibot Desktop on the computer and sign in with the same account, and it is under *Account → Devices* within seconds, on any network — the only way since 0.1.24 (the local-network host script is gone). *Settings → Computers* lists the account's computers and says which account the phone uses, since a missing computer has nearly always signed in with another one. Approvals are decided on the phone before anything is sent. [every-device.md](every-device.md). |
| **The hub** (`hub/`) | Every signed-in device of the account meets on the relay's hub: the phone sees your computers, asks them to do things, gets their approvals as cards, and can be asked by them. A foreground service keeps it reachable in the background (Android 13+ asks for the notification permission for that). [hub.md](hub.md). |
| **Coding agents** (`ui/coding/`) | The Cursor, Codex and Claude Code sessions on your computers, seen and steered from the phone. [coding-agents.md](coding-agents.md). |
| **Account** (`ui/cloud/`) | Who is signed in, the password, every device holding a key, usage by kind and by model, the ways out. |

The web console of the same account is at the relay (`/app`), the desktop app in
[desktop.md](desktop.md); the phone, the desktop and the web share the design language
described in [brand.md](brand.md).

## Privacy and permissions

The relay keeps an account id, a masked identifier, usage counts and the agent's name and
look (so your devices match) — never message content ([privacy.md](privacy.md)). On the phone, API keys, the account key and Reach
pairing tokens are in `EncryptedSharedPreferences`; the backup rules
(`res/xml/unibot_backup_rules.xml`, `unibot_data_extraction_rules.xml`) keep every
secret store out of device backups and transfers. Hands needs the accessibility service
and the overlay permission, both optional and both revocable from the same screen. The
app allows plain HTTP on the LAN for a model server or a computer of your own; the relay
and the hub are TLS only.

## Building it yourself

JDK 17 and the Android SDK (Android Studio installs both); `scripts/android/env.sh` sets
the environment on a bare machine. The project is under `android/src/android`:

```bash
cd android/src/android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # signed with android/keystore.properties when present
```

`scripts/rebrand.py` carries the version (`VERSION_NAME`, `VERSION_CODE`) and applies
unibot's naming to the OpenMinis tree; `android/BUILDING.md` covers the native pieces.
Without a signing key the release build is signed with the debug key, which installs but
cannot update a properly signed build. To sign, create a key once and keep it outside the
repository:

```bash
keytool -genkeypair -keystore ~/.unibot-release/unibot.jks -alias unibot \
        -keyalg RSA -keysize 4096 -validity 10950
```

and put its four values in `android/keystore.properties` (git-ignored: `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`).

## Releases

`.github/workflows/android.yml` builds the debug APK on every change under `android/`
(the "debug APK · arm64-v8a" check). A release is made from a tag by
`scripts/release-apk.sh <version>`: it checks the version against `rebrand.py` and
Gradle, builds `:app:assembleRelease` with the release key, writes
`dist/unibot-<version>-arm64.apk` and its `.sha256`, and with `--publish` tags
`v<version>` and attaches both to the GitHub release with the notes from
`docs/releases/v<version>.md`. A debug-signed APK never goes on a release, because it
could not be updated by a properly signed one. The `versionName` must match the tag,
like the Python package's version does.

## Where things are

| Path (`android/src/android/app/src/main/java/io/github/unibot/`) | Does |
| --- | --- |
| `cloud/UnibotCloud.kt` | The account: sign-in with a code or a password, the key in the encrypted store, `/v1/me`, usage, sessions, the localized error sentences |
| `hub/` | `Hub` (state, device identity, settings), `HubClient` (the socket with backoff; stops when the relay refuses the key), `HubService` (the foreground service), `HubActions` (what other devices may ask this phone), `HubErrors` (failures in words) |
| `reach/` | `Computers` (paired computers, tokens in the encrypted store), the offload handler that sends work to a computer |
| `hands/` | The accessibility service as the hand, the stage and the capsule, the screen reader |
| `ui/coding/` | The coding agents of your computers |
| `ui/cloud/` | Sign-in, the Account screen, the Devices section |
| `res/values*/nm_strings.xml` | Every unibot string, in English, 简体中文 and 繁體中文 (the three files carry the same keys) |
