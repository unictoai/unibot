"""The agent loop: think (LLM) → act (tools through the Sentinel) → repeat."""

from __future__ import annotations

import asyncio
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from unibot import prompts
from unibot.calendar import CalendarFeeds
from unibot.config import Settings
from unibot.contacts import ContactBook
from unibot.fsutil import atomic_write_text
from unibot.goals import GoalStore
from unibot.llm.base import BaseLLM
from unibot.logger import logger
from unibot.memory import MemoryItem, MemoryStore
from unibot.reminders import user_timezone
from unibot.runtime import device
from unibot.schema import AgentState, Attachment, Message, Role, ToolResult
from unibot.sentinel import AuditLog, Sentinel
from unibot.skills import SkillLibrary
from unibot.tools.base import ToolCollection
from unibot.ui import UI


class Incoming:
    """A user message on its way to the agent: the text and what was attached to it."""

    __slots__ = ("files", "text")

    def __init__(self, text: str, files: list[Attachment] | None = None):
        self.text = text
        self.files = files or []


class UnibotAgent:
    def __init__(
        self,
        settings: Settings,
        llm: BaseLLM,
        tools: ToolCollection,
        sentinel: Sentinel,
        ui: UI,
        audit: AuditLog,
        memory: MemoryStore | None = None,
        goals: GoalStore | None = None,
        calendar: CalendarFeeds | None = None,
        contacts: ContactBook | None = None,
        skills: SkillLibrary | None = None,
        session_file: Path | None = None,
    ):
        self.settings = settings
        self.llm = llm
        self.tools = tools
        self.sentinel = sentinel
        self.ui = ui
        self.audit = audit
        self.memory = memory
        self.goals = goals
        self.calendar = calendar
        self.contacts = contacts
        self.skills = skills
        self.session_file = session_file
        self.messages: list[Message] = []
        self.state = AgentState.IDLE
        self.turns = 0
        # This conversation's taint flag, passed to the Sentinel on every guard()
        # call: reading private data taints *this* thread only. Clearing another
        # chat must not un-taint this one while it still holds private data.
        self.tainted = False
        # Set when the thread is deleted: the cancelled run's finally blocks must
        # not write the transcript back to disk.
        self.deleted = False
        # Optional queue of user messages that arrive *while* a run is in progress (the app
        # lets you interrupt or pile on requests). They are folded into the conversation
        # before the next model call instead of waiting for the current run to finish.
        self.inbox: asyncio.Queue[str | Incoming] | None = None
        self._told_no_vision = False
        self._vision_warned_model: str | None = None

    def _drain_inbox(self) -> int:
        if self.inbox is None:
            return 0
        count = 0
        while not self.inbox.empty():
            try:
                item = self.inbox.get_nowait()
            except asyncio.QueueEmpty:  # pragma: no cover
                break
            incoming = item if isinstance(item, Incoming) else Incoming(item)
            self.messages.append(self.user_message(incoming.text, incoming.files))
            self.audit.record("user_message", content=incoming.text, interjected=True)
            count += 1
        return count

    def user_message(self, text: str, files: list[Attachment] | None = None) -> Message:
        """The user's message as the model gets it: the text, a line per attached file
        (path and what it is), and the pictures as images when the model takes them."""
        if not files:
            return Message.user(text)
        lines = [f"- {a.path} ({a.describe()})" for a in files]
        note = "[Attached files]\n" + "\n".join(lines)
        if any(a.kind != "image" for a in files):
            note += "\nRead them with `files` action=read."
        content = f"{text}\n\n{note}" if text.strip() else note
        workspace = Path(self.settings.agent.workspace).expanduser()
        images = [str(workspace / a.path) for a in files if a.kind == "image"]
        return Message.user(content, images=images or None)

    # ------------------------------------------------------------------ prompt
    def sandbox_note(self) -> str:
        """How commands run, from the shell tool's sandbox (the tools own it)."""
        shell = self.tools.get("shell")
        box = getattr(shell, "sandbox", None)
        if box is not None and box.active:
            note = box.describe().replace("Commands run", "Commands (shell, python_execute) run")
            if box.blocks_network:
                note += (
                    " Commands that need the network say so by what they run (curl, pip, git, a "
                    "URL, a script that imports requests …); otherwise pass network=true."
                )
            return note
        return "Commands (shell, python_execute) run in the workspace with a scrubbed environment."

    def skills_section(self) -> str:
        """The index of skills — names and descriptions; the tool has the instructions."""
        lib = self.skills
        if lib is None or "skills" not in self.tools:
            return ""
        index = lib.index()
        if not index:
            return ""
        return prompts.SKILLS_SECTION.format(items=index)

    def phone_section(self) -> str:
        """The phone, when GUI operation is on: connected or not, and what is on it."""
        task = self.tools.get("phone_task")
        link = getattr(task, "link", None)
        if task is None or link is None:
            return ""
        device = link.device
        if device is None:
            status = (
                "- GUI operation is on, but no phone is connected right now: the `phone_*` tools "
                "will fail until the user opens the unibot app on the phone (or the MobileGym "
                "module). Say so if a step needs the phone."
            )
        else:
            apps = device.app_list()
            status = f"- The user's phone is connected: {device.name} ({device.platform})."
            if apps:
                status += f" Apps on it: {apps}."
        return prompts.PHONE_SECTION.format(status=status)

    def device_section(self) -> str:
        """The phone this runs on, when its capabilities are there as `device__*` tools."""
        from unibot.runtime import DEVICE_PREFIX

        names = sorted(
            t.name[len(DEVICE_PREFIX) :] for t in self.tools if t.name.startswith(DEVICE_PREFIX)
        )
        if not names:
            return ""
        return prompts.DEVICE_SECTION.format(tools=", ".join(names))

    def computer_section(self) -> str:
        """This computer's screen, when the hands are on: available or not, and what is in front.
        On a computer with the hands off, one line saying where the switch is — so a request
        for a desktop application gets the way to it, not a shrug."""
        task = self.tools.get("computer_task")
        link = getattr(task, "link", None)
        if task is None or link is None:
            if device() is None and not self.settings.hands.enabled:
                return prompts.COMPUTER_OFF_SECTION
            return ""
        status = link.status() if hasattr(link, "status") else {}
        if not status.get("available", True):
            reason = status.get("reason") or "no backend"
            status_line = (
                "- The hands are turned on, but they cannot drive this computer right now "
                f"({reason}): the `computer_*` tools will fail. Say so if a step needs the screen."
            )
        else:
            last = link.last_screen
            status_line = (
                f"- The hands can drive this computer ({status.get('backend') or 'hands'} on "
                f"{link.device.platform}, screen {link.device.width}×{link.device.height})."
            )
            if last is not None and last.title:
                status_line += f" In front last time: {last.title}."
        return prompts.COMPUTER_SECTION.format(status=status_line)

    def devices_section(self) -> str:
        """The user's other devices, when this computer is on the hub (the `devices` tool)."""
        tool = self.tools.get("devices")
        hub = getattr(tool, "hub", None)
        if tool is None or hub is None:
            return ""
        others = hub.others()
        if not getattr(hub, "client", None) or not hub.client.connected.is_set():
            status = (
                "- This computer is not connected to the unibot hub right now, so the other "
                "devices cannot be reached; say so if a step needs one."
            )
        elif not others:
            status = (
                f"- This computer is on the hub as “{hub.device_name}”; no other device of the "
                "user's has joined yet."
            )
        else:
            listed = ", ".join(
                f"{d.get('name')} ({d.get('kind') or 'device'}, "
                f"{'online' if d.get('online') else 'offline'})"
                for d in others
            )
            status = f"- This computer is “{hub.device_name}”. Other devices: {listed}."
        return prompts.DEVICES_SECTION.format(status=status)

    def contacts_note(self) -> str:
        """One line on the address book, when there is one (the tool does the looking up)."""
        book = self.contacts
        if book is None or "contacts" not in self.tools or not book.configured:
            return ""
        n = len(book)
        return (
            f"- Address book: {n} {'person' if n == 1 else 'people'} — look someone up with "
            "`contacts` before writing to them; never guess an address\n"
        )

    async def recall_for(self, user_input: str) -> list[MemoryItem] | None:
        """The memories to put in the system prompt for this message — by meaning too,
        when an embedding endpoint is set up (that is the awaited part)."""
        if self.memory is None or not self.settings.memory.enabled:
            return None
        try:
            return await self.memory.relevant_async(
                user_input, limit=self.settings.memory.max_inject
            )
        except Exception as exc:  # noqa: BLE001 — recall must never stop a turn
            logger.warning("recall failed ({}); using the keyword ranking", exc)
            return self.memory.relevant(user_input, limit=self.settings.memory.max_inject)

    def build_system_prompt(
        self, user_input: str, memories_: list[MemoryItem] | None = None
    ) -> str:
        """``memories_`` is what ``recall_for`` returned; without it the keyword ranking is
        used on the spot."""
        a = self.settings.agent
        language_rule = (
            prompts.LANGUAGE_AUTO.format(detected=prompts.detect_language(user_input))
            if a.language in ("", "auto")
            else prompts.LANGUAGE_FIXED.format(language=a.language)
        )
        memories = ""
        if self.memory is not None and self.settings.memory.enabled:
            items = (
                memories_
                if memories_ is not None
                else self.memory.relevant(user_input, limit=self.settings.memory.max_inject)
            )
            if items:
                memories = prompts.MEMORY_SECTION.format(
                    items="\n".join(f"- {m.render()}" for m in items)
                )
        goals = ""
        if self.goals is not None:
            active = self.goals.list("active")[:5]
            if active:
                lines = []
                for g in active:
                    nxt = g.next_step
                    bits = [f"progress {g.progress}"]
                    if g.category:
                        bits.append(g.category)
                    if g.due:
                        bits.append(f"due {g.due}" + (" — overdue" if g.overdue else ""))
                    if nxt:
                        bits.append(f"next: {nxt.idx}. {nxt.title}")
                    if g.proposal:
                        bits.append("a plan change is awaiting the user's answer")
                    lines.append(f"- {g.id}: {g.title} ({', '.join(bits)})")
                goals = prompts.GOALS_SECTION.format(items="\n".join(lines))
        calendar = ""
        if self.calendar is not None and self.calendar.configured:
            # today and tomorrow, from the cache — the tool fetches when more is needed
            today = datetime.now(self.calendar.tz).date()
            events = self.calendar.agenda(today, 2)
            calendar = prompts.CALENDAR_SECTION.format(
                items=self.calendar.render(events, today)
                if events
                else "(nothing on the calendar today or tomorrow)"
            )
        profile = (
            prompts.USER_PROFILE_SECTION.format(profile=a.user_profile.strip())
            if a.user_profile.strip()
            else ""
        )
        extra = (
            f"\n## Additional instructions\n{a.instructions.strip()}\n"
            if a.instructions.strip()
            else ""
        )
        return prompts.SYSTEM_PROMPT.format(
            name=a.name,
            language_rule=language_rule,
            # the "now" the model reasons from is in the user's timezone, so relative
            # times it writes ("tomorrow 09:00") land on the right wall clock
            now=datetime.now(user_timezone(a.timezone)).strftime("%Y-%m-%d %H:%M (%A, UTC%z)"),
            workspace=str(a.workspace.resolve()),
            sentinel_mode=self.settings.sentinel.mode,
            sandbox=self.sandbox_note(),
            contacts=self.contacts_note(),
            tool_names=", ".join(t.name for t in self.tools),
            user_profile=profile,
            memories=memories,
            goals=goals
            + calendar
            + self.device_section()
            + self.phone_section()
            + self.computer_section()
            + self.devices_section()
            + self.skills_section(),
            extra=extra,
        )

    # ------------------------------------------------------------------ context window
    def context_messages(self) -> list[Message]:
        limit = self.settings.agent.max_context_messages
        msgs = self.messages
        if len(msgs) <= limit:
            return list(msgs)
        cutoff = len(msgs) - limit
        # Never start in the middle of a tool exchange: advance to the next user message.
        while cutoff < len(msgs) and msgs[cutoff].role != Role.USER:
            cutoff += 1
        if cutoff < len(msgs):
            return list(msgs[cutoff:])
        # No user message past the cutoff (a long autonomous run): take the tail,
        # but never start on a dangling tool result — OpenAI-compatible providers
        # reject a `tool` message with no preceding `assistant.tool_calls` (HTTP 400).
        start = len(msgs) - limit
        while start < len(msgs) and msgs[start].role == Role.TOOL:
            start += 1
        return list(msgs[start:])

    # ------------------------------------------------------------------ stuck detection
    def _is_stuck(self) -> bool:
        """True when the agent is looping: the same step signature (tool name +
        normalized args + the first 200 chars of its result) repeats 3+ times
        inside the last 6 assistant turns."""
        # Pair each assistant message with the tool results that followed it.
        pairs: list[tuple[Message, list[Message]]] = []
        pending: Message | None = None
        results: list[Message] = []
        for m in self.messages:
            if m.role == Role.ASSISTANT:
                if pending is not None:
                    pairs.append((pending, results))
                pending, results = m, []
            elif m.role == Role.TOOL and pending is not None:
                results.append(m)
        if pending is not None:
            pairs.append((pending, results))
        recent = pairs[-6:]
        if len(recent) < 3:
            return False

        def norm_args(args: object) -> str:
            try:
                text = json.dumps(args, ensure_ascii=False, sort_keys=True, default=str)
            except Exception:  # pragma: no cover - defensive
                text = str(args)
            return "".join(text.split())

        def sig(pair: tuple[Message, list[Message]]) -> str:
            assistant, tool_results = pair
            calls = []
            for tc in assistant.tool_calls or []:
                output = ""
                for r in tool_results:
                    if r.tool_call_id == tc.id:
                        output = (r.content or "")[:200]
                        break
                calls.append((tc.function.name, norm_args(tc.function.arguments), output))
            return json.dumps([assistant.content, calls], ensure_ascii=False, sort_keys=True)

        seen: dict[str, int] = {}
        for s in (sig(p) for p in recent):
            seen[s] = seen.get(s, 0) + 1
            if seen[s] >= 3:
                return True
        return False

    # ------------------------------------------------------------------ main loop
    async def run(
        self,
        user_input: str,
        purpose: str | None = None,
        files: list[Attachment] | None = None,
    ) -> str:
        """One task: a user message (or a background prompt) worked to completion.

        ``purpose`` is what approval cards show as the reason for an action; it defaults
        to the message itself. ``files`` are the attachments that came with the message.
        Task-scoped approvals end when this call returns.
        """
        if self.state == AgentState.RUNNING:
            raise RuntimeError("agent is already running")
        self.state = AgentState.RUNNING
        self.turns += 1
        if self.skills is not None and "skills" in self.tools:
            # "/weekly-review …" — the skill's instructions ride along with the message
            user_input = self.skills.expand(user_input)
        self.messages.append(self.user_message(user_input, files))
        attached = {"files": [a.path for a in files]} if files else {}
        self.audit.record("user_message", content=user_input, **attached)
        system_prompt = self.build_system_prompt(user_input, await self.recall_for(user_input))
        tool_params = self.tools.to_params()
        final: str | None = None
        step = 0
        empty_replies = 0
        task_token = self.sentinel.begin_task(purpose or user_input)
        try:
            while step < self.settings.agent.max_steps:
                step += 1
                if self._drain_inbox():
                    logger.debug("folded queued user message(s) into the running turn")
                    # the language rule and the memory section follow the latest message
                    latest = self.messages[-1].content or ""
                    system_prompt = self.build_system_prompt(latest, await self.recall_for(latest))
                context = [Message.system(system_prompt), *self.context_messages()]
                response = await self.llm.ask(
                    context,
                    tools=tool_params,
                    on_delta=self.ui.on_text_delta,
                    on_stream_reset=getattr(self.ui, "on_stream_reset", None),
                )
                if (
                    response.finish_reason == "length"
                    and not response.content
                    and not response.tool_calls
                ):
                    # the model thought until the budget ran out (reasoning models do on a
                    # hard step): the same call once more, with room to think and answer
                    logger.info("reply cut off before it began; asking again with more room")
                    response = await self.llm.ask(
                        context,
                        tools=tool_params,
                        on_delta=self.ui.on_text_delta,
                        max_tokens=self.llm.roomier_max_tokens(),
                        on_stream_reset=getattr(self.ui, "on_stream_reset", None),
                    )
                model_id = getattr(getattr(self.llm, "settings", None), "model", None)
                if model_id != self._vision_warned_model:
                    # The model changed since the vision warning: the new model may
                    # take images, so the one-shot warning may fire again for it.
                    self._told_no_vision = False
                if self.llm.vision_available is False and not self._told_no_vision:
                    if any(m.images for m in self.messages if m.role == Role.USER):
                        self._told_no_vision = True
                        self._vision_warned_model = model_id
                        self.ui.warn(
                            "This model does not take images, so the picture was described to "
                            "it by name only. It stays in the workspace; a model with vision "
                            "(Settings → Connections) would see it."
                        )
                assistant = response.to_message()
                assistant.meta.update({"step": step, "usage": response.usage})
                self.messages.append(assistant)
                self.ui.on_assistant_message(response.content, response.reasoning)
                self.audit.record(
                    "assistant_message",
                    step=step,
                    content=response.content or "",
                    tool_calls=[tc.function.name for tc in response.tool_calls],
                    usage=response.usage,
                )

                if not response.tool_calls:
                    if response.content:
                        final = response.content
                        break
                    empty_replies += 1
                    if empty_replies >= 2:
                        msg = "The model returned empty replies twice; stopping."
                        logger.warning(msg)
                        self.ui.warn(msg)
                        self.audit.record("empty_replies", step=step, stopped=True)
                        final = (
                            "I couldn't get a usable reply from the model — it returned "
                            "empty responses twice in a row, so I stopped. Try asking "
                            "again or switching models."
                        )
                        break
                    self.messages.append(
                        Message.user(
                            "(Your reply was empty. Continue the task, or call `terminate` if it is done.)"
                        )
                    )
                    continue

                stop = False
                pictures: list[str] = []
                for call in response.tool_calls:
                    tool = self.tools.get(call.name)
                    if tool is None:
                        summary = f"{call.name}(?)"
                    elif "__raw__" in call.arguments:
                        raw = str(call.arguments["__raw__"])
                        summary = f"{call.name}: arguments cut off ({len(raw)} chars)"
                    else:
                        summary = tool.assess(call.arguments).summary
                    self.ui.on_tool_call(call, summary)
                    if tool is None:
                        result = ToolResult.fail(
                            f"unknown tool '{call.name}'. Available tools: {', '.join(t.name for t in self.tools)}"
                        )
                    else:
                        result = await self.sentinel.guard(call, tool, taint=self)
                    self.ui.on_tool_result(call, result)
                    self.messages.append(Message.tool(result.for_model(), call.id, call.name))
                    if result.images:
                        pictures.extend(result.images)
                    if result.stop:
                        final = result.output
                        stop = True
                if stop:
                    break
                if (
                    pictures
                    and self.llm.vision_available is not False
                    and self.settings.llm.vision != "off"
                ):
                    # A tool result cannot carry an image, so the picture (the phone's screen)
                    # follows as a user message; models without vision get the text alone.
                    self.messages.append(
                        Message.user(
                            "[The screenshot that goes with the tool result above.]",
                            images=pictures[-2:],
                        )
                    )
                if self._is_stuck():
                    logger.warning("agent seems stuck – nudging")
                    self.messages.append(Message.user(prompts.STUCK_PROMPT))
            else:
                # Step budget exhausted: ask for a wrap-up without tools.
                self.messages.append(Message.user(prompts.MAX_STEPS_PROMPT))
                context = [Message.system(system_prompt), *self.context_messages()]
                response = await self.llm.ask(
                    context,
                    tools=None,
                    on_delta=self.ui.on_text_delta,
                    on_stream_reset=getattr(self.ui, "on_stream_reset", None),
                )
                self.messages.append(response.to_message())
                self.ui.on_assistant_message(response.content, response.reasoning)
                final = response.content or "(step limit reached)"
            self.state = AgentState.FINISHED
        except Exception:
            self.state = AgentState.ERROR
            raise
        finally:
            self.sentinel.end_task(task_token)
            self._save_session()
        return final or ""

    # ------------------------------------------------------------------ session persistence
    def reset(self) -> None:
        self.messages.clear()
        # only this conversation's taint is cleared; other threads keep theirs
        self.tainted = False
        self.state = AgentState.IDLE

    def _save_session(self) -> None:
        if not self.session_file or self.deleted:
            return
        try:
            data: dict[str, Any] = {
                "saved_at": datetime.now().astimezone().isoformat(timespec="seconds"),
                "turns": self.turns,
                "messages": [m.model_dump(mode="json") for m in self.messages],
            }
            atomic_write_text(
                self.session_file, json.dumps(data, ensure_ascii=False, indent=1), mode=0o600
            )
        except OSError as exc:  # pragma: no cover
            logger.warning("could not save session: {}", exc)

    def load_session(self, path: Path) -> int:
        data = json.loads(Path(path).read_text("utf-8"))
        self.messages = [Message.model_validate(m) for m in data.get("messages", [])]
        self.turns = int(data.get("turns", 0))
        self._repair_dangling_tool_calls()
        return len(self.messages)

    def _repair_dangling_tool_calls(self) -> None:
        """A restart in the middle of a tool call (say, while an approval was pending)
        leaves an assistant message whose tool calls have no results. Providers reject
        that history outright, so give every orphan a result that says what happened."""
        repaired: list[Message] = []
        i = 0
        while i < len(self.messages):
            msg = self.messages[i]
            repaired.append(msg)
            i += 1
            if msg.role != Role.ASSISTANT or not msg.tool_calls:
                continue
            answered: set[str] = set()
            while i < len(self.messages) and self.messages[i].role == Role.TOOL:
                repaired.append(self.messages[i])
                answered.add(self.messages[i].tool_call_id or "")
                i += 1
            for call in msg.tool_calls:
                if call.id not in answered:
                    repaired.append(
                        Message.tool(
                            "(no result: the app was restarted before this call finished)",
                            call.id,
                            call.function.name,
                        )
                    )
        if len(repaired) != len(self.messages):
            logger.info("repaired {} unanswered tool call(s)", len(repaired) - len(self.messages))
            self.messages = repaired


__all__ = ["UnibotAgent"]
