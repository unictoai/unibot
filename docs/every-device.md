# Every device

The fourth thing that defines unibot ([roadmap](roadmap.md)): one agent, and
every device you own is a pair of hands and a front door for it. This page is
the design for that stage — what every device's app looks like, what each one
can do on its own and for the others, how a task travels, and which existing
pieces each part is built from. The protocol itself is in [hub.md](hub.md);
this page is about the product shape and the code around it.

## One Muse per device, one shape

Every device runs its own Muse — the phone its OpenMinis-based agent, the
computer the Python runtime — and every one of them shows the same app. The
Android app is the reference; the others follow its shape, not its pixels:

| On every device | Android today | Computer (this stage) |
|---|---|---|
| A face with a name pill and a status line at the top of the conversation | `HomeShell`, `AgentAvatarDisc` | the web app's chat header, dragon by default |
| One main chat, side chats in a drawer | drawer | drawer on narrow windows, a sidebar on wide ones |
| Feed · Ideas · Goals · Library | bottom bar | bottom bar / sidebar |
| Approval cards in the chat, three tiers, *remember* pills | `RiskApprovalCard`, `RiskTier` | the Sentinel's card with the same labels |
| Settings → Permissions: what was remembered, by tier | `GrantsSection` | Permissions page, grants by tier |
| Settings → Hands: the screen as a hand, off by default | `Hands` | Settings → Hands (this computer's screen) |
| Devices: the account's other devices, online dots, *Remote control*, rename, forget, *ask this device* | `DevicesSection`, `ComputersScreen` | Devices page |
| The stage while the hands work: a ring where the next tap lands, a capsule with the step and **Stop** | `HandsStage`, capsule | a live *Hands* card in the chat; the window adds an overlay |
| First run: what it is, sign in with a phone number or an e-mail (free) or your own key, the Hands permissions, meet | `FirstRunSetup` | the same four pages |

## Two roles

Each device plays both, at the same time.

**An agent of its own.** It climbs the same ladder everywhere ([gui.md](gui.md)):
a skill, a CLI or an MCP server first; a page fetched with your login; the
browser; and last, when you allowed it, the device's own screen — looking at a
screenshot, deciding one action, doing it, looking again. On the phone the
shell is the app's Linux sandbox and the screen is *Hands*. On the computer the
shell is the real shell (sandboxed where the runtime can), the browser is
Playwright or the system browser, and the screen is *Hands on this computer*:
`computer_screen`, `computer_act`, `computer_task` — the phone operator's loop
with a computer under it. A click is assessed by the words under the cursor
(`label`), exactly as a tap is, and the same words (pay, transfer, send,
delete…) make it a highest-tier approval that no standing permission covers.

**Hands and a front door for the others.** Signed in to one account, the
devices meet over the hub and each one exposes what it can do as actions
(`shell`, `files`, `file.get`, `file.put`, `open`, `screen`, `notify`) and as a
target for whole tasks (`task`). Any device's agent has the others as tools:
`devices`, `device_shell`, `device_files`, `device_get`, `device_put`,
`device_open`, `device_screen`, `device_notify`, and `delegate` — a job in
words for the other device's Muse. So "find the order number on my phone, then
put it in the spreadsheet on my Mac" is one conversation: the phone's Muse
finds it with Hands, the Mac's puts it in the file, and the person who asked
sees both halves in the chat they typed in.

A device decides what it lets the others do: **Remote control** off makes it
answer `info` and nothing else, while it still drives the rest.

## How a task travels

```
you, on the phone ──"编译一下项目，把日志发我"──▶ phone's Muse
                                                  │ delegate("desk", …)
                                                  ▼  hub: call task
                                         desk's Muse, in a side chat
                                         "From Pixel" that the desk shows live
                                                  │ tool events ─▶ phone (chips)
                                                  │ approval? ──▶ phone (card) ── approve ─▶
                                                  ▼
                                         result {text} ──▶ phone's Muse ──▶ you
```

- The **asking device's brain** runs the conversation; the target device's
  Muse runs the delegated job in a conversation of its own. Raw actions
  (`device_shell`…) skip the target's brain entirely.
- **Approvals are answered where the question was asked**: the target's
  Sentinel raises the card, the frame `event {stage:"approval"}` carries it
  back, the asker shows its usual card, and `approve` carries the answer. The
  target also shows the same card in its own side chat; the first answer wins.
  Commands typed on one device for another are judged on the typing device
  before they leave (ShellGuard on the phone, the Sentinel's assessment here).
- **Watching and taking over**: the delegated conversation is a real side chat
  on the target, so a person at that device sees it run, can type into it
  (folded into the running turn) and can press Stop. The asker sees the chips
  and the final answer; `stop` from the asker ends it too.
- Files and screenshots travel base64 in the frames; the size limits are the
  hub's.

## The computer: unibot Desktop

The desktop is the Python runtime (`unibot serve`) with three front doors on
the same local service — the terminal (`unibot chat`), the browser (the web
app it serves, also reachable from the phone on the same network) and a
window. That is the shape OpenCode v2 and Codex take (a local service, a CLI
and a desktop app over it) and it is what the runtime already was; this stage
adds the hub, the Cloud account and the hands to it.

```
   unibot serve  ─ FastAPI + WebSocket, 127.0.0.1:8787 ─┬─ web/ (React) in a browser or on the phone
      │ UnibotAgent · Sentinel · tools                        ├─ desktop/app (Electron window, tray, stage overlay)
      │ unibot/hub  ── wss://…/v1/hub ── the other devices └─ unibot chat (terminal)
      │ unibot/computer ── mss + pyautogui: the screen as a hand
      └ unibot/cloud ── the unibot Cloud account (e-mail code → key → models + hub)
```

- **Runtime** (`unibot/`): `cloud.py` signs in to unibot Cloud with an
  e-mail (or phone) code, keeps the key in the vault and can make the relay the
  model provider — the same *Sign in — free* first step as on the
  phone. `hub/` is the async port of the desktop binary's hub client: one
  socket kept up, incoming actions answered by `hub/actions.py`, incoming
  `task`s run in a visible side chat with their approvals relayed, outgoing
  calls behind the `device_*` and `delegate` tools. `computer/` is the screen
  as a hand on this machine. Settings: `[cloud]`, `[hub]`, `[hands]`; all of
  them switchable from the app.
- **Web app** (`web/`): the Devices page, side chats addressed to a device,
  the approval card's tiers and *remember* pills, the Hands card with Stop,
  Settings → Hands, the Cloud sign-in in the first run and under Connections,
  the dragon as the default face. Built into `unibot/server/static/`.
- **Window** (`desktop/app/`): an Electron shell in the layout OpenCode v2's
  desktop uses (electron-vite, a main process that starts or attaches to the
  local service and opens the app), plus what a browser tab cannot do: a tray
  icon, native notifications, the **stage** — a transparent, click-through,
  always-on-top window that draws the ring and the ripple where the hands are
  about to click (UI-TARS-desktop's ScreenMarker, the way `HandsStage` redrew
  it on Android) — and a global Stop shortcut. Packaged since 0.1.19:
  `unibot-Desktop-<version>-…` installers for Windows, macOS and Linux on every
  release ([desktop.md](desktop.md)).
- **The standard-library binary** (`desktop/unibot_desktop`) stays as the
  zero-install fallback for a machine without Python; its hub code is the
  origin of `unibot/hub`. In time its terminal becomes a client of the
  service like the other two doors.

### Hands on this computer

`computer_task` is the phone operator with a computer under it: the same loop
(look, decide one action, act through the Sentinel, look again), the same
report, the same Stop. What differs is the dialect and the device:

- **Dialect.** The model speaks Qwen's `computer_use` shape — `left_click`,
  `double_click`, `right_click`, `left_click_drag`, `mouse_move`, `scroll`,
  `type`, `key`, `wait`, plus `open` (an app by name), `answer`, `ask_user` and
  `terminate` — with coordinates on the 0–999 grid the phone uses, so one
  vision model serves both. The reply format (Thought / Action / one
  `<tool_call>`) and the parser are the phone's.
- **Device.** Screenshots through `mss` (every platform, scaled for the
  model); actions through `pyautogui` (`pip install "unibot[hands]"`), with
  `xdotool` as the fallback on Linux when it is there and pyautogui is not; the
  active window's title from the platform (`xdotool` / `osascript` /
  `GetForegroundWindow`) so the card and the log say *in Firefox*, not *on the
  screen*.
- **Permissions.** macOS asks for Screen Recording and Accessibility once;
  Settings → Hands says so and opens the panes. Linux needs X11 (Wayland has
  no portable way to move the pointer yet; the page says so). Windows needs
  nothing.
- **Never** typed by the hands: passwords, PINs, card numbers, one-time codes.
  The operator asks; the person types.

## The phone

Already both roles since 0.1.12/0.1.13/0.1.17: the sandbox shell and Hands
locally, `unibot-pc` and the hub outward, `task` inward through the headless
chat runner. Left for this stage's Android pass, in order: the hub `approve`
reaching the RiskGate card (today a task the desk delegates is approved on the
phone's screen only), the desk's Hands events shown while it works, a
*Devices* entry in the drawer. None of it blocks the desktop work.

## unibot Web: a Muse with no device of yours

The same runtime, run for you by whoever operates a showcase: sign in at the operator's
web address with an e-mail or a phone code and the showcase gateway
(`demo/showcase/gateway/showcase_gateway/accounts.py`) starts a container of
your own — the `ghcr.io/unictoai/unibot` image with three named volumes
(`/data`, `/workspace`, `/home/muse`), on a network next to the Cloud relay —
seeded from the environment so it comes up signed in as the device *Web*, with
the Cloud as its model and the first run behind it (`UNIBOT_CLOUD_KEY`,
`UNIBOT_CLOUD_BASE_URL`, `UNIBOT_HUB_NAME`, `UNIBOT_ONBOARDED`;
`unibot/hub/service.py`, `_seed_from_env`). The browser is sent to
`https://<slug>.<SESSION_DOMAIN>/?token=…` (the operator's session domain on the project's gateway), the door the phone's QR code opens.
It has local hands of its own (a shell and files inside the container, the
browser tool) and sits on the hub like any other device: the phone can ask it,
it can ask the phone or the computer. Quiet for six hours, the container is
stopped and its volumes kept; the next request wakes it. Details and the
settings in [demo/showcase/README.md](../demo/showcase/README.md).

## iOS, the web console, glasses

iOS speaks the hub (`info`, `open`, `notify`) and gets the shape later
([ios.md](ios.md)). The cloud console (`/app/`) is a front door with no hands
of its own and stays that way. Glasses are a sentence in and a sentence back,
the hands elsewhere — the hub is already enough for them.

## What is reused, and from where

- **UI-TARS-desktop** (Apache-2.0): the operator pattern — screenshot in,
  parsed action out, an `execute` per device — and the on-screen marker while
  the agent works. Not its Electron app or its model: its action space is the
  UI-TARS model's, ours is Qwen's `mobile_use` / `computer_use`.
- **OpenCode v2** (MIT): the desktop as a thin Electron shell that starts a
  local service and renders a web app against it (electron-vite,
  electron-builder, a tray, deep links later). Not its agent: it is a coding
  agent around a project directory; unibot is a personal one around a person.
- **Codex** (Apache-2.0): the CLI as a first-class front door to the same
  agent, approvals in the terminal, a sandbox for commands — the runtime's
  `unibot chat` and the Sentinel already have that shape.
- **Qwen-Agent / MemGUI-Bench** (MIT): the `mobile_use` and `computer_use`
  dialects and the reply parser, already in `unibot/phone/operator.py`.
- **pyautogui**, **mss**, **xdotool**: mouse, keyboard and screenshots. No
  input library of our own.

## Status

| | Phone | Computer | unibot Web | Web console | iOS |
|---|---|---|---|---|---|
| Local shell / files / browser | yes | yes (runtime) | yes, inside its container | no hands | no |
| Screen as a hand | Hands (0.1.12) | `computer_*` (0.1.19) | — | — | — |
| Drives other devices | `unibot-pc`, hub | `device_*`, `delegate` (0.1.19) | the same runtime | picks a device, sends a task | — |
| Answers other devices | yes | in a visible side chat (0.1.19) | yes | — | info / open / notify |
| GUI in the Android shape | reference | web app (sidebar on wide screens) + window (0.1.19) | the web app | console | later |
| Stage while the hands work | `HandsStage` | Hands card; the stage overlay in the window | — | — | — |

Released with 0.1.19: the APK, the desktop installers (`unibot-Desktop-…`,
[`.github/workflows/desktop-app.yml`](../.github/workflows/desktop-app.yml)),
the terminal binary, and unibot Web at the operator's address.

## Debugging it all on one machine

Two runtimes and a relay on `127.0.0.1` are enough to walk a task from one
device to another and back. Nothing here needs a real account, an e-mail
provider or a phone.

```bash
# 1. the relay, in dev mode: no CLOUD_SECRET, codes go to the log; sign-up and the
#    hub are on by default. Without UPSTREAM_KEY the proxy answers 503 while sign-up
#    and the hub still work; give the relay a real key (from the environment, never
#    on the command line) if you want the Cloud model to answer.
mkdir -p /tmp/nm-dev/cloud
CLOUD_DB=/tmp/nm-dev/cloud/cloud.db CODE_SENDER=log PUBLIC_BASE=http://127.0.0.1:8790 \
  PYTHONPATH=cloud .venv/bin/python -m unibot_cloud --host 127.0.0.1 --port 8790 \
  > /tmp/nm-dev/cloud/relay.log 2>&1 &

# 2. two runtimes, each with its own data dir, workspace and port
for d in a b; do
  mkdir -p /tmp/nm-dev/$d/home /tmp/nm-dev/$d/ws
  printf 'data_dir = "/tmp/nm-dev/%s/home"\nworkspace = "/tmp/nm-dev/%s/ws"\n\n[llm]\napi_key = ""\n\n[cloud]\nbase_url = "http://127.0.0.1:8790"\n\n[hub]\nname = "%s"\n' \
    $d $d "$([ $d = a ] && echo 'Desk A' || echo 'Laptop B')" > /tmp/nm-dev/$d/config.toml
done
# ambient provider keys would be picked up as the model — clear them for a clean first run
env -u OPENAI_API_KEY -u DASHSCOPE_API_KEY -u ANTHROPIC_API_KEY -u OPENAI_BASE_URL \
  .venv/bin/unibot serve -c /tmp/nm-dev/a/config.toml --no-auth --no-qr --port 8799 > /tmp/nm-dev/a/serve.log 2>&1 &
env -u OPENAI_API_KEY -u DASHSCOPE_API_KEY -u ANTHROPIC_API_KEY -u OPENAI_BASE_URL \
  .venv/bin/unibot serve -c /tmp/nm-dev/b/config.toml --no-auth --no-qr --port 8798 > /tmp/nm-dev/b/serve.log 2>&1 &

# 3. sign both in with the same e-mail in the first run (the code is in relay.log:
#    grep -i code /tmp/nm-dev/cloud/relay.log), pick "Use the Cloud model" on each.
# 4. the window over Desk A:
cd desktop/app && npm install && npm run build && \
  UNIBOT_PORT=8799 UNIBOT_HOME=/tmp/nm-dev/a/home npx electron out/main/index.js
```

Then on Desk A: *Devices* shows Laptop B online; *Ask* (or the chip under the
chats) opens a chat *on Laptop B*; a task typed there runs on B, its tool
chips and approval cards appear in A with the device pill, an approval decided
in A closes the card on both sides, and B's answer lands in A's chat. Swap the
ports and the same happens the other way. `npm run stage-demo` in
`desktop/app/` plays a scripted hands run into the stage without moving the
real mouse. Stop everything with `ss -ltnp | grep ':879'` and `kill`.

## The phone, in 0.1.19

Three things the computer does are mirrored on Android since 0.1.19: the
phone's hub client reads the `tool` / `tool_result` / `approval_result` stages
(`ReachOffloadHandler`), hub `approve` frames reach `RiskGate` and the phone's
own approvals travel to the asker as `approval` events (`HubActions`), and a
*Devices* row sits in the drawer next to the chats. Still to come: the desk's
hands events (`HandsLive`) as a Hands card on the phone when the phone asked
for the task.
