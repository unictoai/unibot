from __future__ import annotations

import json
from dataclasses import replace

import httpx
import pytest

from showcase_gateway.app import create_app
from showcase_gateway.config import Lane, Settings
from showcase_gateway.sessions import SessionManager
from showcase_gateway.trials import TrialManager, TrialStore


class FakeRunner:
    """Containers that exist only as names; every one comes up at 10.0.0.<n>."""

    def __init__(self) -> None:
        self.running: dict[str, dict[str, str]] = {}
        self.stopped: list[str] = []

    async def start(self, name: str, env: dict[str, str]) -> str:
        self.running[name] = env
        return f"10.0.0.{len(self.running) + 1}"

    async def stop(self, name: str) -> None:
        self.running.pop(name, None)
        self.stopped.append(name)

    async def leftovers(self) -> list[str]:
        return []

    async def gateway_address(self) -> str | None:
        return "10.0.0.1"

    # kept containers (unibot Web): created once, then stopped and started by name
    @property
    def kept(self) -> dict[str, dict]:
        if not hasattr(self, "_kept"):
            self._kept: dict[str, dict] = {}
        return self._kept

    async def start_persistent(self, name, env, *, volumes, network, memory, cpus, pids, image):
        c = self.kept.get(name)
        if c is None:
            c = self.kept[name] = {
                "env": env,
                "volumes": volumes,
                "network": network,
                "image": image,
                "address": f"10.0.1.{len(self.kept) + 1}",
                "starts": 0,
            }
        c["running"] = True
        c["starts"] += 1
        return c["address"]

    async def stop_only(self, name) -> None:
        if name in self.kept:
            self.kept[name]["running"] = False

    async def remove(self, name) -> None:
        self.kept.pop(name, None)

    async def address_of(self, name) -> str | None:
        c = self.kept.get(name)
        return c["address"] if c and c.get("running") else None


class Clock:
    def __init__(self) -> None:
        self.now = 1_700_000_000.0

    def __call__(self) -> float:
        return self.now


def make_settings(**over) -> Settings:
    base = Settings.from_env()
    values = dict(
        public_scheme="http",
        site_host="localhost",
        session_domain="s.localhost",
        public_port=":8000",
        trust_proxy=True,
        site_dir="",
        cdn_dir="",
        image="unibot:test",
        internal_url="",
        session_ttl_s=600,
        idle_ttl_s=120,
        start_timeout_s=5,
        max_sessions=3,
        per_ip_active=1,
        per_ip_daily=3,
        main=Lane("openai", "demo-model", "https://models.example", "sk-demo"),
        gui=Lane("openai", "gui-model", "https://gui.example", "sk-gui"),
        session_requests=3,
        session_tokens=1000,
        daily_requests=100,
        daily_tokens=100_000,
        byok_hosts=("models.example", "byok.example", "localhost"),
        trial_enabled=True,
        trial_db=":memory:",
        trial_tokens=1000,
        trial_per_ip_daily=2,
        trial_daily_new=3,
        trial_daily_tokens=100_000,
        trial_rpm=5,
        web_enabled=True,
        web_relay_url="https://cloud.example",
        web_relay_internal_url="http://relay:8787",
        web_network="web-net",
        web_db=":memory:",
        web_image="unibot:web",
        web_max_accounts=2,
        web_max_running=1,
        web_idle_stop_s=3600,
    )
    values.update(over)
    return replace(base, **values)


class Wire(httpx.AsyncByteStream):
    """A response body that is still on the wire, as the gateway sees real ones."""

    def __init__(self, data: bytes) -> None:
        self.data = data

    async def __aiter__(self):
        yield self.data


def wire(status: int, text: str, **headers: str) -> httpx.Response:
    return httpx.Response(status, stream=Wire(text.encode()), headers=headers)


class Upstream:
    """Stands in for the containers (``/api/health``, the app) and the model providers."""

    def __init__(self) -> None:
        self.calls: list[httpx.Request] = []
        self.usage_total = 10
        self.stream = False
        self.codes: dict[str, str] = {}  # the relay's: identifier → code
        self.keys_issued = 0
        self.invites: list[str] = []  # the invite field of each verify, "" when none

    def relay(self, request: httpx.Request) -> httpx.Response:
        """A little unibot Cloud: any identifier gets the code 246810."""
        data = json.loads(request.content or b"{}")
        ident = str(data.get("identifier", "")).lower()
        if request.url.path == "/v1/auth/code":
            if "@" not in ident and not ident.isdigit():
                return wire(
                    400,
                    json.dumps(
                        {
                            "error": {
                                "code": "bad_identifier",
                                "message": "Enter a mobile number or an e-mail address",
                            }
                        }
                    ),
                )
            self.codes[ident] = "246810"
            return httpx.Response(204)
        if request.url.path == "/v1/auth/login":
            # the password way: one account has a password, the others say so
            if ident != "someone@example.com" or data.get("password") != "correct horse":
                code = "password_wrong" if ident == "someone@example.com" else "no_password"
                return wire(400, json.dumps({"error": {"code": code, "message": "No."}}))
        elif request.url.path == "/v1/auth/verify":
            if self.codes.get(ident) != data.get("code"):
                return wire(
                    400,
                    json.dumps(
                        {"error": {"code": "code_wrong", "message": "That code is not right"}}
                    ),
                )
        if request.url.path in ("/v1/auth/verify", "/v1/auth/login"):
            self.keys_issued += 1
            self.invites.append(str(data.get("invite") or ""))
            account = {
                "id": "acct-" + ident.replace("@", "-at-"),
                "channel": "email" if "@" in ident else "sms",
                "hint": ident[:2] + "…",
                "created_at": 1_700_000_000,
                "member": False,
            }
            return wire(
                200,
                json.dumps(
                    {
                        "api_key": f"nm_key{self.keys_issued}",
                        "created": self.keys_issued == 1,
                        "account": account,
                    }
                ),
                **{"content-type": "application/json"},
            )
        return wire(404, "{}")

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if request.url.path == "/api/health":
            return wire(200, '{"ok": true}', **{"content-type": "application/json"})
        if request.url.host.startswith("10.0."):
            return wire(200, f"container says {request.url.path}", **{"x-upstream": "yes"})
        if request.url.host == "cloud.example":
            return self.relay(request)
        # a model provider
        if self.stream:
            body = (
                'data: {"choices":[{"delta":{"content":"hi"}}]}\n\n'
                f'data: {{"choices":[],"usage":{{"total_tokens":{self.usage_total}}}}}\n\n'
                "data: [DONE]\n\n"
            )
            return wire(200, body, **{"content-type": "text/event-stream"})
        return wire(
            200,
            json.dumps(
                {
                    "choices": [{"message": {"content": "hi"}}],
                    "usage": {"total_tokens": self.usage_total},
                }
            ),
            **{"content-type": "application/json"},
        )


@pytest.fixture
def world():
    settings = make_settings()
    runner = FakeRunner()
    upstream = Upstream()
    clock = Clock()
    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream.handler))
    manager = SessionManager(settings, runner, http=client, clock=clock)
    manager.resolve = lambda host, port: ["93.184.216.34"]
    trials = TrialManager(settings, TrialStore(":memory:"), clock=clock)
    app = create_app(settings, manager, client=client, trials=trials)
    return settings, runner, upstream, clock, manager, app


def body(resp: httpx.Response) -> dict:
    return json.loads(resp.content)
