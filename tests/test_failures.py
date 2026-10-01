"""The sentence a failed run leaves in the chat (unibot/server/failures.py)."""

from __future__ import annotations

import httpx
import openai

from unibot.server.failures import describe_failure, failure_notice


def _status_error(cls, status: int, body: dict):
    request = httpx.Request("POST", "https://relay.test/v1/chat/completions")
    response = httpx.Response(status, request=request, json={"error": body})
    return cls(body.get("message", "no"), response=response, body=body)


def test_relay_refusals_are_named_by_code():
    exc = _status_error(
        openai.RateLimitError, 429, {"code": "daily_cap", "message": "Today's share …"}
    )
    code, text = describe_failure(exc)
    assert code == "allowance" and "Connections" in text and "midnight" in text

    exc = _status_error(
        openai.PermissionDeniedError, 402, {"code": "out_of_tokens", "message": "…"}
    )
    assert describe_failure(exc)[0] == "allowance"

    # the streaming path: openai.APIError with the relay's body and no HTTP status
    exc = openai.APIError(
        "The relay's model provider refused its key", request=None, body={"code": "upstream_auth"}
    )  # type: ignore[arg-type]
    code, text = describe_failure(exc)
    assert code == "relay" and "operator" in text

    exc = openai.APIError("busy", request=None, body={"code": "upstream_503", "message": "x"})  # type: ignore[arg-type]
    assert describe_failure(exc)[0] == "provider"


def test_an_exhausted_allowance_carries_the_three_ways_on():
    """relay 0.5: ``429 allowance_exhausted`` says what is left and where invite,
    co-creation and one's own key lead; the notice passes that on for the card."""
    body = {
        "code": "allowance_exhausted",
        "message": "Your free allowance (¥10) is used up. …",
        "left": 0,
        "grant": 10,
        "invite_url": "https://relay.example/web/?invite=ABCD2345",
        "invite_bonus_cny": 5,
        "contribute_bonus_available": True,
        "contribute_bonus_cny": 10,
        "own_key_docs": "https://github.com/unictoai/unibot/blob/main/docs/own-key.md",
        "type": "unibot_cloud",
    }
    exc = _status_error(openai.RateLimitError, 429, body)
    code, text = describe_failure(exc)
    assert code == "allowance" and "co-creation" in text and "keep working" in text
    notice = failure_notice(exc, "t1")
    assert notice["code"] == "allowance" and notice["allowance"] == {
        "left": 0,
        "grant": 10,
        "invite_url": "https://relay.example/web/?invite=ABCD2345",
        "invite_bonus_cny": 5,
        "contribute_bonus_available": True,
        "contribute_bonus_cny": 10,
        "own_key_docs": "https://github.com/unictoai/unibot/blob/main/docs/own-key.md",
    }
    # any other failure carries no such block
    other = _status_error(openai.RateLimitError, 429, {"code": "rate_limited", "message": "slow"})
    assert "allowance" not in failure_notice(other, "t1")


def test_own_key_failures():
    exc = _status_error(openai.AuthenticationError, 401, {"message": "Incorrect API key provided"})
    code, text = describe_failure(exc)
    assert code == "key" and "Incorrect API key" not in text

    exc = _status_error(
        openai.BadRequestError,
        400,
        {"message": "This model's maximum context length is 32768 tokens"},
    )
    assert describe_failure(exc)[0] == "too_long"

    exc = _status_error(openai.BadRequestError, 400, {"message": "unknown parameter foo"})
    code, text = describe_failure(exc)
    assert code == "request" and "unknown parameter foo" in text

    assert (
        describe_failure(openai.APITimeoutError(httpx.Request("POST", "https://x")))[0] == "timeout"
    )
    assert (
        describe_failure(openai.APIConnectionError(request=httpx.Request("POST", "https://x")))[0]
        == "network"
    )
    assert (
        describe_failure(_status_error(openai.NotFoundError, 404, {"message": "model not found"}))[
            0
        ]
        == "model"
    )


def test_unknown_failures_keep_the_type_and_first_line():
    code, text = describe_failure(ValueError("first line\nsecond line"))
    assert code == "unknown" and text == "Something went wrong: ValueError: first line"
    notice = failure_notice(ValueError("boom"), "t1")
    assert notice["type"] == "notice" and notice["level"] == "error" and notice["thread"] == "t1"
    assert notice["code"] == "unknown" and notice["detail"] == "ValueError: boom"


def test_timeline_writes_are_coalesced_on_a_loop_and_immediate_off_one(tmp_path):
    import asyncio
    import json

    from unibot.server.events import Timeline

    path = tmp_path / "t.json"
    off = Timeline("t", path)
    off.add({"type": "user", "text": "now"})
    assert json.loads(path.read_text("utf-8"))["events"][0]["text"] == "now"

    async def burst() -> None:
        tl = Timeline("t", path)
        for i in range(20):
            tl.add({"type": "user", "text": f"e{i}"})
        # nothing written yet: the burst is still being coalesced
        assert "e19" not in path.read_text("utf-8")
        await asyncio.sleep(0.8)
        assert "e19" in path.read_text("utf-8")
        tl.add({"type": "user", "text": "last"})
        tl.flush()  # shutdown path: written at once, the scheduled write dropped
        assert "last" in path.read_text("utf-8")

    asyncio.run(burst())


# ----------------------------------------------------------------------------- update check
def test_update_check_versions_and_opt_out(monkeypatch):
    from unibot.server import update

    assert update.parse_version("v0.1.21") == (0, 1, 21)
    assert update.parse_version("0.2.0-rc1") == (0, 2, 0)
    assert update.parse_version("nightly") == ()
    assert update.newer_than("0.1.21", "0.1.20") and not update.newer_than("0.1.20", "0.1.20")
    assert not update.newer_than("nightly", "0.1.20")
    monkeypatch.delenv("UNIBOT_CLOUD_KEY", raising=False)
    monkeypatch.delenv("UNIBOT_NO_UPDATE_CHECK", raising=False)
    assert update.enabled(True) and not update.enabled(False)
    monkeypatch.setenv("UNIBOT_NO_UPDATE_CHECK", "1")
    assert not update.enabled(True)
    monkeypatch.delenv("UNIBOT_NO_UPDATE_CHECK")
    monkeypatch.setenv(
        "UNIBOT_CLOUD_KEY", "nm_hosted"
    )  # a hosted web session: the operator updates
    assert not update.enabled(True)


def test_update_check_caches_and_survives_failures(monkeypatch):
    import asyncio
    import time

    from unibot.server import update

    monkeypatch.delenv("UNIBOT_CLOUD_KEY", raising=False)
    monkeypatch.delenv("UNIBOT_NO_UPDATE_CHECK", raising=False)
    calls = 0
    real_check = update.UpdateCheck._check

    async def fake_check(self: update.UpdateCheck) -> None:
        nonlocal calls
        calls += 1
        self.checked_at = time.monotonic()
        self.latest = "0.1.21"
        self.url = "https://github.com/unictoai/unibot/releases/tag/v0.1.21"

    monkeypatch.setattr(update.UpdateCheck, "_check", fake_check)
    check = update.UpdateCheck(True, current="0.1.20")
    first = asyncio.run(check.view())
    assert first["newer"] is True and first["latest"] == "0.1.21" and first["enabled"] is True
    asyncio.run(check.view())
    assert calls == 1  # the second look is answered from the cache

    monkeypatch.setattr(
        update.UpdateCheck, "_check", real_check
    )  # the real one, with a client that fails

    class Boom:
        def __init__(self, *a, **k): ...

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, *a, **k):
            raise update.httpx.ConnectError("no network")

    monkeypatch.setattr(update.httpx, "AsyncClient", Boom)
    check = update.UpdateCheck(True, current="0.1.20")
    view = asyncio.run(check.view())
    assert view["newer"] is False and view["latest"] is None and view["error"] == "ConnectError"
    assert not asyncio.run(update.UpdateCheck(False, current="0.1.20").view())["enabled"]
