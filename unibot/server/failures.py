"""What to tell the person when a run dies on an exception.

A chat turn that fails used to surface as ``Something went wrong: APIError: Incorrect API
key provided …`` — the provider's words, about a key the person may never have seen (the
relay's). This module turns the exception into two things: a short **code** the apps can
act on (``allowance``, ``key``, ``network`` …) and one **English sentence** that says what
happened and where to go, which the web app translates with its own dictionary (the
sentence is the key, so the wording here must match ``web/src/i18n/zh-CN.ts``).
"""

from __future__ import annotations

import asyncio
from typing import Any

import httpx
import openai

_TOO_LONG = (
    "context length",
    "context_length",
    "maximum context",
    "too many tokens",
    "token limit",
    "too long",
)

# The relay's own refusals (cloud/unibot_cloud/service.py, api.py) by their error code.
_RELAY: dict[str, tuple[str, str]] = {
    "allowance_exhausted": (
        "allowance",
        "The free allowance is used up. Invite a friend (+¥5), join the co-creation programme (+¥10), or add your own model key under Connections — your sign-in and your devices keep working either way.",
    ),
    "daily_cap": (
        "allowance",
        "Today's share of the free allowance is used up; it comes back at midnight, Beijing time. Your own model key under Connections keeps you going now.",
    ),
    "out_of_tokens": (
        "allowance",
        "The account's free allowance is used up. Add your own model key under Connections to keep going.",
    ),
    "rate_limited": ("busy", "Too many requests at once; wait a moment and try again."),
    "upstream_busy": ("busy", "The model provider is busy; try again in a moment."),
    "upstream_auth": (
        "relay",
        "The relay's model provider refused its key; the operator has been told. Your own key under Connections works meanwhile.",
    ),
    "upstream_model": (
        "model",
        "The model provider does not know this model right now; pick another under Connections.",
    ),
    "upstream_unconfigured": (
        "relay",
        "unibot Cloud has no model key configured; the operator has been told.",
    ),
    "account_disabled": ("account", "This unibot Cloud account is disabled."),
    "bad_key": (
        "account",
        "The unibot Cloud sign-in is no longer valid; sign in again under Account.",
    ),
    "model_not_offered": (
        "model",
        "That model is not offered by unibot Cloud; pick another under Connections.",
    ),
}


def _first_line(exc: BaseException) -> str:
    text = str(exc).strip() or type(exc).__name__
    return text.splitlines()[0][:200]


def _relay_error(exc: BaseException) -> dict[str, Any]:
    """The relay's error object (``{"code", "message", …}``) if the exception carries one."""
    body: Any = getattr(exc, "body", None)
    if isinstance(body, dict):
        inner = body["error"] if isinstance(body.get("error"), dict) else body
        return dict(inner)
    extra = getattr(exc, "extra", None)
    if isinstance(extra, dict):
        return {"code": getattr(exc, "code", None), **extra}
    return {}


def _relay_code(exc: BaseException) -> str | None:
    code = getattr(exc, "code", None)
    if isinstance(code, str) and code:
        return code
    code = _relay_error(exc).get("code")
    return code if isinstance(code, str) and code else None


# What the relay says beside the words when the allowance is spent: what is left, the
# invite link, whether the co-creation bonus is still to be had, the own-key guide.
_ALLOWANCE_FIELDS = (
    "left",
    "grant",
    "invite_url",
    "invite_bonus_cny",
    "contribute_bonus_available",
    "contribute_bonus_cny",
    "own_key_docs",
)


def allowance_detail(exc: BaseException) -> dict[str, Any] | None:
    """The structured part of a ``429 allowance_exhausted`` refusal, or None."""
    if _relay_code(exc) != "allowance_exhausted":
        return None
    err = _relay_error(exc)
    return {k: err[k] for k in _ALLOWANCE_FIELDS if k in err}


def describe_failure(exc: BaseException) -> tuple[str, str]:
    """``(code, sentence)`` for a run that ended in ``exc``."""
    code = _relay_code(exc)
    if code in _RELAY:
        return _RELAY[code]
    if isinstance(code, str) and code.startswith("upstream"):
        return "provider", "The model provider is having trouble; try again in a moment."
    if isinstance(exc, openai.AuthenticationError | openai.PermissionDeniedError):
        return "key", "The model provider refused the API key. Check it under Connections."
    if isinstance(exc, openai.NotFoundError):
        return "model", "The endpoint does not know this model. Pick another under Connections."
    if isinstance(exc, openai.RateLimitError):
        return "busy", "The model provider is rate-limiting this key; wait a moment and try again."
    if isinstance(
        exc, openai.APITimeoutError | asyncio.TimeoutError | TimeoutError | httpx.TimeoutException
    ):
        return "timeout", "The model took too long to answer. Try again."
    if isinstance(exc, openai.APIConnectionError | httpx.TransportError | ConnectionError):
        return "network", "Could not reach the model provider. Check the connection and try again."
    if isinstance(exc, openai.BadRequestError):
        low = str(exc).lower()
        if any(k in low for k in _TOO_LONG):
            return (
                "too_long",
                "This conversation is too long for the model. Start a new one, or pick a model with a larger context.",
            )
        return "request", f"The model refused the request: {_first_line(exc)}"
    if isinstance(exc, openai.InternalServerError):
        return "provider", "The model provider is having trouble; try again in a moment."
    if isinstance(exc, openai.APIError):
        return "provider", f"The model provider answered with an error: {_first_line(exc)}"
    return "unknown", f"Something went wrong: {type(exc).__name__}: {_first_line(exc)}"


def failure_notice(exc: BaseException, thread_id: str) -> dict[str, Any]:
    """The ``notice`` event for the apps: the sentence, the code, and the raw detail for
    bug reports (collapsed in the apps, never the only thing shown)."""
    code, text = describe_failure(exc)
    notice: dict[str, Any] = {
        "type": "notice",
        "level": "error",
        "code": code,
        "text": text,
        "detail": f"{type(exc).__name__}: {str(exc).strip()[:400]}",
        "thread": thread_id,
    }
    allowance = allowance_detail(exc)
    if allowance is not None:
        # the apps draw the three ways on (invite / co-creation / own key) from this
        notice["allowance"] = allowance
    return notice
