"""The hub: the devices of one account find each other here and pass requests
through, whatever network each of them is on.

Every device — a phone, a computer, a browser tab — keeps one WebSocket to
`/v1/hub`, opened outbound, so nothing has to be reachable from the outside.
The relay only forwards; it never runs anything itself and never sees a
command's approval, which is decided on the device that would carry it out.

Frames are JSON text. From a device:

    {"type":"hello", "key": "nm_…"?, "device": {"id","name","kind","os","version","actions":[…]}}
    {"type":"call",   "id","to","action","args"}         ask another device to do something
    {"type":"result", "id","ok", "body" | "error","message"}   the answer to a call received
    {"type":"event",  "id","body"}                        progress on a call still running
    {"type":"devices"}  {"type":"rename","name"}  {"type":"forget","device_id"}  {"type":"ping"}

From the hub:

    {"type":"welcome","device_id","devices":[…],"server":{…}}
    {"type":"devices","devices":[…]}                      whenever a device comes or goes
    {"type":"call","id","from":{id,name,kind},"action","args"}
    {"type":"result", …}  {"type":"event", …}             forwarded to the caller
    {"type":"error","code","message","id"?}               e.g. device_offline, not_controllable
    {"type":"pong"}

`key` in hello is for browsers, which cannot set an Authorization header. A
`web` device is a front door only: it can call, it cannot be called. Devices of
the other kinds are remembered per account so the console can show them while
they are offline. Binary payloads (files, screenshots) travel base64-encoded
inside `body`, under the frame limit.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
import uuid
from dataclasses import dataclass, field

from fastapi import WebSocket, WebSocketDisconnect

from . import __version__
from .db import Database
from .service import Caller, Cloud, CloudError

log = logging.getLogger("unibot_cloud.hub")

KINDS = ("phone", "computer", "web")
HELLO_TIMEOUT_S = 15
CALL_TTL_S = 15 * 60
_ID = re.compile(r"^[A-Za-z0-9._:-]{4,80}$")


def now() -> int:
    return int(time.time())


@dataclass
class Connection:
    ws: WebSocket
    account_id: str
    key_hash: str
    device_id: str
    name: str
    kind: str
    os: str = ""
    version: str = ""
    actions: list[str] = field(default_factory=list)
    connected_at: int = field(default_factory=now)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    closed: bool = False
    key_ok_at: int = 0  # last time the sign-in behind this socket was re-checked

    def brief(self) -> dict:
        return {"id": self.device_id, "name": self.name, "kind": self.kind}

    async def send(self, frame: dict) -> None:
        if self.closed:
            return
        text = json.dumps(frame, ensure_ascii=False, separators=(",", ":"))
        async with self.lock:
            try:
                await self.ws.send_text(text)
            except Exception as e:  # the socket went away under us; the receive loop notices too
                log.debug("send to %s failed: %s", self.device_id, e)
                self.closed = True


@dataclass
class Pending:
    call_id: str
    caller: str
    target: str
    account_id: str
    started: int = field(default_factory=now)


class Hub:
    def __init__(self, cloud: Cloud, frame_limit: int = 16 * 1024 * 1024):
        self.cloud = cloud
        self.db: Database = cloud.db
        self.frame_limit = frame_limit
        self.online: dict[str, dict[str, Connection]] = {}
        self.pending: dict[str, Pending] = {}

    # -- presence -------------------------------------------------------------------

    def online_count(self) -> int:
        """Sockets open right now, across every account (the operator's page)."""
        return sum(len(v) for v in self.online.values())

    def devices(self, account_id: str) -> list[dict]:
        """Remembered devices with their presence, then the browser tabs, newest first."""
        live = self.online.get(account_id, {})
        out: list[dict] = []
        seen: set[str] = set()
        for row in self.db.devices_for(account_id):
            c = live.get(row["id"])
            seen.add(row["id"])
            out.append(
                {
                    "id": row["id"],
                    "name": c.name if c else row["name"],
                    "kind": row["kind"],
                    "os": c.os if c else row["os"],
                    "version": c.version if c else row["version"],
                    "actions": c.actions if c else json.loads(row["actions"] or "[]"),
                    "online": c is not None,
                    "last_seen": now() if c else int(row["last_seen"]),
                    "controllable": row["kind"] != "web",
                }
            )
        for c in live.values():
            if c.device_id in seen:
                continue
            out.append(
                {
                    "id": c.device_id,
                    "name": c.name,
                    "kind": c.kind,
                    "os": c.os,
                    "version": c.version,
                    "actions": c.actions,
                    "online": True,
                    "last_seen": now(),
                    "controllable": c.kind != "web",
                }
            )
        out.sort(key=lambda d: (not d["online"], d["kind"] == "web", -d["last_seen"]))
        return out

    async def broadcast_devices(self, account_id: str) -> None:
        conns = list(self.online.get(account_id, {}).values())
        if not conns:
            return
        frame = {"type": "devices", "devices": self.devices(account_id)}
        await asyncio.gather(*(c.send(frame) for c in conns))

    async def broadcast_profile(self, account_id: str, rev: int, device: str) -> None:
        """The account's name or look changed: every connected device of it hears the new
        rev (and who wrote it, so the writer skips its own echo) and fetches the profile."""
        conns = list(self.online.get(account_id, {}).values())
        if not conns:
            return
        frame = {"type": "profile", "rev": rev, "device": device}
        await asyncio.gather(*(c.send(frame) for c in conns))

    def forget(self, account_id: str, device_id: str) -> None:
        if device_id in self.online.get(account_id, {}):
            raise CloudError(409, "device_online", "That device is connected right now; sign it out there first")
        self.db.forget_device(account_id, device_id)

    async def drop_account(self, account_id: str) -> None:
        """When an account is deleted or disabled: every socket of it is closed
        (the receive loops then remove themselves) so no device keeps a live line."""
        for c in list(self.online.get(account_id, {}).values()):
            c.closed = True
            try:
                await c.ws.close(code=4001, reason="account_gone")
            except Exception as e:  # already gone
                log.debug("close %s: %s", c.device_id, e)

    async def drop_keys(self, account_id: str, hashes: set[str] | None, *, exclude: set[str] = frozenset()) -> int:
        """A sign-in was revoked: close every socket of the account that used one of
        the revoked keys (4001). Pass hashes=None to close every socket except the
        excluded ones — what "sign out every other device" does after losing a phone.
        The receive loops then remove themselves, so a revoked device cannot keep
        sending call frames to the account's computers."""
        dropped = 0
        for c in list(self.online.get(account_id, {}).values()):
            if c.key_hash in exclude:
                continue
            if hashes is not None and c.key_hash not in hashes:
                continue
            c.closed = True
            try:
                await c.ws.close(code=4001, reason="key_revoked")
            except Exception as e:  # already gone
                log.debug("close %s: %s", c.device_id, e)
            dropped += 1
        return dropped

    def _key_live(self, conn: Connection) -> bool:
        """The sign-in this socket introduced itself with, still valid. Checked at
        most once every ~30 s per socket so a call-heavy peer does not hit the
        database on every frame."""
        if now() - conn.key_ok_at < 30:
            return True
        if self.db.key_live(conn.account_id, conn.key_hash):
            conn.key_ok_at = now()
            return True
        return False

    # -- one socket ------------------------------------------------------------------

    async def serve(self, ws: WebSocket, caller: Caller | None) -> None:
        """The whole life of one connection: hello, then frames until it drops."""
        await ws.accept()
        try:
            raw = await asyncio.wait_for(ws.receive_text(), HELLO_TIMEOUT_S)
        except (TimeoutError, WebSocketDisconnect):
            await self._close(ws, 4000, "hello expected")
            return
        hello = _loads(raw)
        if not isinstance(hello, dict) or hello.get("type") != "hello":
            await self._close(ws, 4000, "hello expected")
            return
        if caller is None:
            try:
                caller = self.cloud.authenticate(str(hello.get("key") or ""))
            except CloudError as e:
                await ws.send_text(json.dumps({"type": "error", "code": e.code, "message": e.message}))
                await self._close(ws, 4001, e.code)
                return
        dev = hello.get("device") if isinstance(hello.get("device"), dict) else {}
        try:
            conn = self._connection(ws, caller, dev)
        except CloudError as e:
            await ws.send_text(json.dumps({"type": "error", "code": e.code, "message": e.message}))
            await self._close(ws, 4002, e.code)
            return

        await self._attach(conn)
        try:
            await conn.send(
                {
                    "type": "welcome",
                    "device_id": conn.device_id,
                    "devices": self.devices(conn.account_id),
                    "server": {"version": __version__, "frame_limit": self.frame_limit, "time": now()},
                }
            )
            await self.broadcast_devices(conn.account_id)
            while True:
                try:
                    raw = await ws.receive_text()
                except WebSocketDisconnect:
                    break
                except RuntimeError:
                    # A socket we closed ourselves (drop_keys / drop_account /
                    # replaced) can surface as a state error instead of a
                    # disconnect on some servers and the test client.
                    break
                if len(raw) > self.frame_limit:
                    await conn.send({"type": "error", "code": "too_large", "message": f"Frames are capped at {self.frame_limit} bytes"})
                    continue
                frame = _loads(raw)
                if not isinstance(frame, dict):
                    await conn.send({"type": "error", "code": "bad_frame", "message": "Frames are JSON objects"})
                    continue
                await self.route(conn, frame)
        finally:
            await self._detach(conn)

    def _connection(self, ws: WebSocket, caller: Caller, dev: dict) -> Connection:
        kind = str(dev.get("kind") or "")
        if kind not in KINDS:
            raise CloudError(400, "bad_device", "kind must be phone, computer or web")
        device_id = str(dev.get("id") or "")
        if kind == "web" and not device_id:
            device_id = "web-" + uuid.uuid4().hex[:12]
        if not _ID.match(device_id):
            raise CloudError(400, "bad_device", "device id: 4–80 letters, digits, . _ : -")
        name = str(dev.get("name") or "").strip()[:60] or {"phone": "Phone", "computer": "Computer", "web": "Browser"}[kind]
        actions = [str(a)[:40] for a in dev.get("actions") or [] if isinstance(a, str)][:64]
        return Connection(
            ws=ws,
            account_id=caller.account_id,
            key_hash=caller.key_hash,
            device_id=device_id,
            name=name,
            kind=kind,
            os=str(dev.get("os") or "")[:60],
            version=str(dev.get("version") or "")[:40],
            actions=actions,
        )

    async def _attach(self, conn: Connection) -> None:
        account = self.online.setdefault(conn.account_id, {})
        old = account.get(conn.device_id)
        if old is not None:
            old.closed = True
            await self._close(old.ws, 4003, "replaced by a newer connection")
        account[conn.device_id] = conn
        if conn.kind != "web":
            self.db.upsert_device(conn.account_id, conn.device_id, conn.name, conn.kind, conn.os, conn.version, json.dumps(conn.actions))
        log.info(
            "hub: %s '%s' (%s) connected; account %s has %d online", conn.kind, conn.name, conn.device_id, conn.account_id[:8], len(account)
        )

    async def _detach(self, conn: Connection) -> None:
        conn.closed = True
        account = self.online.get(conn.account_id, {})
        if account.get(conn.device_id) is conn:
            del account[conn.device_id]
            if not account:
                self.online.pop(conn.account_id, None)
            if conn.kind != "web":
                self.db.touch_device(conn.account_id, conn.device_id)
            log.info("hub: %s '%s' (%s) left", conn.kind, conn.name, conn.device_id)
            await self._fail_pending_for(conn)
            await self.broadcast_devices(conn.account_id)

    async def _fail_pending_for(self, gone: Connection) -> None:
        """A device left: every call it was carrying out is answered `device_offline`; calls it made are dropped."""
        for call_id, p in list(self.pending.items()):
            if p.account_id != gone.account_id:
                continue
            if p.target == gone.device_id:
                del self.pending[call_id]
                caller = self.online.get(p.account_id, {}).get(p.caller)
                if caller:
                    await caller.send(
                        {"type": "error", "id": call_id, "code": "device_offline", "message": f"{gone.name} disconnected before answering"}
                    )
            elif p.caller == gone.device_id:
                del self.pending[call_id]

    @staticmethod
    async def _close(ws: WebSocket, code: int, reason: str) -> None:
        try:
            await ws.close(code=code, reason=reason)
        except Exception:
            pass

    # -- routing -----------------------------------------------------------------------

    async def route(self, conn: Connection, frame: dict) -> None:
        kind = frame.get("type")
        if kind == "ping":
            await conn.send({"type": "pong", "time": now()})
        elif kind == "devices":
            await conn.send({"type": "devices", "devices": self.devices(conn.account_id)})
        elif kind == "call":
            if not self._key_live(conn):
                # The sign-in behind this socket was revoked after hello (or the
                # drop missed it): kill the socket so it cannot drive the
                # account's devices any longer.
                conn.closed = True
                try:
                    await conn.ws.close(code=4001, reason="key_revoked")
                except Exception as e:  # already gone
                    log.debug("close %s: %s", conn.device_id, e)
                return
            await self._call(conn, frame)
        elif kind in ("result", "event"):
            await self._answer(conn, frame)
        elif kind == "rename":
            name = str(frame.get("name") or "").strip()[:60]
            if name:
                conn.name = name
                if conn.kind != "web":
                    self.db.upsert_device(conn.account_id, conn.device_id, name, conn.kind, conn.os, conn.version, json.dumps(conn.actions))
                await self.broadcast_devices(conn.account_id)
        elif kind == "forget":
            try:
                self.forget(conn.account_id, str(frame.get("device_id") or ""))
            except CloudError as e:
                await conn.send({"type": "error", "code": e.code, "message": e.message})
                return
            await self.broadcast_devices(conn.account_id)
        else:
            await conn.send({"type": "error", "code": "bad_frame", "message": f"Unknown frame type {kind!r}"})

    async def _call(self, conn: Connection, frame: dict) -> None:
        call_id = str(frame.get("id") or "")
        to = str(frame.get("to") or "")
        action = str(frame.get("action") or "")
        if not call_id or not to or not action:
            await conn.send({"type": "error", "id": call_id, "code": "bad_frame", "message": "call needs id, to and action"})
            return
        target = self.online.get(conn.account_id, {}).get(to)
        if target is None or target.closed:
            await conn.send({"type": "error", "id": call_id, "code": "device_offline", "message": "That device is not connected"})
            return
        if target.kind == "web":
            await conn.send(
                {"type": "error", "id": call_id, "code": "not_controllable", "message": "A browser tab cannot be asked to do things"}
            )
            return
        if target is conn:
            await conn.send({"type": "error", "id": call_id, "code": "self_call", "message": "That is this device"})
            return
        await self._sweep()
        self.pending[call_id] = Pending(call_id=call_id, caller=conn.device_id, target=to, account_id=conn.account_id)
        args = frame.get("args") if isinstance(frame.get("args"), dict) else {}
        await target.send({"type": "call", "id": call_id, "from": conn.brief(), "action": action, "args": args})

    async def _answer(self, conn: Connection, frame: dict) -> None:
        call_id = str(frame.get("id") or "")
        p = self.pending.get(call_id)
        if p is None or p.account_id != conn.account_id or p.target != conn.device_id:
            # Unknown or not this device's call: nothing to deliver to. Tell the sender once.
            await conn.send(
                {"type": "error", "id": call_id, "code": "unknown_call", "message": "No call with that id is waiting on this device"}
            )
            return
        caller = self.online.get(p.account_id, {}).get(p.caller)
        if frame.get("type") == "result":
            del self.pending[call_id]
        if caller is None:
            return
        out = {k: v for k, v in frame.items() if k in ("type", "id", "ok", "body", "error", "message")}
        out["from"] = conn.brief()
        await caller.send(out)

    async def _sweep(self) -> None:
        """Calls nobody answered in time are dropped, and the caller hears so instead of waiting for its own clock."""
        cutoff = now() - CALL_TTL_S
        for call_id, p in list(self.pending.items()):
            if p.started < cutoff:
                del self.pending[call_id]
                caller = self.online.get(p.account_id, {}).get(p.caller)
                if caller:
                    await caller.send({"type": "error", "id": call_id, "code": "timeout", "message": "the device did not answer in time"})


def _loads(raw: str):
    try:
        return json.loads(raw)
    except ValueError:
        return None
