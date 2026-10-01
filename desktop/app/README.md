# unibot for the desktop (Electron shell)

The app you see is the web app the runtime serves (`web/` → `unibot/server/static`),
loaded from `127.0.0.1` with the runtime's token. This package adds what a browser tab
cannot do:

- starts `unibot serve` when it is not running (or attaches to the one that is);
- a tray that keeps the runtime going when the window is closed;
- a global **Stop** — `Ctrl/Cmd+Shift+Esc` — that takes the mouse back from the hands;
- the **stage**: a transparent, click-through window over the display that draws the ring
  and the ripple where the hands are about to click (UI-TARS-desktop's ScreenMarker, the
  Android `HandsStage`), with a pill saying what is going on and a **Stop** button on it —
  the one part of the stage that takes a click (the shortcut works too).

Design notes and the wider picture: [`docs/every-device.md`](../../docs/every-device.md),
[`docs/desktop.md`](../../docs/desktop.md).

## Develop

```bash
cd desktop/app
ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ npm install   # the mirror is optional
npm run check          # typecheck + build (main, preload, stage renderer)
npm run start          # build, then open the window against the runtime
npm run stage-demo     # build, then play a scripted hands run into the stage
```

Environment the shell reads:

| variable | meaning |
| --- | --- |
| `UNIBOT_PORT` | the runtime's port (default `8787`) |
| `UNIBOT_HOME` | the runtime's data dir, where `server_token` lives (default `~/.unibot`) |
| `UNIBOT_CONFIG` | passed to `unibot serve -c …` when the shell has to start it |
| `UNIBOT_BIN` | the `unibot` executable; otherwise `../../.venv/bin/unibot`, then `PATH` |

Flags for checks without a person at the screen: `--screenshot=/tmp/win.png` writes the
window and quits; with `--stage-demo` it writes the stage instead.

`npm run dist` packages it with electron-builder — a bundled runtime built by
`scripts/desktop-app/build-runtime.py` goes into `resources/runtime/` first — and
`.github/workflows/desktop-app.yml` does the same on every `v*` tag, attaching
`unibot-Desktop-<version>-…` (`.exe`, `.dmg` + `.zip`, `.AppImage`, `.deb`) to the
release. On macOS electron-builder only lays out the `.app` (`npm run dist:dir`);
`scripts/desktop-app/package-mac.sh arm64|x64` ad-hoc signs it and writes the zip
(`ditto`) and an APFS dmg (`hdiutil`) — electron-builder's HFS+ image failed to
copy with Finder error -36 on some Macs. There is no developer certificate: macOS
shows "Apple could not verify" (Open Anyway in System Settings → Privacy &
Security), Windows "Run anyway" ([docs/troubleshooting.md](../../docs/troubleshooting.md)).
The shell starts the runtime in its data folder with `UNIBOT_DATA_DIR` and
`UNIBOT_WORKSPACE` set, so nothing is written next to the executable.
The tray has **About unibot** (version, runtime, shortcuts) and **Check for
updates** (GitHub Releases; nothing is downloaded on its own). The standard-library
`desktop/unibot_desktop` binary remains the zero-install way to get a window.
