"""The desktop's end of the hub: one socket to the cloud, kept up; calls in,
calls out, and the list of the account's devices as it changes."""

from __future__ import annotations

import json
import logging
import platform
import queue
import threading
import time
import uuid
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from . import __version__
from .wsclient import ConnectionClosed, WebSocket

log = logging.getLogger("unibot.hub")


class HubError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class IncomingCall:
    """A call another device made to this one; answer it exactly once."""

    id: str
    sender: dict
    action: str
    args: dict
    _client: HubClient
    answered: bool = False

    @property
    def sender_name(self) -> str:
        return str(self.sender.get("name") or self.sender.get("id") or "a device")

    def event(self, body: dict) -> None:
        if not self.answered:
            self._client._send({"type": "event", "id": self.id, "body": body})

    def result(self, body: dict) -> None:
        if not self.answered:
            self.answered = True
            self._client._send({"type": "result", "id": self.id, "ok": True, "body": body})

    def fail(self, code: str, message: str, **extra) -> None:
        if not self.answered:
            self.answered = True
            self._client._send({"type": "result", "id": self.id, "ok": False, "error": code, "message": message, "body": extra or {}})


@dataclass
class _Pending:
    done: threading.Event = field(default_factory=threading.Event)
    frame: dict | None = None
    on_event: Callable[[dict], None] | None = None
    events: queue.Queue = field(default_factory=queue.Queue)

    def pump(self) -> None:
        """Delivers progress events in order, off the socket's reader thread — so a
        handler may itself call through the hub (an approval, say) without deadlock."""
        while True:
            body = self.events.get()
            if body is None:
                return
            try:
                if self.on_event:
                    self.on_event(body)
            except Exception:
                log.exception("on_event")


class HubClient:
    def __init__(
        self,
        url: str,
        api_key: str,
        device_id: str,
        name: str,
        actions: list[str],
        kind: str = "computer",
        on_call: Callable[[IncomingCall], None] | None = None,
        on_devices: Callable[[list[dict]], None] | None = None,
        on_state: Callable[[str, str], None] | None = None,
        workers: int = 8,
    ):
        self.url = url
        self.api_key = api_key
        self.device_id = device_id
        self.name = name
        self.kind = kind
        self.actions = actions
        self.on_call = on_call
        self.on_devices = on_devices
        self.on_state = on_state
        self.devices: list[dict] = []
        self.connected = threading.Event()
        self._ws: WebSocket | None = None
        self._pending: dict[str, _Pending] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="hub-call")
        self._thread: threading.Thread | None = None
        self.last_error = ""

    # -- lifecycle ---------------------------------------------------------------------

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="hub", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        ws = self._ws
        if ws:
            ws.close(1000, "bye")
        self._fail_all("closed", "the hub connection was closed")

    def wait_connected(self, timeout: float) -> bool:
        return self.connected.wait(timeout)

    def _state(self, state: str, detail: str = "") -> None:
        if self.on_state:
            try:
                self.on_state(state, detail)
            except Exception:
                log.exception("on_state")

    def _run(self) -> None:
        delay = 1.0
        while not self._stop.is_set():
            try:
                self._session()
                delay = 1.0
            except ConnectionClosed as e:
                self.last_error = str(e)
                if e.code in (4001, 4002):  # bad key, bad device: no point retrying quickly
                    self._state("refused", e.reason or str(e.code))
                    delay = 60.0
                else:
                    self._state("disconnected", str(e))
            except OSError as e:
                self.last_error = str(e)
                self._state("disconnected", str(e))
            except Exception as e:  # keep the loop alive whatever happened
                self.last_error = str(e)
                log.exception("hub session")
                self._state("disconnected", str(e))
            finally:
                self.connected.clear()
                self._fail_all("disconnected", "the hub connection dropped")
            if self._stop.is_set():
                break
            self._stop.wait(delay)
            delay = min(delay * 2, 30.0)

    def _session(self) -> None:
        self._state("connecting", self.url)
        ws = WebSocket(self.url, headers={"Authorization": f"Bearer {self.api_key}", "User-Agent": f"unibot-Desktop/{__version__}"})
        ws.connect()
        self._ws = ws
        ws.send_text(
            json.dumps(
                {
                    "type": "hello",
                    "device": {
                        "id": self.device_id,
                        "name": self.name,
                        "kind": self.kind,
                        "os": f"{platform.system()} {platform.release()}".strip(),
                        "version": __version__,
                        "actions": self.actions,
                    },
                },
                ensure_ascii=False,
            )
        )
        pinger = threading.Thread(target=self._pinger, args=(ws,), name="hub-ping", daemon=True)
        pinger.start()
        try:
            while not self._stop.is_set():
                text = ws.recv_text()
                try:
                    frame = json.loads(text)
                except ValueError:
                    continue
                if isinstance(frame, dict):
                    self._on_frame(frame)
        finally:
            ws.close()
            self._ws = None

    def _pinger(self, ws: WebSocket) -> None:
        while not self._stop.is_set() and not ws.closed and self._ws is ws:
            if self._stop.wait(25.0):
                break
            try:
                ws.send_text('{"type":"ping"}')
            except ConnectionClosed:
                break

    # -- frames --------------------------------------------------------------------------

    def _on_frame(self, frame: dict) -> None:
        kind = frame.get("type")
        if kind == "welcome":
            self.devices = list(frame.get("devices") or [])
            self.connected.set()
            self._state("connected", str(frame.get("device_id") or self.device_id))
            if self.on_devices:
                self.on_devices(self.devices)
        elif kind == "devices":
            self.devices = list(frame.get("devices") or [])
            if self.on_devices:
                self.on_devices(self.devices)
        elif kind == "call":
            call = IncomingCall(
                id=str(frame.get("id") or ""),
                sender=frame.get("from") if isinstance(frame.get("from"), dict) else {},
                action=str(frame.get("action") or ""),
                args=frame.get("args") if isinstance(frame.get("args"), dict) else {},
                _client=self,
            )
            if self.on_call is None:
                call.fail("not_controllable", "this device takes no calls")
            else:
                self._pool.submit(self._handle, call)
        elif kind in ("result", "event", "error"):
            call_id = str(frame.get("id") or "")
            with self._lock:
                p = self._pending.get(call_id)
            if p is None:
                if kind == "error" and not call_id:
                    log.warning("hub error: %s %s", frame.get("code"), frame.get("message"))
                return
            if kind == "event":
                if p.on_event:
                    p.events.put(frame.get("body") if isinstance(frame.get("body"), dict) else {})
                return
            p.frame = frame
            p.done.set()
        elif kind == "pong":
            pass

    def _handle(self, call: IncomingCall) -> None:
        try:
            assert self.on_call is not None
            self.on_call(call)
        except Exception as e:
            log.exception("handling %s from %s", call.action, call.sender_name)
            call.fail("failed", f"{type(e).__name__}: {e}")
        if not call.answered:
            call.fail("failed", "the handler did not answer")

    def _send(self, frame: dict) -> None:
        ws = self._ws
        if ws is None or ws.closed:
            raise HubError("disconnected", "not connected to the hub")
        ws.send_text(json.dumps(frame, ensure_ascii=False, separators=(",", ":")))

    def _fail_all(self, code: str, message: str) -> None:
        with self._lock:
            pending = list(self._pending.values())
            self._pending.clear()
        for p in pending:
            p.frame = {"type": "error", "code": code, "message": message}
            p.done.set()

    # -- calls out -----------------------------------------------------------------------

    def call(self, to: str, action: str, args: dict | None = None, timeout: float = 120.0, on_event: Callable[[dict], None] | None = None) -> dict:
        """Ask device `to` to carry out `action`; returns the result body or raises HubError."""
        call_id = uuid.uuid4().hex[:16]
        p = _Pending(on_event=on_event)
        with self._lock:
            self._pending[call_id] = p
        pump = None
        if on_event is not None:
            pump = threading.Thread(target=p.pump, name=f"hub-events-{call_id[:6]}", daemon=True)
            pump.start()
        try:
            self._send({"type": "call", "id": call_id, "to": to, "action": action, "args": args or {}})
            if not p.done.wait(timeout):
                raise HubError("timeout", f"no answer from the device within {int(timeout)} s")
        finally:
            with self._lock:
                self._pending.pop(call_id, None)
            if pump is not None:
                p.events.put(None)
                pump.join(5)
        frame = p.frame or {}
        if frame.get("type") == "error":
            raise HubError(str(frame.get("code") or "error"), str(frame.get("message") or "the hub refused the call"))
        if not frame.get("ok"):
            raise HubError(str(frame.get("error") or "failed"), str(frame.get("message") or "the device could not do it"))
        body = frame.get("body")
        return body if isinstance(body, dict) else {}

    def send_devices_request(self) -> None:
        self._send({"type": "devices"})

    def rename(self, name: str) -> None:
        self.name = name
        self._send({"type": "rename", "name": name})

    def find(self, query: str) -> dict | None:
        """A device by name (case-insensitive), by id, or by a unique substring; never this one."""
        q = (query or "").strip().lower()
        others = [d for d in self.devices if d.get("id") != self.device_id and d.get("kind") != "web"]
        if not q:
            online = [d for d in others if d.get("online")]
            return online[0] if len(online) == 1 else None
        for d in others:
            if str(d.get("name", "")).lower() == q or d.get("id") == query:
                return d
        hits = [d for d in others if q in str(d.get("name", "")).lower()]
        if len(hits) == 1:
            return hits[0]
        kinds = [
            d
            for d in others
            if d.get("online") and (q in ("phone", "手机") and d.get("kind") == "phone" or q in ("pc", "computer", "电脑") and d.get("kind") == "computer")
        ]
        return kinds[0] if len(kinds) == 1 else None

    def others(self) -> list[dict]:
        return [d for d in self.devices if d.get("id") != self.device_id and d.get("kind") != "web"]


def wait_for(predicate: Callable[[], bool], timeout: float, step: float = 0.1) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(step)
    return predicate()
