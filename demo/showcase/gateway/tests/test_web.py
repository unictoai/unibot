"""unibot Web: sign in with a code, get a kept Muse, come back to it."""

from __future__ import annotations

from dataclasses import replace

import httpx
import pytest

from showcase_gateway.accounts import AccountManager, AccountStore
from showcase_gateway.app import create_app
from showcase_gateway.sessions import SessionManager
from showcase_gateway.trials import TrialManager, TrialStore

from .conftest import Clock, FakeRunner, Upstream, body, make_settings


@pytest.fixture
def web():
    settings = make_settings()
    runner = FakeRunner()
    upstream = Upstream()
    clock = Clock()
    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream.handler))
    manager = SessionManager(settings, runner, http=client, clock=clock)
    trials = TrialManager(settings, TrialStore(":memory:"), clock=clock)
    accounts = AccountManager(settings, runner, AccountStore(":memory:"), http=client, clock=clock)
    app = create_app(settings, manager, client=client, trials=trials, accounts=accounts)
    return settings, runner, upstream, clock, accounts, app


async def client_for(app):
    transport = httpx.ASGITransport(app=app)
    return httpx.AsyncClient(transport=transport, base_url="http://localhost:8000")


async def sign_in(c, ident: str, ip: str = "1.2.3.4"):
    r = await c.post("/api/web/code", json={"identifier": ident}, headers={"x-forwarded-for": ip})
    assert r.status_code == 204, r.text
    return await c.post(
        "/api/web/verify",
        json={"identifier": ident, "code": "246810"},
        headers={"x-forwarded-for": ip},
    )


async def test_the_page_and_a_first_sign_in(web):
    settings, runner, upstream, clock, accounts, app = web
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        r = await c.get("/web/")
        assert r.status_code == 200 and "unibot Web" in r.text and "/api/web/verify" in r.text
        r = await c.get("/web", follow_redirects=False)
        assert r.status_code == 308 and r.headers["location"] == "/web/"

        # a wrong code is the relay's message, passed on
        r = await c.post("/api/web/code", json={"identifier": "someone@example.com"})
        assert r.status_code == 204
        r = await c.post(
            "/api/web/verify", json={"identifier": "someone@example.com", "code": "000000"}
        )
        assert r.status_code == 400 and body(r)["error"] == "code_wrong"

        r = await sign_in(c, "someone@example.com")
        assert r.status_code == 200, r.text
        assert upstream.invites[-1] == ""  # none given, none sent
        me = body(r)
        token = me["url"].split("token=")[1]
        assert me["slug"].startswith("w") and len(me["slug"]) == 12
        assert me["url"] == f"http://{me['slug']}.s.localhost:8000/?token={token}"
        assert me["running"] is True and me["channel"] == "email"
        # the relay saw the visitor's address, not the gateway's
        code_calls = [x for x in upstream.calls if x.url.path == "/v1/auth/code"]
        assert code_calls[-1].headers["x-forwarded-for"] == "1.2.3.4"
        # the container: kept, on the web network, signed in with the account's own key
        kept = runner.kept[f"nmw-{me['slug']}"]
        env = kept["env"]
        assert env["UNIBOT_CLOUD_KEY"] == "nm_key1"
        assert env["UNIBOT_CLOUD_BASE_URL"] == "http://relay:8787"
        assert env["UNIBOT_HUB_NAME"] == "Web" and env["UNIBOT_ONBOARDED"] == "1"
        assert env["UNIBOT_SERVER_TOKEN"] == token
        assert "UNIBOT_LLM_BASE_URL" not in env  # the runtime makes the Cloud its model itself
        assert kept["network"] == "web-net" and kept["image"] == "unibot:web"
        assert set(kept["volumes"].values()) == {"/data", "/workspace", "/home/muse"}
        # and the browser's traffic on the account host reaches it
        host = me["origin"].split("//")[1]
        r = await c.get("/api/state", headers={"host": host})
        assert r.status_code == 200 and r.text == "container says /api/state"
        info = body(await c.get("/api/demo/info"))
        assert info["web"] == {
            "enabled": True,
            "accounts": 1,
            "running": 1,
            "max_accounts": 2,
            "max_running": 1,
        }


async def test_coming_back_wakes_the_same_muse(web):
    settings, runner, upstream, clock, accounts, app = web
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        me = body(await sign_in(c, "someone@example.com"))
        name = f"nmw-{me['slug']}"
        host = me["origin"].split("//")[1]

        # quiet for long enough: the container is stopped, not removed
        clock.now += settings.web_idle_stop_s + 1
        await accounts.reap_once()
        assert runner.kept[name]["running"] is False and name in runner.kept
        assert accounts.stats()["running"] == 0

        # a request on its host wakes it — same slug, same token, same volumes
        r = await c.get("/api/state", headers={"host": host})
        assert r.status_code == 200 and runner.kept[name]["running"] is True
        assert runner.kept[name]["starts"] == 2

        # signing in again from another browser: the relay issues a fresh key, so the
        # container is recreated with it; slug and token stay
        again = body(await sign_in(c, "someone@example.com", ip="9.9.9.9"))
        assert again["slug"] == me["slug"] and again["url"] == me["url"]
        assert runner.kept[name]["env"]["UNIBOT_CLOUD_KEY"] == "nm_key2"
        assert runner.kept[name]["starts"] == 1  # a new container


async def test_a_restarted_gateway_finds_the_running_muses(web):
    settings, runner, upstream, clock, accounts, app = web
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        me = body(await sign_in(c, "someone@example.com"))
    # a new gateway process over the same store and runner
    fresh = AccountManager(settings, runner, accounts.store, http=accounts.http, clock=clock)
    await fresh.startup()
    assert fresh.stats()["running"] == 1
    live = fresh.live[me["slug"]]
    assert live.address == "10.0.1.1" and live.token == me["url"].split("token=")[1]


async def test_the_caps(web):
    settings, runner, upstream, clock, accounts, app = web
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        first = body(await sign_in(c, "a@example.com"))
        # one running at a time (web_max_running=1): a second account's Muse needs the first
        # to have been quiet for a while
        r = await sign_in(c, "b@example.com", ip="2.2.2.2")
        assert r.status_code == 503 and body(r)["error"] == "web_busy"
        clock.now += 600
        r = await sign_in(c, "b@example.com", ip="2.2.2.2")
        assert r.status_code == 200, r.text
        assert runner.kept[f"nmw-{first['slug']}"]["running"] is False
        # and no more than two accounts here
        clock.now += 600
        r = await sign_in(c, "c@example.com", ip="3.3.3.3")
        assert r.status_code == 503 and body(r)["error"] == "web_full"
        # an unknown host is still "ended"
        r = await c.get("/api/state", headers={"host": "wnotanaccount.s.localhost:8000"})
        assert r.status_code == 404


async def test_switched_off(web):
    settings, runner, upstream, clock, accounts, app = web
    off = replace(settings, web_enabled=False)
    manager = SessionManager(off, runner, http=accounts.http, clock=clock)
    app = create_app(off, manager, client=accounts.http)
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        assert (await c.get("/web/")).status_code == 404
        r = await c.post("/api/web/code", json={"identifier": "a@example.com"})
        assert r.status_code == 404 and body(r)["error"] == "web_off"


async def test_an_invite_code_is_passed_on_to_the_relay(web):
    settings, runner, upstream, clock, accounts, app = web
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        r = await c.get("/web/?invite=abcd2345")
        assert r.status_code == 200 and 'id="invite"' in r.text
        assert "¥" not in r.text  # no amounts on the way in; the account page has them
        assert "Mainland China phone number or e-mail" in r.text and "/api/web/login" in r.text
        assert "phone_region" in r.text  # the relay's refusal of an overseas number, in Chinese too
        r = await c.post("/api/web/code", json={"identifier": "invited@example.com"})
        assert r.status_code == 204
        r = await c.post(
            "/api/web/verify",
            json={"identifier": "invited@example.com", "code": "246810", "invite": "ABCD2345"},
        )
        assert r.status_code == 200, r.text
        assert upstream.invites[-1] == "ABCD2345"
        r = await c.post(
            "/api/web/verify",
            json={"identifier": "invited@example.com", "code": "246810", "invite": "x" * 33},
        )
        assert r.status_code == 422


async def test_the_password_way_in(web):
    settings, runner, upstream, clock, accounts, app = web
    async with app.router.lifespan_context(app):
        c = await client_for(app)
        # the relay's refusals are passed on: no password yet, wrong password
        r = await c.post("/api/web/login", json={"identifier": "13800138000", "password": "x"})
        assert r.status_code == 400 and body(r)["error"] == "no_password"
        r = await c.post(
            "/api/web/login", json={"identifier": "someone@example.com", "password": "nope"}
        )
        assert r.status_code == 400 and body(r)["error"] == "password_wrong"
        r = await c.post(
            "/api/web/login", json={"identifier": "someone@example.com", "password": ""}
        )
        assert r.status_code == 422

        r = await c.post(
            "/api/web/login",
            json={"identifier": "someone@example.com", "password": "correct horse"},
        )
        assert r.status_code == 200, r.text
        me = body(r)
        assert me["slug"].startswith("w") and "token=" in me["url"]
        assert upstream.calls[-1].url.path != "/v1/auth/code"  # no code was asked for
        # the same account by code lands on the same Muse
        r2 = await sign_in(c, "someone@example.com")
        assert body(r2)["slug"] == me["slug"]
