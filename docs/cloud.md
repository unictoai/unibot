# unibot Cloud

"unibot Cloud" is the name of the optional relay software in [`cloud/`](../cloud/README.md) —
**unibot operates no relay and no sign-in service.** The app is bring-your-own-key first:
paste an API key from any provider and you never touch this page. A relay is for people who
run their own (a family, a class, a company): the app signs in to *your* relay with a phone
number or e-mail address plus a code, and the relay acts as a model provider. Everything
below describes what the relay software does and what the app shows when pointed at one —
the operator of the relay sets its own terms, allowances and limits.

## In the app

unibot starts with your own API key — no account needed. *Settings → unibot Cloud*
is where the app signs in to a relay you run (or one you trust): it asks for the
relay's URL, then a phone number or an e-mail address, and sends a six-digit code —
or, once you have set one, takes your password. Signing out brings that screen back.
After the code the app has:

- a provider called **unibot Cloud** under *Settings → Providers*, an
  ordinary OpenAI-compatible provider whose key is the token the relay issued;
- a model group with the recommended chat model, set as the default if you had
  none;
- the relay's picture model as the avatar's image model, if none was set.

*Settings → unibot Cloud* (the *Account* screen) shows who is signed in (a
masked hint, never the number) and since when; sets, changes or removes the
**password** (eight characters or more; scrypt on the relay; locked for a while
after repeated wrong attempts, and a fresh code sign-in unlocks it); today's
spend against the allowance in ¥ and $ with a meter; **usage by kind** —
chat, pictures, video — today and in all, and **by model**; the
**sign-ins** — every device holding a key, how it signed in (code or password),
when it was last used — each revocable; the account's own **history** (sign-ins,
password changes, refusals; never message content); and the ways
out: *Sign out* on this device, *Sign out everywhere*, *Delete the account*.
The allowance belongs to the address: signing in again, on
this phone or another, gives a new key for the same account and does not grant
a second allowance.

Signing in a second provider next to it — your own Model Studio key, DeepSeek,
a local server — works as always; the relay's models can be mixed with yours
in a model group.

## What the relay keeps

The relay is the code in [`cloud/`](../cloud/README.md). It stores:

- a salted hash (HMAC-SHA256) of the e-mail address (phone accounts from
  0.1.18–0.1.21 keep working), a masked hint such as `so***@example.com`, and
  the address itself encrypted (AES-GCM, key derived from the relay's secret)
  so the operator can see who an account belongs to on the admin page — the
  database file alone shows nothing;
- the hash of each key issued, with the device name you signed in from, how
  (code or password) and when it was last used; revoked keys keep their row
  so the sign-ins list can say so;
- the password, if you set one, as an scrypt hash — never the password;
- per request: the kind (chat, picture, video), the model, the token counts
  and the amount charged;
- a timeline of account events — signed in, failed sign-in, password set or
  changed, signed out, refused for budget, upstream error — with a
  device name, a model or an error code as the detail, never message content;
- the id of each video task, so only the account that started one can poll it;
- the agent's name and look (`/v1/me/profile`): which face it wears — the dragon,
  an emoji on a colour, or one drawn in the avatar studio, with that face's five
  stills as small WebP pictures — so every device of the account shows the same
  one. Never a key or a setting.

It does not store message content, images or tool results; they are forwarded
to the upstream model (Alibaba Cloud Model Studio) and the reply is streamed
back. Every response carries an `X-Unibot-Request` id so a problem report
can be matched to a ledger row without any content being logged. Deleting the
account (`POST /v1/auth/delete` with the account's key) removes all of it. See
[privacy.md](privacy.md).

## Allowance

### Contributing conversations (off by default)

*Account → Contribute conversations* is a switch each person owns. Off — the
default — the relay forwards a chat request and keeps nothing of it. On, each
turn is kept: the messages sent (pictures replaced by a marker), the model's
reply, the token counts and the app's platform and language from the request
headers, tied to the account id only. The point is a training set for the
community's own open model. The person can turn it off and delete what they
gave at any time; deleting the account deletes it too. The operator sees these
turns on the admin page for contributing accounts only and exports them as
JSON lines without account ids (`GET /v1/admin/samples/export`). The relay
does not know where anyone is: the apps never send a location, and IP
addresses are not recorded.

The reference relay implements a per-account allowance pool so one relay can serve many
people without handing out its upstream key. **There is no unibot-operated relay and no
funded pool** — the numbers below are the reference defaults; whoever runs a relay sets
their own (`ALLOWANCE_CNY`, `INVITE_BONUS_CNY`, `CONTRIBUTE_BONUS_CNY`, `SIGNUP_OPEN`,
`ALLOWED_IDENTIFIERS`; see [`cloud/README.md`](../cloud/README.md)):

| | reference default |
|---|---|
| sign-up | whoever the operator allows (phone number or e-mail address) |
| free allowance | **¥10 per account**, across chat, pictures and clips; it does not reset |
| invitations | each *new* person who signs up with your code adds **¥5** to your pool |
| co-creation programme | joining (*Contribute conversations*, off by default) adds **¥10** once |
| when it is gone | bring your own key — [any OpenAI-compatible provider](own-key.md); sign-in and your devices are unaffected |
| rate | 30 requests per minute |
| tokens | no ceiling; usage is metered and shown |

Spend is counted at the model provider's list prices (Alibaba Cloud Model
Studio, Beijing region, September 2026): `qwen3.8-27b` ¥3 in / ¥12 out per
million tokens, `qwen3.8-flash` ¥0.8 / ¥2.7, `qwen-image-3.0` ¥0.18 a
picture, `wan2.2-i2v-flash` ¥0.10 a second of video at 480P (a 5-second clip
is ¥0.50). A typical day of chatting costs a few fen; ¥10 is roughly
seven hundred thousand tokens of the 27B model or fifty pictures. A new face —
four candidates, four poses and four clips — comes to about ¥3.5, and the app
shows the estimate and what is left before it draws.

*Settings → unibot Cloud* shows what was used of the pool in ¥ and $, what
is left, and how the pool grows. At 80 % the app says so once; when the pool
is spent the relay refuses with `allowance_exhausted` and the app shows the
three ways on: your own key (Alibaba Cloud Bailian first — the provider form
opens pre-filled, [guide](own-key.md)), an invitation (+¥5 a head), or the
co-creation programme (+¥10, once). Other relays may set other rules
(`ALLOWANCE_CNY`, `INVITE_BONUS_CNY`, `CONTRIBUTE_BONUS_CNY`, `SIGNUP_OPEN`,
`ALLOWED_IDENTIFIERS`; see [`cloud/README.md`](../cloud/README.md)).

## Running your own

Anyone can run a relay — for a family, a class, a company — and point the app
at it. The server is a single Python process over SQLite; a VPS with Docker and
a domain name is enough:

```bash
cd cloud
cp .env.example .env    # domain, secrets, upstream key, how codes are sent
docker compose up -d    # Caddy fetches the TLS certificate
```

[`cloud/README.md`](../cloud/README.md) has the settings, the sender options
(SMTP for e-mail, Aliyun SMS for mainland phones), the admin endpoints for
topping up, and the test suite.

A relay for one person, or a few: `SIGNUP_OPEN=0` with
`ALLOWED_IDENTIFIERS=139…, me@example.com` lets only those numbers and
addresses sign in; everyone else gets `not_invited` before any code is sent.
The trial deployments ran this way. With sign-up open, the same list names
the members who have no daily cap; the admin page can add more.

The relay is also the meeting point for the account's devices — the **hub** at
`/v1/hub` and the web console at `/app`; see [hub.md](hub.md). `HUB_ENABLED`
turns it off, `HUB_FRAME_LIMIT` caps one frame (files and screenshots travel
inside frames, 16 MB by default).

The Android app ships with **no default relay**: the sign-in screen asks for the relay's
URL, which you enter yourself (on the emulator, the host machine is `http://10.0.2.2:8787`).
Without a relay the app works fully on your own API key.

## Protocol

The app's calls, all JSON:

```
POST /v1/auth/code          {identifier}                      → 204
POST /v1/auth/verify        {identifier, code, device}        → {api_key, base_url, account, tokens, models}
POST /v1/auth/login         {identifier, password, device}    → the same; 401 bad_credentials, 429 locked, 400 no_password
POST /v1/auth/password      Bearer  {password, current?}      → 204; "" with current removes it
GET  /v1/me                 Bearer                            → {account{…, has_password, sessions, signed_in_via}, usage{today, total by kind / model}, tokens, spend, models, recent}
GET  /v1/me/sessions        Bearer                            → {sessions: [{prefix, device, via, created_at, last_used_at, current}]}
DELETE /v1/me/sessions/{prefix}  Bearer                       → 204
GET  /v1/me/events          Bearer  ?limit=50                 → {events: [{ts, kind, detail}]}
POST /v1/auth/sign-out      Bearer                            → 204
POST /v1/auth/sign-out-all  Bearer  {all?}                    → {signed_out}
POST /v1/auth/delete        Bearer                            → 204
```

Everything else is the OpenAI API: `GET /v1/models` (with `architecture`
modalities so the picture model is recognisable), `POST /v1/chat/completions`
with streaming, `POST /v1/images/generations` and `/v1/images/edits`. Errors
are `{"error": {"message", "type": "unibot_cloud", "code"}}` with a stable
`code` the app turns into a sentence.

Devices: `GET /v1/devices` lists the account's devices (online or last seen),
`DELETE /v1/devices/{id}` forgets an offline one, and `WS /v1/hub` is the hub
itself — the frames are in [hub.md](hub.md).
