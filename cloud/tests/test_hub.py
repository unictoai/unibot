"""The hub through the ASGI app: two devices of one account find each other and
pass calls; a second account sees nothing of it."""

from __future__ import annotations

import hashlib
import json

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from unibot_cloud.api import create_app
from unibot_cloud.config import Settings
from unibot_cloud.db import Database
from unibot_cloud.senders import LogSender
from unibot_cloud.service import Cloud, _sha256


@pytest.fixture
def client():
    settings = Settings(database=":memory:", secret="test-secret", public_base="http://cloud.test", hub_frame_limit=4096)
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud)
    with TestClient(app) as c:
        c.sender = sender
        c.cloud = cloud
        yield c


def sign_up(client: TestClient, identifier: str) -> str:
    assert client.post("/v1/auth/code", json={"identifier": identifier}).status_code == 204
    _, code = client.sender.sent[-1]
    r = client.post("/v1/auth/verify", json={"identifier": identifier, "code": code, "device": "test"})
    assert r.status_code == 200, r.text
    return r.json()["api_key"]


def hello(kind: str, device_id: str, name: str, key: str | None = None, actions=("info", "shell")) -> dict:
    frame = {
        "type": "hello",
        "device": {"id": device_id, "name": name, "kind": kind, "os": "TestOS", "version": "0.1", "actions": list(actions)},
    }
    if key:
        frame["key"] = key
    return frame


def connect(client: TestClient, key: str | None, **kw):
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    return client.websocket_connect("/v1/hub", headers=headers, **kw)


def test_two_devices_route_calls_and_results(client):
    key = sign_up(client, "13800138000")
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        w1 = phone.receive_json()
        assert w1["type"] == "welcome" and w1["device_id"] == "phone-1"
        assert [d["id"] for d in w1["devices"]] == ["phone-1"]
        phone.receive_json()  # the devices broadcast triggered by its own arrival

        pc.send_json(hello("computer", "pc-1", "desk", actions=("info", "shell", "screen")))
        w2 = pc.receive_json()
        assert w2["type"] == "welcome"
        assert {d["id"]: d["online"] for d in w2["devices"]} == {"phone-1": True, "pc-1": True}
        pc.receive_json()  # broadcast
        upd = phone.receive_json()
        assert upd["type"] == "devices" and {d["id"] for d in upd["devices"]} == {"phone-1", "pc-1"}
        pc_row = next(d for d in upd["devices"] if d["id"] == "pc-1")
        assert pc_row["actions"] == ["info", "shell", "screen"] and pc_row["controllable"] is True

        # phone → pc: a call, progress, then the result; each lands on the right side.
        phone.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "shell", "args": {"command": "uname"}})
        call = pc.receive_json()
        assert call == {
            "type": "call",
            "id": "c1",
            "from": {"id": "phone-1", "name": "Pixel", "kind": "phone"},
            "action": "shell",
            "args": {"command": "uname"},
        }
        pc.send_json({"type": "event", "id": "c1", "body": {"stage": "running"}})
        ev = phone.receive_json()
        assert ev["type"] == "event" and ev["body"] == {"stage": "running"} and ev["from"]["id"] == "pc-1"
        pc.send_json({"type": "result", "id": "c1", "ok": True, "body": {"stdout": "Linux\n", "exit_code": 0}})
        res = phone.receive_json()
        assert res["type"] == "result" and res["ok"] is True and res["body"]["stdout"] == "Linux\n"

        # A result for a call that is not pending is refused, once, to its sender.
        pc.send_json({"type": "result", "id": "c1", "ok": True, "body": {}})
        assert pc.receive_json()["code"] == "unknown_call"

        # pc → phone works the same way.
        pc.send_json({"type": "call", "id": "c2", "to": "phone-1", "action": "notify", "args": {"text": "hi"}})
        assert phone.receive_json()["action"] == "notify"
        phone.send_json({"type": "result", "id": "c2", "ok": False, "error": "denied", "message": "no"})
        r2 = pc.receive_json()
        assert r2["ok"] is False and r2["error"] == "denied"

        # Calling yourself, or a device that is not there.
        pc.send_json({"type": "call", "id": "c3", "to": "pc-1", "action": "info", "args": {}})
        assert pc.receive_json()["code"] == "self_call"
        pc.send_json({"type": "call", "id": "c4", "to": "nope", "action": "info", "args": {}})
        assert pc.receive_json()["code"] == "device_offline"

        pc.send_json({"type": "ping"})
        assert pc.receive_json()["type"] == "pong"

    # Both gone: remembered, offline, listable over HTTP.
    r = client.get("/v1/devices", headers={"Authorization": f"Bearer {key}"})
    assert r.status_code == 200
    rows = {d["id"]: d for d in r.json()["devices"]}
    assert set(rows) == {"phone-1", "pc-1"} and not rows["pc-1"]["online"]
    assert client.delete("/v1/devices/pc-1", headers={"Authorization": f"Bearer {key}"}).status_code == 204
    assert [d["id"] for d in client.get("/v1/devices", headers={"Authorization": f"Bearer {key}"}).json()["devices"]] == ["phone-1"]


def test_target_leaving_fails_the_pending_call(client):
    key = sign_up(client, "someone@example.com")
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json()
        phone.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json()
        pc.receive_json()
        phone.receive_json()  # devices: pc joined
        phone.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "shell", "args": {"command": "sleep 100"}})
        assert pc.receive_json()["action"] == "shell"
        # pc drops without answering (an explicit close: the test session's own
        # exit cancels the server task before it can tell anyone).
        pc.close(1000)
        seen = [phone.receive_json(), phone.receive_json()]
        kinds = {f["type"]: f for f in seen}
        assert kinds["error"]["code"] == "device_offline" and kinds["error"]["id"] == "c1"
        assert {d["id"]: d["online"] for d in kinds["devices"]["devices"]} == {"phone-1": True, "pc-1": False}


def test_web_tab_authenticates_in_hello_and_cannot_be_called(client):
    key = sign_up(client, "13800138000")
    with connect(client, None) as web, connect(client, key) as pc:
        web.send_json(hello("web", "", "Chrome", key=key))
        w = web.receive_json()
        assert w["type"] == "welcome" and w["device_id"].startswith("web-")
        web.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json()
        pc.receive_json()
        upd = web.receive_json()
        tab = next(d for d in upd["devices"] if d["kind"] == "web")
        assert tab["controllable"] is False

        pc.send_json({"type": "call", "id": "x", "to": w["device_id"], "action": "info", "args": {}})
        assert pc.receive_json()["code"] == "not_controllable"

        web.send_json({"type": "call", "id": "t1", "to": "pc-1", "action": "task", "args": {"text": "open the calendar"}})
        assert pc.receive_json()["args"] == {"text": "open the calendar"}
        pc.send_json({"type": "result", "id": "t1", "ok": True, "body": {"text": "done"}})
        assert web.receive_json()["body"] == {"text": "done"}

    # Browser tabs are not remembered.
    assert [d["kind"] for d in client.get("/v1/devices", headers={"Authorization": f"Bearer {key}"}).json()["devices"]] == ["computer"]


def test_bad_key_and_bad_hello(client):
    with connect(client, None) as ws:
        ws.send_json(hello("phone", "p", "x", key="nm_nothing"))
        assert ws.receive_json()["code"] == "bad_key"
    key = sign_up(client, "13800138000")
    with connect(client, key) as ws:
        ws.send_json(hello("toaster", "t-1", "x"))
        assert ws.receive_json()["code"] == "bad_device"
    with connect(client, key) as ws:
        ws.send_json({"type": "call"})
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()  # closed: hello expected


def test_accounts_are_separate_and_frames_are_capped(client):
    a = sign_up(client, "13800138000")
    b = sign_up(client, "13900139000")
    with connect(client, a) as pa, connect(client, b) as pb:
        pa.send_json(hello("phone", "phone-1", "A's"))
        pa.receive_json()
        pa.receive_json()
        pb.send_json(hello("computer", "pc-1", "B's"))
        w = pb.receive_json()
        assert [d["id"] for d in w["devices"]] == ["pc-1"]
        pb.receive_json()
        pa.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "info", "args": {}})
        assert pa.receive_json()["code"] == "device_offline"
        pa.send_text(json.dumps({"type": "ping", "pad": "x" * 5000}))
        assert pa.receive_json()["code"] == "too_large"


def test_reconnect_replaces_the_older_socket(client):
    key = sign_up(client, "13800138000")
    with connect(client, key) as first:
        first.send_json(hello("computer", "pc-1", "desk"))
        first.receive_json()
        first.receive_json()
        with connect(client, key) as second:
            second.send_json(hello("computer", "pc-1", "desk"))
            w = second.receive_json()
            assert [d["id"] for d in w["devices"]] == ["pc-1"]
            second.send_json({"type": "devices"})
            frames = [second.receive_json(), second.receive_json()]
            assert all(f["type"] == "devices" for f in frames)
            assert all(len(f["devices"]) == 1 for f in frames)


def test_the_profile_is_shared_and_announced(client):
    """The agent's name and look live on the relay so every device of the account wears
    the same one: a PUT from one device is a ``profile`` frame on the other's socket, and
    the pictures are fetched only when asked for."""
    import base64

    key = sign_up(client, "13800138000")
    auth = {"Authorization": f"Bearer {key}"}
    assert client.get("/v1/me/profile", headers=auth).json()["rev"] == 0

    webp = base64.b64encode(b"RIFF\x00\x00\x00\x00WEBPVP8 " + b"\x00" * 40).decode()
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json(), phone.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json(), pc.receive_json()
        phone.receive_json()  # the devices broadcast for the pc's arrival

        r = client.put(
            "/v1/me/profile",
            headers=auth,
            json={
                "device": "pc-1",
                "name": "小火",
                "avatar": "face",
                "style": "pixel",
                "description": "a robot owl",
                "face": {"idle": webp, "happy": webp},
            },
        )
        assert r.status_code == 200 and r.json()["rev"] == 1
        assert phone.receive_json() == {"type": "profile", "rev": 1, "device": "pc-1"}
        assert pc.receive_json() == {"type": "profile", "rev": 1, "device": "pc-1"}

    light = client.get("/v1/me/profile", params={"face": "false"}, headers=auth).json()
    assert light["rev"] == 1 and light["name"] == "小火" and light["has_face"] is True and "face" not in light
    full = client.get("/v1/me/profile", headers=auth).json()
    assert set(full["face"]) == {"idle", "happy"} and full["device"] == "pc-1"
    # the idle still's hash, so a device that already wears these pictures keeps its copy
    assert light["face_id"] == hashlib.sha1(base64.b64decode(webp)).hexdigest()[:12] == full["face_id"]

    # a rename keeps the pictures; switching to an emoji drops them
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "小火龙", "avatar": "face"})
    assert r.status_code == 200 and r.json()["rev"] == 2
    assert client.get("/v1/me/profile", headers=auth).json()["face"]["idle"] == webp
    r = client.put(
        "/v1/me/profile", headers=auth, json={"name": "小火龙", "avatar": "emoji", "emoji": "🦉", "color": "#0064d4", "face": None}
    )
    after = client.get("/v1/me/profile", headers=auth).json()
    assert r.status_code == 200 and after["face"] is None and after["face_id"] == ""

    # what is refused: a face without pictures, a stray mood, a picture that is not a picture
    bad = [
        {"name": "x", "avatar": "face"},
        {"name": "x", "avatar": "face", "face": {"idle": webp, "angry": webp}},
        {"name": "x", "avatar": "face", "face": {"idle": base64.b64encode(b"hello").decode()}},
        {"name": "", "avatar": "dragon"},
        {"name": "x", "avatar": "emoji", "color": "blue"},
    ]
    for body in bad:
        assert client.put("/v1/me/profile", headers=auth, json=body).status_code == 400, body
    assert client.delete("/v1/me/profile", headers=auth).status_code == 204
    assert client.get("/v1/me/profile", headers=auth).json()["rev"] == 0


def _second_sign_in(client: TestClient, identifier: str, device: str) -> str:
    assert client.post("/v1/auth/code", json={"identifier": identifier}).status_code == 204
    _, code = client.sender.sent[-1]
    r = client.post("/v1/auth/verify", json={"identifier": identifier, "code": code, "device": device})
    assert r.status_code == 200, r.text
    return r.json()["api_key"]


def test_revoking_a_sign_in_drops_its_hub_socket(client):
    """A revoked device must not keep a live line to the account's computers:
    sign-out, sign-out-all and per-session revoke close the matching hub
    sockets, and a call frame on a socket whose key died behind the hub's
    back kills the socket (route() re-checks the key)."""
    ident = "13800138011"
    key1 = sign_up(client, ident)
    key2 = _second_sign_in(client, ident, "test2")
    key3 = _second_sign_in(client, ident, "test3")
    auth = lambda k: {"Authorization": f"Bearer {k}"}  # noqa: E731
    account_id = client.cloud.authenticate(key2).account_id

    with connect(client, key1) as phone, connect(client, key2) as pc, connect(client, key3) as tablet:
        for ws, kind, did, name in (
            (phone, "phone", "phone-1", "Pixel"),
            (pc, "computer", "pc-1", "desk"),
            (tablet, "phone", "tab-1", "Tab"),
        ):
            ws.send_json(hello(kind, did, name))
            ws.receive_json()  # welcome
            ws.receive_json()  # devices broadcast for its own arrival
        phone.receive_json()  # pc joined
        phone.receive_json()  # tablet joined
        pc.receive_json()  # tablet joined

        # 1. Revoke key1's session from key2: the phone's socket dies, the rest live.
        sessions = client.get("/v1/me/sessions", headers=auth(key2)).json()["sessions"]
        prefix1 = next(s["prefix"] for s in sessions if not s["current"] and s["prefix"] == key1[:10])
        assert client.delete(f"/v1/me/sessions/{prefix1}", headers=auth(key2)).status_code == 204
        with pytest.raises(WebSocketDisconnect):
            phone.receive_json()
        pc.send_json({"type": "ping"})
        assert pc.receive_json()["type"] == "pong"
        tablet.send_json({"type": "ping"})
        assert tablet.receive_json()["type"] == "pong"

        # 2. "Sign out every other device" from key2: the tablet's socket dies,
        #    key2's own socket survives.
        r = client.post("/v1/auth/sign-out-all", json={}, headers=auth(key2))
        assert r.status_code == 200 and r.json()["signed_out"] == 1
        with pytest.raises(WebSocketDisconnect):
            tablet.receive_json()
        pc.send_json({"type": "ping"})
        assert pc.receive_json()["type"] == "pong"

        # 3. Signing out key2 itself drops its socket too.
        assert client.post("/v1/auth/sign-out", headers=auth(key2)).status_code == 204
        with pytest.raises(WebSocketDisconnect):
            pc.receive_json()

    # 4. Defence in depth: the key is revoked straight in the db, so no drop
    #    ran — the next call frame on that socket must still kill it.
    key4, _ = client.cloud._issue_key(account_id, "watch", via="test")
    with connect(client, key4) as watch:
        watch.send_json(hello("phone", "watch-1", "Watch"))
        watch.receive_json()  # welcome
        watch.receive_json()  # devices broadcast
        client.cloud.db.revoke_key(_sha256(key4))  # revoked behind the hub's back
        watch.send_json({"type": "call", "id": "c9", "to": "watch-1", "action": "info", "args": {}})
        with pytest.raises(WebSocketDisconnect):
            watch.receive_json()
