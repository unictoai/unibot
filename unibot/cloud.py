"""unibot Cloud from the runtime: sign in with a code, look at the account, the models
the relay offers, and the hub's address.

The relay (``cloud/`` in this repository, ``docs/cloud.md``) is one more OpenAI-compatible
provider to the agent — ``<base_url>/v1`` with the account key — so signing in here is the
same first step the phone offers: *Sign in — free*, with a phone number or an e-mail. The key is kept in the
vault under :data:`CLOUD_KEY`; nothing here ever shows it to the model.
"""

from __future__ import annotations

import platform
from typing import Any

import httpx

from unibot import __version__

CLOUD_KEY = "UNIBOT_CLOUD_KEY"
DEFAULT_MODEL = "qwen3.8-27b"

MESSAGES = {
    "bad_identifier": "Enter a mainland phone number or an e-mail address.",
    "phone_region": "Codes reach mainland China numbers only for now; elsewhere, sign in with an e-mail address.",
    "code_wrong": "That code is not right.",
    "code_expired": "That code has expired; ask for a new one.",
    "code_too_often": "Too many codes were sent; wait a few minutes.",
    "not_invited": "This relay is private; that address is not on its list.",
    "send_failed": "The code could not be sent; try again in a moment.",
    "bad_key": "Sign in again.",
    "out_of_tokens": "This account has used its tokens.",
    "account_disabled": "This account is disabled.",
    "model_not_offered": "That model is not offered here.",
    "rate_limited": "Too many requests; slow down a little.",
    "allowance_exhausted": "The free allowance is used up. Invite a friend (+¥5), join the co-creation programme (+¥10), or add your own model key — your sign-in and your devices keep working either way.",
    "daily_cap": "Today's token quota is used up; it comes back tomorrow.",
    "upstream": "The model provider did not answer.",
    "upstream_unconfigured": "unibot Cloud has no model key configured.",
    "offline": "unibot Cloud cannot be reached.",
    "bad_credentials": "That address and password do not match.",
    "no_password": "This account has no password yet; sign in with a code and set one under Account.",
    "locked": "Too many wrong passwords; wait a while or sign in with a code.",
    "password_short": "Use at least 8 characters.",
    "password_weak": "Choose a stronger password.",
    "password_wrong": "That is not the current password.",
    "password_required": "Enter the current password.",
    "no_session": "That sign-in is already gone.",
}


class CloudError(Exception):
    def __init__(self, status: int, code: str, message: str, extra: dict[str, Any] | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        # what the relay said beside the words (an ``allowance_exhausted`` carries what is
        # left, the invite link, the own-key guide)
        self.extra: dict[str, Any] = dict(extra or {})

    def describe(self) -> str:
        return MESSAGES.get(self.code, self.message or self.code)


def hub_url(base_url: str) -> str:
    base = base_url.rstrip("/")
    return base.replace("https://", "wss://", 1).replace("http://", "ws://", 1) + "/v1/hub"


def model_url(base_url: str) -> str:
    return base_url.rstrip("/") + "/v1"


class CloudClient:
    """The relay's account API. One instance per relay; the key may change (sign in/out)."""

    def __init__(self, base_url: str, api_key: str = "", timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self._client = httpx.AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": f"unibot/{__version__} ({platform.system()})",
                "Accept": "application/json",
            },
        )

    @property
    def hub_url(self) -> str:
        return hub_url(self.base_url)

    async def close(self) -> None:
        await self._client.aclose()

    async def _request(
        self, method: str, path: str, body: dict[str, Any] | None = None, token: str | None = None
    ) -> dict[str, Any]:
        headers = {}
        tok = self.api_key if token is None else token
        if tok:
            headers["Authorization"] = f"Bearer {tok}"
        try:
            response = await self._client.request(
                method, self.base_url + path, json=body, headers=headers
            )
        except httpx.TimeoutException:
            raise CloudError(0, "timeout", f"{self.base_url} did not answer in time") from None
        except httpx.HTTPError as exc:
            raise CloudError(0, "offline", f"Cannot reach {self.base_url}: {exc}") from None
        if response.status_code >= 400:
            try:
                body_err = response.json()
            except ValueError:
                body_err = None
            # the error shape is not guaranteed: {"error": "bad key"} (a string)
            # or a bare list both used to raise AttributeError here, escaping as
            # a non-CloudError the callers don't catch
            err = body_err.get("error") if isinstance(body_err, dict) else None
            if not isinstance(err, dict):
                err = {"message": str(err or "")}
            raise CloudError(
                response.status_code,
                str(err.get("code") or f"http_{response.status_code}"),
                str(err.get("message") or response.text[:200]),
                extra={k: v for k, v in err.items() if k not in ("code", "message", "type")}
                if isinstance(err, dict)
                else None,
            )
        if not response.content.strip():
            return {}
        try:
            data = response.json()
        except ValueError as exc:
            # a 2xx HTML body (captive portal, proxy) is not JSON at all
            raise CloudError(
                response.status_code, "bad_response", f"expected JSON: {exc}"
            ) from exc
        return data if isinstance(data, dict) else {"data": data}

    # ------------------------------------------------------------------ account
    async def request_code(self, identifier: str) -> None:
        await self._request("POST", "/v1/auth/code", {"identifier": identifier}, token="")

    async def verify(
        self, identifier: str, code: str, device: str, invite: str = ""
    ) -> dict[str, Any]:
        """→ ``{api_key, account{channel,hint}, tokens{…}, models[…]}``; the key is kept on
        this client from then on. ``invite`` is a friend's code; it counts for a new account
        only and the relay ignores it otherwise."""
        body: dict[str, Any] = {"identifier": identifier, "code": code, "device": device}
        if invite.strip():
            body["invite"] = invite.strip()
        data = await self._request("POST", "/v1/auth/verify", body, token="")
        key = str(data.get("api_key") or "")
        if key:
            self.api_key = key
        return data

    async def login(self, identifier: str, password: str, device: str) -> dict[str, Any]:
        """The password way in, for accounts that set one; same reply as ``verify``."""
        data = await self._request(
            "POST",
            "/v1/auth/login",
            {"identifier": identifier, "password": password, "device": device},
            token="",
        )
        key = str(data.get("api_key") or "")
        if key:
            self.api_key = key
        return data

    async def set_password(self, password: str, current: str | None = None) -> None:
        """Set or change the account password (``current`` when one exists, unless this key
        came from a code sign-in just now); an empty password with ``current`` removes it."""
        body: dict[str, Any] = {"password": password}
        if current is not None:
            body["current"] = current
        await self._request("POST", "/v1/auth/password", body)

    async def me(self) -> dict[str, Any]:
        return await self._request("GET", "/v1/me")

    async def profile(self, with_face: bool = True) -> dict[str, Any]:
        """The agent's name and look as the account's devices share it (``rev`` 0 = none yet);
        without the face's pictures when ``with_face`` is false."""
        return await self._request("GET", "/v1/me/profile" + ("" if with_face else "?face=false"))

    async def put_profile(self, body: dict[str, Any]) -> dict[str, Any]:
        """This device's name and look for the account (last writer wins); the new ``rev``."""
        return await self._request("PUT", "/v1/me/profile", body)

    async def set_contribute(self, on: bool) -> dict[str, Any]:
        """Keep (or stop keeping) this account's chat turns for the community's model."""
        return await self._request("POST", "/v1/me/contribute", {"on": on})

    async def delete_samples(self) -> int:
        data = await self._request("DELETE", "/v1/me/samples")
        return int(data.get("deleted") or 0)

    async def sessions(self) -> list[dict[str, Any]]:
        data = await self._request("GET", "/v1/me/sessions")
        sessions = data.get("sessions")
        return sessions if isinstance(sessions, list) else []

    async def revoke_session(self, prefix: str) -> None:
        await self._request("DELETE", f"/v1/me/sessions/{prefix}")

    async def sign_out_all(self, everything: bool = False) -> int:
        data = await self._request("POST", "/v1/auth/sign-out-all", {"all": everything})
        if everything:
            self.api_key = ""
        return int(data.get("signed_out") or 0)

    async def events(self, limit: int = 50) -> list[dict[str, Any]]:
        data = await self._request("GET", f"/v1/me/events?limit={int(limit)}")
        events = data.get("events")
        return events if isinstance(events, list) else []

    async def sign_out(self) -> None:
        try:
            await self._request("POST", "/v1/auth/sign-out", {})
        finally:
            self.api_key = ""

    async def delete_account(self) -> None:
        try:
            await self._request("POST", "/v1/auth/delete", {})
        finally:
            self.api_key = ""

    async def devices(self) -> list[dict[str, Any]]:
        data = await self._request("GET", "/v1/devices")
        devices = data.get("devices")
        return devices if isinstance(devices, list) else []

    async def models(self) -> list[dict[str, Any]]:
        data = await self._request("GET", "/v1/models")
        models = data.get("data")
        return models if isinstance(models, list) else []

    async def estimate(
        self,
        images: int = 0,
        image_model: str = "",
        size: str = "",
        clips: int = 0,
        video_model: str = "",
    ) -> dict[str, Any]:
        """What ``images`` pictures and ``clips`` short videos would cost against the pool
        (``cny``, ``left_cny``, ``affordable``, ``unlimited``); nothing is charged."""
        q = f"/v1/estimate?images={int(images)}"
        if image_model:
            q += f"&image_model={image_model}"
        if size:
            q += f"&size={size}"
        if clips:
            q += f"&clips={int(clips)}"
            if video_model:
                q += f"&video_model={video_model}"
        return await self._request("GET", q)

    @staticmethod
    def recommended_model(models: list[dict[str, Any]]) -> str:
        """The relay's recommended chat model, else the default when it is offered, else the
        first chat model."""
        chat = [
            m
            for m in models
            if (m.get("architecture") or {}).get("output_modalities", ["text"]) == ["text"]
        ]
        for m in chat:
            if (m.get("unibot") or {}).get("recommended"):
                return str(m["id"])
        if any(m.get("id") == DEFAULT_MODEL for m in chat):
            return DEFAULT_MODEL
        return str(chat[0]["id"]) if chat else DEFAULT_MODEL


__all__ = [
    "CLOUD_KEY",
    "DEFAULT_MODEL",
    "MESSAGES",
    "CloudClient",
    "CloudError",
    "hub_url",
    "model_url",
]
