"""unibot Web: a Muse of your own in the browser, kept for you.

The showcase gives a visitor a Muse for half an hour. An *account* — someone signed in to
unibot Cloud with an e-mail or phone code — gets one that stays: a container of its own
with a volume for its data and its workspace, started when the person arrives, put to sleep
after a long quiet spell (``docker stop``; the volume remains), woken on the next visit. It
signs in to the Cloud with the account's own key, so its model use is the account's daily
allowance and it sits on the hub as one of the account's devices ("Web"), able to ask the
phone or the computer for things and to be asked.

The gateway never talks to the model for these containers; they reach the relay directly on
the web network. What the gateway keeps: which account has which container, its access
token, and the key the container was started with — in SQLite on the gateway's volume.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import secrets
import sqlite3
import time
from dataclasses import dataclass
from typing import Any

import httpx

from .config import Settings
from .runner import Runner, RunnerError
from .sessions import Refused

log = logging.getLogger("showcase.web")


@dataclass
class Account:
    id: str  # the relay's account id (opaque), or a hash of what identifies the account
    slug: str  # the DNS label its Muse lives at: <slug>.<session_domain>
    token: str  # the runtime's access token, what the browser keeps
    key: str  # the Cloud key the container was started with (never shown again)
    hint: str
    channel: str
    created_at: float
    last_seen: float
    address: str = ""  # where the gateway reaches the container while it runs
    port: int = 8787
    running: bool = False

    @property
    def container(self) -> str:
        return f"nmw-{self.slug}"

    @property
    def http_base(self) -> str:
        return f"http://{self.address}:{self.port}"

    @property
    def ws_base(self) -> str:
        return f"ws://{self.address}:{self.port}"

    def public(self, settings: Settings) -> dict[str, Any]:
        return {
            "slug": self.slug,
            "url": f"{settings.session_origin(self.slug)}/?token={self.token}",
            "origin": settings.session_origin(self.slug),
            "hint": self.hint,
            "channel": self.channel,
            "running": self.running,
        }


class AccountStore:
    """SQLite: one row per account. ``:memory:`` in tests."""

    def __init__(self, path: str) -> None:
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute(
            """CREATE TABLE IF NOT EXISTS accounts (
                 id TEXT PRIMARY KEY, slug TEXT UNIQUE NOT NULL, token TEXT NOT NULL,
                 key TEXT NOT NULL, hint TEXT NOT NULL DEFAULT '', channel TEXT NOT NULL DEFAULT '',
                 created_at REAL NOT NULL, last_seen REAL NOT NULL)"""
        )
        self.db.commit()

    def get(self, account_id: str) -> Account | None:
        row = self.db.execute("SELECT * FROM accounts WHERE id = ?", (account_id,)).fetchone()
        return self._account(row) if row else None

    def by_slug(self, slug: str) -> Account | None:
        row = self.db.execute("SELECT * FROM accounts WHERE slug = ?", (slug,)).fetchone()
        return self._account(row) if row else None

    def all(self) -> list[Account]:
        return [self._account(r) for r in self.db.execute("SELECT * FROM accounts")]

    def put(self, a: Account) -> None:
        self.db.execute(
            """INSERT INTO accounts (id, slug, token, key, hint, channel, created_at, last_seen)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET token=excluded.token, key=excluded.key,
                 hint=excluded.hint, channel=excluded.channel, last_seen=excluded.last_seen""",
            (a.id, a.slug, a.token, a.key, a.hint, a.channel, a.created_at, a.last_seen),
        )
        self.db.commit()

    def touch(self, account_id: str, when: float) -> None:
        self.db.execute("UPDATE accounts SET last_seen = ? WHERE id = ?", (when, account_id))
        self.db.commit()

    def count(self) -> int:
        return int(self.db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0])

    @staticmethod
    def _account(row: sqlite3.Row) -> Account:
        return Account(
            id=row["id"],
            slug=row["slug"],
            token=row["token"],
            key=row["key"],
            hint=row["hint"],
            channel=row["channel"],
            created_at=row["created_at"],
            last_seen=row["last_seen"],
        )


def _slug(account_id: str, salt: str) -> str:
    # 12 lowercase letters and digits from an HMAC of the account id: a DNS label that cannot
    # be guessed from the id and does not change
    digest = hmac.new(salt.encode(), account_id.encode(), hashlib.sha256).digest()
    return "w" + base64.b32encode(digest[:8]).decode().rstrip("=").lower()[:11]


class RelayError(Refused):
    """The relay said no; passed to the browser with the relay's own code and message."""


class AccountManager:
    def __init__(
        self,
        settings: Settings,
        runner: Runner,
        store: AccountStore,
        http: httpx.AsyncClient | None = None,
        clock=time.time,
    ) -> None:
        self.s = settings
        self.runner = runner
        self.store = store
        self.http = http or httpx.AsyncClient(timeout=15)
        self.clock = clock
        # accounts whose container is up, by slug, with the address it answers at
        self.live: dict[str, Account] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    # ------------------------------------------------------------------ the relay
    async def _relay(
        self, method: str, path: str, body: dict | None, ip: str, key: str = ""
    ) -> Any:
        headers = {"Content-Type": "application/json", "X-Forwarded-For": ip}
        if key:
            headers["Authorization"] = f"Bearer {key}"
        try:
            r = await self.http.request(
                method, f"{self.s.web_relay_url}{path}", json=body, headers=headers
            )
        except httpx.HTTPError as exc:
            raise Refused(
                502, "relay_unreachable", "unibot Cloud is not reachable right now."
            ) from exc
        if r.status_code >= 400:
            try:
                err = r.json().get("error", {})
            except ValueError:
                err = {}
            raise RelayError(
                r.status_code if r.status_code < 500 else 502,
                str(err.get("code") or "relay_error"),
                str(err.get("message") or "unibot Cloud refused the request."),
            )
        if r.status_code == 204 or not r.content:
            return {}
        return r.json()

    async def request_code(self, identifier: str, ip: str) -> None:
        if not self.s.web_enabled:
            raise Refused(404, "web_off", "The web version is not turned on here.")
        await self._relay("POST", "/v1/auth/code", {"identifier": identifier}, ip)

    async def verify(self, identifier: str, code: str, ip: str, invite: str = "") -> Account:
        """A code from the relay → the account's key → its Muse, started (or restarted with
        the fresh key when the account already has one). ``invite`` is a friend's code, sent
        along only when given (older relays do not know the field)."""
        if not self.s.web_enabled:
            raise Refused(404, "web_off", "The web version is not turned on here.")
        payload: dict[str, str] = {"identifier": identifier, "code": code, "device": "Web"}
        if invite:
            payload["invite"] = invite
        data = await self._relay("POST", "/v1/auth/verify", payload, ip)
        return await self._admit(data)

    async def login(self, identifier: str, password: str, ip: str) -> Account:
        """The password way in — the account set one under Account earlier; no code to wait
        for. The relay's answer (wrong password, no password yet, locked out) is passed on."""
        if not self.s.web_enabled:
            raise Refused(404, "web_off", "The web version is not turned on here.")
        payload = {"identifier": identifier, "password": password, "device": "Web"}
        data = await self._relay("POST", "/v1/auth/login", payload, ip)
        return await self._admit(data)

    async def _admit(self, data: dict[str, Any]) -> Account:
        """The relay said yes: the account's key → its Muse, started (or restarted with the
        fresh key when the account already has one)."""
        key = str(data.get("api_key") or "")
        if not key:
            raise Refused(502, "relay_error", "unibot Cloud returned no key.")
        account_info = data.get("account") if isinstance(data.get("account"), dict) else {}
        account_id = str(account_info.get("id") or "")
        if not account_id:
            # older relays: what identifies the account without naming the person
            raw = f"{account_info.get('channel')}|{account_info.get('hint')}|{account_info.get('created_at')}"
            account_id = hashlib.sha256(raw.encode()).hexdigest()[:24]
        now = self.clock()
        existing = self.store.get(account_id)
        if existing is None:
            if self.store.count() >= self.s.web_max_accounts:
                raise Refused(
                    503,
                    "web_full",
                    "Every place in the web version is taken. Install the app instead — it runs on your own device.",
                )
            account = Account(
                id=account_id,
                slug=_slug(account_id, self.s.web_slug_salt),
                token=secrets.token_urlsafe(24),
                key=key,
                hint=str(account_info.get("hint") or ""),
                channel=str(account_info.get("channel") or ""),
                created_at=now,
                last_seen=now,
            )
            self.store.put(account)
            log.info("web account %s created (%s)", account.slug, account.channel)
        else:
            account = existing
            rekey = account.key != key
            account.key = key
            account.hint = str(account_info.get("hint") or account.hint)
            account.channel = str(account_info.get("channel") or account.channel)
            account.last_seen = now
            self.store.put(account)
            if rekey and account.slug in self.live:
                # the container carries its key in its environment: start it afresh with this one
                log.info(
                    "web account %s signed in again: restarting with the new key", account.slug
                )
                await self._stop(account, remove=True)
        await self.open(account)
        return account

    # ------------------------------------------------------------------ the containers
    def _lock(self, slug: str) -> asyncio.Lock:
        return self._locks.setdefault(slug, asyncio.Lock())

    def _env_for(self, a: Account) -> dict[str, str]:
        env = {
            "UNIBOT_SERVER_TOKEN": a.token,
            "UNIBOT_CLOUD_BASE_URL": self.s.web_relay_internal_url,
            "UNIBOT_CLOUD_KEY": a.key,
            "UNIBOT_CLOUD_HINT": a.hint,
            "UNIBOT_CLOUD_CHANNEL": a.channel,
            "UNIBOT_HUB_NAME": self.s.web_device_name,
            "UNIBOT_ONBOARDED": "1",
        }
        # the runtime makes the Cloud its model on first start (hub/service.py _seed_from_env)
        env.update(self.s.extra_env)
        return env

    async def open(self, account: Account) -> Account:
        """The account's Muse, running: started, or woken, or already up."""
        async with self._lock(account.slug):
            live = self.live.get(account.slug)
            if live is not None and live.running:
                live.last_seen = self.clock()
                return live
            running = [a for a in self.live.values() if a.running]
            if len(running) >= self.s.web_max_running:
                # put the quietest one to sleep to make room
                quietest = min(running, key=lambda a: a.last_seen)
                if self.clock() - quietest.last_seen < 300:
                    raise Refused(
                        503,
                        "web_busy",
                        "The web version is busy right now. Try again in a few minutes, or install the app.",
                    )
                await self._stop(quietest)
            try:
                address = await self.runner.start_persistent(
                    account.container,
                    self._env_for(account),
                    volumes={
                        f"nmw-{account.slug}-data": "/data",
                        f"nmw-{account.slug}-ws": "/workspace",
                        f"nmw-{account.slug}-home": "/home/muse",
                    },
                    network=self.s.web_network,
                    memory=self.s.web_memory,
                    cpus=self.s.web_cpus,
                    pids=self.s.pids,
                    image=self.s.web_image or self.s.image,
                )
            except RunnerError as exc:
                log.warning("web account %s: %s", account.slug, exc)
                raise Refused(
                    503, "start_failed", "Could not start your Muse. Try again in a moment."
                ) from exc
            account.address = address
            account.running = True
            account.last_seen = self.clock()
            self.live[account.slug] = account
            try:
                await self._wait_ready(account)
            except Refused:
                await self._stop(account)
                raise
            log.info("web account %s up at %s", account.slug, address)
            return account

    async def _wait_ready(self, a: Account) -> None:
        deadline = self.clock() + max(self.s.start_timeout_s, 60)
        while True:
            try:
                r = await self.http.get(f"{a.http_base}/api/health")
                if r.status_code == 200 and r.json().get("ok"):
                    return
            except (httpx.HTTPError, ValueError):
                pass
            if self.clock() >= deadline:
                raise Refused(503, "start_timeout", "Your Muse took too long to start.")
            await asyncio.sleep(0.5)

    async def _stop(self, a: Account, remove: bool = False) -> None:
        a.running = False
        self.live.pop(a.slug, None)
        try:
            if remove:
                await self.runner.remove(a.container)
            else:
                await self.runner.stop_only(a.container)
        except RunnerError as exc:
            log.warning("stopping %s: %s", a.container, exc)

    # ------------------------------------------------------------------ requests
    async def for_host(self, slug: str) -> Account | None:
        """The account behind ``<slug>.<domain>``, its Muse up (woken if it slept); None when
        there is no such account."""
        live = self.live.get(slug)
        if live is not None and live.running:
            live.last_seen = self.clock()
            self.store.touch(live.id, live.last_seen)
            return live
        account = self.store.by_slug(slug)
        if account is None:
            return None
        return await self.open(account)

    def touch(self, a: Account) -> None:
        a.last_seen = self.clock()

    # ------------------------------------------------------------------ lifecycle
    async def startup(self) -> None:
        """Containers that kept running while the gateway was away are picked up again."""
        if not self.s.web_enabled:
            return
        for account in self.store.all():
            try:
                address = await self.runner.address_of(account.container)
            except RunnerError:
                address = None
            if address:
                account.address = address
                account.running = True
                account.last_seen = self.clock()
                self.live[account.slug] = account
        log.info("web: %d accounts, %d up", self.store.count(), len(self.live))

    async def reap_once(self) -> None:
        now = self.clock()
        for a in list(self.live.values()):
            if a.running and now - a.last_seen >= self.s.web_idle_stop_s:
                log.info("web account %s asleep after a quiet %ds", a.slug, int(now - a.last_seen))
                self.store.touch(a.id, a.last_seen)
                await self._stop(a)

    async def reap_forever(self, interval: float = 60) -> None:
        while True:
            await asyncio.sleep(interval)
            try:
                await self.reap_once()
            except Exception:  # noqa: BLE001
                log.exception("web reaper")

    def stats(self) -> dict[str, Any]:
        return {
            "enabled": self.s.web_enabled,
            "accounts": self.store.count() if self.s.web_enabled else 0,
            "running": sum(1 for a in self.live.values() if a.running),
            "max_accounts": self.s.web_max_accounts,
            "max_running": self.s.web_max_running,
        }
