"""Operating this computer's screen: ``computer_screen``, ``computer_act`` and
``computer_task`` — the phone's three tools with a mouse and a keyboard under them.

They exist only while the hands are turned on (``[hands] enabled``, the switch in the app),
and they are the last rung: a command in the shell, a file, a web page or a skill that does
the thing exactly comes first; the screen is for what has no other door — a desktop app, a
dialog, a page that will not load without a real browser session.

Risk is judged like the phone's: looking is safe but private; a click is *moderate* until
the words under the cursor say pay, transfer, send, delete, confirm — then it is *sensitive*
and asked every time. Every pointed action carries a ``label`` (the words on the button or
field, as the screenshot shows them); the window in front is the *target* of a standing
approval, so "always allow in Terminal" never covers the bank's site in a browser.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from pydantic import ConfigDict

from unibot.computer.link import ACTIONS, POINTED, ComputerLink
from unibot.config import GUISettings
from unibot.phone.link import STOP_MARKER, DeviceError, DeviceStopped
from unibot.phone.screen import Screen
from unibot.schema import RiskLevel, ToolResult
from unibot.tools.base import BaseTool, CallAssessment

if TYPE_CHECKING:
    from unibot.phone.operator import PhoneOperator

# key combinations that leave the current app or change what is on disk
_HEAVY_COMBOS = {
    ("alt", "f4"): "closes the window",
    ("ctrl", "q"): "quits the application",
    ("command", "q"): "quits the application",
    ("ctrl", "w"): "closes the tab or window",
    ("command", "w"): "closes the tab or window",
    ("ctrl", "s"): "saves a file",
    ("command", "s"): "saves a file",
    ("ctrl", "shift", "delete"): "clears browsing data",
}


def _screen_result(screen: Screen, prefix: str = "") -> ToolResult:
    text = (prefix + "\n\n" if prefix else "") + screen.render()
    if not screen.image_path:
        text += "\n(no screenshot came back; the display may be locked)"
    return ToolResult(output=text, images=[screen.image_path] if screen.image_path else None)


class ComputerScreen(BaseTool):
    """Read this computer's screen."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    name: str = "computer_screen"
    description: str = (
        "Look at this computer's screen: a screenshot, which window is in front and the screen "
        "size in pixels. Coordinates for `computer_act` are pixels of that screen, (0,0) "
        "top-left. Call it again after the screen changed. For a whole job on the screen, "
        "prefer `computer_task`."
    )
    parameters: dict[str, Any] = {"type": "object", "properties": {}}
    risk: RiskLevel = RiskLevel.SAFE
    reads_private_data: bool = True
    link: ComputerLink

    async def execute(self, **kwargs: Any) -> ToolResult:
        try:
            screen = await self.link.screen()
        except DeviceStopped as exc:
            return ToolResult.fail(f"{exc} ({STOP_MARKER})")
        except DeviceError as exc:
            return ToolResult.fail(str(exc))
        return _screen_result(screen)


class ComputerAct(BaseTool):
    """One action of the mouse or keyboard on this computer."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    name: str = "computer_act"
    description: str = (
        "One action on this computer's screen, then a fresh look. `click`, `double_click`, "
        "`right_click`, `middle_click` or `move` at `x`/`y` (pixels of the last screenshot), with "
        "`label` — the words of what is under the cursor, as the screen shows them; `drag` from "
        "`x`/`y` to `x2`/`y2`; `scroll` at `x`/`y` by `dy` pixels (negative = up); `type` `text` "
        "into the focused field (`clear` first, `submit` to press Enter after); `key` presses "
        '`keys` together (e.g. ["ctrl", "s"]); `open_app` starts an application by name; '
        "`wait` `seconds`. Use `computer_task` for anything longer than a few steps."
    )
    parameters: dict[str, Any] = {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": list(ACTIONS)},
            "x": {"type": "number"},
            "y": {"type": "number"},
            "x2": {"type": "number"},
            "y2": {"type": "number"},
            "dy": {"type": "number", "description": "scroll amount in pixels; negative = up"},
            "label": {
                "type": "string",
                "description": "the words of what is under the cursor, as shown on the screen",
            },
            "text": {"type": "string"},
            "clear": {"type": "boolean", "description": "select all before typing"},
            "submit": {"type": "boolean", "description": "press Enter after typing"},
            "keys": {"type": "array", "items": {"type": "string"}},
            "app": {"type": "string", "description": "for open_app: the application's name"},
            "seconds": {"type": "number"},
        },
        "required": ["action"],
    }
    risk: RiskLevel = RiskLevel.MODERATE
    link: ComputerLink
    gui: GUISettings

    # ------------------------------------------------------------------ risk
    def assess(self, args: dict[str, Any]) -> CallAssessment:
        action = str(args.get("action") or "")
        screen = self.link.last_screen
        app = (screen.app_name or screen.app if screen else "") or None
        where = f" in {screen.title}" if screen and screen.title != "phone" else ""
        label = str(args.get("label") or "").strip()
        shown = f' "{label[:60]}"' if label else ""
        at = (
            f" at ({_num(args.get('x'))},{_num(args.get('y'))})"
            if args.get("x") is not None and args.get("y") is not None
            else ""
        )
        if action in POINTED:
            summary = f"computer_act: {action}{shown}{at}{where}"
        elif action == "type":
            text = str(args.get("text") or "")
            summary = (
                f'computer_act: type "{text[:40]}"'
                + (f" into{shown}" if label else "")
                + (" and press enter" if args.get("submit") else "")
                + where
            )
        elif action == "key":
            summary = (
                f"computer_act: press {'+'.join(str(k) for k in args.get('keys') or [])}{where}"
            )
        elif action == "drag":
            summary = f"computer_act: drag{shown}{at}{where}"
        elif action == "scroll":
            summary = f"computer_act: scroll {_num(args.get('dy') or 300)}px{at}{where}"
        elif action == "open_app":
            summary = f"computer_act: open {args.get('app') or '?'}"
        else:
            summary = f"computer_act: {action}{shown}{where}"

        words = list(self.gui.sensitive_words)
        risk, warnings, egress = RiskLevel.MODERATE, [], False
        hit: list[str] = []
        if action in (*POINTED, "drag") and label:
            hit = [w for w in words if w.lower() in label.lower()]
        if action in POINTED and action != "move" and not label:
            warnings.append("a click with no `label`: nothing says what is under the cursor")
        if action == "type" and args.get("submit"):
            risk, egress = RiskLevel.SENSITIVE, True
        if action == "type" and not args.get("submit"):
            # pyautogui and xdotool type "\n" as Enter: a newline in the text submits
            # just like submit=True, so it gets the same sensitive treatment
            text = str(args.get("text") or "")
            if "\n" in text or "\r" in text:
                risk, egress = RiskLevel.SENSITIVE, True
                warnings.append("the typed text contains a newline, which submits it")
        if action == "open_app":
            # open_app starts a program on this computer, outside any sandbox and
            # with the full parent environment — never a moderate auto-run
            risk = RiskLevel.SENSITIVE
            warnings.append("open_app starts a program on this computer")
        if action == "key":
            combo = tuple(str(k).lower() for k in args.get("keys") or [])
            if combo == ("enter",) or combo == ("return",):
                risk, egress = RiskLevel.SENSITIVE, True
            elif combo in _HEAVY_COMBOS:
                risk = RiskLevel.SENSITIVE
                warnings.append(_HEAVY_COMBOS[combo])
        if hit:
            risk = RiskLevel.SENSITIVE
            warnings.append(f"the words under the cursor say: {', '.join(hit)}")
        return CallAssessment(
            risk=risk,
            reads_private_data=True,
            egress=egress,
            target=app,
            summary=summary,
            warnings=warnings,
        )

    # ------------------------------------------------------------------ doing
    async def execute(self, **kwargs: Any) -> ToolResult:
        action = str(kwargs.get("action") or "")
        if action not in ACTIONS:
            return ToolResult.fail(f"unknown action '{action}'. One of: {', '.join(ACTIONS)}")
        params: dict[str, Any] = {"action": action}
        label = str(kwargs.get("label") or "").strip()
        if label:
            params["label"] = label[:120]
        screen = self.link.last_screen
        if action in (*POINTED, "drag", "scroll"):
            point = _point(kwargs, "x", "y")
            if point is None:
                return ToolResult.fail(f"`{action}` needs `x` and `y` (pixels of the screen)")
            if screen is not None and not _inside(point, screen):
                return ToolResult.fail(
                    f"({point[0]:g},{point[1]:g}) is outside the {screen.width}×{screen.height} screen"
                )
            params["x"], params["y"] = point
        if action in POINTED and action != "move" and not label:
            return ToolResult.fail(
                f"`{action}` needs `label`: the words of what is under the cursor, as the screen "
                "shows them (the Sentinel and the audit log go by it)"
            )
        if action == "drag":
            end = _point(kwargs, "x2", "y2")
            if end is None:
                return ToolResult.fail("`drag` needs `x2`/`y2` (where the drag ends)")
            params["x2"], params["y2"] = end
        if action == "scroll":
            raw_dy = kwargs.get("dy")
            try:
                params["dy"] = float(raw_dy) if raw_dy is not None else 300.0
            except (TypeError, ValueError):
                return ToolResult.fail("`dy` must be a number of pixels")
        if action == "type":
            text = kwargs.get("text")
            if text is None:
                return ToolResult.fail("`type` needs `text`")
            params.update(
                text=str(text)[:4000],
                clear=bool(kwargs.get("clear")),
                submit=bool(kwargs.get("submit")),
            )
        if action == "key":
            keys = kwargs.get("keys")
            if isinstance(keys, str):
                keys = [k for k in keys.replace("+", " ").split() if k]
            if not isinstance(keys, list) or not keys:
                return ToolResult.fail('`key` needs `keys`, e.g. ["ctrl", "s"]')
            params["keys"] = [str(k)[:20] for k in keys][:5]
        if action == "open_app":
            app = str(kwargs.get("app") or "").strip()
            if not app:
                return ToolResult.fail("`open_app` needs `app`")
            params["app"] = app[:80]
        if action == "wait":
            params["seconds"] = max(0.2, min(10.0, float(kwargs.get("seconds") or 1.0)))
        try:
            raw = await self.link.act(
                params, timeout=self.gui.device_timeout_s + params.get("seconds", 0)
            )
        except DeviceStopped as exc:
            return ToolResult.fail(f"{exc} ({STOP_MARKER})")
        except DeviceError as exc:
            return ToolResult.fail(str(exc))
        note = str(raw.get("note") or "")
        done = "Done" + (f": {note}" if note else "")
        after = self.link.last_screen
        if after is None or not raw.get("looked", True):
            return ToolResult(output=f"{done}. The screen could not be read afterwards.")
        return _screen_result(after, done + ". Screen now:")


class ComputerTask(BaseTool):
    """A whole job on this computer's screen, run step by step by the operator."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    name: str = "computer_task"
    description: str = (
        "Hand a job on this computer's screen to the operator: it opens the application, looks "
        "at the screen, clicks, types and scrolls step by step until the job is done, then "
        "reports what it found or did. Use it only for what has no other door — a desktop "
        "application, a dialog, a page that needs the user's real browser session — and not "
        "for what `shell`, `files`, `browser`, `web_fetch` or a skill does exactly and "
        "instantly. Give one concrete `goal` with the facts it needs and any `context` you "
        "already have; one goal per call, in order. It stops and asks before paying, sending or "
        "deleting; it never enters passwords or codes — when it asks, put the question to the "
        "user and call again with their answer in `context`."
    )
    parameters: dict[str, Any] = {
        "type": "object",
        "properties": {
            "goal": {"type": "string", "description": "what to achieve on the screen, concretely"},
            "context": {
                "type": "string",
                "description": "facts that help: what was found earlier, preferences, constraints",
            },
            "app": {"type": "string", "description": "the application to start in, if known"},
        },
        "required": ["goal"],
    }
    risk: RiskLevel = RiskLevel.MODERATE
    reads_private_data: bool = True
    link: ComputerLink
    operator: Any  # PhoneOperator with the computer dialect

    def assess(self, args: dict[str, Any]) -> CallAssessment:
        goal = str(args.get("goal") or "")
        app = str(args.get("app") or "").strip() or None
        return CallAssessment(
            risk=self.risk,
            reads_private_data=True,
            target=app,
            summary=f"computer_task: {goal[:80]}" + (f" ({app})" if app else ""),
        )

    async def execute(self, **kwargs: Any) -> ToolResult:
        goal = str(kwargs.get("goal") or "").strip()
        if not goal:
            return ToolResult.fail("`goal` is required")
        if not self.link.connected:
            return ToolResult.fail(
                "the hands are not available on this computer: "
                + (self.link.status().get("reason") or "no backend")
            )
        operator: PhoneOperator = self.operator
        outcome = await operator.run(
            goal, context=str(kwargs.get("context") or ""), app=str(kwargs.get("app") or "")
        )
        text = outcome.report()
        images = [outcome.last_image] if outcome.last_image else None
        return ToolResult(output=text, images=images)


def _num(value: Any) -> str:
    try:
        return f"{float(value):g}"
    except (TypeError, ValueError):
        return "?"


def _point(args: dict[str, Any], kx: str, ky: str) -> tuple[float, float] | None:
    if args.get(kx) is None or args.get(ky) is None:
        return None
    try:
        return float(args[kx]), float(args[ky])
    except (TypeError, ValueError):
        return None


def _inside(point: tuple[float, float], screen: Screen) -> bool:
    if not (screen.width and screen.height):
        return True
    return 0 <= point[0] <= screen.width and 0 <= point[1] <= screen.height


__all__ = ["ComputerAct", "ComputerScreen", "ComputerTask"]
