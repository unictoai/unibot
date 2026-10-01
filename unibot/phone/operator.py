"""The GUI operator: a step loop on the phone's screen with its own model.

The main agent hands over one concrete goal (``phone_task``). The operator looks at the
screen — a screenshot, nothing else — decides one action, does it through the Sentinel
(every tap is a ``phone_act`` call, assessed and, when it looks like paying or sending,
approved by the user), looks again, and so on, until it is done, must ask, or gives up. It
then reports back in words; the main agent goes on with the rest of the task.

The model is the one under ``[gui]`` — a model that takes images — or the main model when
none is set. It is spoken to in the ``mobile_use`` dialect of the Qwen-VL agents: a tool
schema whose coordinates live in a 999×999 space, and one ``Thought: / Action: /
<tool_call>`` reply per step. That format is what the open Qwen-VL models were trained on
for phone operation, so it is used as is rather than a JSON dialect of our own.

The same loop drives this computer's screen (docs/every-device.md): a :class:`Dialect`
swaps the function offered to the model — ``computer_use``, the Qwen-VL desktop dialect,
with mouse buttons and key combinations instead of taps and system buttons — and what its
steps become on the device (``computer_act`` through :class:`~unibot.computer.link.ComputerLink`).

Attribution: the prompt, the user template and the parsers below are ported from
MemGUI-Bench (https://github.com/lgy0404/MemGUI-Bench, MIT License), files
``src/mobile_world/agents/utils/prompts/qwen3vl.py`` and
``src/mobile_world/agents/implementations/qwen3vl.py``, with the ``open`` action of the
original Qwen ``mobile_use`` tool restored and our device actions on the other end.
"""

from __future__ import annotations

import itertools
import json
import re
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from unibot.config import GUISettings
from unibot.llm.base import BaseLLM
from unibot.logger import logger
from unibot.phone.link import STOP_MARKER, DeviceError, DeviceStopped, PhoneLink
from unibot.phone.screen import Screen
from unibot.phone.trace import Trace
from unibot.schema import Function, Message, ToolCall, ToolResult
from unibot.sentinel import Sentinel
from unibot.tools.base import BaseTool
from unibot.ui import UI

# The model places points on a 999×999 screen whatever the picture's size (MemGUI-Bench's
# convention; the open Qwen-VL agents ground well in it).
SCALE_FACTOR = 999

MOBILE_USE_TOOL: dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "mobile_use",
        "description": (
            "Use a touchscreen to interact with a mobile device, and take screenshots.\n"
            "* This is an interface to a mobile device with touchscreen. You can perform actions "
            "like clicking, typing, swiping, etc.\n"
            "* Some applications may take time to start or process actions, so you may need to "
            "wait and take successive screenshots to see the results of your actions.\n"
            "* The screen's resolution is 999x999.\n"
            "* Make sure to click any buttons, links, icons, etc with the cursor tip in the "
            "center of the element. Don't click boxes on their edges unless asked."
        ),
        "parameters": {
            "properties": {
                "action": {
                    "description": (
                        "The action to perform. The available actions are:\n"
                        "* `click`: Click the point on the screen with coordinate (x, y).\n"
                        "* `long_press`: Press the point on the screen with coordinate (x, y) for "
                        "specified seconds.\n"
                        "* `swipe`: Swipe from the starting point with coordinate (x, y) to the "
                        "end point with coordinates2 (x2, y2).\n"
                        "* `type`: Input the specified text into the activated input box.\n"
                        "* `open`: Open an app on the device by its name (in `text`).\n"
                        "* `answer`: Output the answer.\n"
                        "* `system_button`: Press the system button.\n"
                        "* `wait`: Wait specified seconds for the change to happen.\n"
                        "* `terminate`: Terminate the current task and report its completion "
                        "status.\n"
                        "* `ask_user`: Ask user for clarification."
                    ),
                    "enum": [
                        "click",
                        "long_press",
                        "swipe",
                        "type",
                        "open",
                        "answer",
                        "system_button",
                        "wait",
                        "ask_user",
                        "terminate",
                    ],
                    "type": "string",
                },
                "coordinate": {
                    "description": (
                        "(x, y): The x (pixels from the left edge) and y (pixels from the top "
                        "edge) coordinates to move the mouse to. Required only by `action=click`, "
                        "`action=long_press`, and `action=swipe`."
                    ),
                    "type": "array",
                },
                "coordinate2": {
                    "description": (
                        "(x, y): The x (pixels from the left edge) and y (pixels from the top "
                        "edge) coordinates to move the mouse to. Required only by `action=swipe`."
                    ),
                    "type": "array",
                },
                "text": {
                    "description": (
                        "Required only by `action=type`, `action=open`, `action=ask_user` and "
                        "`action=answer`."
                    ),
                    "type": "string",
                },
                "time": {
                    "description": (
                        "The seconds to wait. Required only by `action=long_press` and "
                        "`action=wait`."
                    ),
                    "type": "number",
                },
                "button": {
                    "description": (
                        "Back means returning to the previous interface, Home means returning to "
                        "the desktop, Menu means opening the application background menu, and "
                        "Enter means pressing the enter. Required only by `action=system_button`"
                    ),
                    "enum": ["Back", "Home", "Menu", "Enter"],
                    "type": "string",
                },
                "status": {
                    "description": ("The status of the task. Required only by `action=terminate`."),
                    "type": "string",
                    "enum": ["success", "failure"],
                },
            },
            "required": ["action"],
            "type": "object",
        },
    },
}

COMPUTER_USE_TOOL: dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "computer_use",
        "description": (
            "Use a mouse and keyboard to interact with a computer, and take screenshots.\n"
            "* This is an interface to a desktop GUI. You do not have access to a terminal or "
            "applications menu; `open` starts an application by its name, everything else is "
            "done with the mouse and keyboard on what is on the screen.\n"
            "* Some applications may take time to start or process actions, so you may need to "
            "wait and take successive screenshots to see the results of your actions.\n"
            "* The screen's resolution is 999x999.\n"
            "* Whenever you intend to click on an element like an icon, consult the screenshot "
            "to determine its coordinates first. Make sure to click any buttons, links, icons, "
            "etc with the cursor tip in the center of the element. Don't click boxes on their "
            "edges unless asked."
        ),
        "parameters": {
            "properties": {
                "action": {
                    "description": (
                        "The action to perform. The available actions are:\n"
                        "* `key`: Press a key or key combination on the keyboard (in `keys`, e.g. "
                        '["ctrl", "s"] or ["Return"]).\n'
                        "* `type`: Type a string of text on the keyboard.\n"
                        "* `mouse_move`: Move the cursor to the point (x, y) on the screen.\n"
                        "* `left_click`: Click the left mouse button at (x, y).\n"
                        "* `left_click_drag`: Click and drag the cursor from (x, y) to (x2, y2).\n"
                        "* `right_click`: Click the right mouse button at (x, y).\n"
                        "* `middle_click`: Click the middle mouse button at (x, y).\n"
                        "* `double_click`: Double-click the left mouse button at (x, y).\n"
                        "* `scroll`: Scroll the mouse wheel at (x, y) by `pixels` (negative = "
                        "up, positive = down).\n"
                        "* `open`: Open an application by its name (in `text`).\n"
                        "* `wait`: Wait specified seconds for the change to happen.\n"
                        "* `answer`: Output the answer.\n"
                        "* `ask_user`: Ask user for clarification.\n"
                        "* `terminate`: Terminate the current task and report its completion "
                        "status."
                    ),
                    "enum": [
                        "key",
                        "type",
                        "mouse_move",
                        "left_click",
                        "left_click_drag",
                        "right_click",
                        "middle_click",
                        "double_click",
                        "scroll",
                        "open",
                        "wait",
                        "answer",
                        "ask_user",
                        "terminate",
                    ],
                    "type": "string",
                },
                "keys": {
                    "description": "Required only by `action=key`: the keys pressed together.",
                    "type": "array",
                },
                "text": {
                    "description": (
                        "Required only by `action=type`, `action=open`, `action=ask_user` and "
                        "`action=answer`."
                    ),
                    "type": "string",
                },
                "coordinate": {
                    "description": (
                        "(x, y): The x (pixels from the left edge) and y (pixels from the top "
                        "edge) coordinates to move the mouse to. Required by the click actions, "
                        "`mouse_move`, `left_click_drag` and `scroll`."
                    ),
                    "type": "array",
                },
                "coordinate2": {
                    "description": "(x, y): where a `left_click_drag` ends.",
                    "type": "array",
                },
                "pixels": {
                    "description": (
                        "The amount of scrolling: negative scrolls up, positive scrolls down. "
                        "Required only by `action=scroll`."
                    ),
                    "type": "number",
                },
                "time": {
                    "description": "The seconds to wait. Required only by `action=wait`.",
                    "type": "number",
                },
                "status": {
                    "description": "The status of the task. Required only by `action=terminate`.",
                    "type": "string",
                    "enum": ["success", "failure"],
                },
            },
            "required": ["action"],
            "type": "object",
        },
    },
}

SYSTEM_PROMPT = """# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{tool}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{{"name": <function-name>, "arguments": <args-json-object>}}
</tool_call>

# Response format

Response format for every step:
1) Thought: one concise sentence explaining the next move (no multi-step reasoning).
2) Action: a short imperative describing what to do.
3) A single <tool_call>...</tool_call> block containing only the JSON: {{"name": <function-name>, "arguments": <args-json-object>}}.

Rules:
- Output exactly in the order: Thought, Action, <tool_call>.
- Be brief: one sentence for Thought, one for Action.
- Do not output anything else outside those three parts.
- If finishing, use {tool_name} with action=terminate in the tool call.
- Never type passwords, PINs, card numbers or one-time codes, and never confirm a payment, a transfer or an order on your own: use action=ask_user before that step and describe what the screen is about to do.
- Do only what the query asks: do not send, buy, delete or post anything it did not name.
- When the query asks for information, put everything you read that answers it (names, times, prices, seat numbers, order state) in action=answer, exactly as shown on the screen, before terminating.
- Write `text` for answer and ask_user in {language}.
"""

USER_TEMPLATE = """
The user query: {instruction}
Task progress (You have done the following operation on the current device): {steps}
"""

# a device with an accessibility tree adds this after the template — the picture stays the
# first input, the list helps read small text and shows which field is a password field
NODES_TEMPLATE = """
Elements the phone reports on this screen (text · kind [flags] @ x,y on the 999×999 grid). The picture decides; use these to read small text and to aim:
{nodes}
"""


@dataclass
class Outcome:
    status: str  # done | ask | abort | blocked | failed | max_steps
    message: str = ""
    steps: int = 0
    last_screen: str = ""
    last_image: str | None = None
    actions: list[str] = field(default_factory=list)
    trace_id: str = ""
    noun: str = "phone"  # what was operated: "phone" or "computer"

    def report(self) -> str:
        n = self.noun
        head = {
            "done": f"The {n} operator finished.",
            "ask": f"The {n} operator stopped: it needs the user.",
            "stopped": f"The user pressed Stop on the {n}.",
            "abort": f"The {n} operator gave up.",
            "blocked": f"The {n} operator was stopped by the Sentinel (an approval was refused).",
            "failed": f"The {n} operator could not continue.",
            "max_steps": f"The {n} operator ran out of steps before finishing.",
        }.get(self.status, self.status)
        lines = [f"{head} ({self.steps} step{'s' if self.steps != 1 else ''})"]
        if self.message:
            lines.append(self.message.strip())
        if self.actions:
            shown = self.actions[-8:]
            lines.append(
                "Steps taken:"
                + ("" if len(self.actions) <= 8 else f" (last {len(shown)} of {len(self.actions)})")
            )
            lines.extend(f"- {a}" for a in shown)
        if self.last_screen and self.status != "done":
            lines.append("Screen when it stopped: " + self.last_screen.replace("\n", "; "))
        if self.trace_id:
            lines.append(f"Trace: {self.trace_id}")
        return "\n".join(lines)


# ------------------------------------------------------------------ parsing the reply

_THINK_RE = re.compile(r"<think>.*?</think>", re.DOTALL)
_TOOL_CALL_RE = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL)
_JSON_RE = re.compile(r"\{.*\}", re.DOTALL)


@dataclass
class Step:
    """One parsed reply: what the model thought, what it said it would do, and the call."""

    thought: str
    action: str  # the "Action:" sentence — the words the Sentinel and the trace see
    name: str  # the function called (mobile_use, normally)
    arguments: dict[str, Any]

    @property
    def kind(self) -> str:
        return str(self.arguments.get("action") or "")


def parse_tagged_text(text: str) -> dict[str, Any]:
    """``Thought: … Action: … <tool_call>{…}</tool_call>`` → its three parts.

    Ported from MemGUI-Bench, made tolerant: the tags may be missing or out of order, the
    JSON may sit in a code fence or stand alone, and ``<think>`` blocks are ignored.
    """
    text = _THINK_RE.sub("", text or "").strip()
    result: dict[str, Any] = {"thinking": None, "conclusion": None, "tool_call": None}
    if not text:
        return result

    head, payload = text, ""
    m = _TOOL_CALL_RE.search(text)
    if m:
        payload = m.group(1)
        head = text[: m.start()]
    else:
        fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if fenced:
            payload, head = fenced.group(1), text[: fenced.start()]
        else:
            bare = _JSON_RE.search(text)
            if bare:
                payload, head = bare.group(0), text[: bare.start()]
    if payload:
        try:
            result["tool_call"] = json.loads(payload)
        except json.JSONDecodeError as exc:
            raise ValueError(f"tool_call is not valid JSON: {exc}") from exc

    before, tagged, rest = head.partition("Thought:")
    if not tagged:
        rest = before  # no "Thought:" — whatever precedes "Action:" is the thinking
    thinking, tagged, action = rest.partition("Action:")
    if not tagged:
        thinking, action = rest, ""
    result["thinking"] = thinking.strip() or None
    result["conclusion"] = action.strip().strip('"') or None
    return result


def parse_step(text: str | None, tool: str = "mobile_use") -> Step | None:
    """The model's reply as a :class:`Step`, or None when it is not one. ``tool`` is the
    function the dialect offers (``mobile_use`` / ``computer_use``): the name assumed for a
    bare arguments object, and the one whose ``action`` must be a string."""
    if not text:
        return None
    try:
        parts = parse_tagged_text(text)
    except ValueError:
        return None
    call = parts.get("tool_call")
    if not isinstance(call, dict):
        return None
    if "arguments" in call and isinstance(call["arguments"], dict):
        name = str(call.get("name") or tool)
        arguments = dict(call["arguments"])
    elif "action" in call:
        # the bare arguments object, without the {"name":..., "arguments":...} wrapper
        name, arguments = tool, dict(call)
    else:
        return None
    if name == tool and not isinstance(arguments.get("action"), str):
        return None
    for key in ("coordinate", "coordinate2"):
        if key in arguments:
            arguments[key] = _normalise_point(arguments[key])
    # some replies fold Thought and Action into one line: the thought then names the target
    conclusion = parts.get("conclusion") or parts.get("thinking") or _describe(arguments)
    return Step(
        thought=str(parts.get("thinking") or "")[:400],
        action=str(conclusion)[:200],
        name=name,
        arguments=arguments,
    )


def _normalise_point(value: Any) -> list[float]:
    """``[x, y]`` or ``[x1, y1, x2, y2]`` in the 999 space → ``[x, y]`` as fractions."""
    if isinstance(value, str):
        value = [float(v) for v in re.findall(r"-?\d+(?:\.\d+)?", value)]
    if not isinstance(value, list | tuple):
        raise ValueError("coordinate must be a list")
    nums = [float(v) for v in value]
    if len(nums) == 4:
        nums = [(nums[0] + nums[2]) / 2, (nums[1] + nums[3]) / 2]
    if len(nums) != 2:
        raise ValueError("coordinate must have two numbers")
    return [min(1.0, max(0.0, nums[0] / SCALE_FACTOR)), min(1.0, max(0.0, nums[1] / SCALE_FACTOR))]


def _describe(arguments: dict[str, Any]) -> str:
    kind = str(arguments.get("action") or "")
    if kind in _POINTED_KINDS and "coordinate" in arguments:
        x, y = arguments["coordinate"]
        return f"{kind} at ({x:.2f}, {y:.2f}) of the screen"
    if kind == "type":
        return f"type {str(arguments.get('text') or '')[:60]!r}"
    if kind == "system_button":
        return f"press {arguments.get('button') or '?'}"
    if kind == "key":
        return "press " + "+".join(str(k) for k in (arguments.get("keys") or []))
    if kind == "open":
        return f"open {arguments.get('text') or '?'}"
    return kind or "?"


_POINTED_KINDS = (
    "click",
    "long_press",
    "left_click",
    "right_click",
    "middle_click",
    "double_click",
    "mouse_move",
)


def to_device_action(step: Step, screen: Screen) -> dict[str, Any] | None:
    """The ``phone_act`` arguments for a ``mobile_use`` step; None for the ones that end the
    loop (answer, terminate, ask_user)."""
    a = step.arguments
    kind = step.kind
    w, h = screen.width or 1, screen.height or 1
    label = step.action

    def point(key: str) -> tuple[float, float]:
        if key not in a:
            raise ValueError(f"`{kind}` needs `{key}`")
        fx, fy = a[key]
        return round(fx * w, 1), round(fy * h, 1)

    if kind == "click":
        x, y = point("coordinate")
        return {"action": "tap", "x": x, "y": y, "label": label}
    if kind == "long_press":
        x, y = point("coordinate")
        seconds = _seconds(a.get("time"), default=1.0, cap=5.0)
        return {"action": "long_press", "x": x, "y": y, "seconds": seconds, "label": label}
    if kind == "swipe":
        x, y = point("coordinate")
        x2, y2 = point("coordinate2")
        return {"action": "swipe", "x": x, "y": y, "x2": x2, "y2": y2, "label": label}
    if kind == "type":
        return {"action": "type", "text": str(a.get("text") or ""), "label": label}
    if kind == "system_button":
        button = str(a.get("button") or "").lower()
        mapped = {"back": "back", "home": "home", "enter": "enter", "menu": "recents"}.get(button)
        if not mapped:
            raise ValueError(f"unknown system button {a.get('button')!r}")
        return {"action": mapped, "label": label}
    if kind == "wait":
        return {"action": "wait", "seconds": _seconds(a.get("time"), default=2.0, cap=10.0)}
    if kind == "open":
        app = str(a.get("text") or a.get("app") or "").strip()
        if not app:
            raise ValueError("`open` needs the app's name in `text`")
        return {"action": "open_app", "app": app, "label": label}
    if kind in ("answer", "terminate", "ask_user"):
        return None
    raise ValueError(f"unknown action {kind!r}")


def to_computer_action(step: Step, screen: Screen) -> dict[str, Any] | None:
    """The ``computer_act`` arguments for a ``computer_use`` step; None for the ones that
    end the loop (answer, terminate, ask_user)."""
    a = step.arguments
    kind = step.kind
    w, h = screen.width or 1, screen.height or 1
    label = step.action

    def point(key: str) -> tuple[float, float]:
        if key not in a:
            raise ValueError(f"`{kind}` needs `{key}`")
        fx, fy = a[key]
        return round(fx * w, 1), round(fy * h, 1)

    clicks = {
        "left_click": "click",
        "right_click": "right_click",
        "middle_click": "middle_click",
        "double_click": "double_click",
        "mouse_move": "move",
    }
    if kind in clicks:
        x, y = point("coordinate")
        return {"action": clicks[kind], "x": x, "y": y, "label": label}
    if kind == "left_click_drag":
        x, y = point("coordinate")
        x2, y2 = point("coordinate2")
        return {"action": "drag", "x": x, "y": y, "x2": x2, "y2": y2, "label": label}
    if kind == "scroll":
        x, y = point("coordinate")
        raw_pixels = a.get("pixels")
        try:
            pixels = float(raw_pixels) if raw_pixels is not None else 300.0
        except (TypeError, ValueError):
            pixels = 300.0
        return {"action": "scroll", "x": x, "y": y, "dy": pixels, "label": label}
    if kind == "type":
        return {"action": "type", "text": str(a.get("text") or ""), "label": label}
    if kind == "key":
        keys = a.get("keys")
        if isinstance(keys, str):
            keys = [k for k in re.split(r"[+\s]+", keys) if k]
        if not isinstance(keys, list) or not keys:
            raise ValueError('`key` needs `keys`: a list such as ["ctrl", "s"]')
        return {"action": "key", "keys": [str(k) for k in keys][:5], "label": label}
    if kind == "wait":
        return {"action": "wait", "seconds": _seconds(a.get("time"), default=2.0, cap=10.0)}
    if kind == "open":
        app = str(a.get("text") or a.get("app") or "").strip()
        if not app:
            raise ValueError("`open` needs the application's name in `text`")
        return {"action": "open_app", "app": app, "label": label}
    if kind in ("answer", "terminate", "ask_user"):
        return None
    raise ValueError(f"unknown action {kind!r}")


def _seconds(value: Any, default: float, cap: float) -> float:
    try:
        seconds = float(value) if value is not None else default
    except (TypeError, ValueError):
        seconds = default
    return max(0.2, min(cap, seconds))


@dataclass(frozen=True)
class Dialect:
    """How the operator's model is spoken to: the function it may call, what the actions
    become on the device, and the word for the device in reports."""

    name: str  # the function's name: mobile_use | computer_use
    tool: dict[str, Any]
    noun: str  # phone | computer
    to_action: Callable[[Step, Screen], dict[str, Any] | None]
    # one line of extra rules for the system prompt, or ""
    rules: str = ""


MOBILE = Dialect("mobile_use", MOBILE_USE_TOOL, "phone", to_device_action)
COMPUTER = Dialect(
    "computer_use",
    COMPUTER_USE_TOOL,
    "computer",
    to_computer_action,
    rules=(
        "- Prefer the keyboard where it is exact (a shortcut, typing in a focused field) and "
        "the mouse for what is only on the screen; after `open`, wait for the window before "
        "clicking in it.\n"
    ),
)


# ------------------------------------------------------------------ the operator


class PhoneOperator:
    def __init__(
        self,
        link: PhoneLink,
        settings: GUISettings,
        sentinel: Sentinel,
        act_tool: BaseTool,
        ui: UI,
        make_llm: Callable[[], BaseLLM],
        language: Callable[[], str] | None = None,
        traces_dir: Path | None = None,
        dialect: Dialect = MOBILE,
    ):
        self.link = link
        self.settings = settings
        self.dialect = dialect
        self.sentinel = sentinel
        self.act_tool = act_tool
        self.ui = ui
        self._make_llm = make_llm
        self._llm: BaseLLM | None = None
        self._language = language or (lambda: "the language of the query")
        self.traces_dir = traces_dir
        # how many times a reply that is not a step is asked again before giving up
        self.parse_retries = 3

    @property
    def llm(self) -> BaseLLM:
        if self._llm is None:
            self._llm = self._make_llm()
        return self._llm

    def reset_llm(self) -> None:
        self._llm = None

    def system_prompt(self) -> str:
        prompt = SYSTEM_PROMPT.format(
            tool=json.dumps(self.dialect.tool, ensure_ascii=False),
            tool_name=self.dialect.name,
            language=self._language(),
        )
        return prompt.rstrip("\n") + "\n" + self.dialect.rules if self.dialect.rules else prompt

    # ------------------------------------------------------------------ the loop
    async def run(self, goal: str, context: str = "", app: str = "") -> Outcome:
        outcome = Outcome(status="failed", noun=self.dialect.noun)
        instruction = goal.strip()
        if context.strip():
            instruction += f"\n(Known already: {context.strip()})"
        trace = Trace.start(self.traces_dir, goal=goal, app=app, context=context)
        outcome.trace_id = trace.id
        if self.llm.vision_available is False:
            outcome.message = (
                "the operator model does not take images; set [gui] model to one that does"
            )
            trace.end(outcome)
            return outcome

        steps: list[str] = []  # the "Action:" sentences, with results appended
        recent: list[str] = []  # the last tool calls, to notice loops
        await self.link.task_event("begin", goal)
        try:
            if app:
                await self._act({"action": "open_app", "app": app, "label": f"open {app}"}, outcome)
                screen = self.link.last_screen or await self.link.screen()
            else:
                screen = await self.link.screen()
        except DeviceStopped as exc:
            outcome.status, outcome.message = "stopped", str(exc)
            trace.end(outcome)
            await self.link.task_event("end")
            return outcome
        except DeviceError as exc:
            outcome.message = str(exc)
            trace.end(outcome)
            await self.link.task_event("end")
            return outcome

        refusals = 0
        # No step cap unless configured: the task runs until it is done, asks, is stopped
        # or fails. A cap (max_steps > 0) still ends with status "max_steps".
        step_numbers = (
            range(1, self.settings.max_steps + 1)
            if self.settings.max_steps > 0
            else itertools.count(1)
        )
        for step_no in step_numbers:
            outcome.steps = step_no
            outcome.last_screen = screen.render()
            outcome.last_image = screen.image_path
            if not screen.image_path:
                outcome.status = "failed"
                outcome.message = f"the {self.dialect.noun} sent no screenshot; the operator cannot see the screen"
                break

            step, raw, latency_ms = await self._decide(instruction, steps, screen)
            if step is None:
                outcome.status = "failed"
                outcome.message = "the operator model did not produce usable actions"
                trace.step(step_no, screen, raw=raw, latency_ms=latency_ms, error=outcome.message)
                break
            entry = step.action.replace("\n", " ").replace('"', "")
            logger.info(
                "{} step {}: {} — {}", self.dialect.noun, step_no, step.action, step.arguments
            )

            if step.name != self.dialect.name:
                steps.append(f"{entry}; Result: only the {self.dialect.name} function is available")
                trace.step(step_no, screen, step=step, raw=raw, latency_ms=latency_ms)
                continue

            kind = step.kind
            if kind in ("answer", "terminate", "ask_user"):
                text = str(step.arguments.get("text") or "").strip()
                if kind == "ask_user":
                    outcome.status, outcome.message = "ask", text or step.thought
                elif kind == "answer":
                    outcome.status, outcome.message = "done", text or step.thought
                else:
                    ok = str(step.arguments.get("status") or "success") == "success"
                    outcome.status = "done" if ok else "abort"
                    outcome.message = text or step.thought
                trace.step(step_no, screen, step=step, raw=raw, latency_ms=latency_ms)
                break

            try:
                params = self.dialect.to_action(step, screen)
            except ValueError as exc:
                steps.append(f"{entry}; Result: {exc}")
                trace.step(
                    step_no, screen, step=step, raw=raw, latency_ms=latency_ms, error=str(exc)
                )
                continue
            assert params is not None

            signature = json.dumps(step.arguments, sort_keys=True, ensure_ascii=False)
            recent.append(signature)
            if len(recent) >= 3 and len(set(recent[-3:])) == 1:
                steps.append(
                    f"{entry}; Note: this same action was taken three times with no visible "
                    "change — try another way, or terminate with status failure"
                )
                recent.clear()
                trace.step(step_no, screen, step=step, raw=raw, latency_ms=latency_ms, error="loop")
                continue

            result = await self._act(params, outcome)
            trace.step(
                step_no,
                screen,
                step=step,
                raw=raw,
                latency_ms=latency_ms,
                params=params,
                error=result.error,
            )
            if result.error and STOP_MARKER in result.error:
                outcome.status = "stopped"
                outcome.message = str(DeviceStopped())
                break
            if result.error and "Sentinel blocked" in result.error:
                refusals += 1
                steps.append(f"{entry}; Result: the owner refused this step")
                if refusals >= 2:
                    outcome.status = "blocked"
                    outcome.message = result.error
                    break
            elif result.error:
                steps.append(f"{entry}; Result: {result.error[:200]}")
            else:
                refusals = 0
                steps.append(entry)

            try:
                fresh = self.link.last_screen if not result.error else None
                screen = fresh if fresh is not None else await self.link.screen()
            except DeviceStopped as exc:
                outcome.status, outcome.message = "stopped", str(exc)
                break
            except DeviceError as exc:
                outcome.message = str(exc)
                break
        else:
            outcome.status = "max_steps"
            outcome.message = f"stopped after {self.settings.max_steps} steps"

        trace.end(outcome)
        if outcome.status in ("ask", "blocked"):
            # the user is looking at the operated app, not at the chat: say so there
            await self.link.task_event("notice", outcome.message or outcome.status)
        else:
            await self.link.task_event("end")
        return outcome

    # ------------------------------------------------------------------ helpers
    async def _decide(
        self, instruction: str, steps: list[str], screen: Screen
    ) -> tuple[Step | None, str, int]:
        """Ask the model for the next step; a reply that is not one is asked again."""
        progress = "".join(f"Step {i}: {s}; " for i, s in enumerate(steps, start=1))
        text = USER_TEMPLATE.format(instruction=instruction, steps=progress)
        if screen.nodes and screen.width and screen.height:
            scale = (SCALE_FACTOR / screen.width, SCALE_FACTOR / screen.height)
            text += NODES_TEMPLATE.format(nodes="\n".join(screen.node_lines(scale=scale)))
        messages = [
            Message.system(self.system_prompt()),
            Message.user(text, images=[screen.image_path] if screen.image_path else None),
        ]
        raw = ""
        started = time.monotonic()
        for attempt in range(self.parse_retries):
            response = await self.llm.ask_complete(messages)
            raw = response.content or ""
            step = parse_step(raw, tool=self.dialect.name)
            if step is not None:
                return step, raw, int((time.monotonic() - started) * 1000)
            logger.debug("phone step: reply was not a step (try {}): {!r}", attempt + 1, raw[:600])
            if attempt + 1 < self.parse_retries:
                messages = messages[:2] + [
                    Message.assistant(content=raw),
                    Message.user(
                        "That was not in the response format. Reply with Thought, Action and one "
                        f"<tool_call> block calling {self.dialect.name}."
                    ),
                ]
        return None, raw, int((time.monotonic() - started) * 1000)

    async def _act(self, params: dict[str, Any], outcome: Outcome) -> ToolResult:
        call = ToolCall(
            function=Function(
                name=self.act_tool.name, arguments=json.dumps(params, ensure_ascii=False)
            )
        )
        summary = self.act_tool.assess(params).summary
        outcome.actions.append(summary.removeprefix(f"{self.act_tool.name}: "))
        self.ui.on_tool_call(call, summary)
        result = await self.sentinel.guard(call, self.act_tool)
        self.ui.on_tool_result(call, result)
        if result.error:
            logger.info("{} step failed: {}", self.dialect.noun, result.error)
        return result


__all__ = [
    "COMPUTER",
    "COMPUTER_USE_TOOL",
    "MOBILE",
    "MOBILE_USE_TOOL",
    "NODES_TEMPLATE",
    "SCALE_FACTOR",
    "SYSTEM_PROMPT",
    "USER_TEMPLATE",
    "Dialect",
    "Outcome",
    "PhoneOperator",
    "Step",
    "parse_step",
    "parse_tagged_text",
    "to_computer_action",
    "to_device_action",
]
