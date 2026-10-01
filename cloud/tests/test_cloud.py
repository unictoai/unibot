"""End-to-end through the ASGI app, with a fake provider standing in for the upstream."""

from __future__ import annotations

import asyncio
import base64
import json

import httpx
import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from unibot_cloud.api import create_app
from unibot_cloud.config import Settings
from unibot_cloud.db import Database
from unibot_cloud.identifiers import BadIdentifier, parse
from unibot_cloud.senders import LogSender
from unibot_cloud.service import Cloud

PNG_1PX = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")


def fake_upstream() -> FastAPI:
    up = FastAPI()
    up.state.requests = []

    @up.post("/compat/v1/chat/completions")
    async def chat(request: Request):
        body = await request.json()
        up.state.requests.append(("chat", dict(request.headers), body))
        if body.get("model") == "boom":
            return JSONResponse(status_code=500, content={"error": {"message": "upstream exploded"}})
        if body.get("stream"):

            async def gen():
                for piece in ("你好", "，", "世界"):
                    chunk = {
                        "id": "c1",
                        "object": "chat.completion.chunk",
                        "model": body["model"],
                        "choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}],
                    }
                    yield f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
                yield 'data: {"id":"c1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":40,"completion_tokens":6}}\n\n'
                yield "data: [DONE]\n\n"

            return StreamingResponse(gen(), media_type="text/event-stream")
        return {
            "id": "c1",
            "object": "chat.completion",
            "model": body["model"],
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
        }

    up.state.image_429s = 0  # how many times the next pictures are refused with a 429 first

    @up.post("/ds/api/v1/services/aigc/multimodal-generation/generation")
    async def draw(request: Request):
        body = await request.json()
        up.state.requests.append(("image", dict(request.headers), body))
        if up.state.image_429s > 0:
            up.state.image_429s -= 1
            return JSONResponse(
                status_code=429,
                content={"code": "Throttling.RateQuota", "message": "Requests rate limit exceeded, please try again later."},
            )
        return {"output": {"choices": [{"message": {"content": [{"image": "http://upstream/pic.png"}]}}]}}

    @up.get("/pic.png")
    async def pic():
        from fastapi.responses import Response

        return Response(content=PNG_1PX, media_type="image/png")

    # DashScope's asynchronous video API: submit, poll, upload policy.
    @up.post("/ds/api/v1/services/aigc/video-generation/video-synthesis")
    async def video(request: Request):
        body = await request.json()
        up.state.requests.append(("video", dict(request.headers), body))
        if body.get("model") == "nope":
            return JSONResponse(status_code=404, content={"code": "InvalidParameter", "message": "Model not exist"})
        if not body.get("input"):
            return JSONResponse(status_code=400, content={"code": "InvalidParameter", "message": "prompt is required"})
        nth = sum(1 for r in up.state.requests if r[0] == "video" and r[2].get("input"))
        return {"output": {"task_id": f"task-{41 + nth}", "task_status": "PENDING"}, "request_id": "r1"}

    @up.get("/ds/api/v1/tasks/{task_id}")
    async def task(task_id: str, request: Request):
        up.state.requests.append(("task", dict(request.headers), task_id))
        polls = sum(1 for r in up.state.requests if r[0] == "task" and r[2] == task_id)
        status = "RUNNING" if polls == 1 else "SUCCEEDED"
        return {"output": {"task_id": task_id, "task_status": status, "video_url": "http://upstream/clip.mp4"}}

    @up.get("/ds/api/v1/uploads")
    async def uploads(request: Request):
        up.state.requests.append(("uploads", dict(request.headers), dict(request.query_params)))
        return {"data": {"upload_dir": "tmp/x", "upload_host": "http://oss", "policy": "p", "signature": "s", "oss_access_key_id": "k"}}

    return up


@pytest.fixture
def stack():
    up = fake_upstream()
    settings = Settings(
        database=":memory:",
        secret="test-secret",
        admin_token="admin",
        upstream_base="http://upstream/compat/v1",
        upstream_key="sk-upstream",
        dashscope_base="http://upstream/ds/api/v1",
        signup_tokens=1000,
        daily_cap_tokens=100_000,
        per_minute_requests=100,
        public_base="http://cloud.test",
    )
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud, upstream_transport=httpx.ASGITransport(app=up))
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://cloud.test")
    return app, client, sender, up, cloud


async def sign_up(client, sender, identifier="13800138000", device="pixel"):
    r = await client.post("/v1/auth/code", json={"identifier": identifier})
    assert r.status_code == 204, r.text
    ident, code = sender.sent[-1]
    r = await client.post("/v1/auth/verify", json={"identifier": identifier, "code": code, "device": device})
    assert r.status_code == 200, r.text
    return r.json()


def test_identifiers():
    assert parse("138 0013 8000").value == "+8613800138000"
    assert parse("+8613800138000").hint == "138****8000"
    assert parse("0086 13800138000").value == "+8613800138000"
    assert parse("+14155552671").channel == "phone"
    assert parse("Someone@Example.COM").value == "someone@example.com"
    assert parse("someone@example.com").hint == "so***@example.com"
    for bad in ("", "12345", "not an address", "@x.com", "1234567890123456789"):
        with pytest.raises(BadIdentifier):
            parse(bad)


async def test_signup_grants_and_lists_models(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    assert data["api_key"].startswith("nm_")
    assert data["created"] is True
    assert data["tokens"] == {"unlimited": False, "granted": 1000, "used": 0, "remaining": 1000, "used_today": 0, "daily_cap": 100_000}
    assert data["account"]["hint"] == "138****8000"
    assert data["base_url"] == "http://cloud.test"
    ids = [m["id"] for m in data["models"]]
    assert "qwen3.8-27b" in ids and "qwen-image-3.0" in ids and "wan2.2-i2v-flash" in ids
    video = next(m for m in data["models"] if m["id"] == "wan2.2-i2v-flash")
    assert video["unibot"]["kind"] == "video" and video["architecture"]["output_modalities"] == ["video"]

    headers = {"Authorization": f"Bearer {data['api_key']}"}
    r = await client.get("/v1/models", headers=headers)
    assert r.status_code == 200
    plus = next(m for m in r.json()["data"] if m["id"] == "qwen3.8-27b")
    assert plus["architecture"]["input_modalities"] == ["text", "image"]
    assert plus["unibot"]["recommended"] is True

    # The same number again: same account, a second key, no second grant.
    again = await sign_up(client, sender, device="tablet")
    assert again["created"] is False
    assert again["tokens"]["granted"] == 1000
    assert again["api_key"] != data["api_key"]


async def test_wrong_code_and_expiry_rules(stack):
    app, client, sender, up, cloud = stack
    r = await client.post("/v1/auth/code", json={"identifier": "a@b.co"})
    assert r.status_code == 204
    r = await client.post("/v1/auth/verify", json={"identifier": "a@b.co", "code": "000000"})
    assert r.status_code == 400 and r.json()["error"]["code"] == "code_wrong"
    r = await client.post("/v1/auth/verify", json={"identifier": "a@b.co", "code": "12"})
    assert r.status_code == 400
    r = await client.post("/v1/auth/verify", json={"identifier": "nobody@b.co", "code": "123456"})
    assert r.json()["error"]["code"] == "code_expired"
    r = await client.post("/v1/auth/code", json={"identifier": "garbage"})
    assert r.status_code == 400 and r.json()["error"]["code"] == "bad_identifier"
    # Three codes in ten minutes is the ceiling per identifier.
    for _ in range(2):
        assert (await client.post("/v1/auth/code", json={"identifier": "a@b.co"})).status_code == 204
    r = await client.post("/v1/auth/code", json={"identifier": "a@b.co"})
    assert r.status_code == 429 and r.json()["error"]["code"] == "code_too_often"


async def test_chat_is_relayed_and_charged(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}

    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.status_code == 200, r.text
    assert r.json()["choices"][0]["message"]["content"] == "hi"
    assert r.json()["model"] == "qwen3.8-27b"
    assert r.headers["x-unibot-charged"] == "150"
    kind, up_headers, up_body = up.state.requests[-1]
    assert up_headers["authorization"] == "Bearer sk-upstream"
    assert up_body["model"] == "qwen3.8-27b" and up_body["user"]
    assert up_body["enable_thinking"] is False  # CHAT_DEFAULTS filled in

    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["tokens"]["used"] == 150 and me["tokens"]["remaining"] == 850
    assert me["recent"][0]["kind"] == "chat" and me["recent"][0]["charged"] == 150

    # Flash is cheaper: 100×0.3 + 50×0.3 = 45.
    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-flash", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.headers["x-unibot-charged"] == "45"

    # A model we do not offer is refused before anything is forwarded.
    n = len(up.state.requests)
    r = await client.post("/v1/chat/completions", headers=headers, json={"model": "gpt-4o", "messages": []})
    assert r.status_code == 404 and r.json()["error"]["code"] == "model_not_offered"
    assert len(up.state.requests) == n

    # Upstream failures come back as 502 in the relay's words (the provider's under
    # ``upstream``), uncharged.
    used = cloud.me(cloud.authenticate(data["api_key"]))["tokens"]["used"]
    settings = app.state.settings
    object.__setattr__(settings, "models", settings.models + (type(settings.models[0])(id="boom", name="Boom", upstream="boom"),))
    r = await client.post("/v1/chat/completions", headers=headers, json={"model": "boom", "messages": []})
    assert r.status_code == 502
    err = r.json()["error"]
    assert err["code"] == "upstream_500" and "exploded" not in err["message"] and "exploded" in err["upstream"]
    assert cloud.me(cloud.authenticate(data["api_key"]))["tokens"]["used"] == used


async def test_stream_refused_upstream_is_not_charged(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    settings = app.state.settings
    object.__setattr__(settings, "models", settings.models + (type(settings.models[0])(id="boom", name="Boom", upstream="boom"),))
    async with client.stream(
        "POST",
        "/v1/chat/completions",
        headers=headers,
        json={"model": "boom", "stream": True, "messages": [{"role": "user", "content": "hi " * 500}]},
    ) as r:
        assert r.status_code == 200
        body = (await r.aread()).decode()
    lines = [ln for ln in body.split("\n") if ln.startswith("data:")]
    assert lines[-1] == "data: [DONE]"
    err = json.loads(lines[0][5:])["error"]
    assert err["code"] == "upstream_500" and err["upstream"] == "upstream exploded"
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["tokens"]["used"] == 0  # nothing was generated, nothing is owed
    assert not [row for row in me["recent"] if row["kind"] == "chat"]


async def test_stream_passes_through_and_charges_from_usage_chunk(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    async with client.stream(
        "POST",
        "/v1/chat/completions",
        headers=headers,
        json={"model": "qwen3.8-27b", "stream": True, "messages": [{"role": "user", "content": "hi"}]},
    ) as r:
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("text/event-stream")
        body = (await r.aread()).decode()
    lines = [ln for ln in body.split("\n") if ln.startswith("data:")]
    assert lines[-1] == "data: [DONE]"
    pieces = []
    for ln in lines[:-1]:
        obj = json.loads(ln[5:])
        assert obj.get("model") in ("qwen3.8-27b", None)
        for ch in obj.get("choices", []):
            pieces.append(ch["delta"]["content"])
    assert "".join(pieces) == "你好，世界"
    up_body = up.state.requests[-1][2]
    assert up_body["stream_options"] == {"include_usage": True}
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["tokens"]["used"] == 46  # 40 + 6 from the usage chunk


async def test_out_of_tokens_and_bad_keys(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    # Seven full-price calls of 150 exhaust a 1000-token grant.
    for _ in range(7):
        r = await client.post(
            "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
        )
        assert r.status_code == 200
    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.status_code == 402 and r.json()["error"]["code"] == "out_of_tokens"

    # An admin top-up brings it back.
    me = (await client.get("/v1/me", headers=headers)).json()
    accounts = (await client.get("/v1/admin/accounts", headers={"X-Admin-Token": "admin"})).json()["accounts"]
    assert accounts[0]["hint"] == me["account"]["hint"]
    r = await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"account_id": accounts[0]["id"], "tokens": 500})
    assert r.status_code == 200
    # ...or by the phone number itself, which is hashed the same way.
    r = await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"identifier": "138 0013 8000", "tokens": 0})
    assert r.status_code == 200 and r.json()["granted"] == 1500
    r = await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"identifier": "nobody@example.com", "tokens": 1})
    assert r.status_code == 404
    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.status_code == 200
    assert (await client.get("/v1/admin/accounts")).status_code == 401

    # Bad, missing and revoked keys.
    assert (await client.get("/v1/models")).status_code == 401
    assert (await client.get("/v1/models", headers={"Authorization": "Bearer nm_nope"})).status_code == 401
    assert (await client.get("/v1/models", headers={"Authorization": "Bearer sk-other"})).status_code == 401
    assert (await client.post("/v1/auth/sign-out", headers=headers)).status_code == 204
    r = await client.get("/v1/me", headers=headers)
    assert r.status_code == 401 and r.json()["error"]["code"] == "bad_key"


async def test_a_rate_limited_picture_is_tried_again_behind_the_gate(stack, monkeypatch):
    """DashScope allows an account a couple of image tasks at a time and says 429 past that.
    The relay waits it out (with growing pauses) instead of handing the app a 502 that
    quotes the provider; only when it never clears does the app hear `provider_busy`."""
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    accounts = (await client.get("/v1/admin/accounts", headers={"X-Admin-Token": "admin"})).json()["accounts"]
    await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"account_id": accounts[0]["id"], "tokens": 1_000_000})
    naps: list[float] = []

    async def no_sleep(seconds: float) -> None:
        naps.append(seconds)

    monkeypatch.setattr("unibot_cloud.api.asyncio.sleep", no_sleep)

    up.state.image_429s = 2
    r = await client.post("/v1/images/generations", headers=headers, json={"model": "qwen-image-3.0", "prompt": "a dragon"})
    assert r.status_code == 200, r.text
    assert naps == [2.0, 4.0]
    assert sum(1 for k, _, _ in up.state.requests if k == "image") == 3
    assert r.headers["x-unibot-charged"] == "30000"

    # never clears: a 429 of the relay's own, a stable code, no charge
    naps.clear()
    up.state.image_429s = 99
    before = (await client.get("/v1/me", headers=headers)).json()
    r = await client.post("/v1/images/generations", headers=headers, json={"model": "qwen-image-3.0", "prompt": "a dragon"})
    assert r.status_code == 429
    assert r.json()["error"]["code"] == "provider_busy"
    assert r.json()["error"]["retry_after"] == 20
    assert len(naps) == 4  # IMAGE_RETRIES
    after = (await client.get("/v1/me", headers=headers)).json()
    assert after["usage"] == before["usage"]
    up.state.image_429s = 0


async def test_pictures_are_drawn_a_couple_at_a_time(stack):
    """Six at once from six devices: the gate lets two through at a time, the rest queue,
    all six come back — the provider never sees more than two in flight."""
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    accounts = (await client.get("/v1/admin/accounts", headers={"X-Admin-Token": "admin"})).json()["accounts"]
    await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"account_id": accounts[0]["id"], "tokens": 1_000_000})
    in_flight = {"now": 0, "peak": 0}

    async def slow(request: Request):
        in_flight["now"] += 1
        in_flight["peak"] = max(in_flight["peak"], in_flight["now"])
        await asyncio.sleep(0.05)
        in_flight["now"] -= 1
        return {"output": {"choices": [{"message": {"content": [{"image": "http://upstream/pic.png"}]}}]}}

    # the slow handler takes the place of the fake's usual one
    up.router.routes[:] = [
        rt for rt in up.router.routes if getattr(rt, "path", "") != "/ds/api/v1/services/aigc/multimodal-generation/generation"
    ]
    up.add_api_route("/ds/api/v1/services/aigc/multimodal-generation/generation", slow, methods=["POST"])

    rs = await asyncio.gather(
        *(
            client.post("/v1/images/generations", headers=headers, json={"model": "qwen-image-3.0", "prompt": f"dragon {i}"})
            for i in range(6)
        )
    )
    assert [r.status_code for r in rs] == [200] * 6
    assert in_flight["peak"] <= 2


async def test_images_go_through_dashscope_native(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    # 30 000 per picture is more than the 1 000 grant: refused first.
    r = await client.post(
        "/v1/images/generations", headers=headers, json={"model": "qwen-image-3.0", "prompt": "a small dragon", "size": "1024x1024"}
    )
    assert r.status_code == 402
    accounts = (await client.get("/v1/admin/accounts", headers={"X-Admin-Token": "admin"})).json()["accounts"]
    await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"account_id": accounts[0]["id"], "tokens": 100_000})

    r = await client.post(
        "/v1/images/generations",
        headers=headers,
        json={"model": "qwen-image-3.0", "prompt": "a small dragon", "size": "1024x1024", "response_format": "b64_json"},
    )
    assert r.status_code == 200, r.text
    assert base64.b64decode(r.json()["data"][0]["b64_json"]) == PNG_1PX
    kind, up_headers, up_body = up.state.requests[-1]
    assert kind == "image" and up_body["model"] == "qwen-image-3.0"
    assert up_body["parameters"] == {"size": "1024*1024", "watermark": False, "prompt_extend": False}
    assert up_body["input"]["messages"][0]["content"] == [{"text": "a small dragon"}]
    assert r.headers["x-unibot-charged"] == "30000"

    r = await client.post(
        "/v1/images/edits",
        headers=headers,
        data={"model": "qwen-image-3.0", "prompt": "same dragon, waving", "n": "1", "size": "1024x1024"},
        files={"image": ("image0.png", PNG_1PX, "image/png")},
    )
    assert r.status_code == 200, r.text
    kind, up_headers, up_body = up.state.requests[-1]
    content = up_body["input"]["messages"][0]["content"]
    assert content[0]["image"].startswith("data:image/png;base64,") and content[1] == {"text": "same dragon, waving"}
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["tokens"]["used"] == 60_000


async def test_unconfigured_upstream_answers_503(stack):
    app, client, sender, up, cloud = stack
    object.__setattr__(app.state.settings, "upstream_key", "")
    data = await sign_up(client, sender)
    r = await client.post(
        "/v1/chat/completions", headers={"Authorization": f"Bearer {data['api_key']}"}, json={"model": "qwen3.8-27b", "messages": []}
    )
    assert r.status_code == 503 and r.json()["error"]["code"] == "upstream_unconfigured"
    assert (await client.get("/healthz")).json()["ok"] is True


def make_stack(**overrides):
    up = fake_upstream()
    kwargs = dict(
        database=":memory:",
        secret="test-secret",
        admin_token="admin",
        upstream_base="http://upstream/compat/v1",
        upstream_key="sk-upstream",
        dashscope_base="http://upstream/ds/api/v1",
        public_base="http://cloud.test",
    )
    kwargs.update(overrides)
    settings = Settings(**kwargs)
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud, upstream_transport=httpx.ASGITransport(app=up))
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://cloud.test")
    return app, client, sender, up, cloud


async def test_private_relay_only_lets_listed_identifiers_in():
    app, client, sender, up, cloud = make_stack(allowed_identifiers="139 0000 1111, Me@Example.com", signup_open=False)
    r = await client.post("/v1/auth/code", json={"identifier": "13800138000"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "not_invited"
    assert sender.sent == []
    for ok in ("13900001111", "+8613900001111", "me@example.com"):
        r = await client.post("/v1/auth/code", json={"identifier": ok})
        assert r.status_code == 204, (ok, r.text)
    data = await sign_up(client, sender, identifier="me@example.com", device="desk")
    assert data["account"]["hint"] == "m***@example.com"
    assert data["account"]["member"] is True and data["spend"]["unlimited"] is True


async def test_open_signup_members_unlimited_everyone_else_has_one_allowance():
    # The released relay: anyone may sign in; the listed people have no limit,
    # the rest have one pool for good (¥10 in production) at the provider's
    # list prices — chat, pictures and clips alike.
    app, client, sender, up, cloud = make_stack(
        allowed_identifiers="Me@Example.com",
        signup_tokens=0,
        daily_cap_tokens=0,
        per_minute_requests=0,
        allowance_cny=0.002,
        invite_bonus_cny=5,
        contribute_bonus_cny=10,
        usd_cny=7.0,
    )
    admin = {"X-Admin-Token": "admin"}
    guest = await sign_up(client, sender, identifier="13800138000", device="pixel")
    assert guest["account"]["member"] is False
    assert guest["spend"] == {
        "currency": "CNY",
        "total": 0,
        "grant": 0.002,
        "left": 0.002,
        "unlimited": False,
        "warn": False,
        "usd_cny": 7.0,
        "total_usd": 0,
        "grant_usd": 0.0003,
        "left_usd": 0.0003,
        "today": 0,
        "today_usd": 0,
        "allowance_cny": 0.002,
        "invite_bonus_cny": 5,
        "contribute_bonus_cny": 10,
        "contribute_bonus_available": True,
        "own_key_docs": "https://unibot.cn/own-key",
        # what a 0.4 app still reads: the pool as the "cap", no midnight
        "daily_cap": 0.002,
        "daily_cap_usd": 0.0003,
        "day_offset_h": 8,
        "resets_at": 0,
        "credit_left": 0,
        "left_today": 0.002,
    }
    assert guest["clips"]["unlimited"] is True
    # the allowance is the first line of the statement
    assert guest["recent"][0]["kind"] == "credit" and guest["recent"][0]["detail"] == {"credit_uy": 2000, "from": "signup"}
    # Prices travel with the model list, so the apps can show them.
    price = next(m for m in guest["models"] if m["id"] == "qwen3.8-27b")["unibot"]["price_cny"]
    assert price["per_m_input"] == 3.0 and price["per_m_output"] == 12.0

    headers = {"Authorization": f"Bearer {guest['api_key']}"}
    # 100 prompt + 50 completion tokens at ¥3 / ¥12 per million = ¥0.0009.
    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.status_code == 200
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["spend"]["today"] == 0.0009 and me["spend"]["total"] == 0.0009 and me["spend"]["left"] == 0.0011
    assert me["spend"]["warn"] is False
    assert me["recent"][0]["cost_cny"] == 0.0009
    # Chats are priced after the fact, so one starts as long as anything is left:
    # the second and third go through (¥0.0027), the fourth does not — and the
    # refusal says what is left and where the three ways on lead.
    for _ in range(2):
        assert (
            await client.post(
                "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
            )
        ).status_code == 200
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["spend"]["left"] == 0 and me["spend"]["warn"] is True
    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.status_code == 429
    err = r.json()["error"]
    assert err["code"] == "allowance_exhausted" and "¥0.002" in err["message"] and "keep working" in err["message"]
    assert err["left"] == 0 and err["grant"] == 0.002 and err["own_key_docs"] == "https://unibot.cn/own-key"
    assert (
        err["invite_url"].startswith("https://unibot.cn/web/?invite=")
        and len(err["invite_url"]) == len("https://unibot.cn/web/?invite=") + 8
    )
    assert err["contribute_bonus_available"] is True and err["contribute_bonus_cny"] == 10 and err["invite_bonus_cny"] == 5
    # A picture that would go over is refused before it is drawn.
    r = await client.post("/v1/images/generations", headers=headers, json={"model": "qwen-image-3.0", "prompt": "a dragon"})
    assert r.status_code == 429 and not any(k == "image" for k, _, _ in up.state.requests)
    # Joining the co-creation programme adds ¥10, once: the chat goes through again.
    r = await client.post("/v1/me/contribute", headers=headers, json={"on": True})
    assert r.status_code == 200 and r.json()["bonus_granted"] is True and r.json()["bonus_available"] is False
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["spend"]["grant"] == 10.002 and me["spend"]["left"] == round(10.002 - 0.0027, 4) and me["spend"]["warn"] is False
    assert me["contribute"]["on"] is True and me["contribute"]["bonus_available"] is False and me["contribute"]["bonus_at"]
    r = await client.post(
        "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
    )
    assert r.status_code == 200
    await client.post("/v1/me/contribute", headers=headers, json={"on": False})
    r = await client.post("/v1/me/contribute", headers=headers, json={"on": True})
    assert r.json()["bonus_granted"] is False
    assert (await client.get("/v1/me", headers=headers)).json()["spend"]["grant"] == 10.002

    # The listed person has no limit and sees none.
    member = await sign_up(client, sender, identifier="me@example.com", device="desk")
    assert member["account"]["member"] is True and member["spend"]["grant"] == 0 and member["spend"]["left"] is None
    assert member["spend"]["unlimited"] is True and member["spend"]["daily_cap"] == 0
    mh = {"Authorization": f"Bearer {member['api_key']}"}
    for _ in range(4):
        assert (
            await client.post(
                "/v1/chat/completions", headers=mh, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
            )
        ).status_code == 200
    r = await client.post("/v1/images/generations", headers=mh, json={"model": "qwen-image-3.0", "prompt": "a dragon"})
    assert r.status_code == 200
    me = (await client.get("/v1/me", headers=mh)).json()
    assert me["spend"]["today"] == round(4 * 0.0009 + 0.18, 4)

    # The operator's view carries money next to tokens, and can make a guest a member.
    listing = (await client.get("/v1/admin/accounts", headers=admin)).json()
    s = listing["settings"]
    assert s["signup_open"] is True and s["allowance_cny"] == 0.002 and s["usd_cny"] == 7.0
    assert s["invite_bonus_cny"] == 5 and s["contribute_bonus_cny"] == 10 and s["contributors"] == 1
    assert "daily_cap_cny" not in s and "video_clips_free" not in s
    assert s["prices"]["qwen-image-3.0"]["per_image"] == 0.18
    by_id = {a["identifier"]: a for a in listing["accounts"]}
    g, m = by_id["+8613800138000"], by_id["me@example.com"]
    assert g["member"] is False and g["spent_today_cny"] == 0.0036 and g["spent_cny"] == 0.0036
    assert g["grant_cny"] == 10.002 and g["left_cny"] == round(10.002 - 0.0036, 4) and g["contribute_bonus_at"]
    assert "credit_uy" not in g and "grant_uy" not in g and "clips_bonus" not in g
    assert m["member"] is True and m["listed"] is True and m["unlimited"] is False and m["left_cny"] is None
    usage = (await client.get("/v1/admin/usage", headers=admin)).json()["days"]
    assert {(u["kind"], u["cost_cny"]) for u in usage} == {("chat", round(8 * 0.0009, 4)), ("image", 0.18)}

    r = await client.post("/v1/admin/unlimited", headers=admin, json={"identifier": "138 0013 8000"})
    assert r.status_code == 204
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["account"]["member"] is True and me["spend"]["unlimited"] is True
    assert (
        await client.post(
            "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
        )
    ).status_code == 200
    listing = (await client.get("/v1/admin/accounts", headers=admin)).json()
    g = next(a for a in listing["accounts"] if a["identifier"] == "+8613800138000")
    assert g["member"] is True and g["unlimited"] is True and g["listed"] is False


def test_prices_and_day_boundary():
    s = Settings(database=":memory:", day_offset_h=8, usd_cny=7.1)
    m = s.model("qwen3.8-27b")
    assert m.chat_cost_uy(1_000_000, 0) == 3_000_000 and m.chat_cost_uy(0, 1_000_000) == 12_000_000
    assert m.chat_cost_uy(333, 21) == round(333 * 3 + 21 * 12)
    img = s.model("qwen-image-3.0")
    assert s.model("qwen-image-3.0-pro") is img  # what 0.1.21 phones still ask for
    assert s.model("MiniMax/MiniMax-H3") is None and s.model("nope") is None
    assert img.image_cost_uy("1024*1024") == 180_000 and img.image_cost_uy("2048x2048") == 180_000 and img.image_cost_uy(None) == 180_000
    vid = s.model("wan2.2-i2v-flash")
    assert vid.video_cost_uy(5) == 500_000 and vid.video_cost_uy(vid.clip_seconds) == 500_000 and vid.video_cost_uy(0) == 0
    # 2026-09-29 02:00 UTC is still the 29th in Beijing; its day began at 16:00 UTC on the 28th.
    t = 1790647200  # 2026-09-29T02:00:00Z
    assert s.day_start(t) == t - 10 * 3600
    assert s.day_start(t) == s.day_start(t + 13 * 3600)  # 15:00 UTC = 23:00 Beijing, same day
    assert s.day_start(t + 14 * 3600 + 1) == s.day_start(t) + 86400  # 16:00:01 UTC = the 30th
    assert s.cny_to_usd(25) == round(25 / 7.1, 4) and s.uy_to_cny(1234) == 0.0012


async def test_video_is_relayed_under_dashscope_paths(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender)
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    await client.post("/v1/admin/grant", headers={"X-Admin-Token": "admin"}, json={"identifier": "13800138000", "tokens": 1_000_000})

    # The app's probe: an unknown name is 404, an offered one answers 400 on an empty body (nothing charged).
    r = await client.post(
        "/api/v1/services/aigc/video-generation/video-synthesis",
        headers=headers,
        json={"model": "wan2.6-i2v", "input": {}, "parameters": {}},
    )
    assert r.status_code == 404
    r = await client.post(
        "/api/v1/services/aigc/video-generation/video-synthesis",
        headers=headers,
        json={"model": "wan2.2-i2v-flash", "input": {}, "parameters": {}},
    )
    assert r.status_code == 400 and r.json()["message"] == "prompt is required"
    assert (await client.get("/v1/me", headers=headers)).json()["tokens"]["used"] == 0

    # Upload policy, then the task itself, with the operator's key and the OSS header passed on.
    r = await client.get("/api/v1/uploads", params={"action": "getPolicy", "model": "wan2.2-i2v-flash"}, headers=headers)
    assert r.status_code == 200 and r.json()["data"]["upload_dir"] == "tmp/x"
    kind, up_headers, q = up.state.requests[-1]
    assert kind == "uploads" and up_headers["authorization"] == "Bearer sk-upstream" and q["model"] == "wan2.2-i2v-flash"
    r = await client.post(
        "/api/v1/services/aigc/video-generation/video-synthesis",
        headers={**headers, "X-DashScope-OssResourceResolve": "enable"},
        json={"model": "wan2.2-i2v-flash", "input": {"prompt": "a dragon waves"}, "parameters": {"duration": 4}},
    )
    assert r.status_code == 200 and r.json()["output"]["task_id"] == "task-42"
    kind, up_headers, up_body = up.state.requests[-1]
    assert kind == "video" and up_headers["x-dashscope-async"] == "enable" and up_headers["x-dashscope-ossresourceresolve"] == "enable"
    assert up_headers["authorization"] == "Bearer sk-upstream" and up_body["model"] == "wan2.2-i2v-flash"
    assert (await client.get("/v1/me", headers=headers)).json()["tokens"]["used"] == 0  # nothing until the clip exists

    # Polling: still running, then done — charged once, however often it is asked again.
    r = await client.get("/api/v1/tasks/task-42", headers=headers)
    assert r.status_code == 200 and r.json()["output"]["task_status"] == "RUNNING"
    assert (await client.get("/v1/me", headers=headers)).json()["tokens"]["used"] == 0
    for _ in range(2):
        r = await client.get("/api/v1/tasks/task-42", headers=headers)
        assert r.status_code == 200 and r.json()["output"]["video_url"].endswith("clip.mp4")
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["tokens"]["used"] == 200_000 and me["recent"][0]["kind"] == "video"
    # The owner sees the task, another account does not.
    other = await sign_up(client, sender, identifier="13900001111", device="other")
    r = await client.get("/api/v1/tasks/task-42", headers={"Authorization": f"Bearer {other['api_key']}"})
    assert r.status_code == 404
    # Too little grant left for a clip: refused before the provider is asked.
    r = await client.post(
        "/api/v1/services/aigc/video-generation/video-synthesis",
        headers={"Authorization": f"Bearer {other['api_key']}"},
        json={"model": "wan2.2-i2v-flash", "input": {"prompt": "x"}, "parameters": {}},
    )
    assert r.status_code == 402


async def test_unlimited_relay_meters_but_never_refuses():
    up = fake_upstream()
    settings = Settings(
        database=":memory:",
        secret="test-secret",
        admin_token="admin",
        upstream_base="http://upstream/compat/v1",
        upstream_key="sk-upstream",
        dashscope_base="http://upstream/ds/api/v1",
        public_base="http://cloud.test",
        signup_tokens=0,
        daily_cap_tokens=0,
        per_minute_requests=0,
    )
    assert settings.unlimited
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud, upstream_transport=httpx.ASGITransport(app=up))
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://cloud.test")
    data = await sign_up(client, sender)
    assert data["tokens"]["unlimited"] is True and data["tokens"]["granted"] == 0
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    for _ in range(3):
        r = await client.post(
            "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": [{"role": "user", "content": "hi"}]}
        )
        assert r.status_code == 200
    r = await client.post("/v1/images/generations", headers=headers, json={"model": "qwen-image-3.0", "prompt": "a dragon"})
    assert r.status_code == 200
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["tokens"] == {"unlimited": True, "granted": 0, "used": 450 + 30_000, "remaining": 0, "used_today": 30_450, "daily_cap": 0}
    accounts = (await client.get("/v1/admin/accounts", headers={"X-Admin-Token": "admin"})).json()
    assert accounts["settings"]["unlimited"] is True
    assert accounts["accounts"][0]["used_today"] == 30_450 and accounts["accounts"][0]["requests"] == 4


async def test_admin_sees_identifiers_and_people_can_leave(stack):
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender, identifier="Someone@Example.com", device="pixel")
    await sign_up(client, sender, identifier="13800138000", device="desk")
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    admin = {"X-Admin-Token": "admin"}

    listing = (await client.get("/v1/admin/accounts", headers=admin)).json()
    by_id = {a["identifier"]: a for a in listing["accounts"]}
    assert set(by_id) == {"someone@example.com", "+8613800138000"}
    a = by_id["someone@example.com"]
    assert a["hint"] == "so***@example.com" and a["channel"] == "email" and a["live_keys"] == 1 and a["devices"] == []
    assert "id_hash" not in a and "identifier_enc" not in a
    # The database itself holds no plaintext.
    row = cloud.db.account(a["id"])
    assert "someone" not in row["identifier_enc"] and cloud.crypto.decrypt(a["id"], row["identifier_enc"]) == "someone@example.com"
    assert cloud.crypto.decrypt("other-account", row["identifier_enc"]) is None
    assert listing["settings"]["models"][0] == "qwen3.8-27b"
    usage = (await client.get("/v1/admin/usage", headers=admin)).json()
    assert usage["days"] == []

    # Disable and re-enable by identifier; a disabled account's key stops working.
    r = await client.post("/v1/admin/disable", headers=admin, json={"identifier": "someone@example.com"})
    assert r.status_code == 204
    assert (await client.get("/v1/me", headers=headers)).status_code == 401
    r = await client.post("/v1/admin/disable", headers=admin, json={"identifier": "someone@example.com", "disabled": False})
    assert r.status_code == 204
    assert (await client.get("/v1/me", headers=headers)).status_code == 200

    # The person deletes themselves: key dead, account gone, the number can sign up afresh.
    r = await client.post("/v1/auth/delete", headers=headers)
    assert r.status_code == 204
    assert (await client.get("/v1/me", headers=headers)).status_code == 401
    listing = (await client.get("/v1/admin/accounts", headers=admin)).json()
    assert [a["identifier"] for a in listing["accounts"]] == ["+8613800138000"]
    again = await sign_up(client, sender, identifier="someone@example.com", device="pixel")
    assert again["created"] is True

    # The operator removes the other one.
    r = await client.post("/v1/admin/delete", headers=admin, json={"identifier": "138 0013 8000"})
    assert r.status_code == 204
    assert (await client.post("/v1/admin/delete", headers=admin, json={"identifier": "138 0013 8000"})).status_code == 404


def test_code_mail_has_text_and_html_in_both_languages():
    from unibot_cloud.senders import compose_code_mail

    msg = compose_code_mail("no-reply@mail.unibot.cn", "someone@example.com", "123456", 10)
    assert msg["From"] == "unibot <no-reply@mail.unibot.cn>" and msg["To"] == "someone@example.com"
    assert "123456" in msg["Subject"]
    parts = {p.get_content_type(): p.get_content() for p in msg.iter_parts()}
    assert set(parts) == {"text/plain", "text/html"}
    assert "验证码是 123456" in parts["text/plain"] and "Your unibot code is 123456" in parts["text/plain"]
    assert "1 2 3 4 5 6" in parts["text/html"] and "10 分钟" in parts["text/html"] and "10 minutes" in parts["text/html"]
    assert "<script" not in parts["text/html"]


def test_provider_picture_urls_are_only_fetched_from_the_provider():
    from unibot_cloud.api import _provider_url_ok

    own = ("dashscope.aliyuncs.com",)
    assert _provider_url_ok("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/x/y.png", own)
    assert _provider_url_ok("https://dashscope.aliyuncs.com/api/v1/files/1", own)
    assert _provider_url_ok("http://upstream/pic.png", ("upstream",))
    assert not _provider_url_ok("http://dashscope-result.oss-cn-beijing.aliyuncs.com/x.png", own)  # plain http, not our host
    assert not _provider_url_ok("https://169.254.169.254/latest/meta-data/", own)
    assert not _provider_url_ok("https://evil.example.com/aliyuncs.com/x.png", own)
    assert not _provider_url_ok("file:///etc/passwd", own)
    assert not _provider_url_ok("", own)


def test_a_video_task_seen_twice_stays_charged():
    db = Database(":memory:")
    db.create_account("h", "phone", "138****8000", 0, account_id="a1")
    db.insert_video_task("t1", "a1", "wan-x", cost_uy=500)
    assert db.mark_video_charged("t1") is True
    db.insert_video_task("t1", "a1", "wan-x", cost_uy=500)  # a retried submission answer, same task id
    assert db.mark_video_charged("t1") is False
    row = db.video_task("t1")
    assert row is not None and row["charged"] == 1


async def test_contributed_conversations_are_opt_in_and_deletable(stack):
    """Off by default: nothing about a chat turn is kept. On: the messages (pictures
    replaced by a marker) and the reply land in ``samples`` for the operator, exported
    without the account id; off again or a delete removes them."""
    app, client, sender, up, cloud = stack
    data = await sign_up(client, sender, identifier="dev-a@example.com")
    headers = {"Authorization": f"Bearer {data['api_key']}"}
    admin = {"X-Admin-Token": "admin"}
    msgs = [{"role": "user", "content": "hi"}]
    r = await client.post("/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": msgs})
    assert r.status_code == 200
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["contribute"] == {"on": False, "samples": 0, "bonus_cny": 10, "bonus_available": True, "bonus_at": None}
    assert (await client.get("/v1/admin/samples", headers=admin)).json() == {"samples": [], "total": 0}

    r = await client.post("/v1/me/contribute", headers=headers, json={"on": True})
    assert r.status_code == 200
    assert r.json() == {
        "on": True,
        "samples": 0,
        "bonus_cny": 10,
        "bonus_granted": True,
        "bonus_available": False,
        "bonus_at": r.json()["bonus_at"],
    }
    assert r.json()["bonus_at"] > 0
    # a plain reply, with a picture in the request
    picture = {
        "role": "user",
        "content": [{"type": "text", "text": "what is this"}, {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}],
    }
    r = await client.post(
        "/v1/chat/completions",
        headers={**headers, "User-Agent": "unibot/0.1.22 (Android)", "Accept-Language": "zh-CN"},
        json={"model": "qwen3.8-27b", "messages": [picture]},
    )
    assert r.status_code == 200
    # and a streamed one
    async with client.stream(
        "POST", "/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "stream": True, "messages": msgs}
    ) as r:
        await r.aread()
    me = (await client.get("/v1/me", headers=headers)).json()
    assert me["contribute"]["on"] is True and me["contribute"]["samples"] == 2
    got = (await client.get("/v1/admin/samples", headers=admin)).json()
    assert got["total"] == 2 and [s["response"] for s in got["samples"]] == ["你好，世界", "hi"]
    first = got["samples"][1]
    assert first["request"][0]["content"] == [{"type": "text", "text": "what is this"}, {"type": "image_url", "omitted": True}]
    assert "AAAA" not in json.dumps(first)
    assert first["meta"] == {"ua": "unibot/0.1.22 (Android)", "lang": "zh-CN"}
    assert first["prompt_tokens"] == 100 and first["completion_tokens"] == 50
    account = (await client.get(f"/v1/admin/accounts/{data['account']['id']}", headers=admin)).json()["account"]
    assert account["contribute"] is True and account["samples"] == 2
    overview = (await client.get("/v1/admin/overview", headers=admin)).json()
    assert overview["contributions"] == {"accounts": 1, "samples": 2}
    # the export has no account ids
    r = await client.get("/v1/admin/samples/export", headers=admin)
    assert r.status_code == 200 and r.headers["content-type"].startswith("application/x-ndjson")
    lines = [json.loads(ln) for ln in r.text.splitlines() if ln]
    assert len(lines) == 2 and all("account_id" not in ln for ln in lines)
    assert {ln["response"] for ln in lines} == {"hi", "你好，世界"}
    # the person's own delete
    r = await client.delete("/v1/me/samples", headers=headers)
    assert r.json() == {"deleted": 2}
    r = await client.post("/v1/me/contribute", headers=headers, json={"on": False})
    assert r.json()["on"] is False and r.json()["samples"] == 0 and r.json()["bonus_granted"] is False
    r = await client.post("/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": msgs})
    assert (await client.get("/v1/admin/samples", headers=admin)).json()["total"] == 0
    kinds = [e["kind"] for e in (await client.get("/v1/me/events", headers=headers)).json()["events"]]
    assert {"contribute.on", "contribute.off", "contribute.deleted"} <= set(kinds)
    # deleting the account takes any samples with it
    await client.post("/v1/me/contribute", headers=headers, json={"on": True})
    await client.post("/v1/chat/completions", headers=headers, json={"model": "qwen3.8-27b", "messages": msgs})
    assert cloud.db.sample_count() == 1
    assert (await client.post("/v1/auth/delete", headers=headers)).status_code == 204
    assert cloud.db.sample_count() == 0


def test_long_samples_are_cut_whole_and_old_cut_rows_still_load():
    """A conversation over the sample limit is shortened message by message (then text by
    text) so that what is stored parses; a row cut at a character count by 0.5.1 gives back
    the messages that are whole instead of failing the list and the export."""
    from unibot_cloud.service import _fit_messages, _load_messages

    msgs = [{"role": "system", "content": "be brief"}] + [
        {"role": "user" if i % 2 == 0 else "assistant", "content": f"turn {i} " + "x" * 500} for i in range(40)
    ]
    text, cut = _fit_messages(msgs, 3000)
    assert cut and len(text) <= 3000
    kept = json.loads(text)
    assert kept[0] == msgs[0] and kept[-1] == msgs[-1] and kept[1]["content"].startswith("turn 3")
    # one huge paste: the text is cut, the message stays
    text, cut = _fit_messages([{"role": "user", "content": "y" * 10_000}], 1000)
    assert cut and len(text) <= 1000 and json.loads(text)[0]["content"].endswith("[…]")
    text, cut = _fit_messages(msgs[:3], 100_000)
    assert not cut and json.loads(text) == msgs[:3]
    # the 0.5.1 shape: JSON cut mid-string
    old = json.dumps(msgs[:6], ensure_ascii=False)[:1800]
    back, cut = _load_messages(old)
    assert cut and 1 <= len(back) < 6 and back[0] == msgs[0]
    assert _load_messages("") == ([], False)
    assert _load_messages(json.dumps(msgs[:2])) == (msgs[:2], False)


def test_database_from_before_0_4_migrates(tmp_path):
    """A relay upgraded in place: the accounts table lacks the 0.4 columns and the
    partial unique index on invite_code must not be attempted before they exist."""
    import sqlite3

    from unibot_cloud.db import Database

    path = str(tmp_path / "old.db")
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE accounts (
            id TEXT PRIMARY KEY, id_hash TEXT NOT NULL UNIQUE, channel TEXT NOT NULL, hint TEXT NOT NULL,
            created_at INTEGER NOT NULL, disabled INTEGER NOT NULL DEFAULT 0, grant_tokens INTEGER NOT NULL DEFAULT 0,
            note TEXT NOT NULL DEFAULT ''
        );
        INSERT INTO accounts (id, id_hash, channel, hint, created_at) VALUES ('a1', 'h1', 'email', 'a***@x', 1);
        """
    )
    conn.commit()
    conn.close()
    db = Database(path)
    cols = {r["name"] for r in db._conn.execute("PRAGMA table_info(accounts)").fetchall()}
    assert {"invite_code", "invited_by", "credit_uy", "clips_bonus", "contribute"} <= cols
    names = {r["name"] for r in db._conn.execute("PRAGMA index_list(accounts)").fetchall()}
    assert "accounts_invite_code" in names
    assert db._conn.execute("SELECT invite_code, contribute FROM accounts WHERE id='a1'").fetchone()[0] == ""


def test_database_from_0_4_moves_to_the_lifetime_allowance(tmp_path):
    """0.4 kept a daily cap and credit beyond it; 0.5 has one pool. On the first open the
    service seeds every account with what it had spent plus the allowance, plus any 0.4
    credit still unused — nobody starts in debt, an invite's money is kept. A second open
    changes nothing, and accounts made afterwards start with the allowance alone."""
    import sqlite3

    path = str(tmp_path / "v04.db")
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE accounts (
            id TEXT PRIMARY KEY, id_hash TEXT NOT NULL UNIQUE, channel TEXT NOT NULL, hint TEXT NOT NULL,
            created_at INTEGER NOT NULL, granted INTEGER NOT NULL DEFAULT 0, used INTEGER NOT NULL DEFAULT 0,
            disabled INTEGER NOT NULL DEFAULT 0, identifier_enc TEXT NOT NULL DEFAULT '', unlimited INTEGER NOT NULL DEFAULT 0,
            password_hash TEXT NOT NULL DEFAULT '', password_set_at INTEGER, failed_logins INTEGER NOT NULL DEFAULT 0,
            locked_until INTEGER, invite_code TEXT NOT NULL DEFAULT '', invited_by TEXT NOT NULL DEFAULT '',
            invites INTEGER NOT NULL DEFAULT 0, credit_uy INTEGER NOT NULL DEFAULT 0, credit_used_uy INTEGER NOT NULL DEFAULT 0,
            clips_bonus INTEGER NOT NULL DEFAULT 0, contribute INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE ledger (
            id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL,
            model TEXT NOT NULL DEFAULT '', prompt_tokens INTEGER NOT NULL DEFAULT 0, completion_tokens INTEGER NOT NULL DEFAULT 0,
            charged INTEGER NOT NULL DEFAULT 0, request_id TEXT NOT NULL DEFAULT '', cost_uy INTEGER NOT NULL DEFAULT 0,
            extra TEXT NOT NULL DEFAULT ''
        );
        INSERT INTO accounts (id, id_hash, channel, hint, created_at, invites, credit_uy, credit_used_uy)
            VALUES ('heavy', 'h1', 'email', 'a***@x', 1, 1, 3000000, 400000);
        INSERT INTO accounts (id, id_hash, channel, hint, created_at) VALUES ('fresh', 'h2', 'email', 'b***@x', 2);
        INSERT INTO ledger (account_id, ts, kind, model, charged, cost_uy) VALUES ('heavy', 10, 'chat', 'm', 100, 12500000);
        INSERT INTO ledger (account_id, ts, kind, model, charged, cost_uy) VALUES ('heavy', 11, 'video', 'v', 100, 500000);
        INSERT INTO ledger (account_id, ts, kind, charged, extra) VALUES ('heavy', 12, 'credit', 0, '{"credit_uy":3000000}');
        """
    )
    conn.commit()
    conn.close()

    settings = Settings(database=path, secret="s", admin_token="admin", allowance_cny=10, signup_tokens=0)
    db = Database(path)
    assert "accounts.grant_uy" in db.added
    Cloud(settings, db, LogSender())
    grants = {r["id"]: (int(r["grant_uy"]), r["contribute_bonus_at"]) for r in db._conn.execute("SELECT * FROM accounts")}
    # heavy: ¥13 spent + ¥10 + ¥2.6 of unused 0.4 credit; fresh: ¥10 — and no bonus taken yet
    assert grants == {"heavy": (25_600_000, None), "fresh": (10_000_000, None)}
    db.close()

    db2 = Database(path)
    assert db2.added == set()
    Cloud(settings, db2, LogSender())
    assert {int(r["grant_uy"]) for r in db2._conn.execute("SELECT * FROM accounts")} == {25_600_000, 10_000_000}
    a = db2.create_account("h3", "email", "c***@x", 0, grant_uy=settings.allowance_uy)
    assert int(a["grant_uy"]) == 10_000_000
    db2.close()
