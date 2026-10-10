"""The running desktop: the hub connection, the agent, and what happens when another
device calls — a raw action (shell, files, screen…), a whole task for the agent,
or an answer to one of the agent's approval questions."""

from __future__ import annotations

import logging
import sys
import threading
import time
import uuid
from collections.abc import Callable

from . import actions, guard
from .agent import Agent, RunResult
from .cloud import Cloud
from .config import Config, save
from .hub import HubClient, IncomingCall

log = logging.getLogger("unibot.app")

APPROVAL_TIMEOUT_S = 180


class Desktop:
    def __init__(self, cfg: Config, quiet: bool = False):
        self.cfg = cfg
        self.quiet = quiet
        self.cloud = Cloud(cfg.cloud_base, cfg.api_key)
        self.hub = HubClient(
            self.cloud.hub_url,
            cfg.api_key,
            cfg.device_id,
            cfg.name,
            actions=list(actions.ACTIONS) + ["stop"],
            on_call=self.on_call,
            on_devices=self.on_devices,
            on_state=self.on_state,
        )
        self.agent = Agent(cfg, self.cloud, self.hub)
        self._approvals: dict[str, tuple[threading.Event, list[bool]]] = {}
        self._lock = threading.Lock()
        self.state = "starting"
        self.state_detail = ""
        self.terminal_lock = threading.Lock()  # one question on the terminal at a time

    # -- lifecycle -----------------------------------------------------------------------

    def start(self) -> None:
        self.hub.start()

    def stop(self) -> None:
        self.hub.stop()

    def say(self, text: str) -> None:
        if not self.quiet:
            print(text, flush=True)

    def on_state(self, state: str, detail: str) -> None:
        self.state, self.state_detail = state, detail
        if state == "connected":
            self.say(f"· connected to the hub as “{self.cfg.name}”")
        elif state == "refused":
            self.say(f"· the hub refused this device ({detail}); sign in again with `unibot-desktop sign-in`")
        elif state == "disconnected" and detail:
            log.info("hub: %s", detail)

    def on_devices(self, devices: list[dict]) -> None:
        others = [d for d in devices if d.get("id") != self.cfg.device_id and d.get("kind") != "web"]
        if others and not self.quiet:
            line = ", ".join(f"{d['name']} ({'online' if d.get('online') else 'offline'})" for d in others)
            self.say(f"· devices: {line}")

    # -- calls in ------------------------------------------------------------------------

    def on_call(self, call: IncomingCall) -> None:
        if call.action == "approve":
            self._decide(str(call.args.get("approval_id") or ""), bool(call.args.get("allow")))
            call.result({"ok": True})
            return
        if not self.cfg.remote_control and call.action != "info":
            call.fail("not_allowed", f"{self.cfg.name} is set not to be operated from other devices")
            return
        if call.action == "task":
            self._task(call)
            return
        if call.action == "stop":
            call.result({"stopped": self.agent.stop(str(call.args.get("conversation") or call.sender.get("id") or ""))})
            return
        if call.action in actions.ACTIONS:
            self.say(f"· {call.sender_name} → {call.action} {self._brief(call)}")
            try:
                if call.action == "shell":
                    call.result(self._guarded_shell(call.args))
                else:
                    call.result(actions.run(call.action, call.args))
            except actions.ActionError as e:
                call.fail(e.code, e.message)
            return
        call.fail("unknown_action", f"this computer does not do '{call.action}'")

    def _guarded_shell(self, args: dict) -> dict:
        """A remote ``shell`` call, judged by the guard first: the agent's own path asks
        the user through ``approve``, but a peer over the hub has no one to ask — so
        anything the guard would ask about (destructive, outbound, system) and anything
        it refuses outright (money) is refused here instead of running. Fail closed: a
        guard that cannot judge refuses too."""
        try:
            risk = guard.assess(str(args.get("command") or ""))
        except Exception as exc:  # noqa: BLE001
            raise actions.ActionError("refused", f"the command could not be checked: {exc}") from exc
        if risk.asks or risk.refused:
            self.say(f"  ✗ guard refused a remote shell call: {guard.describe(risk)}")
            raise actions.ActionError(
                "refused", f"the guard refused this command ({guard.describe(risk)})"
            )
        return actions.run("shell", args)

    @staticmethod
    def _brief(call: IncomingCall) -> str:
        a = call.args
        for k in ("command", "path", "url", "text"):
            if a.get(k):
                s = str(a[k]).replace("\n", " ")
                return s if len(s) < 100 else s[:99] + "…"
        return ""

    def _task(self, call: IncomingCall) -> None:
        text = str(call.args.get("text") or "").strip()
        if not text:
            call.fail("usage", "text is required")
            return
        conversation = str(call.args.get("conversation") or f"from-{call.sender.get('id') or 'unknown'}")
        images = [str(u) for u in call.args.get("images") or [] if isinstance(u, str)]
        self.say(f"· {call.sender_name} asks: {text if len(text) < 120 else text[:119] + '…'}")

        def emit(body: dict) -> None:
            call.event(body)
            if body.get("stage") == "tool" and not self.quiet:
                self.say(f"  ↳ {body.get('name')} {body.get('summary', '')}")

        def approve(preview: str, risk: guard.Risk) -> bool:
            return self._ask_remote(call, preview, risk)

        try:
            result: RunResult = self.agent.run(text, conversation, emit, approve, images)
        except RuntimeError as e:
            call.fail("busy", str(e))
            return
        call.result({"text": result.text, "conversation": result.conversation, "steps": result.steps, "device": self.cfg.name})

    # -- approvals -----------------------------------------------------------------------

    def _ask_remote(self, call: IncomingCall, preview: str, risk: guard.Risk) -> bool:
        approval_id = uuid.uuid4().hex[:12]
        ev, box = threading.Event(), [False]
        with self._lock:
            self._approvals[approval_id] = (ev, box)
        try:
            call.event(
                {
                    "stage": "approval",
                    "approval_id": approval_id,
                    "preview": preview,
                    "risk": risk.level,
                    "reason": risk.reason,
                    "device": self.cfg.name,
                    "timeout": APPROVAL_TIMEOUT_S,
                }
            )
            self.say(f"  ? waiting for {call.sender_name} to allow: {preview}")
            if not ev.wait(APPROVAL_TIMEOUT_S):
                self.say("  ✗ no answer; not done")
                return False
            self.say("  ✓ allowed" if box[0] else "  ✗ declined")
            return box[0]
        finally:
            with self._lock:
                self._approvals.pop(approval_id, None)

    def _decide(self, approval_id: str, allow: bool) -> None:
        with self._lock:
            entry = self._approvals.get(approval_id)
        if entry:
            entry[1][0] = allow
            entry[0].set()

    def ask_terminal(self, preview: str, risk: guard.Risk) -> bool:
        with self.terminal_lock:
            print(f"\n  ? {risk.level}: {risk.reason}\n    {preview}", flush=True)
            try:
                answer = input("    allow? [y/N] ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                return False
            return answer in ("y", "yes", "是", "好", "允许")

    # -- the terminal ------------------------------------------------------------------------

    def ask(self, text: str, conversation: str = "terminal", images: list[str] | None = None) -> RunResult:
        """One turn from the terminal: progress on stderr-ish lines, the answer returned."""

        def emit(body: dict) -> None:
            stage = body.get("stage")
            if stage == "tool":
                print(f"  ↳ {body.get('name')} {body.get('summary', '')}", flush=True)
            elif stage == "delegate":
                print(f"  ↳ asking {body.get('device')}: {body.get('task')}", flush=True)
            elif stage == "remote" and (body.get("body") or {}).get("stage") == "tool":
                b = body["body"]
                print(f"    {body.get('device')} ↳ {b.get('name')} {b.get('summary', '')}", flush=True)
            elif stage == "image":
                path = self._save_image(body)
                if path:
                    print(f"  ▣ picture saved: {path}", flush=True)
            elif stage == "error":
                print(f"  ! {body.get('message')}", flush=True)

        return self.agent.run(text, conversation, emit, self.ask_terminal, images)

    def _save_image(self, body: dict) -> str | None:
        import base64

        data = body.get("data")
        if not isinstance(data, str) or not data:
            return None
        ext = "jpg" if "jpeg" in str(body.get("mime") or "") else "png"
        p = self.cfg.downloads_dir() / f"{str(body.get('from') or 'screen').replace(' ', '_')}-{time.strftime('%Y%m%d-%H%M%S')}.{ext}"
        try:
            p.write_bytes(base64.b64decode(data))
        except (OSError, ValueError):
            return None
        return str(p)

    def repl(self, opener: Callable[[], None] | None = None) -> None:
        print("Type a message for this computer's Muse; `/devices`, `/ask <device> <text>`, `/quit`.", flush=True)
        if opener:
            opener()
        while True:
            try:
                line = input("\nyou › ").strip()
            except (EOFError, KeyboardInterrupt):
                print()
                return
            if not line:
                continue
            if line in ("/quit", "/exit", "/q"):
                return
            if line == "/devices":
                for d in self.hub.others():
                    print(f"  {'●' if d.get('online') else '○'} {d['name']}  {d['kind']}  {d.get('os', '')}")
                if not self.hub.others():
                    print("  no other device yet")
                continue
            if line.startswith("/ask "):
                rest = line[5:].strip()
                dev, _, task = rest.partition(" ")
                line = f"Ask the device “{dev}” to do this and give me its answer: {task}"
            try:
                result = self.ask(line)
            except RuntimeError as e:
                print(f"  ! {e}")
                continue
            print(f"\nunibot › {result.text}", flush=True)


def apply_name(cfg: Config, name: str, desktop: Desktop | None = None) -> None:
    cfg.name = name.strip()[:60] or cfg.name
    save(cfg)
    if desktop is not None and desktop.hub.connected.is_set():
        desktop.hub.rename(cfg.name)


def utf8_console() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass
