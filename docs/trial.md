# Trial runbook: one account, all your devices

What it takes to run the private trial end to end — relay, phone, computers,
web — from a clean VPS to "the phone tells the Mac to build the project".
Everything here is in the private `unibot-trial` repository (branch `trial`)
until it ships in the public one.

## 0. What you need

| | for |
|---|---|
| A small Linux VPS with Docker (1 vCPU / 1 GB is plenty) and a domain name pointed at it, e.g. `cloud.example.com` | the relay + hub + web console |
| An OpenAI-compatible model key — Alibaba Model Studio (百炼) by default; the same key serves pictures | what the Muses think with |
| A way to send codes: SMTP credentials (e-mail sign-in) and/or Aliyun SMS (mainland numbers) — or `CODE_SENDER=log` and read codes from the server log while it is only you | sign-in |
| Android phone: the arm64 debug APK from `./gradlew :app:assembleDebug` (sideload) | the phone's Muse |
| Computers: the installer for each from the `desktop` workflow artifacts — *Actions → desktop → Run workflow* builds all four (Windows `-setup.exe`, macOS `.pkg` for Apple silicon and Intel, Linux `.deb`); a `desktop-v*` tag also publishes them as a release | the computers' Muses |
| iPhone (optional): TestFlight through the `ios-testflight` workflow — needs an Apple Developer account and App Store Connect API key in the repo secrets; run *iOS · build check* first, it needs neither | the iPhone on the hub (it answers `info`, `open`, `notify`; shell, files and tasks on the iPhone are still to come) |

## 1. The relay

```bash
git clone git@github.com:unictoai/unibot-trial.git && cd unibot-trial/cloud
cp .env.example .env
```

Fill `.env`:

```
CLOUD_DOMAIN=cloud.example.com
PUBLIC_BASE=https://cloud.example.com
CLOUD_SECRET=<openssl rand -hex 32>        # back it up with data/: rotating it orphans accounts
CLOUD_ADMIN_TOKEN=<openssl rand -hex 32>
UPSTREAM_BASE=…/compatible-mode/v1          # your Model Studio endpoint
UPSTREAM_KEY=sk-…
DASHSCOPE_BASE=…/api/v1
SIGNUP_OPEN=0                               # members only while you try it; 1 opens sign-up
ALLOWED_IDENTIFIERS=139xxxxxxxx, you@example.com   # members: no daily cap
DAILY_CAP_CNY=25                            # yuan a day for everyone else once sign-up is open
CODE_SENDER=smtp                            # or aliyun / both / log
SMTP_HOST=… SMTP_PORT=465 SMTP_USER=… SMTP_PASSWORD=… SMTP_FROM=unibot <no-reply@example.com>
HUB_ENABLED=true
```

```bash
docker compose up -d            # relay on :8787 behind Caddy, which fetches the certificate
docker compose logs -f relay    # codes appear here when CODE_SENDER=log
curl https://cloud.example.com/healthz
```

The web console is at `https://cloud.example.com/app/`, the operator's page at
`/app/admin/` (asks for `CLOUD_ADMIN_TOKEN`: who signed in, usage, devices,
grant / disable / delete). Data (SQLite) lives in `cloud/data/`; back it up
together with `CLOUD_SECRET`. Tokens have no ceiling by default; the daily
money cap (`DAILY_CAP_CNY`) is what limits an account, and members escape it.

The reference production relay ran this same code on the box that served the project's
site, behind the showcase's Caddy (unibot, the fork, operates no relay);
[`cloud/deploy/unibot-hk/`](../cloud/deploy/unibot-hk/README.md) has the
compose file, the Caddy site, the backup timer and the deploy script.

## 2. The phone

Install the APK. On the first screen, *接入模型* → your number or address →
the code → done: the unibot Cloud provider, a default model group, and the
hub connection are set up in one go. (A debug build shows the *中转服务器*
field; type your relay's address there. Release builds use the default relay.)

*Settings → unibot Cloud* now has a **Devices** section: the two switches
(reachable · operable), the phone's name (rename it to something you will say
out loud — "pixel", "小米"), the other devices, and the console link. A quiet
notification shows while the phone is on the hub.

## 3. The computers

Install the package. Then, in a terminal:

```
unibot-desktop
```

Server → number or address → code. Name the computer (`unibot-desktop rename mac`).
`unibot-desktop run --open` chats in the terminal and opens the console;
`unibot-desktop serve` keeps it reachable without a chat (put it in a login
item / systemd user service / Task Scheduler if you want it always on).

## 4. Try it

On the phone: 「在 mac 上列一下下载文件夹」 「让 desk 把 ~/proj 编译一遍，把最后二十行日志发我」
「电脑截个图给我看」 「给电脑发个通知：该睡了」

On the computer: "on the phone, take a screenshot", "tell pixel's Muse to read
me the last notification", "send pixel a notification: build finished".

In the console: pick a device, type; approvals show as cards.

Anything that deletes, sends, pays or touches the system asks first — on the
device where you typed it. A remote Muse's approval question comes back to you
the same way.

## 5. Operating

```bash
# accounts (hints only, never identifiers) and allowance
curl -H "X-Admin-Token: $CLOUD_ADMIN_TOKEN" https://cloud.example.com/v1/admin/accounts
# top up
curl -H "X-Admin-Token: $CLOUD_ADMIN_TOKEN" -X POST https://cloud.example.com/v1/admin/grant \
     -H 'content-type: application/json' -d '{"identifier":"139…","tokens":1000000}'
# who is on the hub (with a device's own key)
curl -H "Authorization: Bearer nm_…" https://cloud.example.com/v1/devices
```

Letting someone else in: `SIGNUP_OPEN=1` and `docker compose up -d` again lets
anyone register with the daily cap; *设为成员* on the admin page, or a line in
`ALLOWED_IDENTIFIERS`, lifts the cap for one person.

## 6. Where it stands

Verified on the development machine: relay + hub, Linux desktop, Android
emulator and the web console on one account — phone→PC (`devices`, `run`,
`ls`, `notify`, `task`), PC→phone (`info`, `notify`, `task`, natural-language
notify + delegate), web→phone and web→PC (tasks, approval cards), rename and
forget (also from the console). The macOS and Windows packages come out of CI
(the `desktop` workflow, all four targets green) and were not run on real
machines here. The iOS side — sign-in and the hub client — is written without
a Mac; the `ios-check` workflow is the compile test, the `ios-testflight`
workflow the delivery. Shell and files on the phone use the Linux sandbox,
which exists on arm64 phones (the emulator build has none).
