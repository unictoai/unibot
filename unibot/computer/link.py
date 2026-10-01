"""This computer as a device the operator can look at and act on — the same face
:class:`~unibot.phone.link.PhoneLink` shows for the phone, so the operator loop, the
tools and the GUI's cards are shared.

``screen()`` is a screenshot plus the window in front; ``act()`` is one action of the hands
(:mod:`unibot.computer.hands`), followed by a short settle and a fresh look. ``stop()`` is
the Stop button: the next action raises :class:`~unibot.phone.link.DeviceStopped`, which
ends the task the way the phone's Stop pill does. Every action and task boundary is also
handed to ``on_event`` — the GUI shows a *Hands* card with the current step and the Electron
stage draws a ring where the click lands.
"""

from __future__ import annotations

import asyncio
import platform
import socket
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from unibot.computer import hands as hands_mod
from unibot.computer.screen import DEFAULT_MAX_WIDTH, active_window, capture, screen_size
from unibot.config import HandsSettings
from unibot.logger import logger
from unibot.phone.link import Device, DeviceError, DeviceStopped
from unibot.phone.screen import Screen

ACTIONS = (
    "click",
    "double_click",
    "right_click",
    "middle_click",
    "move",
    "drag",
    "scroll",
    "type",
    "key",
    "open_app",
    "wait",
)
POINTED = ("click", "double_click", "right_click", "middle_click", "move")

Listener = Callable[[dict[str, Any]], None]


class ComputerLink:
    def __init__(
        self,
        settings: HandsSettings,
        shots_dir: Path | None = None,
        on_event: Listener | None = None,
        backend: hands_mod.HandsBackend | None = None,
    ):
        self.settings = settings
        self.shots_dir = shots_dir
        self.on_event = on_event
        self._backend: hands_mod.HandsBackend | None = backend
        self._backend_error = ""
        self.last_screen: Screen | None = None
        self._stopped = False
        self.task_active = False
        self.task_text = ""
        self.last_action: dict[str, Any] | None = None
        w, h = screen_size()
        self._device = Device(
            id="this-computer",
            name=socket.gethostname() or "this computer",
            platform=platform.system().lower() or "computer",
            gui=True,
            browser=False,
            capsule=True,
            width=w,
            height=h,
        )

    # ------------------------------------------------------------------ the device
    @property
    def device(self) -> Device:
        return self._device

    @property
    def connected(self) -> bool:
        return self.backend_available()

    def backend_available(self) -> bool:
        try:
            self._hands()
        except hands_mod.HandsUnavailable:
            return False
        return True

    def _hands(self) -> hands_mod.HandsBackend:
        if self._backend is None:
            try:
                self._backend = hands_mod.pick_backend(self.settings.backend)
                self._backend_error = ""
            except hands_mod.HandsUnavailable as exc:
                self._backend_error = str(exc)
                raise
        return self._backend

    def status(self) -> dict[str, Any]:
        available = self.backend_available()
        return {
            "enabled": self.settings.enabled,
            "available": available,
            "backend": self._backend.name if self._backend else None,
            "reason": self._backend_error,
            "device": self._device.to_dict(),
            "task_active": self.task_active,
            "task_text": self.task_text,
            "last_screen": self.last_screen.to_dict() if self.last_screen else None,
        }

    # ------------------------------------------------------------------ stop
    def stop(self) -> bool:
        """The Stop button: whatever is running ends at its next step."""
        if not self.task_active and self.last_action is None:
            return False
        self._stopped = True
        self._emit({"event": "stop"})
        return True

    def _check_stopped(self) -> None:
        if self._stopped:
            self._stopped = False
            raise DeviceStopped("the user pressed Stop")

    # ------------------------------------------------------------------ looking
    async def screen(self) -> Screen:
        self._check_stopped()
        try:
            raw = await asyncio.to_thread(
                capture, self.settings.max_image_width or DEFAULT_MAX_WIDTH
            )
        except Exception as exc:  # noqa: BLE001 — platform tools fail in many ways
            raise DeviceError(f"could not take a screenshot of this computer: {exc}") from exc
        if raw is None:
            raise DeviceError(
                "no screenshot could be taken on this computer: install mss and Pillow "
                "(pip install 'unibot[hands]') or a screenshot tool, and make sure there is a "
                "display session"
            )
        if raw.get("width"):
            self._device.width, self._device.height = int(raw["width"]), int(raw["height"])
        screen = Screen.from_device(raw, device=self._device, shots_dir=self.shots_dir)
        self.last_screen = screen
        return screen

    # ------------------------------------------------------------------ acting
    async def act(self, params: dict[str, Any], timeout: float | None = None) -> dict[str, Any]:
        """One action of the hands, a short settle, a fresh look (``last_screen``). Returns
        ``{"note": ...}``; the tool renders ``last_screen`` afterwards."""
        self._check_stopped()
        action = str(params.get("action") or "")
        if action not in ACTIONS:
            raise DeviceError(f"unknown action {action!r}")
        try:
            hands = self._hands()
        except hands_mod.HandsUnavailable as exc:
            raise DeviceError(str(exc)) from exc
        self.last_action = dict(params)
        self._emit({"event": "act", **self._where(params)})
        started = time.monotonic()
        try:
            note = await asyncio.wait_for(
                asyncio.to_thread(self._do, hands, action, params), timeout or 60.0
            )
        except TimeoutError:
            raise DeviceError(f"the hands did not finish '{action}' in time") from None
        except DeviceError:
            raise
        except Exception as exc:  # noqa: BLE001 — pyautogui's FailSafeException and friends
            name = type(exc).__name__
            if "FailSafe" in name:
                self._emit({"event": "stop"})
                raise DeviceStopped("the mouse was thrown into a corner") from exc
            raise DeviceError(f"{action} failed: {exc}") from exc
        settle = max(0.0, self.settings.settle_s - (time.monotonic() - started))
        if action != "wait" and settle:
            await asyncio.sleep(settle)
        try:
            await self.screen()
        except DeviceError as exc:
            logger.debug("no screenshot after {}: {}", action, exc)
            return {"note": note, "looked": False}
        return {"note": note, "looked": True}

    def _do(self, hands: hands_mod.HandsBackend, action: str, p: dict[str, Any]) -> str:
        x, y = float(p.get("x") or 0), float(p.get("y") or 0)
        if action == "click":
            hands.click(x, y)
        elif action == "double_click":
            hands.click(x, y, clicks=2)
        elif action == "right_click":
            hands.click(x, y, button="right")
        elif action == "middle_click":
            hands.click(x, y, button="middle")
        elif action == "move":
            hands.move(x, y)
        elif action == "drag":
            hands.drag(x, y, float(p.get("x2") or x), float(p.get("y2") or y))
        elif action == "scroll":
            hands.scroll(x, y, float(p.get("dy") or 300))
        elif action == "type":
            text = str(p.get("text") or "")
            if p.get("clear"):
                hands.key(["command" if platform.system() == "Darwin" else "ctrl", "a"])
            hands.type(text)
            if p.get("submit"):
                hands.key(["enter"])
        elif action == "key":
            keys = [str(k) for k in (p.get("keys") or [])]
            if not keys:
                raise DeviceError("`key` needs `keys`")
            hands.key(keys)
        elif action == "open_app":
            try:
                started = hands.open_app(str(p.get("app") or ""))
            except (ValueError, OSError) as exc:
                raise DeviceError(str(exc)) from exc
            except Exception as exc:  # noqa: BLE001 — CalledProcessError and friends
                raise DeviceError(f"could not open {p.get('app')!r}: {exc}") from exc
            time.sleep(1.5)
            return f"started {started}"
        elif action == "wait":
            time.sleep(max(0.2, min(10.0, float(p.get("seconds") or 1.0))))
        return ""

    def _where(self, params: dict[str, Any]) -> dict[str, Any]:
        """The action for the GUI: what and where, with the point as fractions of the screen."""
        w, h = self._device.width or 1, self._device.height or 1
        out: dict[str, Any] = {
            "action": params.get("action"),
            "label": str(params.get("label") or "")[:120],
        }
        if params.get("x") is not None and params.get("y") is not None:
            out["fx"] = round(float(params["x"]) / w, 4)
            out["fy"] = round(float(params["y"]) / h, 4)
        if params.get("x2") is not None and params.get("y2") is not None:
            out["fx2"] = round(float(params["x2"]) / w, 4)
            out["fy2"] = round(float(params["y2"]) / h, 4)
        if params.get("action") == "type":
            out["text"] = str(params.get("text") or "")[:80]
        if params.get("action") == "key":
            out["keys"] = list(params.get("keys") or [])
        return out

    # ------------------------------------------------------------------ task boundaries
    async def task_event(self, event: str, text: str = "") -> None:
        if event == "begin":
            self.task_active = True
            self.task_text = text[:200]
            self._stopped = False
        elif event == "end":
            self.task_active = False
            self.task_text = ""
        self._emit({"event": event, "text": text[:200]})

    def _emit(self, body: dict[str, Any]) -> None:
        if self.on_event is None:
            return
        try:
            app, title = active_window() if body.get("event") == "act" else ("", "")
        except Exception:  # noqa: BLE001
            app, title = "", ""
        try:
            self.on_event({**body, "app": app, "title": title, "ts": time.time()})
        except Exception:  # noqa: BLE001
            logger.exception("hands on_event")


__all__ = ["ACTIONS", "POINTED", "ComputerLink"]
