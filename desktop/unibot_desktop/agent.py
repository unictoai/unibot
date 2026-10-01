"""The desktop Muse: an agent loop over unibot Cloud's chat models with two kinds
of hands — this computer's own (shell, files, browser, screen) and, through the
hub, every other device of the account (a phone's, another computer's), plus
`delegate`, which hands a whole task to the Muse running on that device.

One run = one user turn: the model is called, its tool calls carried out, until
it answers in words. Progress is reported through `emit` so a console or a
phone can follow along; anything that deletes, sends or pays is judged by
guard.py first and asked through `approve`.
"""

from __future__ import annotations

import base64
import json
import logging
import re
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from . import __version__, actions, guard
from .cloud import Cloud, CloudError, describe
from .config import Config, home
from .hub import HubClient, HubError

log = logging.getLogger("unibot.agent")

MAX_STEPS = 30
RUN_TIMEOUT_S = 15 * 60
HISTORY_KEEP = 40
MEMORY_LIMIT = 4000
TOOL_RESULT_LIMIT = 24_000

Emit = Callable[[dict], None]
Approve = Callable[[str, guard.Risk], bool]


@dataclass
class RunResult:
    text: str
    steps: int
    conversation: str


def _summ(s: str, n: int = 160) -> str:
    s = re.sub(r"\s+", " ", (s or "")).strip()
    return s if len(s) <= n else s[: n - 1] + "…"


def _clip(s: str, n: int = TOOL_RESULT_LIMIT) -> str:
    return s if len(s) <= n else s[:n] + f"\n… [{len(s) - n} more characters not shown]"


class Memory:
    def __init__(self, path: Path):
        self.path = path

    def read(self) -> str:
        try:
            text = self.path.read_text(encoding="utf-8")
        except OSError:
            return ""
        return text[-MEMORY_LIMIT:]

    def add(self, line: str) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as f:
            f.write(f"- {time.strftime('%Y-%m-%d')} {line.strip()}\n")


class Agent:
    def __init__(self, cfg: Config, cloud: Cloud, hub: HubClient | None):
        self.cfg = cfg
        self.cloud = cloud
        self.hub = hub
        self.memory = Memory(home() / "desktop" / "memory.md")
        self.conv_dir = home() / "desktop" / "conversations"
        self.model = cfg.model
        self._lock = threading.Lock()
        self._active: dict[str, threading.Event] = {}

    # -- prompt --------------------------------------------------------------------------

    def system_prompt(self) -> str:
        me = actions.info()
        lines = [
            f"You are unibot, the user's personal agent, running on their computer “{self.cfg.name}” "
            f"({me['os']} {me['os_version']}, {me['arch']}; user {me['user']}; home {me['home']}; shell {me['shell']}). "
            "The same person has Muses on their other devices — a phone, other computers — and all of you meet through the unibot hub, "
            "so you can act here and also ask their other devices to act. You are warm, brief and concrete; you answer in the user's language "
            "(the language of their message), and you never pretend something happened that did not.",
            "",
            "How to work:",
            "- Use `shell`, `read_file`, `write_file`, `list_dir`, `open`, `screenshot` and `notify` for this computer. Prefer one well-chosen command over many; quote paths.",
            "- `devices` lists the user's other devices and whether each is online. Use the device's name as given there.",
            "- `device_shell`, `device_files`, `device_get`, `device_put`, `device_open`, `device_screen`, `device_notify` act on another device directly. "
            "On a phone, `device_shell` runs inside the phone app's Linux sandbox (Alpine), not on Android itself; the phone's own apps are reached through `delegate`.",
            "- `delegate` hands a whole task, in plain words, to the Muse running on that device — use it when the job needs that device's apps, screen, context or judgement "
            "(e.g. “open the calendar and tell me tomorrow's first meeting” on the phone, or “find the PDF I downloaded yesterday and send it here”). Pass on its answer faithfully.",
            "- If a device is offline, say so plainly; do not guess what it would have said.",
            "- Before anything that removes files, sends something out, changes the system or installs software, say in one line what you are about to run; the user may be asked to approve it. "
            "If they say no, stop and say so in one line — do not try another way. Never make a payment or anything that looks like one.",
            "- Say what you did with the result in two or three sentences; include the concrete output the user needs (paths, numbers, the text they asked for). Use Markdown sparingly.",
            "- `remember` keeps a short note for future conversations (preferences, names of things, where files live); use it when the user tells you something worth keeping.",
        ]
        if self.hub is not None:
            others = self.hub.others()
            if others:
                lines += [
                    "",
                    "Devices right now: " + "; ".join(f"{d['name']} ({d['kind']}, {'online' if d.get('online') else 'offline'})" for d in others) + ".",
                ]
            else:
                lines += ["", "No other device of the user is known to the hub yet; they can sign in on their phone or another computer with the same account."]
        else:
            lines += ["", "The hub is not connected right now: only this computer's own tools work."]
        mem = self.memory.read()
        if mem.strip():
            lines += ["", "Notes you kept earlier:", mem.strip()]
        if self.cfg.language:
            lines += ["", f"The user prefers answers in: {self.cfg.language}."]
        lines += ["", f"Today is {time.strftime('%Y-%m-%d %A %H:%M')} (local time). unibot Desktop {__version__}."]
        return "\n".join(lines)

    # -- tools ---------------------------------------------------------------------------

    def tools(self) -> list[dict]:
        def t(name: str, desc: str, props: dict, required: list[str]) -> dict:
            return {
                "type": "function",
                "function": {"name": name, "description": desc, "parameters": {"type": "object", "properties": props, "required": required}},
            }

        dev = {"device": {"type": "string", "description": "the other device's name as listed by `devices`"}}
        local = [
            t(
                "shell",
                "Run a shell command on this computer and get exit code, stdout and stderr.",
                {
                    "command": {"type": "string"},
                    "cwd": {"type": "string", "description": "working directory (optional)"},
                    "timeout": {"type": "integer", "description": "seconds, default 120, max 900"},
                },
                ["command"],
            ),
            t("read_file", "Read a text file on this computer (first 100 KB).", {"path": {"type": "string"}}, ["path"]),
            t(
                "write_file",
                "Write a text file on this computer; existing files need overwrite=true.",
                {"path": {"type": "string"}, "content": {"type": "string"}, "overwrite": {"type": "boolean"}},
                ["path", "content"],
            ),
            t("list_dir", "List a folder on this computer.", {"path": {"type": "string", "description": "default: the home folder"}}, []),
            t("open", "Open a URL, file or folder on this computer with the default app.", {"url": {"type": "string"}}, ["url"]),
            t("screenshot", "Look at this computer's screen (a picture is returned to you).", {}, []),
            t("notify", "Show a desktop notification here.", {"text": {"type": "string"}, "title": {"type": "string"}}, ["text"]),
            t("remember", "Keep a short note for future conversations.", {"note": {"type": "string"}}, ["note"]),
        ]
        remote = [
            t("devices", "The user's other devices (phone, computers) and whether each is online.", {}, []),
            t(
                "device_shell",
                "Run a shell command on another device (on a phone: inside the app's Linux sandbox).",
                {**dev, "command": {"type": "string"}, "cwd": {"type": "string"}, "timeout": {"type": "integer"}},
                ["device", "command"],
            ),
            t("device_files", "List a folder on another device.", {**dev, "path": {"type": "string"}}, ["device"]),
            t(
                "device_get",
                "Copy a file from another device to this computer (into the unibot downloads folder).",
                {**dev, "path": {"type": "string", "description": "path on the other device"}},
                ["device", "path"],
            ),
            t(
                "device_put",
                "Copy a file from this computer to another device.",
                {
                    **dev,
                    "local_path": {"type": "string"},
                    "remote_path": {"type": "string"},
                    "force": {"type": "boolean", "description": "replace if it exists"},
                },
                ["device", "local_path", "remote_path"],
            ),
            t("device_open", "Open a URL on another device (its browser / matching app).", {**dev, "url": {"type": "string"}}, ["device", "url"]),
            t("device_screen", "Look at another device's screen (a picture is returned to you).", dev, ["device"]),
            t("device_notify", "Show a notification on another device.", {**dev, "text": {"type": "string"}, "title": {"type": "string"}}, ["device", "text"]),
            t(
                "delegate",
                "Hand a whole task, in plain words, to the Muse running on another device and wait for its answer (up to ten minutes). Give every detail it needs.",
                {**dev, "task": {"type": "string"}},
                ["device", "task"],
            ),
        ]
        return local + (remote if self.hub is not None else [])

    # -- conversations -------------------------------------------------------------------

    def _load(self, conversation: str) -> list[dict]:
        p = self.conv_dir / f"{conversation}.json"
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            return data if isinstance(data, list) else []
        except (OSError, ValueError):
            return []

    def _save(self, conversation: str, messages: list[dict]) -> None:
        self.conv_dir.mkdir(parents=True, exist_ok=True)
        keep = [m for m in messages if m.get("role") != "system"]
        # Never cut between an assistant tool call and its tool results.
        while len(keep) > HISTORY_KEEP and keep:
            keep.pop(0)
            while keep and keep[0].get("role") == "tool":
                keep.pop(0)
        slim = []
        for m in keep:
            if isinstance(m.get("content"), list):  # drop stored images; they are big and served their purpose
                m = {**m, "content": " ".join(c.get("text", "[image]") if c.get("type") == "text" else "[image]" for c in m["content"])}
            slim.append(m)
        (self.conv_dir / f"{conversation}.json").write_text(json.dumps(slim, ensure_ascii=False), encoding="utf-8")

    def forget_conversation(self, conversation: str) -> None:
        try:
            (self.conv_dir / f"{conversation}.json").unlink()
        except OSError:
            pass

    def is_running(self, conversation: str) -> bool:
        return conversation in self._active

    def stop(self, conversation: str) -> bool:
        ev = self._active.get(conversation)
        if ev:
            ev.set()
            return True
        return False

    # -- the loop ----------------------------------------------------------------------------

    def run(self, text: str, conversation: str, emit: Emit, approve: Approve, images: list[str] | None = None) -> RunResult:
        if not self.model:
            self.model = self.cfg.model or self.cloud.recommended_model()
        stop = threading.Event()
        with self._lock:
            if conversation in self._active:
                raise RuntimeError("this conversation is already busy")
            self._active[conversation] = stop
        try:
            return self._run(text, conversation, emit, approve, images or [], stop)
        finally:
            with self._lock:
                self._active.pop(conversation, None)

    def _run(self, text: str, conversation: str, emit: Emit, approve: Approve, images: list[str], stop: threading.Event) -> RunResult:
        history = self._load(conversation)
        user: dict = {"role": "user", "content": text}
        if images:
            user["content"] = [{"type": "text", "text": text}] + [{"type": "image_url", "image_url": {"url": u}} for u in images[:4]]
        messages: list[dict] = [{"role": "system", "content": self.system_prompt()}, *history, user]
        tools = self.tools()
        started = time.monotonic()
        steps = 0
        final = ""
        while True:
            if stop.is_set():
                final = "Stopped."
                break
            if steps >= MAX_STEPS or time.monotonic() - started > RUN_TIMEOUT_S:
                final = "I stopped here: this took too many steps. Tell me how to narrow it down and I will continue."
                messages.append({"role": "assistant", "content": final})
                break
            emit({"stage": "thinking", "step": steps})
            try:
                reply = self.cloud.chat(self.model, messages, tools)
            except CloudError as e:
                if e.code == "model_not_offered" and self.cfg.model:
                    self.model = self.cloud.recommended_model()
                    continue
                final = f"unibot Cloud: {describe(e)}"
                emit({"stage": "error", "code": e.code, "message": final})
                break
            choice = (reply.get("choices") or [{}])[0]
            msg = choice.get("message") or {}
            content = msg.get("content") or ""
            tool_calls = msg.get("tool_calls") or []
            assistant: dict = {"role": "assistant", "content": content if content else None}
            if tool_calls:
                assistant["tool_calls"] = tool_calls
            messages.append(assistant)
            if content and tool_calls:
                emit({"stage": "text", "text": content, "interim": True})
            if not tool_calls:
                final = content or "(no answer)"
                break
            steps += 1
            attachments: list[dict] = []
            for tc in tool_calls:
                fn = tc.get("function") or {}
                name = str(fn.get("name") or "")
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                    if not isinstance(args, dict):
                        args = {}
                except ValueError:
                    args = {}
                emit({"stage": "tool", "name": name, "summary": self._preview(name, args)})
                result, image = self._tool(name, args, emit, approve)
                emit({"stage": "tool_result", "name": name, "ok": not result.startswith('{"error"'), "summary": _summ(result, 200)})
                messages.append({"role": "tool", "tool_call_id": tc.get("id"), "content": _clip(result)})
                if image:
                    attachments.append(image)
            for att in attachments:
                messages.append(
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": f"(the picture from `{att['tool']}`)"},
                            {"type": "image_url", "image_url": {"url": att["data_uri"]}},
                        ],
                    }
                )
        self._save(conversation, messages)
        emit({"stage": "text", "text": final})
        emit({"stage": "done", "steps": steps})
        return RunResult(text=final, steps=steps, conversation=conversation)

    # -- one tool ------------------------------------------------------------------------------

    def _preview(self, name: str, args: dict) -> str:
        if name in ("shell", "device_shell"):
            return (f"{args.get('device')}: " if name == "device_shell" else "") + _summ(str(args.get("command", "")), 120)
        if name == "delegate":
            return f"{args.get('device')}: {_summ(str(args.get('task', '')), 120)}"
        if name in ("read_file", "list_dir", "open", "device_files", "device_get", "device_open"):
            return _summ(str(args.get("path") or args.get("url") or ""), 120)
        if name == "write_file":
            return _summ(str(args.get("path", "")), 120)
        if name in ("notify", "device_notify"):
            return _summ(str(args.get("text", "")), 120)
        if name == "device_put":
            return f"{args.get('local_path')} → {args.get('device')}:{args.get('remote_path')}"
        return _summ(json.dumps(args, ensure_ascii=False), 120) if args else ""

    def _tool(self, name: str, args: dict, emit: Emit, approve: Approve) -> tuple[str, dict | None]:
        """→ (JSON text for the model, optional picture to show it)."""
        try:
            if name.startswith("device_") or name in ("devices", "delegate"):
                return self._remote(name, args, emit, approve)
            return self._local(name, args, emit, approve)
        except actions.ActionError as e:
            return json.dumps({"error": e.code, "message": e.message}), None
        except HubError as e:
            return json.dumps({"error": e.code, "message": e.message}), None
        except Exception as e:  # a tool must never take the loop down
            log.exception("tool %s", name)
            return json.dumps({"error": "failed", "message": f"{type(e).__name__}: {e}"}), None

    def _gate(self, risk: guard.Risk, preview: str, approve: Approve) -> str | None:
        """None when it may go ahead, else the JSON refusal for the model."""
        if risk.refused:
            return json.dumps(
                {"error": "refused", "message": "unibot never makes a payment or anything that looks like one.", "tell_user": "Say so in one line."}
            )
        if risk.asks and self.cfg.approvals != "allow":
            if not approve(preview, risk):
                return json.dumps(
                    {
                        "error": "denied",
                        "message": "The user did not allow it.",
                        "tell_user": "Tell the user in one line, addressing them directly, that it was not done because they declined; do not try another way.",
                    }
                )
        return None

    def _local(self, name: str, args: dict, emit: Emit, approve: Approve) -> tuple[str, dict | None]:
        if name == "shell":
            command = str(args.get("command", ""))
            risk = guard.assess(command)
            refusal = self._gate(risk, f"on {self.cfg.name}: {command}", approve)
            if refusal:
                return refusal, None
            r = actions.shell(command, args.get("cwd"), float(args.get("timeout") or 120))
            return json.dumps(r, ensure_ascii=False), None
        if name == "read_file":
            p = actions.expand(str(args.get("path", "")))
            if not p.is_file():
                raise actions.ActionError("not_found", f"{p} is not a file")
            data = p.read_bytes()[:100_000]
            return json.dumps({"path": str(p), "bytes": p.stat().st_size, "text": data.decode("utf-8", "replace")}, ensure_ascii=False), None
        if name == "write_file":
            p = actions.expand(str(args.get("path", "")))
            if p.exists() and not args.get("overwrite"):
                raise actions.ActionError("exists", f"{p} exists; pass overwrite=true to replace it")
            if p.exists():
                refusal = self._gate(guard.Risk(guard.DESTRUCTIVE, "replaces a file", str(p)), f"replace {p}", approve)
                if refusal:
                    return refusal, None
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(str(args.get("content", "")), encoding="utf-8")
            return json.dumps({"path": str(p), "bytes": p.stat().st_size}), None
        if name == "list_dir":
            return json.dumps(actions.files(args.get("path")), ensure_ascii=False), None
        if name == "open":
            return json.dumps(actions.open_target(str(args.get("url", "")))), None
        if name == "screenshot":
            r = actions.screen()
            emit({"stage": "image", "mime": r["mime"], "data": r["data"], "from": self.cfg.name})
            return json.dumps({"ok": True, "note": "the picture follows"}), {"tool": "screenshot", "data_uri": f"data:{r['mime']};base64,{r['data']}"}
        if name == "notify":
            return json.dumps(actions.notify(str(args.get("text", "")), str(args.get("title") or "unibot"))), None
        if name == "remember":
            note = str(args.get("note", "")).strip()
            if note:
                self.memory.add(note)
            return json.dumps({"ok": True}), None
        return json.dumps({"error": "unknown_tool", "message": f"no tool named {name}"}), None

    def _device(self, query: str) -> dict:
        assert self.hub is not None
        d = self.hub.find(query)
        if d is None:
            names = ", ".join(f"{x['name']} ({'online' if x.get('online') else 'offline'})" for x in self.hub.others()) or "none"
            raise HubError("no_device", f"no device matches “{query}”; known: {names}")
        if not d.get("online"):
            raise HubError("device_offline", f"{d['name']} is offline right now")
        return d

    def _remote(self, name: str, args: dict, emit: Emit, approve: Approve) -> tuple[str, dict | None]:
        if self.hub is None:
            raise HubError("no_hub", "the hub is not connected")
        if name == "devices":
            return json.dumps(
                {"this": self.cfg.name, "devices": [{k: d.get(k) for k in ("name", "kind", "os", "online", "actions")} for d in self.hub.others()]},
                ensure_ascii=False,
            ), None
        d = self._device(str(args.get("device", "")))
        to, dname = d["id"], d["name"]
        if name == "device_shell":
            command = str(args.get("command", ""))
            refusal = self._gate(guard.assess(command), f"on {dname}: {command}", approve)
            if refusal:
                return refusal, None
            r = self.hub.call(
                to,
                "shell",
                {"command": command, "cwd": args.get("cwd"), "timeout": int(args.get("timeout") or 120)},
                timeout=float(args.get("timeout") or 120) + 30,
            )
            return json.dumps({"device": dname, **r}, ensure_ascii=False), None
        if name == "device_files":
            return json.dumps({"device": dname, **self.hub.call(to, "files", {"path": args.get("path")})}, ensure_ascii=False), None
        if name == "device_get":
            r = self.hub.call(to, "file.get", {"path": str(args.get("path", ""))}, timeout=300)
            dest = self.cfg.downloads_dir() / re.sub(r"[/\\]", "_", str(r.get("name") or "file"))
            i = 1
            while dest.exists():
                dest = dest.with_name(f"{dest.stem}-{i}{dest.suffix}")
                i += 1
            dest.write_bytes(base64.b64decode(r.get("data") or ""))
            mime = str(r.get("mime") or "")
            if mime.startswith("image/"):
                emit({"stage": "image", "mime": mime, "data": r.get("data"), "from": dname})
            return json.dumps({"device": dname, "saved_to": str(dest), "bytes": r.get("bytes")}, ensure_ascii=False), None
        if name == "device_put":
            local = actions.expand(str(args.get("local_path", "")))
            if not local.is_file():
                raise actions.ActionError("not_found", f"{local} is not a file")
            if local.stat().st_size > actions.FILE_LIMIT:
                raise actions.ActionError("too_large", f"{local.name} is over the {actions.FILE_LIMIT}-byte limit")
            if args.get("force"):
                refusal = self._gate(
                    guard.Risk(guard.DESTRUCTIVE, "replaces a file", str(args.get("remote_path"))), f"replace {args.get('remote_path')} on {dname}", approve
                )
                if refusal:
                    return refusal, None
            data = base64.b64encode(local.read_bytes()).decode()
            r = self.hub.call(to, "file.put", {"path": str(args.get("remote_path", "")), "data": data, "force": bool(args.get("force"))}, timeout=300)
            return json.dumps({"device": dname, **r}, ensure_ascii=False), None
        if name == "device_open":
            return json.dumps({"device": dname, **self.hub.call(to, "open", {"url": str(args.get("url", ""))})}), None
        if name == "device_screen":
            r = self.hub.call(to, "screen", {}, timeout=60)
            emit({"stage": "image", "mime": r.get("mime"), "data": r.get("data"), "from": dname})
            return json.dumps({"device": dname, "ok": True, "note": "the picture follows"}), {
                "tool": f"device_screen({dname})",
                "data_uri": f"data:{r.get('mime') or 'image/jpeg'};base64,{r.get('data')}",
            }
        if name == "device_notify":
            return json.dumps(
                {"device": dname, **self.hub.call(to, "notify", {"text": str(args.get("text", "")), "title": str(args.get("title") or "unibot")})}
            ), None
        if name == "delegate":
            task = str(args.get("task", "")).strip()
            if not task:
                raise actions.ActionError("usage", "task is required")
            emit({"stage": "delegate", "device": dname, "task": task})

            def on_event(body: dict) -> None:
                stage = body.get("stage")
                if stage == "approval":
                    # The other Muse asks whether it may do something: ask our user, answer it.
                    ok = approve(
                        f"on {dname}: {body.get('preview', '')}", guard.Risk(str(body.get("risk") or guard.DESTRUCTIVE), str(body.get("reason") or ""), "")
                    )
                    try:
                        self.hub.call(to, "approve", {"approval_id": body.get("approval_id"), "allow": ok}, timeout=30)  # type: ignore[union-attr]
                    except HubError as e:
                        log.warning("approve to %s failed: %s", dname, e)
                    return
                if stage == "image":
                    emit({"stage": "image", "mime": body.get("mime"), "data": body.get("data"), "from": dname})
                    return
                if stage in ("tool", "text", "thinking", "tool_result"):
                    emit({"stage": "remote", "device": dname, "body": body})

            r = self.hub.call(to, "task", {"text": task, "from": self.cfg.name}, timeout=600, on_event=on_event)
            return json.dumps(
                {"device": dname, "answer": r.get("text") or r.get("answer") or "", **{k: v for k, v in r.items() if k not in ("text", "answer")}},
                ensure_ascii=False,
            ), None
        return json.dumps({"error": "unknown_tool", "message": f"no tool named {name}"}), None
