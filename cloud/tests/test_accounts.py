"""Passwords, sign-ins, the usage breakdown, calls and the operator's detail views."""

from __future__ import annotations

import httpx
from test_cloud import fake_upstream, sign_up

from unibot_cloud.api import create_app
from unibot_cloud.config import Settings
from unibot_cloud.db import Database
from unibot_cloud.senders import LogSender
from unibot_cloud.service import Cloud, check_password, hash_password


def make(**overrides):
    up = fake_upstream()
    kw = dict(
        database=":memory:",
        secret="test-secret",
        admin_token="admin",
        upstream_base="http://upstream/compat/v1",
        upstream_key="sk-upstream",
        dashscope_base="http://upstream/ds/api/v1",
        signup_tokens=0,
        daily_cap_tokens=0,
        per_minute_requests=100,
        allowance_cny=25,
        public_base="http://cloud.test",
        password_max_attempts=3,
        lockout_s=600,
    )
    kw.update(overrides)
    settings = Settings(**kw)
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud, upstream_transport=httpx.ASGITransport(app=up))
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://cloud.test")
    return app, client, sender, up, cloud, settings


def auth(key: str) -> dict:
    return {"Authorization": f"Bearer {key}"}


def test_password_hashing_roundtrip():
    h = hash_password("correct horse")
    assert h.startswith("scrypt$") and check_password("correct horse", h)
    assert not check_password("wrong", h)
    assert not check_password("x", "garbage")
    assert hash_password("a") != hash_password("a")  # salted


async def test_password_set_login_change_and_lockout():
    app, client, sender, up, cloud, settings = make()
    first = await sign_up(client, sender, "13800138000", "pixel")
    key1 = first["api_key"]
    assert first["account"]["has_password"] is False and first["account"]["signed_in_via"] == "code"

    # No password yet: the password sign-in says so instead of pretending.
    r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "whatever1", "device": "mac"})
    assert r.status_code == 400 and r.json()["error"]["code"] == "no_password"

    # Weak ones are refused; a proper one is set without `current` (none exists).
    r = await client.post("/v1/auth/password", json={"password": "short"}, headers=auth(key1))
    assert r.status_code == 400 and r.json()["error"]["code"] == "password_short"
    r = await client.post("/v1/auth/password", json={"password": "aaaaaaaaaa"}, headers=auth(key1))
    assert r.status_code == 400 and r.json()["error"]["code"] == "password_weak"
    r = await client.post("/v1/auth/password", json={"password": "correct horse 1"}, headers=auth(key1))
    assert r.status_code == 204
    me = (await client.get("/v1/me", headers=auth(key1))).json()
    assert me["account"]["has_password"] is True and me["account"]["password_set_at"]

    # A second device signs in with the password: a new key, same account, via=password.
    r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "correct horse 1", "device": "mac"})
    assert r.status_code == 200, r.text
    second = r.json()
    key2 = second["api_key"]
    assert key2 != key1 and second["account"]["id"] == first["account"]["id"]
    assert second["account"]["signed_in_via"] == "password" and second["account"]["sessions"] == 2

    # Unknown numbers and wrong passwords get the same answer.
    r = await client.post("/v1/auth/login", json={"identifier": "13900001111", "password": "correct horse 1"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "bad_credentials"
    r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "nope nope nope"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "bad_credentials"

    # Changing it needs the current one — this key is well past the reset window.
    cloud.db._conn.execute("UPDATE api_keys SET created_at=created_at-7200")
    r = await client.post("/v1/auth/password", json={"password": "another one 2"}, headers=auth(key1))
    assert r.status_code == 400 and r.json()["error"]["code"] == "password_required"
    r = await client.post("/v1/auth/password", json={"password": "another one 2", "current": "wrong"}, headers=auth(key1))
    assert r.status_code == 400 and r.json()["error"]["code"] == "password_wrong"
    r = await client.post("/v1/auth/password", json={"password": "another one 2", "current": "correct horse 1"}, headers=auth(key1))
    assert r.status_code == 204

    # Three wrong tries lock the password (the code still works); a fresh code
    # sign-in may then set a new password without the old one.
    for _ in range(2):
        r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "bad"})
        assert r.status_code == 401
    r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "bad"})
    assert r.status_code == 429 and r.json()["error"]["code"] == "locked"
    r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "another one 2"})
    assert r.status_code == 429
    third = await sign_up(client, sender, "13800138000", "ipad")
    r = await client.post("/v1/auth/password", json={"password": "reset by code 3"}, headers=auth(third["api_key"]))
    assert r.status_code == 204
    r = await client.post("/v1/auth/login", json={"identifier": "13800138000", "password": "reset by code 3", "device": "win"})
    assert r.status_code == 200

    # The timeline shows what happened, never what was said.
    events = (await client.get("/v1/me/events", headers=auth(key1))).json()["events"]
    kinds = [e["kind"] for e in events]
    assert "password.set" in kinds and "password.changed" in kinds and "sign_in.password" in kinds and "sign_in.failed" in kinds
    assert all("detail" in e and len(e["detail"]) <= 200 for e in events)


async def test_sessions_can_be_listed_and_revoked():
    app, client, sender, up, cloud, settings = make()
    a = await sign_up(client, sender, "dev-a@example.com", "pixel")
    b = await sign_up(client, sender, "dev-a@example.com", "mac")
    c = await sign_up(client, sender, "dev-a@example.com", "ipad")
    r = await client.get("/v1/me/sessions", headers=auth(a["api_key"]))
    sessions = r.json()["sessions"]
    assert [s["device"] for s in sessions][0] == "pixel" and sessions[0]["current"] is True
    assert {s["device"] for s in sessions} == {"pixel", "mac", "ipad"}
    assert all(s["via"] == "code" and len(s["prefix"]) == 10 for s in sessions)

    # Sign the tablet out from the phone; it stops working at once.
    ipad = next(s for s in sessions if s["device"] == "ipad")
    r = await client.delete(f"/v1/me/sessions/{ipad['prefix']}", headers=auth(a["api_key"]))
    assert r.status_code == 204
    assert (await client.get("/v1/me", headers=auth(c["api_key"]))).status_code == 401
    r = await client.delete("/v1/me/sessions/nm_nothere", headers=auth(a["api_key"]))
    assert r.status_code == 404

    # Everyone else out: only the phone stays.
    r = await client.post("/v1/auth/sign-out-all", json={}, headers=auth(a["api_key"]))
    assert r.status_code == 200 and r.json()["signed_out"] == 1
    assert (await client.get("/v1/me", headers=auth(b["api_key"]))).status_code == 401
    assert (await client.get("/v1/me", headers=auth(a["api_key"]))).status_code == 200
    r = await client.post("/v1/auth/sign-out-all", json={"all": True}, headers=auth(a["api_key"]))
    assert r.json()["signed_out"] == 1
    assert (await client.get("/v1/me", headers=auth(a["api_key"]))).status_code == 401


async def test_usage_is_broken_down_by_kind_and_model():
    app, client, sender, up, cloud, settings = make()
    data = await sign_up(client, sender)
    headers = auth(data["api_key"])
    for _ in range(2):
        r = await client.post(
            "/v1/chat/completions", json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}, headers=headers
        )
        assert r.status_code == 200
    r = await client.post(
        "/v1/chat/completions", json={"model": "qwen3.8-flash", "messages": [{"role": "user", "content": "hi"}]}, headers=headers
    )
    assert r.status_code == 200
    r = await client.post("/v1/images/generations", json={"model": "qwen-image-3.0", "prompt": "a cat", "n": 1}, headers=headers)
    assert r.status_code == 200, r.text

    me = (await client.get("/v1/me", headers=headers)).json()
    usage = me["usage"]
    assert usage["kinds"] == ["chat", "image", "video", "realtime"]
    by_kind = {row["kind"]: row for row in usage["today"]["by_kind"]}
    assert by_kind["chat"]["requests"] == 3 and by_kind["chat"]["prompt_tokens"] == 300 and by_kind["chat"]["completion_tokens"] == 150
    assert by_kind["image"]["requests"] == 1 and by_kind["image"]["cost_cny"] == 0.18
    by_model = {(row["model"], row["kind"]): row for row in usage["total"]["by_model"]}
    assert by_model[("qwen3.8-27b", "chat")]["requests"] == 2 and by_model[("qwen3.8-flash", "chat")]["requests"] == 1
    assert by_model[("qwen-image-3.0", "image")]["requests"] == 1
    # Money adds up across kinds: 2 × (100×3 + 50×12) + (100×0.8 + 50×2.7) micro-yuan + ¥0.18
    total_cny = sum(row["cost_cny"] for row in usage["total"]["by_kind"])
    assert round(total_cny, 4) == round(2 * 0.0009 + 0.000215 + 0.18, 4)


async def test_admin_overview_and_account_detail():
    app, client, sender, up, cloud, settings = make()
    a = await sign_up(client, sender, "13800138000", "pixel")
    b = await sign_up(client, sender, "dev-a@example.com", "mac")
    ha, hb = auth(a["api_key"]), auth(b["api_key"])
    await client.post("/v1/chat/completions", json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}, headers=ha)
    await client.post("/v1/chat/completions", json={"model": "qwen3.8-flash", "messages": [{"role": "user", "content": "hi"}]}, headers=hb)
    await client.post("/v1/images/generations", json={"model": "qwen-image-3.0", "prompt": "a cat"}, headers=hb)
    await client.post("/v1/auth/password", json={"password": "correct horse 1"}, headers=hb)
    await client.post("/v1/auth/login", json={"identifier": "dev-a@example.com", "password": "wrong wrong"})
    admin = {"X-Admin-Token": "admin"}

    assert (await client.get("/v1/admin/overview")).status_code == 401
    r = await client.get("/v1/admin/overview", headers=admin)
    assert r.status_code == 200, r.text
    ov = r.json()
    assert ov["accounts"]["total"] == 2 and ov["accounts"]["with_password"] == 1 and ov["accounts"]["live_keys"] == 2
    assert ov["today"]["requests"] == 3 and ov["today"]["active_accounts"] == 2 and ov["today"]["new_accounts"] == 2
    kinds = {row["kind"]: row for row in ov["today"]["by_kind"]}
    assert kinds["chat"]["requests"] == 2 and kinds["image"]["requests"] == 1
    models = {row["model"] for row in ov["period"]["by_model"]}
    assert models == {"qwen3.8-27b", "qwen3.8-flash", "qwen-image-3.0"}
    assert ov["signals_today"]["sign_ins"] == 2 and ov["signals_today"]["sign_in_failures"] == 1
    assert ov["top_accounts"][0]["hint"] == "de***@example.com" and ov["top_accounts"][0]["requests"] == 2
    # The overview never carries identifiers in clear — only the masked hints.
    assert "13800138000" not in r.text and "dev-a@example.com" not in r.text
    assert ov["events"][0]["kind"] == "sign_in.failed" and ov["events"][0]["hint"] == "de***@example.com"

    r = await client.get(f"/v1/admin/accounts/{b['account']['id']}", headers=admin)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["account"]["identifier"] == "dev-a@example.com" and d["account"]["has_password"] is True
    assert "password_hash" not in d["account"]
    assert (
        d["spend"]["requests_total"] == 2
        and d["spend"]["grant_cny"] == 25
        and d["spend"]["left_cny"] == round(25 - d["spend"]["total_cny"], 4)
    )
    assert {row["kind"] for row in d["usage"]["period"]["by_kind"]} == {"chat", "image"}
    assert len(d["usage"]["period"]["by_day"]) == 2
    assert d["sessions"][0]["device"] == "mac" and d["sessions"][0]["revoked_at"] is None
    assert [e["kind"] for e in d["events"]][:3] == ["sign_in.failed", "password.set", "sign_in.code"]
    # the statement: the two requests and, first of all, the allowance itself
    assert len(d["recent"]) == 3 and {x["kind"] for x in d["recent"]} == {"chat", "image", "credit"}
    assert d["recent"][-1]["detail"] == {"credit_uy": 25_000_000, "from": "signup"}
    assert (await client.get("/v1/admin/accounts/nope", headers=admin)).status_code == 404

    r = await client.get("/v1/admin/events?kind=sign_in.failed,password.set", headers=admin)
    assert [e["kind"] for e in r.json()["events"]] == ["sign_in.failed", "password.set"]

    # The list view carries the new flags too.
    accounts = (await client.get("/v1/admin/accounts", headers=admin)).json()["accounts"]
    byid = {x["id"]: x for x in accounts}
    assert byid[b["account"]["id"]]["has_password"] is True and byid[a["account"]["id"]]["has_password"] is False
    assert all("password_hash" not in x for x in accounts)


VIDEO = "/api/v1/services/aigc/video-generation/video-synthesis"


async def clip(client, key: str, prompt: str = "a dragon waves"):
    r = await client.post(VIDEO, headers=auth(key), json={"model": "wan2.2-i2v-flash", "input": {"prompt": prompt}, "parameters": {}})
    if r.status_code == 200:
        task = r.json()["output"]["task_id"]
        for _ in range(2):  # RUNNING, then SUCCEEDED: charged once
            await client.get(f"/api/v1/tasks/{task}", headers=auth(key))
    return r


async def test_invites_and_the_operator_grow_the_one_pool():
    """A friend's code at sign-up adds the bonus to the inviter's pool; clips are not
    counted apart — a clip is just a dearer line on the same allowance; the operator
    can add more; the estimate says what a new face costs before it is made."""
    app, client, sender, up, cloud, settings = make(allowance_cny=0.6, invite_bonus_cny=3, contribute_bonus_cny=0,
                                                         invite_url="https://relay.example/web/?invite=")
    a = await sign_up(client, sender, "dev-a@example.com", "pixel")
    ka = a["api_key"]
    inv = (await client.get("/v1/me/invite", headers=auth(ka))).json()
    assert len(inv["code"]) == 8 and inv["url"] == "https://relay.example/web/?invite=" + inv["code"]
    assert inv["invites"] == 0 and inv["bonus_cny"] == 3 and inv["earned_cny"] == 0 and inv["friends"] == []
    assert (await client.get("/v1/me", headers=auth(ka))).json()["invite"]["code"] == inv["code"]  # stable
    assert a["clips"]["unlimited"] is True and a["spend"]["grant"] == 0.6 and a["spend"]["left"] == 0.6
    # no co-creation bonus on this relay (CONTRIBUTE_BONUS_CNY=0): nothing is offered
    assert a["spend"]["contribute_bonus_available"] is False and a["contribute"]["bonus_available"] is False

    # B signs up with A's code, typed sloppily: A's pool grows; B signing in again does not count twice.
    async def verify(identifier: str, device: str, invite: str):
        await client.post("/v1/auth/code", json={"identifier": identifier})
        _, code = sender.sent[-1]
        r = await client.post("/v1/auth/verify", json={"identifier": identifier, "code": code, "device": device, "invite": invite})
        assert r.status_code == 200, r.text
        return r.json()

    sloppy = inv["code"][:4].lower() + "-" + inv["code"][4:].lower()
    b = await verify("dev-b@example.com", "mac", sloppy)
    assert b["created"] is True and b["invite"]["invites"] == 0 and b["spend"]["grant"] == 0.6
    me_a = (await client.get("/v1/me", headers=auth(ka))).json()
    assert me_a["invite"]["invites"] == 1 and me_a["invite"]["earned_cny"] == 3 and me_a["spend"]["grant"] == 3.6
    assert me_a["spend"]["left"] == 3.6 and me_a["invite"]["friends"][0]["hint"].endswith("example.com")
    assert me_a["recent"][0]["kind"] == "credit" and me_a["recent"][0]["detail"]["from"] == "invite"
    again = await verify("dev-b@example.com", "ipad", inv["code"])
    assert again["created"] is False
    assert (await client.get("/v1/me", headers=auth(ka))).json()["invite"]["invites"] == 1
    # an unknown code (or one's own) is not an error: the person is signed in, nobody is paid
    c = await verify("13900001111", "pixel", "NOPE1234")
    assert c["created"] is True
    assert (await client.get("/v1/me", headers=auth(ka))).json()["invite"]["invites"] == 1

    # Clips: ¥0.5 each here (five seconds at ¥0.10). Seven fit in A's ¥3.6; the eighth
    # would go over and is refused before the provider hears of it.
    for _ in range(7):
        assert (await clip(client, ka)).status_code == 200
    me_a = (await client.get("/v1/me", headers=auth(ka))).json()
    assert me_a["spend"]["total"] == 3.5 and me_a["spend"]["left"] == round(3.6 - 3.5, 4) and me_a["spend"]["warn"] is True
    r = await clip(client, ka)
    assert r.status_code == 429 and r.json()["error"]["code"] == "allowance_exhausted"
    assert r.json()["error"]["left"] == 0.1 and r.json()["error"]["contribute_bonus_available"] is False
    assert "co-creation" not in r.json()["error"]["message"] and "invite a friend" in r.json()["error"]["message"]
    r = await client.get("/api/v1/uploads", params={"action": "getPolicy", "model": "wan2.2-i2v-flash"}, headers=auth(ka))
    assert r.status_code == 429 and r.json()["error"]["code"] == "allowance_exhausted"
    # a probe costs nothing and is not refused for money
    r = await client.post(VIDEO, headers=auth(ka), json={"model": "wan2.2-i2v-flash", "input": {}, "parameters": {}})
    assert r.status_code == 400

    # B has ¥0.6: one clip fits, a second does not. The estimate said so beforehand.
    kb = b["api_key"]
    est = (await client.get("/v1/estimate", params={"images": 5, "clips": 4}, headers=auth(kb))).json()
    assert est["cny"] == 2.9 and est["left_cny"] == 0.6 and est["grant_cny"] == 0.6 and est["affordable"] is False
    assert est["unlimited"] is False and est["clips_ok"] is True and est["left_today_cny"] == 0.6
    assert [p["kind"] for p in est["parts"]] == ["image", "video"] and est["parts"][1]["seconds"] == 5
    est = (await client.get("/v1/estimate", params={"clips": 1}, headers=auth(kb))).json()
    assert est["cny"] == 0.5 and est["affordable"] is True
    assert (await clip(client, kb)).status_code == 200
    r = await clip(client, kb)
    assert r.status_code == 429 and r.json()["error"]["code"] == "allowance_exhausted"

    # The operator credits B for a pull request: straight into the pool.
    admin = {"X-Admin-Token": "admin"}
    r = await client.post("/v1/admin/credit", headers=admin, json={"identifier": "dev-b@example.com", "cny": 10, "note": "PR #12"})
    assert r.status_code == 200 and r.json()["grant_cny"] == 10.6 and r.json()["left_cny"] == 10.1 and "grant_uy" not in r.json()
    est = (await client.get("/v1/estimate", params={"images": 5, "clips": 4}, headers=auth(kb))).json()
    assert est["affordable"] is True and est["left_cny"] == 10.1
    assert (await clip(client, kb)).status_code == 200
    me_b = (await client.get("/v1/me", headers=auth(kb))).json()
    assert me_b["spend"]["total"] == 1.0 and me_b["spend"]["left"] == 9.6 and me_b["recent"][0]["kind"] == "video"
    assert any(row["kind"] == "credit" and row["detail"].get("note") == "PR #12" for row in me_b["recent"])
    r = await client.post("/v1/admin/credit", headers=admin, json={"identifier": "dev-b@example.com", "cny": 5000})
    assert r.status_code == 400

    # The operator's views carry it all.
    listing = (await client.get("/v1/admin/accounts", headers=admin)).json()["accounts"]
    row_a = next(x for x in listing if x["identifier"] == "dev-a@example.com")
    assert row_a["invites"] == 1 and row_a["grant_cny"] == 3.6 and row_a["left_cny"] == 0.1 and "credit_uy" not in row_a
    detail = (await client.get(f"/v1/admin/accounts/{row_a['id']}", headers=admin)).json()
    assert detail["account"]["invited"][0]["hint"].endswith("example.com") and detail["spend"]["left_cny"] == 0.1
    assert "clips_used" not in detail["account"]
    s = (await client.get("/v1/admin/accounts", headers=admin)).json()["settings"]
    assert s["invite_bonus_cny"] == 3 and s["allowance_cny"] == 0.6 and s["contribute_bonus_cny"] == 0
    kinds = {e["kind"] for e in (await client.get("/v1/admin/events", headers=admin)).json()["events"]}
    assert {"invite.accepted", "invite.used", "invite.unknown", "credit.granted", "budget.refused"} <= kinds


async def test_admin_series_and_traffic(tmp_path):
    """The operator's time series come from the relay's own tables; the site's visits and
    downloads from the traffic database the showcase's script keeps — or, without one, a
    panel that says so."""
    import importlib.util
    import json
    from pathlib import Path

    traffic_py = Path(__file__).resolve().parents[2] / "demo" / "showcase" / "mirror" / "traffic.py"
    db_path = tmp_path / "traffic.db"
    app, client, sender, up, cloud, settings = make(traffic_db=str(db_path), signup_tokens=1000, allowance_cny=10)
    a = await sign_up(client, sender, "13800138000", "pixel")
    await sign_up(client, sender, "dev-a@example.com", "mac")
    await client.post(
        "/v1/chat/completions", json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}, headers=auth(a["api_key"])
    )
    admin = {"X-Admin-Token": "admin"}

    assert (await client.get("/v1/admin/series")).status_code == 401
    r = await client.get("/v1/admin/series?days=7", headers=admin)
    assert r.status_code == 200, r.text
    series = r.json()
    assert len(series["days"]) == 7 and series["web"] is None and series["online_devices"] == 0
    today = series["days"][-1]
    assert today["sign_ins"] == 2 and today["new_accounts"] == 2 and today["active_accounts"] == 1
    assert series["days"][0]["sign_ins"] == 0 and series["invites"]["invited"] == 0 and series["contributors"] == 0
    assert "13800138000" not in r.text and "dev-a@example.com" not in r.text

    # no database yet: the panel is told, nothing fails
    r = await client.get("/v1/admin/traffic", headers=admin)
    assert r.status_code == 200 and r.json() == {"available": False}

    # the showcase's script fills one from Caddy's JSON log; the relay reads it as it is
    if not traffic_py.exists():
        return
    spec = importlib.util.spec_from_file_location("nm_traffic", traffic_py)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    mod.DB_PATH = db_path
    conn = mod.open_db()
    tally = mod.Tally()
    ts = float(__import__("time").time())
    for uri, ua, ctype, status in (
        ("/", "Mozilla/5.0", "text/html; charset=utf-8", 200),
        ("/", "Mozilla/5.0", "text/html; charset=utf-8", 200),
        ("/own-key/", "Mozilla/5.0 (X11)", "text/html", 200),
        ("/", "Googlebot/2.1", "text/html", 200),
        ("/dl/v0.1.22/unibot-0.1.22-arm64.apk", "Mozilla/5.0", "application/octet-stream", 200),
        ("/dl/v0.1.22/unibot-0.1.22-arm64.apk.sha256", "Mozilla/5.0", "application/octet-stream", 200),
    ):
        rec = {
            "ts": ts,
            "status": status,
            "size": 1000,
            "request": {
                "host": "unibot.cn",
                "uri": uri,
                "client_ip": "203.0.113.7",
                "headers": {"User-Agent": [ua], "Referer": ["https://github.com/unictoai/unibot"]},
            },
            "resp_headers": {"Content-Type": [ctype]},
        }
        tally.add(rec, conn)
    conn.execute("BEGIN")
    tally.flush(conn)
    conn.execute("COMMIT")
    conn.execute(
        "INSERT INTO github(day, ts, stars, forks, watchers, downloads, assets) VALUES (?,?,?,?,?,?,?)",
        (mod.local_day(ts), int(ts), 24, 5, 3, 188, json.dumps({"v0.1.22": {"unibot-0.1.22-arm64.apk": 100}})),
    )
    conn.close()

    r = await client.get("/v1/admin/traffic?days=7", headers=admin)
    assert r.status_code == 200, r.text
    tr = r.json()
    assert tr["available"] is True and len(tr["days"]) == 7 and tr["days"][0]["pages"] == 0
    day = tr["days"][-1]
    assert day["pages"] == 3 and day["visitors"] == 2 and day["bots"] == 1 and day["downloads"] == 1 and day["requests"] == 6
    assert tr["pages"][0] == {"name": "/", "hits": 2} and tr["referrers"] == [{"name": "github.com", "hits": 3}]
    assert tr["downloads"] == [{"name": "unibot-0.1.22-arm64.apk", "hits": 1, "bytes": 1000}]
    assert tr["github"]["stars"] == 24 and tr["github"]["downloads"] == 188 and tr["github"]["days"][0]["stars"] == 24
    assert "203.0.113.7" not in r.text
