"""The desktop against the real cloud app on a local port: the standard-library
WebSocket client, the hub client, and the Desktop's call handling (raw actions,
a delegated task with an approval, stop)."""

from __future__ import annotations

import base64
import json
import socket
import sys
import threading
import time
from pathlib import Path

import pytest
import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "cloud"))

from unibot_cloud.api import create_app  # noqa: E402
from unibot_cloud.config import Settings  # noqa: E402
from unibot_cloud.db import Database  # noqa: E402
from unibot_cloud.senders import LogSender  # noqa: E402
from unibot_cloud.service import Cloud as CloudService  # noqa: E402

from unibot_desktop import actions, guard  # noqa: E402
from unibot_desktop.app import Desktop  # noqa: E402
from unibot_desktop.cloud import Cloud  # noqa: E402
from unibot_desktop.config import Config  # noqa: E402
from unibot_desktop.hub import HubClient, HubError  # noqa: E402
from unibot_desktop.wsclient import WebSocket  # noqa: E402


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def relay():
    settings = Settings(database=":memory:", secret="test-secret", public_base="http://127.0.0.1", hub_frame_limit=2 * 1024 * 1024)
    sender = LogSender()
    service = CloudService(settings, Database(":memory:"), sender)
    app = create_app(settings, service)
    port = free_port()
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", ws_max_size=settings.hub_frame_limit)
    server = uvicorn.Server(config)
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(0.05)
    base = f"http://127.0.0.1:{port}"
    cloud = Cloud(base)
    cloud.request_code("13800138000")
    key = cloud.verify("13800138000", sender.sent[-1][1], "test")["api_key"]
    yield base, key
    server.should_exit = True
    t.join(5)


def test_wsclient_handshake_and_frames(relay):
    base, key = relay
    ws = WebSocket(base.replace("http", "ws") + "/v1/hub", headers={"Authorization": f"Bearer {key}"})
    ws.connect()
    ws.send_text(json.dumps({"type": "hello", "device": {"id": "raw-1", "name": "raw", "kind": "computer", "actions": []}}))
    welcome = json.loads(ws.recv_text())
    assert welcome["type"] == "welcome" and welcome["device_id"] == "raw-1"
    ws.send_text(json.dumps({"type": "ping", "pad": "x" * 70_000}))  # a 64K+ frame: 8-byte length path
    frames = [json.loads(ws.recv_text()) for _ in range(2)]
    assert {f["type"] for f in frames} == {"devices", "pong"}
    ws.close()
    assert ws.closed


def test_desktop_serves_actions_and_tasks(relay, tmp_path, monkeypatch):
    base, key = relay
    monkeypatch.setenv("UNIBOT_HOME", str(tmp_path))
    cfg = Config(cloud_base=base, api_key=key, device_id="pc-test", name="desk", downloads=str(tmp_path / "dl"))
    desktop = Desktop(cfg, quiet=True)

    # The agent's model call is faked: first a shell tool call that needs approval, then the answer.
    calls = {"n": 0}

    def fake_chat(model, messages, tools=None, **extra):
        calls["n"] += 1
        if calls["n"] == 1:
            return {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [
                                {"id": "t1", "type": "function", "function": {"name": "shell", "arguments": json.dumps({"command": "rm -rf ~/gone"})}}
                            ],
                        }
                    }
                ]
            }
        last = messages[-1]
        assert last["role"] == "tool" and json.loads(last["content"])["error"] == "denied"
        return {"choices": [{"message": {"role": "assistant", "content": "You did not allow it, so nothing was removed."}}]}

    monkeypatch.setattr(desktop.cloud, "chat", fake_chat)
    desktop.agent.model = "fake"
    desktop.start()
    assert desktop.hub.wait_connected(10)

    # A second device — the "phone" — made of the same client class.
    got: list[dict] = []
    phone = HubClient(Cloud(base).hub_url, key, "phone-test", "Pixel", ["notify"], kind="phone", on_call=lambda c: c.result({"echo": c.args}))
    phone.start()
    assert phone.wait_connected(10)
    time.sleep(0.3)
    assert {d["id"] for d in phone.devices} >= {"pc-test", "phone-test"}
    assert desktop.hub.find("desk") is None  # never itself
    assert phone.find("desk")["id"] == "pc-test"
    assert phone.find("电脑")["id"] == "pc-test"

    # phone → desk raw actions
    info = phone.call("pc-test", "info", {})
    assert info["name"] and "shell" in info["actions"]
    r = phone.call("pc-test", "shell", {"command": "echo hi"})
    assert r["exit_code"] == 0 and r["stdout"].strip() == "hi"
    (tmp_path / "a.txt").write_text("hello")
    r = phone.call("pc-test", "files", {"path": str(tmp_path)})
    assert any(e["name"] == "a.txt" for e in r["entries"])
    r = phone.call("pc-test", "file.get", {"path": str(tmp_path / "a.txt")})
    assert base64.b64decode(r["data"]) == b"hello"
    r = phone.call("pc-test", "file.put", {"path": str(tmp_path / "b.txt"), "data": base64.b64encode(b"world").decode()})
    assert (tmp_path / "b.txt").read_text() == "world"
    with pytest.raises(HubError) as e:
        phone.call("pc-test", "file.put", {"path": str(tmp_path / "b.txt"), "data": ""})
    assert e.value.code == "exists"
    with pytest.raises(HubError) as e:
        phone.call("pc-test", "dance", {})
    assert e.value.code == "unknown_action"

    # desk → phone
    assert desktop.hub.call("phone-test", "notify", {"text": "hi"}) == {"echo": {"text": "hi"}}

    # phone → desk: a task; the desk's agent asks for approval through an event, the phone says no.
    events: list[dict] = []

    def on_event(body: dict) -> None:
        events.append(body)
        if body.get("stage") == "approval":
            assert body["risk"] == guard.DESTRUCTIVE and "rm -rf" in body["preview"]
            phone.call("pc-test", "approve", {"approval_id": body["approval_id"], "allow": False})

    result = phone.call("pc-test", "task", {"text": "delete my gone folder", "conversation": "c1"}, timeout=30, on_event=on_event)
    assert result["text"].startswith("You did not allow it")
    stages = [e["stage"] for e in events]
    assert "thinking" in stages and "tool" in stages and "approval" in stages and stages[-1] == "done"
    assert (tmp_path / "desktop" / "conversations" / "c1.json").exists()

    # remote control off: raw actions refused, info still answers
    desktop.cfg.remote_control = False
    with pytest.raises(HubError) as e:
        phone.call("pc-test", "shell", {"command": "echo hi"})
    assert e.value.code == "not_allowed"
    assert phone.call("pc-test", "info", {})["name"]

    phone.stop()
    desktop.stop()
    got.clear()


def test_actions_locally(tmp_path):
    r = actions.shell("echo $((6*7))" if sys.platform != "win32" else "echo 42")
    assert r["exit_code"] == 0 and "42" in r["stdout"]
    # a shell that sleeps in a subprocess: the whole tree must be gone once we time out
    r = actions.shell("sleep 5" if sys.platform != "win32" else "ping -n 6 127.0.0.1 > nul", timeout=1)
    assert r["timed_out"] and r["exit_code"] == 124 and r["duration_ms"] < 4000
    with pytest.raises(actions.ActionError):
        actions.files(str(tmp_path / "nope"))
    assert actions.info()["actions"] == list(actions.ACTIONS)
