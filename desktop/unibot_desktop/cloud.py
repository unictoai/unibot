"""unibot Cloud from the desktop: sign in with a code, look at the account, and
the OpenAI-shaped chat call the agent loop runs on. Standard library only."""

from __future__ import annotations

import json
import platform
import urllib.error
import urllib.parse
import urllib.request

from . import __version__


class CloudError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


MESSAGES = {
    "bad_identifier": "Enter a mobile number or an e-mail address.",
    "code_wrong": "That code is not right.",
    "code_expired": "That code has expired; ask for a new one.",
    "code_too_often": "Too many codes were sent; wait a few minutes.",
    "not_invited": "This relay is private; that number or address is not on its list.",
    "send_failed": "The code could not be sent; try again in a moment.",
    "bad_key": "Sign in again.",
    "out_of_tokens": "This account has used its tokens.",
    "account_disabled": "This account is disabled.",
    "model_not_offered": "That model is not offered here.",
    "rate_limited": "Too many requests; slow down a little.",
    "daily_cap": "Today's allowance is used up; more tomorrow.",
    "upstream": "The model provider did not answer.",
    "upstream_unconfigured": "unibot Cloud has no model key configured.",
}


def describe(e: CloudError) -> str:
    return MESSAGES.get(e.code, e.message or e.code)


class Cloud:
    def __init__(self, base: str, api_key: str = "", timeout: float = 180.0):
        self.base = base.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    @property
    def hub_url(self) -> str:
        return self.base.replace("https://", "wss://", 1).replace("http://", "ws://", 1) + "/v1/hub"

    def _request(self, method: str, path: str, body: dict | None = None, token: str | None = None, timeout: float | None = None) -> dict:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("User-Agent", f"unibot-Desktop/{__version__} ({platform.system()})")
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        tok = token if token is not None else self.api_key
        if tok:
            req.add_header("Authorization", f"Bearer {tok}")
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                raw = r.read()
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                err = json.loads(raw).get("error", {})
            except ValueError:
                err = {}
            raise CloudError(e.code, str(err.get("code") or f"http_{e.code}"), str(err.get("message") or raw[:200].decode("utf-8", "replace"))) from None
        except urllib.error.URLError as e:
            raise CloudError(0, "offline", f"Cannot reach {self.base}: {e.reason}") from None
        except TimeoutError:
            raise CloudError(0, "timeout", f"{self.base} did not answer in time") from None

    # -- account -------------------------------------------------------------------

    def request_code(self, identifier: str) -> None:
        self._request("POST", "/v1/auth/code", {"identifier": identifier}, token="", timeout=30)

    def verify(self, identifier: str, code: str, device: str) -> dict:
        """→ {api_key, account{channel,hint}, tokens{…}, models[…]}"""
        return self._request("POST", "/v1/auth/verify", {"identifier": identifier, "code": code, "device": device}, token="", timeout=30)

    def me(self) -> dict:
        return self._request("GET", "/v1/me", timeout=30)

    def sign_out(self) -> None:
        self._request("POST", "/v1/auth/sign-out", {}, timeout=30)

    def devices(self) -> list[dict]:
        return self._request("GET", "/v1/devices", timeout=30).get("devices", [])

    def recommended_model(self, me: dict | None = None) -> str:
        models = (me or self.me()).get("models", [])
        chat = [m for m in models if (m.get("architecture") or {}).get("output_modalities", ["text"]) == ["text"]]
        for m in chat:
            if (m.get("unibot") or {}).get("recommended"):
                return m["id"]
        return chat[0]["id"] if chat else "qwen3.8-27b"

    # -- the model -------------------------------------------------------------------

    def chat(self, model: str, messages: list[dict], tools: list[dict] | None = None, **extra) -> dict:
        body: dict = {"model": model, "messages": messages, "stream": False}
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        body.update(extra)
        return self._request("POST", "/v1/chat/completions", body)
