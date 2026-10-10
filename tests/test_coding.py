"""The coding agents bridge: reading Cursor / Codex / Claude Code transcripts, driving a CLI
and streaming its output, the runtime API on top (docs/coding-agents.md)."""

from __future__ import annotations

import asyncio
import json
import os
import stat
import sys
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from unibot.coding import agents, runner
from unibot.coding.runner import Run, _normalise, start_run
from unibot.config import Settings
from unibot.llm import MockLLM
from unibot.server import create_app
from unibot.server.service import MuseService

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="posix shell in the tests")


def _jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n")


@pytest.fixture()
def coding_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A home with one session of each agent, as the tools write them."""
    home = tmp_path / "home"
    ws = tmp_path / "proj" / "app"
    ws.mkdir(parents=True)
    slug = str(ws).strip("/").replace("/", "-")
    # Cursor: an IDE chat (wrapped user text) and a CLI chat with meta
    _jsonl(
        home / ".cursor" / "projects" / slug / "agent-transcripts" / "c1" / "c1.jsonl",
        [
            {
                "role": "user",
                "message": {
                    "content": [
                        {
                            "type": "text",
                            "text": "<timestamp>Mon</timestamp>\n<user_query>\nFix the login bug\n</user_query>",
                        }
                    ]
                },
            },
            {
                "role": "assistant",
                "message": {"content": [{"type": "text", "text": "Looking at auth.py now."}]},
            },
            {
                "role": "assistant",
                "message": {
                    "content": [{"type": "text", "text": "Done: the token check was inverted."}]
                },
            },
            {"type": "turn_ended", "status": "completed"},
        ],
    )
    _jsonl(
        home / ".cursor" / "projects" / slug / "agent-transcripts" / "c2" / "c2.jsonl",
        [
            {
                "role": "user",
                "message": {"content": [{"type": "text", "text": "Add tests for the parser"}]},
            },
            {
                "role": "assistant",
                "message": {"content": [{"type": "text", "text": "Writing tests…"}]},
            },
        ],
    )
    meta = home / ".cursor" / "chats" / "abc" / "c2" / "meta.json"
    meta.parent.mkdir(parents=True)
    meta.write_text(
        json.dumps({"cwd": str(ws), "createdAtMs": 1790000000000, "hasConversation": True})
    )
    # Codex: a rollout with meta, developer noise, a user turn and an answer
    _jsonl(
        home
        / ".codex"
        / "sessions"
        / "2026"
        / "09"
        / "22"
        / "rollout-2026-09-22T17-00-28-01a0c858-5820-7853-ba4c-dee7f183169c.jsonl",
        [
            {
                "type": "session_meta",
                "payload": {
                    "id": "01a0c858-5820-7853-ba4c-dee7f183169c",
                    "cwd": str(ws),
                    "originator": "Codex Desktop",
                    "timestamp": "2026-09-22T09:00:28.065Z",
                },
            },
            {"type": "event_msg", "payload": {"type": "task_started"}},
            {
                "type": "response_item",
                "payload": {
                    "type": "message",
                    "role": "developer",
                    "content": [{"type": "input_text", "text": "<app-context>…</app-context>"}],
                },
            },
            {
                "type": "response_item",
                "payload": {
                    "type": "message",
                    "role": "user",
                    "content": [
                        {
                            "type": "input_text",
                            "text": "<environment_context>x</environment_context>",
                        }
                    ],
                },
            },
            {
                "type": "response_item",
                "payload": {
                    "type": "message",
                    "role": "user",
                    "content": [{"type": "input_text", "text": "Rename the CLI flag"}],
                },
            },
            {
                "type": "response_item",
                "payload": {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                        {"type": "output_text", "text": "Renamed --foo to --bar in three files."}
                    ],
                },
            },
            {"type": "event_msg", "payload": {"type": "task_complete"}},
        ],
    )
    # Claude Code
    _jsonl(
        home / ".claude" / "projects" / ("-" + slug) / "s9.jsonl",
        [
            {
                "type": "user",
                "sessionId": "s9",
                "cwd": str(ws),
                "timestamp": "2026-09-23T10:00:00Z",
                "message": {"role": "user", "content": "Explain the build"},
            },
            {
                "type": "assistant",
                "sessionId": "s9",
                "message": {
                    "role": "assistant",
                    "content": [
                        {"type": "tool_use", "name": "Read"},
                        {"type": "text", "text": "It uses hatch."},
                    ],
                },
            },
        ],
    )
    monkeypatch.setenv("UNIBOT_CODING_HOME", str(home))
    return home


# ----------------------------------------------------------------------------- reading
def test_sessions_are_read_from_each_agents_store(coding_home: Path, tmp_path: Path) -> None:
    ws = str(tmp_path / "proj" / "app")
    rows = agents.sessions(limit=10)
    by = {(s.agent, s.id): s for s in rows}
    assert set(by) == {
        ("cursor", "c1"),
        ("cursor", "c2"),
        ("codex", "01a0c858-5820-7853-ba4c-dee7f183169c"),
        ("claude", "s9"),
    }

    c1 = by[("cursor", "c1")]
    assert c1.title == "Fix the login bug" and c1.source == "ide" and c1.messages == 3
    assert c1.workspace == ws and c1.last_assistant == "Done: the token check was inverted."
    assert c1.status == "active"  # written just now, turn closed
    assert c1.resumable is False  # an IDE chat: the CLI cannot reopen it
    c2 = by[("cursor", "c2")]
    assert c2.source == "cli" and c2.workspace == ws and c2.created_at == 1790000000.0
    assert c2.status == "running"  # a user turn without turn_ended, fresh
    assert c2.resumable is True

    cx = by[("codex", "01a0c858-5820-7853-ba4c-dee7f183169c")]
    assert cx.title == "Rename the CLI flag" and cx.source == "Codex Desktop" and cx.messages == 2
    assert cx.workspace == ws and cx.status == "active"
    assert abs(cx.created_at - 1790067628.065) < 1

    cl = by[("claude", "s9")]
    assert (
        cl.title == "Explain the build"
        and cl.workspace == ws
        and cl.last_assistant == "[tool: Read] It uses hatch."
    )

    # filters
    assert [s.id for s in agents.sessions("cursor")] == ["c2", "c1"] or {
        s.id for s in agents.sessions("cursor")
    } == {"c1", "c2"}
    assert agents.sessions(workspace=str(tmp_path / "elsewhere")) == []
    assert {s.agent for s in agents.sessions(workspace=ws)} == {"cursor", "codex", "claude"}


def test_read_session_has_the_transcript(coding_home: Path) -> None:
    s = agents.read_session("cursor", "c1")
    assert s is not None
    assert [m["role"] for m in s.transcript] == ["user", "assistant", "assistant"]
    assert s.transcript[0]["text"] == "Fix the login bug"
    cx = agents.read_session("codex", "01a0c858-5820-7853-ba4c-dee7f183169c")
    assert cx is not None and [m["text"] for m in cx.transcript] == [
        "Rename the CLI flag",
        "Renamed --foo to --bar in three files.",
    ]
    assert agents.read_session("codex", "nope") is None
    assert agents.read_session("cursor", "../c1") is None
    assert agents.read_session("claude", "s9") is not None


def test_detect_reports_installs_without_running_anything(
    coding_home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        agents, "which", lambda agent: "/usr/bin/fake" if agent == "codex" else None
    )
    found = {a.id: a for a in agents.detect()}
    assert found["codex"].installed and found["codex"].cli == "/usr/bin/fake"
    # no CLI, but transcripts on disk: still "installed" for reading
    assert (
        found["cursor"].installed
        and found["cursor"].cli is None
        and found["cursor"].sessions_root.endswith("projects")
    )
    assert found["claude"].installed


# ----------------------------------------------------------------------------- the stream
def test_normalise_cursor_deltas_then_whole_message() -> None:
    run = Run(id="r", agent="cursor", session_id="", workspace="", text="hi")
    evs = _normalise(
        "cursor", {"type": "system", "subtype": "init", "session_id": "S1", "model": "m"}, run
    )
    assert evs[0].kind == "started" and run.result_session_id == "S1"
    _normalise(
        "cursor",
        {
            "type": "assistant",
            "message": {"content": [{"type": "text", "text": "p"}]},
            "timestamp_ms": 1,
        },
        run,
    )
    _normalise(
        "cursor",
        {
            "type": "assistant",
            "message": {"content": [{"type": "text", "text": "ong"}]},
            "timestamp_ms": 2,
        },
        run,
    )
    assert run.current == "pong" and run.output == ""
    evs = _normalise(
        "cursor",
        {"type": "assistant", "message": {"content": [{"type": "text", "text": "pong"}]}},
        run,
    )
    assert evs[0].extra["partial"] is False and run.output == "pong" and run.current == ""
    evs = _normalise(
        "cursor",
        {
            "type": "tool_call",
            "subtype": "started",
            "tool_call": {"shellToolCall": {"args": {"command": "ls -la"}}},
        },
        run,
    )
    assert evs[0].kind == "tool" and evs[0].text == "shell: ls -la" and run.tools == 1
    evs = _normalise(
        "cursor",
        {
            "type": "result",
            "subtype": "success",
            "result": "pong",
            "session_id": "S1",
            "duration_ms": 5,
        },
        run,
    )
    assert evs[0].kind == "done" and run.output == "pong"


def test_normalise_codex_and_claude() -> None:
    run = Run(id="r", agent="codex", session_id="", workspace="", text="hi")
    assert (
        _normalise("codex", {"type": "thread.started", "thread_id": "T1"}, run)[0].extra[
            "session_id"
        ]
        == "T1"
    )
    evs = _normalise(
        "codex",
        {"type": "item.completed", "item": {"type": "command_execution", "command": "pytest -q"}},
        run,
    )
    assert evs[0].kind == "tool" and evs[0].text == "shell: pytest -q"
    evs = _normalise(
        "codex",
        {"type": "item.completed", "item": {"type": "agent_message", "text": "All green."}},
        run,
    )
    assert evs[0].kind == "text" and run.output == "All green."
    evs = _normalise("codex", {"type": "turn.completed", "usage": {"input_tokens": 5}}, run)
    assert evs[0].kind == "done" and evs[0].text == "All green."
    evs = _normalise("codex", {"type": "error", "message": "refresh token was revoked"}, run)
    assert evs[0].kind == "error" and "revoked" in evs[0].text

    run = Run(id="r", agent="claude", session_id="", workspace="", text="hi")
    _normalise("claude", {"type": "system", "subtype": "init", "session_id": "C1"}, run)
    evs = _normalise(
        "claude",
        {
            "type": "assistant",
            "message": {
                "content": [
                    {"type": "tool_use", "name": "Bash", "input": {"command": "make"}},
                    {"type": "text", "text": "Built."},
                ]
            },
        },
        run,
    )
    assert [e.kind for e in evs] == ["tool", "text"] and evs[0].text == "Bash: make"
    evs = _normalise("claude", {"type": "result", "result": "Built.", "session_id": "C1"}, run)
    assert evs[0].kind == "done" and run.result_session_id == "C1" and run.output == "Built."


@pytest.fixture()
def fake_cursor(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A stand-in `cursor-agent`: prints a stream-json run; `--resume nope` fails like the
    real one does for an unknown chat; the arguments are written down for the assertions."""
    script = tmp_path / "cursor-agent"
    script.write_text(
        "#!/bin/sh\n"
        f'echo "$@" >> "{tmp_path}/args.log"\n'
        'case "$*" in *"--resume nope"*) echo "Error: chat not found" >&2; exit 1;; esac\n'
        'echo \'{"type":"system","subtype":"init","session_id":"S-new","model":"m"}\'\n'
        'echo \'{"type":"assistant","message":{"content":[{"type":"text","text":"work"}]},"timestamp_ms":1}\'\n'
        'echo \'{"type":"assistant","message":{"content":[{"type":"text","text":"working on it"}]}}\'\n'
        'echo \'{"type":"tool_call","subtype":"started","tool_call":{"shellToolCall":{"args":{"command":"ls"}}}}\'\n'
        'echo \'{"type":"result","subtype":"success","result":"working on it","session_id":"S-new","duration_ms":3}\'\n'
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    monkeypatch.setattr(runner, "which", lambda agent: str(script) if agent == "cursor" else None)
    monkeypatch.setattr(agents, "which", lambda agent: str(script) if agent == "cursor" else None)
    return script


def test_start_run_streams_and_falls_back_to_a_new_chat(
    coding_home: Path, fake_cursor: Path, tmp_path: Path
) -> None:
    seen: list[dict[str, Any]] = []

    async def on_event(ev: dict[str, Any]) -> None:
        seen.append(ev)

    run = asyncio.run(
        start_run("cursor", "carry on", session_id="c2", on_event=on_event, timeout_s=20)
    )
    assert run.status == "done" and run.resumed is True and run.result_session_id == "S-new"
    assert run.output == "working on it" and run.tools == 1
    assert run.workspace == str(tmp_path / "proj" / "app")  # taken from the session
    kinds = [e["kind"] for e in seen]
    assert kinds == ["started", "text", "text", "tool", "done"]
    args = (tmp_path / "args.log").read_text()
    assert "--resume c2" in args and "--workspace" in args and args.strip().endswith("carry on")

    # an IDE chat is known not to be resumable: no failed attempt first, straight to a new
    # chat in its workspace with the last exchange quoted, and the run says so
    (tmp_path / "args.log").unlink()
    seen.clear()
    run = asyncio.run(
        start_run("cursor", "carry on", session_id="c1", on_event=on_event, timeout_s=20)
    )
    assert run.status == "done" and run.resumed is False and run.result_session_id == "S-new"
    assert seen[0]["kind"] == "tool" and "made in the IDE" in seen[0]["text"]
    args = (tmp_path / "args.log").read_text()
    assert "--resume" not in args and args.count("--workspace") == 1  # one call, no failed try
    assert "Fix the login bug" in args and "token check was inverted" in args
    assert args.strip().endswith("carry on")

    # an unknown chat: the CLI refuses, the runner starts a new chat with the last exchange quoted
    (tmp_path / "args.log").unlink()
    seen.clear()
    run = asyncio.run(
        start_run(
            "cursor",
            "and now this",
            session_id="nope",
            workspace=str(tmp_path / "proj" / "app"),
            on_event=on_event,
            timeout_s=20,
        )
    )
    assert run.status == "done" and run.resumed is False
    assert any(e["kind"] == "tool" and "cannot be resumed" in e["text"] for e in seen)
    lines = (tmp_path / "args.log").read_text().splitlines()
    assert "--resume nope" in lines[0] and "--resume" not in lines[1] and "and now this" in lines[1]

    # not installed
    run = asyncio.run(start_run("codex", "hi", timeout_s=5))
    assert run.status == "failed" and "not installed" in run.error


def test_a_helper_left_behind_does_not_keep_the_run_open(
    fake_cursor: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The real `cursor-agent` leaves a worker running after it answers, and under some
    event loops (uvloop) that worker inherits our pipes, so EOF never comes. The run has to
    finish on the agent's final message, not on EOF."""
    fake_cursor.write_text(
        "#!/bin/sh\n"
        'echo \'{"type":"system","subtype":"init","session_id":"S-new","model":"m"}\'\n'
        'echo \'{"type":"result","subtype":"success","result":"ok","session_id":"S-new","duration_ms":3}\'\n'
        "sleep 30 &\n"  # keeps our stdout open long after the CLI itself has exited
        "exit 0\n"
    )
    t0 = time.monotonic()
    run = asyncio.run(start_run("cursor", "hi", timeout_s=20))
    assert run.status == "done" and run.output == "ok" and run.result_session_id == "S-new"
    assert time.monotonic() - t0 < 10


# ----------------------------------------------------------------------------- workspace hardening
def test_command_for_validates_workspace_and_separates_text(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The peer-supplied workspace must sit inside the allowed roots (the agent
    workspace plus its extra roots — the files tool's `_resolve` rule); the user
    text rides after `--` so a leading dash is never read as an option."""
    monkeypatch.setattr(runner, "which", lambda agent: "/usr/bin/fake-agent")
    roots = [tmp_path / "ws", tmp_path / "extra"]
    for p in roots:
        p.mkdir()
    outside = tmp_path / "elsewhere"
    outside.mkdir()
    resolved = lambda p: str(Path(p).resolve())  # noqa: E731

    for agent in ("cursor", "codex", "claude"):
        # inside a root (or the root itself): fine; `--` guards a dashy text
        cmd = runner.command_for(agent, "", str(roots[0] / "proj"), "--help", False, roots)
        assert cmd[-2:] == ["--", "--help"], (agent, cmd)
        # cursor and codex carry the resolved workspace into the command
        if agent in ("cursor", "codex"):
            assert resolved(roots[0] / "proj") in cmd, (agent, cmd)
    cmd = runner.command_for("cursor", "", str(roots[1]), "do it", False, roots)
    assert resolved(roots[1]) in cmd

    # outside the roots, or a `..` escape back out, raises ValueError
    for bad in (str(outside), str(roots[0] / ".." / "elsewhere")):
        with pytest.raises(ValueError):
            runner.command_for("cursor", "", bad, "do it", False, allowed_roots=roots)
    with pytest.raises(ValueError):
        asyncio.run(start_run("cursor", "do it", workspace=str(outside), allowed_roots=roots))
    # without roots there is nothing to check against: local callers keep working
    runner.command_for("cursor", "", str(outside), "do it", False)


def test_send_rejects_workspace_outside_roots(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The service path (hub peers included) rejects an outside workspace up front."""
    from unibot.coding.service import CodingService

    ws = tmp_path / "ws"
    ws.mkdir()

    class AgentCfg:
        workspace = ws
        extra_roots: list = []

    class _Settings:
        agent = AgentCfg()

    class _App:
        settings = _Settings()

    class _Svc:
        app = _App()

    monkeypatch.setattr(agents, "which", lambda agent: None)
    svc = CodingService(_Svc())  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        asyncio.run(svc.send("cursor", "hi", workspace=str(tmp_path / "nope")))
    # inside the workspace is accepted (it then fails on the missing CLI, not the roots)
    with pytest.raises(Exception, match="not installed"):
        asyncio.run(svc.send("cursor", "hi", workspace=str(ws)))


# ----------------------------------------------------------------------------- the runtime API
@pytest.fixture()
def server(
    settings: Settings, coding_home: Path, fake_cursor: Path
) -> Iterator[tuple[TestClient, MuseService]]:
    settings.server.token = "secret-token"
    service = MuseService(settings, llm=MockLLM([]))
    app = create_app(settings, service)
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer secret-token"
        yield client, service


def test_api_lists_sessions_sends_and_follows(
    server: tuple[TestClient, MuseService], tmp_path: Path
) -> None:
    client, service = server
    status = client.get("/api/coding").json()
    agents_by = {a["id"]: a for a in status["agents"]}
    assert agents_by["cursor"]["installed"] and agents_by["cursor"]["cli"].endswith("cursor-agent")
    assert status["runs"] == []

    rows = client.get("/api/coding/sessions?agent=cursor").json()["sessions"]
    assert {s["id"] for s in rows} == {"c1", "c2"}
    one = client.get("/api/coding/sessions/cursor/c1").json()
    assert one["title"] == "Fix the login bug" and len(one["transcript"]) == 3
    assert client.get("/api/coding/sessions/cursor/zzz").status_code == 404
    assert client.get("/api/coding/sessions/vim/c1").status_code == 404

    with client.websocket_connect("/ws?token=secret-token") as ws:
        assert ws.receive_json()["kind"] == "hello"
        r = client.post(
            "/api/coding/send", json={"agent": "cursor", "text": "carry on", "session_id": "c1"}
        )
        assert r.status_code == 200, r.text
        run = r.json()
        assert run["status"] == "running" and run["agent"] == "cursor"
        kinds: list[str] = []
        final: dict[str, Any] | None = None
        deadline = time.time() + 20
        while time.time() < deadline:
            msg = ws.receive_json()
            if msg.get("kind") != "coding":
                continue
            ev = msg.get("event") or {}
            kinds.append(str(ev.get("kind")))
            if msg.get("run") and msg["run"].get("status") in ("done", "failed"):
                final = msg["run"]
                break
        assert (
            final is not None and final["status"] == "done" and final["output"] == "working on it"
        )
        assert "text" in kinds and "tool" in kinds and "done" in kinds
    runs = client.get("/api/coding").json()["runs"]
    assert (
        runs[0]["id"] == run["id"]
        and runs[0]["status"] == "done"
        and runs[0]["session_id"] == "S-new"
    )
    # the session list shows the run's message count unchanged (the fake CLI writes no transcript)
    assert client.post("/api/coding/send", json={"agent": "codex", "text": "x"}).status_code == 412
    assert client.post("/api/coding/stop", json={"run": "run_none"}).json() == {"stopped": False}
    # the Muse's own tool sees the same
    tool = service.app.tools.get("coding_agents")
    assert tool is not None
    out = asyncio.run(tool.execute(action="sessions", agent="cursor"))
    assert not out.error and "Fix the login bug" in out.output
    out = asyncio.run(
        tool.execute(
            action="session", agent="codex", session_id="01a0c858-5820-7853-ba4c-dee7f183169c"
        )
    )
    assert "Renamed --foo" in out.output
    a = tool.assess({"action": "send", "agent": "cursor", "text": "do it"})
    assert a.risk.value == "moderate" and a.summary.startswith("tell cursor")


def test_runs_survive_a_restart(
    server: tuple[TestClient, MuseService], settings: Settings, tmp_path: Path
) -> None:
    """The record of runs is written to <data_dir>/coding/runs.json as they finish, and a
    new service reads it back; one that was still going comes back as stopped."""
    client, service = server
    r = client.post(
        "/api/coding/send", json={"agent": "cursor", "text": "carry on", "session_id": "c2"}
    )
    assert r.status_code == 200
    store = Path(settings.data_dir) / "coding" / "runs.json"
    deadline = time.time() + 20
    while time.time() < deadline and not store.is_file():
        time.sleep(0.1)
    rows = json.loads(store.read_text(encoding="utf-8"))
    assert len(rows) == 1 and rows[0]["status"] == "done" and rows[0]["session_id"] == "S-new"
    assert rows[0]["asked_session_id"] == "c2" and rows[0]["text"] == "carry on"
    # a run that never finished (the runtime went down): kept as stopped, with a note
    rows.append({**rows[0], "id": "run_lost", "status": "running", "ended_at": None})
    store.write_text(json.dumps(rows), encoding="utf-8")

    from unibot.coding.service import CodingService

    again = CodingService(service)
    listed = {r["id"]: r for r in again.list_runs()}
    assert (
        listed[rows[0]["id"]]["status"] == "done"
        and listed[rows[0]["id"]]["output"] == "working on it"
    )
    lost = listed["run_lost"]
    assert lost["status"] == "stopped" and "restarted" in lost["error"] and lost["ended_at"]


def test_process_table_is_read_the_platforms_way(monkeypatch: pytest.MonkeyPatch) -> None:
    """Linux reads /proc, macOS asks ps, Windows tasklist — the same counts come out, and a
    node process is Cursor's CLI only when its arguments say so."""
    agents._PROCESS_CACHE = (0.0, {})
    monkeypatch.setattr(agents.sys, "platform", "darwin")
    ps_out = (
        "/usr/local/bin/node /usr/local/bin/cursor-agent -p hello\n"
        "/opt/homebrew/bin/codex codex exec --json\n"
        "/usr/local/bin/node /srv/app/server.js\n"
        "/usr/bin/claude claude -p\n"
        "ps ps -axo comm=,args=\n"
    )
    monkeypatch.setattr(
        agents.subprocess,
        "run",
        lambda *a, **k: type("R", (), {"stdout": ps_out, "stderr": ""})(),
    )
    assert agents.running_processes("cursor") == 1
    assert agents.running_processes("codex") == 1 and agents.running_processes("claude") == 1

    agents._PROCESS_CACHE = (0.0, {})
    monkeypatch.setattr(agents.sys, "platform", "win32")
    calls: list[list[str]] = []

    def fake_run(cmd: list[str], **k: Any) -> Any:
        calls.append(cmd)
        if cmd[0] == "tasklist":
            out = '"node.exe","100","Console","1","10 K"\n"codex.exe","101","Console","1","10 K"\n"node.exe","102","Console","1","10 K"\n'
        else:
            out = "C:\\node.exe C:\\cursor-agent\\index.js -p\r\nC:\\node.exe C:\\other\\app.js\r\n"
        return type("R", (), {"stdout": out, "stderr": ""})()

    monkeypatch.setattr(agents.subprocess, "run", fake_run)
    assert agents.running_processes("cursor") == 1 and agents.running_processes("codex") == 1
    assert calls[0][0] == "tasklist" and calls[1][0] == "powershell" and len(calls) == 2
    agents._PROCESS_CACHE = (0.0, {})


def test_offline_device_is_refused(server: tuple[TestClient, MuseService]) -> None:
    client, _ = server
    r = client.get("/api/coding/sessions?device=laptop")
    assert r.status_code in (404, 409, 502)
    assert os.environ.get("UNIBOT_CODING_HOME")
