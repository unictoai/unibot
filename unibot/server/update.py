"""Is there a newer unibot? One request to GitHub Releases, kept for six hours.

Nothing is downloaded and nothing about the install is sent: the request is the same
one a browser makes when it opens the releases page. It is off when ``server.update_check``
is false, when ``UNIBOT_NO_UPDATE_CHECK`` is set, and in a hosted web session (the
operator updates those, not the person using them — those runtimes are the ones handed a
``UNIBOT_CLOUD_KEY``). The desktop app and the Android app have checks of their own;
this one is for ``pipx`` / Docker installs, which show it under *Settings → About*.
"""

from __future__ import annotations

import os
import re
import time
from typing import Any

import httpx

from unibot import __version__
from unibot.logger import logger

RELEASES_API = "https://api.github.com/repos/unictoai/unibot/releases/latest"
RELEASES_PAGE = "https://github.com/unictoai/unibot/releases/latest"
CACHE_S = 6 * 3600
TIMEOUT_S = 6.0


def parse_version(text: str) -> tuple[int, ...]:
    """``v0.1.21`` → ``(0, 1, 21)``; anything after the numbers (``-rc1``) is ignored,
    a string without numbers is ``()`` and therefore older than everything."""
    m = re.match(r"v?(\d+(?:\.\d+)*)", text.strip())
    return tuple(int(p) for p in m.group(1).split(".")) if m else ()


def newer_than(candidate: str, current: str) -> bool:
    a, b = parse_version(candidate), parse_version(current)
    return bool(a) and a > b


def enabled(setting: bool) -> bool:
    if not setting or os.environ.get("UNIBOT_NO_UPDATE_CHECK", "").strip().lower() in (
        "1",
        "true",
        "yes",
        "on",
    ):
        return False
    return not os.environ.get("UNIBOT_CLOUD_KEY")


class UpdateCheck:
    """The cached answer; :meth:`view` is what ``GET /api/update`` returns."""

    def __init__(self, setting: bool = True, *, current: str = __version__) -> None:
        self.setting = setting
        self.current = current
        self.latest: str | None = None
        self.url: str = RELEASES_PAGE
        self.checked_at: float | None = None
        self.error: str | None = None

    async def view(self) -> dict[str, Any]:
        if not enabled(self.setting):
            return {
                "current": self.current,
                "enabled": False,
                "latest": None,
                "newer": False,
                "url": self.url,
            }
        if self.checked_at is None or time.monotonic() - self.checked_at > CACHE_S:
            await self._check()
        return {
            "current": self.current,
            "enabled": True,
            "latest": self.latest,
            "newer": bool(self.latest and newer_than(self.latest, self.current)),
            "url": self.url,
            "error": self.error,
        }

    async def _check(self) -> None:
        self.checked_at = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=TIMEOUT_S, follow_redirects=True) as client:
                r = await client.get(
                    RELEASES_API,
                    headers={
                        "Accept": "application/vnd.github+json",
                        "User-Agent": f"unibot/{self.current}",
                    },
                )
            r.raise_for_status()
            data = r.json()
            tag = str(data.get("tag_name") or "")
            if not parse_version(tag):
                raise ValueError(f"unexpected tag {tag!r}")
            self.latest = tag.lstrip("v")
            self.url = str(data.get("html_url") or RELEASES_PAGE)
            self.error = None
        except Exception as exc:  # noqa: BLE001 — the answer is "could not check", never a failure of the app
            self.error = exc.__class__.__name__
            logger.debug("update check failed: %s", exc)
