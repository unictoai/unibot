"""Web Push: the phone buzzes when your unibot needs you or has something to say.

Standard Web Push (VAPID + RFC 8291 payload encryption, via ``pywebpush``), so it works
with the browser's own push service — no account, no third party of ours. The VAPID key
pair is generated once into ``<data_dir>/push-vapid.json``; subscriptions live in
``<data_dir>/push-subscriptions.json``. Endpoints that the push service reports gone
(404/410) are dropped.

What gets pushed is decided in :mod:`unibot.server.service`: a card waiting for you
(approval, question), the result of a background pass that was worth surfacing, a
check-in. The service worker on the phone shows nothing when the app is already on
screen — the card is right there.

Browsers only allow push in a secure context: ``https://`` or ``localhost``. Over plain
LAN http the app still works, only this part stays off (see docs/deployment.md).
"""

from __future__ import annotations

import asyncio
import base64
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from unibot.fsutil import atomic_write_text, load_json
from unibot.logger import logger

VAPID_FILE = "push-vapid.json"
SUBSCRIPTIONS_FILE = "push-subscriptions.json"
MAX_SUBSCRIPTIONS = 20


def available() -> bool:
    try:
        import pywebpush  # noqa: F401
    except ImportError:
        return False
    return True


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


class PushService:
    def __init__(self, data_dir: Path, contact: str = "mailto:unibot@localhost"):
        self.data_dir = data_dir
        self.contact = contact
        self._vapid = data_dir / VAPID_FILE
        self._subs_file = data_dir / SUBSCRIPTIONS_FILE
        self.private_key_pem: str = ""
        self.public_key: str = ""
        self.subscriptions: list[dict[str, Any]] = []
        # background send tasks, kept referenced so they cannot be garbage-collected
        self._bg_tasks: set[asyncio.Task[None]] = set()
        self._load()

    # ------------------------------------------------------------------ keys / storage
    def _load(self) -> None:
        # corrupt files are quarantined aside (never silently reset: regenerating
        # the key pair would orphan every push subscription)
        vapid = load_json(self._vapid)
        if isinstance(vapid, dict):
            try:
                self.private_key_pem = vapid["private_key_pem"]
                self.public_key = vapid["public_key"]
            except KeyError:
                logger.warning("push: {} has no key pair; generating a new one", self._vapid)
        if not self.public_key and available():
            self._generate_keys()
        subs = load_json(self._subs_file)
        if isinstance(subs, list):
            self.subscriptions = [s for s in subs if s.get("endpoint")]

    def _generate_keys(self) -> None:
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
        from py_vapid import Vapid

        v = Vapid()
        v.generate_keys()
        pem = v.private_pem()
        self.private_key_pem = pem.decode() if isinstance(pem, bytes) else str(pem)
        self.public_key = _b64url(
            v.public_key.public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
        )
        # atomic + 0600 from the start: the private key is never briefly
        # world-readable, and a crash cannot leave a half-written key file
        atomic_write_text(
            self._vapid,
            json.dumps({"private_key_pem": self.private_key_pem, "public_key": self.public_key}),
            mode=0o600,
        )

    def _save_subscriptions(self) -> None:
        atomic_write_text(self._subs_file, json.dumps(self.subscriptions, indent=1))

    # ------------------------------------------------------------------ subscriptions
    @property
    def enabled(self) -> bool:
        return bool(self.public_key) and available()

    def view(self) -> dict[str, Any]:
        return {
            "available": available(),
            "public_key": self.public_key,
            "subscriptions": len(self.subscriptions),
            "devices": [
                {
                    "endpoint": s["endpoint"],
                    "created_at": s.get("created_at"),
                    "ua": s.get("ua", ""),
                }
                for s in self.subscriptions
            ],
        }

    def subscribe(self, subscription: dict[str, Any], ua: str = "") -> int:
        endpoint = str(subscription.get("endpoint") or "").strip()
        keys = subscription.get("keys") or {}
        if not endpoint.startswith("https://") or not keys.get("p256dh") or not keys.get("auth"):
            raise ValueError("a push subscription needs an https endpoint and p256dh/auth keys")
        self.subscriptions = [s for s in self.subscriptions if s["endpoint"] != endpoint]
        self.subscriptions.append(
            {
                "endpoint": endpoint,
                "keys": {"p256dh": str(keys["p256dh"]), "auth": str(keys["auth"])},
                "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
                "ua": ua[:120],
            }
        )
        # the oldest devices fall off; nobody has twenty phones
        self.subscriptions = self.subscriptions[-MAX_SUBSCRIPTIONS:]
        self._save_subscriptions()
        return len(self.subscriptions)

    def unsubscribe(self, endpoint: str) -> bool:
        before = len(self.subscriptions)
        self.subscriptions = [s for s in self.subscriptions if s["endpoint"] != endpoint]
        if len(self.subscriptions) != before:
            self._save_subscriptions()
            return True
        return False

    # ------------------------------------------------------------------ sending
    def notify(
        self,
        title: str,
        body: str,
        *,
        tag: str = "",
        url: str = "/",
        badge: int | None = None,
        kind: str = "",
    ) -> None:
        """Queue a notification to every device. Fire-and-forget; failures are logged."""
        if not self.enabled or not self.subscriptions:
            return
        payload = {
            "title": title[:120],
            "body": body[:240],
            "tag": tag,
            "url": url,
            "badge": badge,
            "kind": kind,
            "ts": datetime.now(UTC).isoformat(timespec="seconds"),
        }
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            self._send_all(payload)
            return
        task = loop.create_task(asyncio.to_thread(self._send_all, payload))
        self._bg_tasks.add(task)
        task.add_done_callback(self._bg_tasks.discard)

    def _send_all(self, payload: dict[str, Any]) -> None:
        from pywebpush import WebPushException, webpush

        data = json.dumps(payload)
        gone: list[str] = []
        for sub in list(self.subscriptions):
            try:
                webpush(
                    subscription_info={"endpoint": sub["endpoint"], "keys": sub["keys"]},
                    data=data,
                    vapid_private_key=self.private_key_pem,
                    vapid_claims={"sub": self.contact},
                    ttl=6 * 3600,
                    timeout=10,
                )
            except WebPushException as exc:
                status = getattr(getattr(exc, "response", None), "status_code", None)
                if status in (404, 410):
                    gone.append(sub["endpoint"])
                else:
                    logger.warning("push failed ({}): {}", status, str(exc)[:200])
            except Exception as exc:  # noqa: BLE001
                logger.warning("push failed: {}", str(exc)[:200])
        if gone:
            self.subscriptions = [s for s in self.subscriptions if s["endpoint"] not in gone]
            self._save_subscriptions()

    async def test(self, name: str = "unibot") -> dict[str, Any]:
        if not self.enabled:
            return {"ok": False, "error": "push is not available on this server"}
        if not self.subscriptions:
            return {"ok": False, "error": "no device has turned notifications on"}
        payload = {
            "title": f"{name} can reach you here",
            "body": f"This is what a notification from {name} looks like.",
            "tag": "test",
            "url": "/",
            "kind": "test",
        }
        await asyncio.to_thread(self._send_all, payload)
        return {"ok": True, "sent": len(self.subscriptions)}


__all__ = ["PushService", "available"]
