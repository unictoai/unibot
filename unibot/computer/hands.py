"""The hands on this computer: a mouse and a keyboard, driven by coordinates.

Two backends do the same eight things — click (left, right, middle, double), move, drag,
scroll, type, press keys, open an application:

* ``pyautogui`` (``pip install 'unibot[hands]'``): every platform; its fail-safe stays on,
  so throwing the mouse into a screen corner aborts whatever the hands were doing — a stop
  button that needs no window.
* ``xdotool``: X11 only, no Python packages; also how non-ASCII text is typed on Linux when
  ``pyautogui`` cannot (it types by key codes, which have no 中文).

Which one is used is ``[hands] backend``: ``auto`` takes ``pyautogui`` when it is there and
``xdotool`` otherwise. Wayland sessions have neither a global cursor nor a screen to grab
without a portal; the hands say so instead of pretending.

Nothing here decides *whether* to act: :class:`~unibot.tools.computer.ComputerAct` carries
the words under the cursor to the Sentinel first, exactly like the phone's ``phone_act``.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
import types
from pathlib import Path
from typing import Any, Protocol

from unibot.logger import logger

# Qwen / pyautogui-style key names → xdotool keysyms
_XDO_KEYS = {
    "ctrl": "ctrl",
    "control": "ctrl",
    "alt": "alt",
    "option": "alt",
    "shift": "shift",
    "cmd": "super",
    "command": "super",
    "win": "super",
    "super": "super",
    "meta": "super",
    "enter": "Return",
    "return": "Return",
    "esc": "Escape",
    "escape": "Escape",
    "backspace": "BackSpace",
    "delete": "Delete",
    "del": "Delete",
    "tab": "Tab",
    "space": "space",
    "up": "Up",
    "down": "Down",
    "left": "Left",
    "right": "Right",
    "home": "Home",
    "end": "End",
    "pageup": "Page_Up",
    "page_up": "Page_Up",
    "pagedown": "Page_Down",
    "page_down": "Page_Down",
    "insert": "Insert",
    "capslock": "Caps_Lock",
    "printscreen": "Print",
}
_PYAUTO_KEYS = {
    "control": "ctrl",
    "option": "alt",
    "cmd": "command",
    "super": "win",
    "meta": "win",
    "return": "enter",
    "escape": "esc",
    "page_up": "pageup",
    "page_down": "pagedown",
    "del": "delete",
}


class HandsUnavailable(RuntimeError):
    """No way to move the mouse or type on this session (and why)."""


class HandsBackend(Protocol):
    name: str

    def click(self, x: float, y: float, button: str = "left", clicks: int = 1) -> None: ...
    def move(self, x: float, y: float) -> None: ...
    def drag(self, x: float, y: float, x2: float, y2: float) -> None: ...
    def scroll(self, x: float, y: float, dy: float) -> None: ...
    def type(self, text: str) -> None: ...
    def key(self, keys: list[str]) -> None: ...
    def open_app(self, name: str) -> str: ...


def _normalise_key(key: str, table: dict[str, str]) -> str:
    k = key.strip()
    return table.get(k.lower(), k if len(k) > 1 else k.lower()) if k else k


def _import_pyautogui() -> Any:
    """``pyautogui``, importable without Tk. Its ``mouseinfo`` dependency *exits the
    interpreter* (``sys.exit``) when tkinter is missing on Linux — and the frozen runtime
    leaves Tk out — which took every request on the Linux desktop app down with it. Nothing
    here ever opens the MouseInfo window, so a stand-in module is registered first."""
    if "mouseinfo" not in sys.modules:
        try:
            import tkinter  # noqa: F401
        except ImportError:
            stub = types.ModuleType("mouseinfo")
            stub.MouseInfoWindow = lambda *args, **kwargs: None  # type: ignore[attr-defined]
            sys.modules["mouseinfo"] = stub
    import pyautogui

    return pyautogui


class PyAutoGUIHands:
    name = "pyautogui"

    def __init__(self) -> None:
        try:
            pyautogui = _import_pyautogui()
        except (Exception, SystemExit) as exc:  # noqa: BLE001 — import errors differ per platform
            raise HandsUnavailable(
                f"pyautogui is not available ({exc}); pip install 'unibot[hands]'"
            ) from exc
        pyautogui.FAILSAFE = True  # the mouse in a corner aborts: a physical Stop
        pyautogui.PAUSE = 0.05
        self._gui = pyautogui

    def click(self, x: float, y: float, button: str = "left", clicks: int = 1) -> None:
        self._gui.click(x=x, y=y, button=button, clicks=clicks, interval=0.08)

    def move(self, x: float, y: float) -> None:
        self._gui.moveTo(x, y, duration=0.15)

    def drag(self, x: float, y: float, x2: float, y2: float) -> None:
        self._gui.moveTo(x, y, duration=0.1)
        self._gui.dragTo(x2, y2, duration=0.4, button="left")

    def scroll(self, x: float, y: float, dy: float) -> None:
        # pyautogui counts clicks, positive = up; the dialect counts pixels, positive = down
        clicks = max(1, min(30, int(abs(dy) / 40) or 1))
        self._gui.scroll(-clicks if dy > 0 else clicks, x=x, y=y)

    def type(self, text: str) -> None:
        if text.isascii():
            self._gui.write(text, interval=0.01)
            return
        # key codes have no 中文: paste through the clipboard, or xdotool on X11
        if sys.platform.startswith("linux") and shutil.which("xdotool"):
            XdotoolHands().type(text)
            return
        try:
            import pyperclip

            pyperclip.copy(text)
            self._gui.hotkey("command" if sys.platform == "darwin" else "ctrl", "v")
        except Exception as exc:  # noqa: BLE001
            raise HandsUnavailable(
                f"cannot type non-ASCII text on this system ({exc}); pip install pyperclip"
            ) from exc

    def key(self, keys: list[str]) -> None:
        names = [_normalise_key(k, _PYAUTO_KEYS) for k in keys if k.strip()]
        if not names:
            return
        if len(names) == 1:
            self._gui.press(names[0])
        else:
            self._gui.hotkey(*names)

    def open_app(self, name: str) -> str:
        return open_application(name)


class XdotoolHands:
    name = "xdotool"

    def __init__(self) -> None:
        if not shutil.which("xdotool"):
            raise HandsUnavailable("xdotool is not installed")
        if not os.environ.get("DISPLAY"):
            raise HandsUnavailable("no X11 display (DISPLAY is not set)")

    @staticmethod
    def _run(*args: str) -> None:
        subprocess.run(["xdotool", *args], check=True, timeout=15, capture_output=True)

    def click(self, x: float, y: float, button: str = "left", clicks: int = 1) -> None:
        code = {"left": "1", "middle": "2", "right": "3"}.get(button, "1")
        self._run("mousemove", str(int(x)), str(int(y)))
        time.sleep(0.05)
        args = ["click"]
        if clicks > 1:
            args += ["--repeat", str(clicks), "--delay", "80"]
        self._run(*args, code)

    def move(self, x: float, y: float) -> None:
        self._run("mousemove", str(int(x)), str(int(y)))

    def drag(self, x: float, y: float, x2: float, y2: float) -> None:
        self._run("mousemove", str(int(x)), str(int(y)))
        self._run("mousedown", "1")
        time.sleep(0.1)
        self._run("mousemove", str(int(x2)), str(int(y2)))
        time.sleep(0.1)
        self._run("mouseup", "1")

    def scroll(self, x: float, y: float, dy: float) -> None:
        self._run("mousemove", str(int(x)), str(int(y)))
        clicks = max(1, min(30, int(abs(dy) / 40) or 1))
        self._run("click", "--repeat", str(clicks), "--delay", "30", "5" if dy > 0 else "4")

    def type(self, text: str) -> None:
        self._run("type", "--delay", "15", "--", text)

    def key(self, keys: list[str]) -> None:
        names = [_normalise_key(k, _XDO_KEYS) for k in keys if k.strip()]
        if names:
            self._run("key", "--clearmodifiers", "+".join(names))

    def open_app(self, name: str) -> str:
        return open_application(name)


def _platform() -> str:
    # a function, so mypy checks every branch below on every platform
    return sys.platform


def open_application(name: str) -> str:
    """Start an application by the name a person uses for it. Returns what was started."""
    name = name.strip()
    if not name:
        raise ValueError("an application name is required")
    platform = _platform()
    if platform == "darwin":
        subprocess.run(["open", "-a", name], check=True, timeout=20, capture_output=True)
        return name
    if platform == "win32":
        subprocess.Popen(["cmd", "/c", "start", "", name], shell=False)  # noqa: S603
        return name
    # Linux: a command on PATH, else a .desktop entry whose Name matches
    exe = shutil.which(name) or shutil.which(name.lower())
    if exe:
        subprocess.Popen(
            [exe], start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        return exe
    desktop = _desktop_entry(name)
    if desktop is None:
        raise ValueError(f"no application called {name!r} was found")
    if shutil.which("gtk-launch"):
        subprocess.Popen(
            ["gtk-launch", desktop.stem],
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return desktop.stem
    if shutil.which("gio"):
        subprocess.Popen(
            ["gio", "launch", str(desktop)],
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return desktop.stem
    raise ValueError(f"found {desktop.name} but no launcher (gtk-launch or gio) to start it")


def _desktop_entry(name: str) -> Path | None:
    low = name.lower()
    roots = [
        Path.home() / ".local/share/applications",
        Path("/usr/local/share/applications"),
        Path("/usr/share/applications"),
        Path("/var/lib/flatpak/exports/share/applications"),
        Path("/var/lib/snapd/desktop/applications"),
    ]
    best: tuple[int, Path] | None = None
    for root in roots:
        if not root.is_dir():
            continue
        for entry in root.glob("*.desktop"):
            try:
                text = entry.read_text("utf-8", errors="ignore")
            except OSError:
                continue
            if "NoDisplay=true" in text:
                continue
            names = [
                line.split("=", 1)[1].strip().lower()
                for line in text.splitlines()
                if line.startswith(("Name=", "Name[", "GenericName="))
            ]
            score = 0
            if low in names or entry.stem.lower() == low:
                score = 3
            elif any(n.startswith(low) for n in names):
                score = 2
            elif any(low in n for n in names):
                score = 1
            if score and (best is None or score > best[0]):
                best = (score, entry)
    return best[1] if best else None


def pick_backend(preference: str = "auto") -> HandsBackend:
    """The hands to use, or :class:`HandsUnavailable` saying what is missing."""
    preference = (preference or "auto").lower()
    if (
        sys.platform.startswith("linux")
        and os.environ.get("WAYLAND_DISPLAY")
        and not os.environ.get("DISPLAY")
    ):
        raise HandsUnavailable(
            "this is a Wayland session without XWayland: the mouse and keyboard cannot be "
            "driven from a program here. Log in to an X11 session, or run the phone's hands."
        )
    errors: list[str] = []
    order = {"pyautogui": ["pyautogui"], "xdotool": ["xdotool"]}.get(
        preference, ["pyautogui", "xdotool"]
    )
    for name in order:
        try:
            backend: HandsBackend = PyAutoGUIHands() if name == "pyautogui" else XdotoolHands()
        except HandsUnavailable as exc:
            errors.append(str(exc))
            continue
        except Exception as exc:  # noqa: BLE001 — a backend that cannot set up is one that is not there
            errors.append(f"{name}: {exc}")
            continue
        logger.info("computer hands: {}", backend.name)
        return backend
    raise HandsUnavailable("; ".join(errors) or "no hands backend")


def describe_availability(preference: str = "auto") -> dict[str, Any]:
    """For the settings screen: which backend would be used, or why none."""
    try:
        backend = pick_backend(preference)
    except HandsUnavailable as exc:
        return {"available": False, "backend": None, "reason": str(exc)}
    return {"available": True, "backend": backend.name, "reason": ""}


__all__ = [
    "HandsBackend",
    "HandsUnavailable",
    "PyAutoGUIHands",
    "XdotoolHands",
    "describe_availability",
    "open_application",
    "pick_backend",
]
