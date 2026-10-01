# unibot Desktop

The computer's Muse: a terminal chat with hands on this machine — shell, files,
browser, a look at the screen — and, through the [hub](hub.md), on every other
device of the account. Windows, macOS and Linux; one binary, standard-library
Python inside, no runtime to install.

Install and commands: [`desktop/README.md`](../desktop/README.md). Packages
come out of `scripts/build-desktop.py` and the `desktop` workflow, named
`unibot-desktop-terminal-<version>-…`: `…-windows-x64-setup.exe`,
`…-macos-arm64.pkg` / `…-macos-x64.pkg`, `…-linux-x64.deb`, plus archives
with the bare binary.

## What it is, and is not

It is a Muse in a terminal. It signs in to the same unibot Cloud account as
the phone, thinks with the relay's models (Qwen 3.7 Plus by default, with
vision, so screenshots can be looked at), and works with the same tool
vocabulary the hub speaks: `shell`, `files`, `file.get`, `file.put`, `open`,
`screen`, `notify`, `task`. Its guard (`guard.py`) is the phone's ShellGuard
ladder in Python: reads and builds run quietly; deleting, sending, paying and
system commands ask first, with the risk named.

It is not a windowed app. `unibot-desktop run --open` opens the web
console next to it, which is where the devices sit side by side. The windowed
desktop — the Python runtime with the hub, the Cloud account and hands on this
computer's screen, the web app in an Electron shell under
[`desktop/app/`](../desktop/app/) (a window, a tray, a global Stop and the
stage that shows where the hands click; installers `unibot-Desktop-<v>-…` on
every release, built by `.github/workflows/desktop-app.yml`) —
is described in [every-device.md](every-device.md); this binary stays the
zero-install fallback. `serve` keeps it connected in the background without a
terminal chat, so the phone can reach the computer while you are away from it.

## Two directions

From the terminal, to the phone: the agent has `device_shell`, `device_files`,
`device_get`, `device_put`, `device_open`, `device_screen`, `device_notify` and
`delegate` (a whole task for the other device's Muse). Say what you want; it
picks the device by name.

From the phone (or the web console), to this computer: incoming `shell`
commands go through the guard and, when they ask, the approval question is sent
back to whoever asked — a card on the phone, a card in the console. Incoming
`task`s run the agent in a conversation of their own; its approvals travel the
same way. `set approvals allow` stops the questions for this computer's own
terminal only.

## Config and data

`~/.unibot/desktop.json` (`$UNIBOT_HOME` moves it): cloud server, key,
device id and name, model, language, downloads folder. Files received land in
`~/Downloads/unibot`. Screenshots use `mss` + Pillow when bundled, else the
platform's own tool (`screencapture`, PowerShell, `gnome-screenshot` /
`grim` / `import`).

## Build

```
pip install pyinstaller pillow mss
python3 scripts/build-desktop.py          # desktop/dist/
python -m pytest desktop/tests
```

The macOS `.pkg` installs `/usr/local/bin/unibot-desktop` and a small
"unibot Desktop.app" that opens it in Terminal; the Windows setup adds the
folder to `PATH` and a Start-menu entry; the `.deb` installs `/usr/bin/…` and
a desktop entry. Nothing is signed — the trial builds are for your own
machines; macOS asks for right-click → Open once, Windows for "Run anyway".
