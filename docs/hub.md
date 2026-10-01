# The hub: every device is a Muse

Sign in to the same unibot Cloud account on a phone, a computer and a browser,
and they see each other. Each device runs its own Muse with its own hands — the
phone's apps and sandbox, the computer's shell and screen — and any of them can
ask any other for something, from any network. The web console has no hands of
its own; it is a front door to the rest.

```
phone ──┐                         ┌── computer (unibot Desktop)
        ├──▶  unibot Cloud  ◀───┤
web  ───┘        /v1/hub          └── another phone / computer
```

Every device opens one outbound WebSocket to the relay and keeps it. Nothing
listens on the device, nothing needs a port forwarded or a shared Wi-Fi; the
relay only routes frames between devices of one account and never looks inside
a task. This is the way to add a computer: install unibot Desktop on it and
sign in with the same account — nothing to download by hand, no code to type.
It is the only way in: the stand-alone host script that paired over the local
network (0.1.13–0.1.23) is gone, so a computer that is missing from the phone's
list has, nearly always, signed in with another account — *Settings → Computers*
on the phone shows which account the phone uses.

## What you can say

On the phone, in any chat:

- "在我 Mac 上列一下下载文件夹" — `unibot-pc ls ~/Downloads --on mac`
- "让电脑把项目编译一遍，把日志发给我" — `unibot-pc task "…" --on desk`
- "电脑截个图给我看" — `unibot-pc screen --on desk`

On the computer, in the terminal (`unibot-desktop`):

- "on my phone, take a screenshot" — `device_screen`
- "tell the phone's Muse to read me the last notification" — `delegate`
- "send the phone a notification: dinner's ready" — `device_notify`

In the web console (`/app`): pick a device, type. The task runs on that
device's Muse; approvals show as cards in the page.

Two kinds of request travel over the hub:

- **Actions** — `info`, `shell`, `files`, `file.get`, `file.put`, `open`,
  `screen`, `notify`. Raw and immediate. A `shell` command is judged *on the
  caller* before it is sent, with the same ladder as a local command (the
  phone's ShellGuard, the desktop's `guard.py`): reads and builds go quietly,
  deleting / sending / paying / system commands wait for the approval card on
  the device that asked.
- **Tasks** — `task {text}`: a whole job in words for the target device's own
  Muse, in a conversation of its own. It may take minutes. When that Muse hits
  something that needs approval, it does not decide alone: the question travels
  back as an `event {stage:"approval"}` and the asking device shows its usual
  card (RiskGate on the phone, the terminal prompt on the desktop, a card in the
  web console). The answer goes back as `approve {approval_id, allow}`.

  While a task runs the target sends `event` frames whose `body.stage` tells
  the caller what to draw, so the run reads the same in the caller's chat as in
  the target's own: `tool {id, name, summary}` when a tool starts and
  `tool_result {id, name, ok, summary}` when it ends (same `id`), `approval
  {approval_id, preview, risk, reason, device, timeout}` and, once decided on
  either side, `approval_result {approval_id, status}`, `text {text, interim}`
  for what the Muse says along the way, `image` and `file` for things it made,
  `error`. The `result` frame carries the final answer.

- **Coding agents** — `coding.agents`, `coding.sessions`, `coding.session`,
  `coding.send`, `coding.stop`, `coding.runs`: the Cursor / Codex / Claude Code
  sessions on a computer, read from disk and steered through the agents' own
  CLIs by the runtime there. `coding.send {agent, text, session_id?, workspace?,
  wait}` streams the run back as `event` frames (`started`, `text`, `tool`,
  `done`, `error`, each with the run id so the caller can `coding.stop`) and
  answers with the finished run. Only a runtime that has the module announces
  these actions. [coding-agents.md](coding-agents.md).

Each device decides what it lets others do. **Remote control** off (phone:
*Settings → unibot Cloud → Devices*; desktop: `set remote_control off`) makes
the device answer `info` and nothing else — it still sees and drives the others.

## Frames

JSON text frames over `WS /v1/hub`, `Authorization: Bearer nm_…` (browsers put
the key in `hello` instead).

```
→ hello    {device:{id,name,kind,os,version,actions[]}}     kind: phone | computer | web
← welcome  {device_id, devices:[…], server:{version,frame_limit,time}}
← devices  {devices:[{id,name,kind,os,version,online,last_seen,controllable}]}

→ call     {id, to, action, args}
← call     {id, from:{id,name,kind}, action, args}          (delivered to the target)
→ event    {id, body}          ← event  {id, from, body}    progress, approvals, images
→ result   {id, ok, body | error, message}                  ← result (to the caller)
← error    {code, message, id?}   device_offline · not_controllable · self_call · unknown_call · too_large · bad_frame

→ devices  {}        → rename {name}        → forget {device_id}        → ping  ← pong
← profile  {rev, device}       the account's name and look changed (PUT /v1/me/profile); fetch it
```

Close codes: `4000` hello expected, `4001` bad key, `4002` bad device,
`4003` replaced by a newer connection of the same device id.

Device ids are per installation (`phone-…`, `pc-…`); names are for people and
can be changed on the device. A `web` device is never a target and is not
remembered. Bodies for `file.get`, `file.put` and `screen` carry the bytes in
base64 (`data`) with `mime`; the desktop refuses files over 8 MB, the relay
refuses frames over `HUB_FRAME_LIMIT`.

The agent's name and look are the account's, not a device's: `GET` / `PUT
/v1/me/profile` on the relay keep them (the dragon, an emoji on a colour, or a
face drawn in the avatar studio with its five small stills), last writer wins,
and a `profile` frame tells the other devices to fetch the new `rev`. The
runtime does this in `unibot/hub/profile.py`, the phone in
`ai.unicto.unibot.cloud.ProfileSync`; the device that wrote it skips its own
echo. Nothing else is synchronised — keys, providers and settings stay where
they were entered.

## The code

How the devices' apps are shaped around these frames — the Devices page, a
side chat addressed to a device, approvals answered on either end, the
computer's own hands — is in [every-device.md](every-device.md).

- Relay: [`cloud/unibot_cloud/hub.py`](../cloud/unibot_cloud/hub.py) —
  per-account registry, routing, `GET /v1/devices`, `DELETE /v1/devices/{id}`;
  the console under [`cloud/unibot_cloud/console/`](../cloud/unibot_cloud/console/).
- Android: `ai.unicto.unibot.hub` — `HubClient` (OkHttp, reconnect),
  `Hub` (state, prefs, `call`/`find`), `HubActions` (what the phone does for
  others, including `task` through the headless chat runner), `HubService`
  (foreground, `remoteMessaging`). `unibot-pc` (`ai.unicto.unibot.reach`)
  reaches the hub devices from the phone's sandbox shell.
- Desktop binary: [`desktop/unibot_desktop/hub.py`](../desktop/unibot_desktop/hub.py),
  `app.py` (incoming calls, approvals), `agent.py` (the `device_*` and
  `delegate` tools).
- Runtime (the windowed desktop, `unibot serve`): [`unibot/cloud.py`](../unibot/cloud.py)
  (the account), [`unibot/hub/client.py`](../unibot/hub/client.py) (the
  socket, reconnect), [`unibot/hub/actions.py`](../unibot/hub/actions.py)
  (what this computer does for others), [`unibot/hub/service.py`](../unibot/hub/service.py)
  (incoming `task`s in a visible side chat, the `tool`/`tool_result`/`approval`/
  `approval_result` stages both ways, device threads), [`unibot/tools/devices.py`](../unibot/tools/devices.py)
  (`devices`, `device_*`, `delegate`); `/api/cloud/*` and `/api/hub/*` in
  [`unibot/server/api.py`](../unibot/server/api.py). Tests:
  `tests/test_hub.py` with a fake relay.
- Web app: `DevicesScreen`, the device chats and the relayed approval cards in
  [`web/src/`](../web/src/) — see [every-device.md](every-device.md).

## Trust

The relay authenticates every socket with the account key and routes only
within the account; it stores device names and last-seen times, not what was
asked. A device answers only devices of its own account, and only while its
*Remote control* switch is on. Commands are judged where they are typed, before
they leave; a remote Muse's approvals are answered by the person who asked,
never by the other Muse. Signing out on a device revokes that device's key at
the relay and takes it off the hub; the *Devices* list on the phone and the
console shows every device that has ever signed in, so a device you no longer
recognise is visible, and *Forget* removes it once it is offline.
