"""The avatar studio: a new face for the agent, drawn from a description.

The phone has had this since 0.1.20 (``ai.unicto.unibot.avatar``); this is the same flow
for the web app and the desktop, run by the runtime so both share it:

1. A chat message asks for a new look ("换个形象：一只橘猫", "new avatar: a robot owl") —
   :func:`parse_request` reads it the way the phone does — or the picker in Settings sends
   the description. The agent is not run for it.
2. A card in the chat says what it will cost (``GET /v1/estimate`` on the relay when the
   account's model is the provider; "at your provider's prices" for a key of one's own) and
   waits for a tap.
3. Four candidates are drawn — the same subject four times, different colouring or outfit —
   and shown 2×2 with *redraw*. A tap (or "第二个", "the first one") picks one.
4. The chosen picture is the idle pose; four more are edits of it — working with headphones
   at a laptop, waiting with a crystal ball, happy hugging a star, sorry with a sweat drop —
   the set the dragon has. Each lands in the workspace under ``avatar/<face>/<mood>.webp``
   and the profile's ``avatar`` becomes the face id; the app shows it through
   ``/api/files/avatar/<face>/<mood>.webp``.

Pictures come from the model endpoint the chat uses: the relay speaks the OpenAI images
API (``/v1/images/generations`` and ``/v1/images/edits``), so does any OpenAI-compatible
provider that draws; Alibaba Cloud Model Studio's own host has neither and is called on its
native multimodal endpoint with the same key. Without an image model the chat says so
plainly instead of trying.

5. Once the stills are on, four short clips — idle, working, waiting, happy, the motions the
   dragon's own clips have — are made from them with the video model (the relay's, or Model
   Studio's Wan through the same asynchronous API the phone uses: an upload, a task, polling,
   the MP4), each under ``avatar/<face>/<mood>.mp4``. The web face plays them and falls back
   to the stills where one is missing; an OpenAI-compatible provider without a video API
   simply has stills. The cost card counts the clips too.
"""

from __future__ import annotations

import asyncio
import base64
import io
import json
import re
import shutil
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any

import httpx

from unibot.cloud import CloudError, model_url
from unibot.logger import logger

if TYPE_CHECKING:
    from unibot.server.service import MuseService

MOODS = ("idle", "working", "waiting", "happy", "error")
CANDIDATES = 4
# candidates plus the four posed edits; the idle still is the chosen candidate itself
PICTURES_PER_FACE = CANDIDATES + len(MOODS) - 1
SIZE = "1024x1024"
# the stored stills: the face is never shown larger than about 200 CSS pixels
STILL_PX = 512
DASHSCOPE_IMAGE_MODEL = "qwen-image-3.0"
DASHSCOPE_VIDEO_MODEL = "wan2.2-i2v-flash"
# the moods that move; "error" stays a still, as on the phone
CLIP_MOODS = ("idle", "working", "waiting", "happy")
CLIP_SECONDS = 4
CLIP_POLL_S = 5.0
CLIP_MAX_WAIT_S = 8 * 60
# pictures in flight at once, and the pauses before a 429 is tried again
IMAGE_AT_ONCE = 2
IMAGE_PAUSES = (3.0, 6.0, 12.0)
# video tasks in flight at once (the provider allows an account a couple), and the pauses
# before a refused task is submitted again
CLIPS_AT_ONCE = 2
CLIP_PAUSES = (10.0, 20.0, 40.0)
# how long a finished or abandoned session's candidates stay on disk
SESSION_TTL_S = 24 * 3600

# the looks a face can be drawn in — the phone's ``AvatarStudio.Style``, same ids and words,
# so a description drawn on either device comes out alike; Muse's house style is the default
STYLES = {
    "muse": (
        "cute 3D character render in the style of a collectible vinyl toy, soft matte materials "
        "with subtle sheen, rounded simplified forms, big friendly eyes, soft studio lighting with "
        "gentle shadows, pastel accents"
    ),
    "flat": "flat vector illustration, soft pastel colours, clean simple shapes, subtle shading",
    "clay": "3D clay render, soft studio lighting, matte rounded forms, gentle colours",
    "watercolor": "gentle watercolour painting, soft edges, light paper texture",
    "pixel": "crisp pixel art, limited palette, clean silhouette",
    "line": "minimal line drawing with two accent colours on cream, confident strokes",
    "sticker": "glossy sticker style, thick white outline, bold saturated colours",
}
DEFAULT_STYLE = "muse"
STYLE = STYLES[DEFAULT_STYLE]
VARIATIONS = (
    "variation 1: the most typical, classic colouring",
    "variation 2: a different breed or colour pattern, lighter tones",
    "variation 3: a different breed or colour pattern, darker or warmer tones, a small accessory such as a scarf or glasses",
    "variation 4: a playful take — unusual colouring or a tiny outfit, slight head tilt",
)
KEEP = (
    "Keep this exact character — same face, colours, outfit, art style, proportions, framing, "
    "camera angle and pure white background. Change only the pose and props described. "
)
# Muse's fixed motions, one per mood, on top of the pose picture (the phone's AvatarMotion)
CLIP_ACTIONS = {
    "idle": "The character stays in place and gently shakes its head left and right while its round body sways very slightly, calm and friendly, like the idle animation of a mascot.",
    "waiting": "The character holds a small glowing crystal ball in its hands and plays with it, turning it slowly and peeking into it with curiosity.",
    "happy": "The character plays happily with a small golden five-pointed star, tossing it up a little and catching it, bouncing gently with joy.",
    "working": "The character wears headphones and types busily on the small laptop in front of it, nodding slightly to the rhythm, focused and content.",
}
CLIP_TAIL = (
    " Plain white background, static camera, no zoom, no cuts, soft even studio lighting, the same {look} "
    "as the picture throughout, nothing else appears in the frame, and the motion loops naturally "
    "with the character back in its starting pose at the end."
)


def clip_prompt(mood: str, style: str = DEFAULT_STYLE) -> str:
    look = "3D toy look" if style == "muse" else "art style"
    return CLIP_ACTIONS[mood] + CLIP_TAIL.format(look=look)


MOOD_INSTRUCTIONS = {
    "working": "It now wears over-ear headphones and sits typing on a small open laptop in front of it, focused and content, a faint glow from the screen on its face.",
    "waiting": "It now holds a small glowing crystal ball in both hands at chest height and gazes into it with wide curious eyes, waiting for an answer.",
    "happy": "It is now celebrating, hugging a big glowing yellow five-pointed star, eyes closed with a wide smile. Same white background; no confetti, no night sky, no extra decoration.",
    "error": "It now looks sheepish and apologetic, a small sweat drop beside its head, one hand behind its head, shoulders slightly raised.",
}


# ----------------------------------------------------------------------------- the words
# the phone's AvatarFlow.parseRequest / parseChoice, kept in step by hand
_ZH_REQUEST = [
    re.compile(
        r"^(?:请|麻烦|帮我|帮忙|可以|能不能|能否)?\s*(?:把|将)?\s*(?:你的|你|我的|我)?\s*(?:虚拟)?(?:形象|头像|样子|外形)\s*"
        r"(?:改|换|变|更换|切换|设置|设定|变更)(?:成|为|到|一下成|一下为)?\s*(.+)$"
    ),
    re.compile(
        r"^(?:请|麻烦|帮我|帮忙)?\s*(?:换|变|改)(?:个|一个|一下)?\s*(?:新的?)?(?:虚拟)?(?:形象|头像)\s*[：:，,、]?\s*(.+)$"
    ),
    re.compile(
        r"^(?:请|麻烦|帮我|帮忙)?\s*(?:变成|化身为|变身为|变身成)\s*(.+?)\s*(?:的)?(?:形象|样子|头像)?$"
    ),
]
_EN_REQUEST = [
    re.compile(
        r"^(?:please\s+)?(?:can you\s+)?(?:change|switch|set|update|turn|make|transform)\s+(?:your|the|my|ur)?\s*"
        r"(?:virtual\s+)?(?:avatar|appearance|look|character)\s+(?:to|into)\s+(.+)$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^(?:please\s+)?(?:new|another)\s+(?:virtual\s+)?avatar\s*[:,-]?\s*(.+)$", re.IGNORECASE
    ),
    re.compile(r"^(?:please\s+)?(?:become|be)\s+(.+)$", re.IGNORECASE),
]
_SUBJECT = re.compile(
    r"\b(cat|dog|puppy|kitten|robot|bear|panda|fox|rabbit|bunny|bird|dragon|penguin|owl|corgi|shiba|husky|otter|character|creature|monster|alien)\b",
    re.IGNORECASE,
)
_TRAILING = "。.!！~～吧呗呀哦啊嘛"


def parse_request(text: str) -> str | None:
    """The description of the new face if ``text`` asks for one, else None."""
    t = text.strip()
    if not t or len(t) > 400 or "\n" in t:
        return None
    for rx in _ZH_REQUEST + _EN_REQUEST:
        hit = rx.search(t)
        if hit:
            break
    else:
        return None
    desc = hit.group(1).strip().rstrip(_TRAILING).strip()
    desc = re.sub(r"^(an?|the)\s+", "", desc, flags=re.IGNORECASE).strip()
    if not desc or len(desc) > 200:
        return None
    head = hit.group(0).lower()
    # "be quiet", "become better": only the bare verbs with something that reads like a subject
    if (head.startswith("be ") or head.startswith("become ")) and not _SUBJECT.search(desc):
        return None
    if head.startswith(("变成", "化身", "变身")) and len(desc) < 2:
        return None
    return desc


_ORDINAL_ZH = {"一": 0, "1": 0, "二": 1, "两": 1, "2": 1, "三": 2, "3": 2, "四": 3, "4": 3}
_ORDINAL_EN = {
    "first": 0,
    "1st": 0,
    "second": 1,
    "2nd": 1,
    "third": 2,
    "3rd": 2,
    "fourth": 3,
    "4th": 3,
    "last": 3,
}
_CORNERS = {
    "左上": 0,
    "右上": 1,
    "左下": 2,
    "右下": 3,
    "top left": 0,
    "top right": 1,
    "bottom left": 2,
    "bottom right": 3,
}
_REGENERATE = re.compile(
    r"^(重新生成|再来一组|再生成|换一批|都不喜欢|都不好|都不要|再来四个|重来|regenerate|try again|another set|none of (these|them)|new options)$"
)
_CANCEL = re.compile(r"^(算了|不换了|取消|不要了|cancel|never mind|nevermind|forget it|stop)$")
_PICK_ZH = re.compile(
    r"^(?:就|选|要|我要|我选|用|我喜欢|喜欢)?\s*第\s*([一二两三四1234])\s*(?:个|只|张|款|号)?(?:吧|好了|好)?$"
)
_PICK_N = re.compile(r"^(?:就|选|要|我要|我选|用)?\s*([1-4])\s*(?:号|个|只|张)?(?:吧|好了|好)?$")
_PICK_EN = re.compile(
    r"^(?:i(?:'ll| will)? (?:take|pick|choose|like|want)|pick|choose|take|use|go with)?\s*(?:the\s+)?(?:option|number|no\.?|#)?\s*([1-4])$"
)
_PICK_EN_ORD = re.compile(
    r"^(?:i(?:'ll| will)? (?:take|pick|choose|like|want)|pick|choose|take|use|go with)?\s*(?:the\s+)?(first|1st|second|2nd|third|3rd|fourth|4th|last)(?:\s+one)?$"
)


def parse_choice(text: str) -> int | str | None:
    """While four candidates wait: the index picked (0–3), ``"regenerate"``, ``"cancel"``, or None."""
    t = text.strip().rstrip(_TRAILING).strip()
    lower = t.lower()
    if not t or len(t) > 40:
        return None
    if _REGENERATE.match(lower):
        return "regenerate"
    if _CANCEL.match(lower):
        return "cancel"
    for corner, idx in _CORNERS.items():
        if lower in (corner, f"{corner}那个", f"{corner}的", f"the {corner} one", f"{corner} one"):
            return idx
    m = _PICK_ZH.match(t)
    if m:
        return _ORDINAL_ZH[m.group(1)]
    m = _PICK_N.match(t)
    if m:
        return int(m.group(1)) - 1
    m = _PICK_EN.match(lower)
    if m:
        return int(m.group(1)) - 1
    m = _PICK_EN_ORD.match(lower)
    if m:
        return _ORDINAL_EN[m.group(1)]
    return None


def build_prompt(description: str, index: int, style: str = DEFAULT_STYLE) -> str:
    subject = description.strip().rstrip(".。!！,，")
    look = STYLES.get(style, STYLE)
    return (
        f"A cute character based on: {subject}. {look}. Full body, standing, facing the viewer, "
        "centred, whole figure visible with margin on all sides, big head and small body, friendly expression, "
        f"pure white background, soft ground shadow only. {VARIATIONS[index % CANDIDATES]}. "
        "Square composition. No text, no watermark, no border, no props other than what is described, one character only."
    )


# ----------------------------------------------------------------------------- the endpoint
@dataclass
class Endpoint:
    """Where pictures come from: the chat model's host and key, and the image model there."""

    base_url: str
    api_key: str
    image_model: str
    # the relay (the account's model): costs are asked of it first
    cloud: bool
    # the image-to-video model, when the host has the asynchronous video API (relay, Model Studio)
    video_model: str = ""

    @property
    def dashscope(self) -> bool:
        """Model Studio's own host: no OpenAI images API, the native endpoint instead."""
        b = self.base_url.lower()
        return not self.cloud and ("dashscope" in b or "aliyuncs.com" in b)

    @property
    def clips(self) -> bool:
        """Whether clips can be made here: a video model on a host that speaks the video API."""
        return bool(self.video_model) and (self.cloud or self.dashscope)

    @property
    def video_host(self) -> str:
        """Where ``/api/v1/uploads``, ``/api/v1/services/aigc/video-generation/…`` and
        ``/api/v1/tasks/…`` live: the relay's root, or Model Studio's."""
        return re.split(r"/compatible-mode|/api/v1|/v1$", self.base_url, maxsplit=1)[0].rstrip("/")


class StudioError(Exception):
    pass


NO_IMAGE_MODEL = (
    "No image model is set, so a new look cannot be drawn. Pick one under Connections → "
    "Image & video models (the account's model draws with qwen-image; Alibaba Cloud Bailian "
    "does too)."
)


@dataclass
class Session:
    id: str
    #: the chat the card is in; "" for a session run from the studio screen, which has no card
    thread: str
    event_id: str
    description: str
    style: str = DEFAULT_STYLE
    created: float = field(default_factory=time.time)
    stage: str = "estimate"  # estimate · drawing · choose · posing · done · cancelled · failed
    cost: dict[str, Any] = field(default_factory=dict)
    candidates: list[str | None] = field(default_factory=lambda: [None] * CANDIDATES)
    errors: list[str] = field(default_factory=list)
    chosen: int | None = None
    face: str | None = None
    moods: dict[str, str] = field(default_factory=dict)
    clips: dict[str, str] = field(default_factory=dict)
    message: str = ""
    tasks: list[asyncio.Task[Any]] = field(default_factory=list)

    def view(self) -> dict[str, Any]:
        return {
            "session": self.id,
            "stage": self.stage,
            "description": self.description,
            "style": self.style,
            "cost": self.cost,
            "candidates": list(self.candidates),
            "errors": list(self.errors),
            "chosen": self.chosen,
            "face": self.face,
            "moods": dict(self.moods),
            "clips": dict(self.clips),
            "message": self.message,
        }

    def cancel_tasks(self) -> None:
        for t in self.tasks:
            if not t.done():
                t.cancel()
        self.tasks.clear()


class AvatarStudio:
    """One studio per runtime; one session at a time (a new request replaces the old)."""

    def __init__(self, svc: MuseService):
        self.svc = svc
        self.current: Session | None = None
        self._http = httpx.AsyncClient(
            timeout=httpx.Timeout(240.0, connect=30.0), follow_redirects=True
        )
        # image providers allow an account a couple of pictures at a time (DashScope says
        # 429 past that): the four candidates and the four poses go two by two, and a 429
        # is waited out before it is shown
        self._gate = asyncio.Semaphore(IMAGE_AT_ONCE)
        self._clip_gate = asyncio.Semaphore(CLIPS_AT_ONCE)

    async def _post_image(self, url: str, **kwargs: Any) -> httpx.Response:
        """One picture request, two at a time, tried again after a 429 with growing pauses."""
        async with self._gate:
            for pause in (*IMAGE_PAUSES, None):
                r = await self._http.post(url, **kwargs)
                if r.status_code != 429 or pause is None:
                    return r
                logger.info("image provider busy (429); again in {:.0f}s", pause)
                await asyncio.sleep(pause)
            return r  # pragma: no cover — the loop returns

    async def close(self) -> None:
        if self.current is not None:
            self.current.cancel_tasks()
        await self._http.aclose()

    # ------------------------------------------------------------------ the endpoint
    def endpoint(self) -> Endpoint | None:
        """The chat model's host and key with an image model, or None when there is none."""
        llm = self.svc.settings.llm
        base = (llm.base_url or "").rstrip("/")
        if not base:
            return None
        key = llm.api_key
        vault = self.svc.app.vault
        if vault.has_placeholders(key):
            key = vault.resolve(key, strict=False)
            if vault.has_placeholders(key):
                key = ""
        hub = getattr(self.svc, "hub", None)
        cloud = bool(hub is not None and base == model_url(hub.cloud.base_url).rstrip("/"))
        ep = Endpoint(
            base_url=base,
            api_key=key,
            image_model=(llm.image_model or "").strip(),
            cloud=cloud,
            video_model=(llm.video_model or "").strip(),
        )
        if not ep.image_model:
            if cloud:
                ep.image_model = self._cloud_model("image", "qwen-image-3.0")
            elif ep.dashscope:
                ep.image_model = DASHSCOPE_IMAGE_MODEL
        if not ep.video_model:
            if cloud:
                ep.video_model = self._cloud_model("video", DASHSCOPE_VIDEO_MODEL)
            elif ep.dashscope:
                ep.video_model = DASHSCOPE_VIDEO_MODEL
        return ep if ep.image_model else None

    def _cloud_model(self, kind: str, default: str) -> str:
        """The relay's image or video model, from the list it sent when the account was checked."""
        hub = self.svc.hub
        for m in hub.models or []:
            nm = m.get("unibot") or {}
            arch = m.get("architecture") or {}
            if nm.get("kind") == kind or arch.get("output_modalities") == [kind]:
                return str(m.get("id") or "")
        return default

    def view(self) -> dict[str, Any]:
        ep = self.endpoint()
        return {
            "available": ep is not None,
            "image_model": ep.image_model if ep else "",
            "video_model": ep.video_model if ep and ep.clips else "",
            "cloud": bool(ep and ep.cloud),
            "host": ep.base_url.split("//", 1)[-1].split("/", 1)[0] if ep else "",
            "current": self.current.view() if self.current else None,
            "face": self.face_info(),
        }

    def face_info(self) -> dict[str, Any] | None:
        """The face the profile wears, when the studio drew it: its description, style and
        the model (``avatar/<face>/face.json``, written when the poses land). None for the
        dragon, the emoji, or a face from before the file was kept."""
        face = self.svc.profile.avatar
        if not face or face == "dragon":
            return None
        path = self.svc.workspace() / "avatar" / face / "face.json"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return {
            "id": face,
            **{k: data.get(k) for k in ("description", "style", "model", "created")},
        }

    # ------------------------------------------------------------------ the chat
    def intercept(self, thread: str, text: str) -> bool:
        """A user message that is about the face: handled here, not by the agent.

        A request for a new look starts a session (the card with the cost); while candidates
        wait, a pick / "regenerate" / "cancel" is applied. Returns True when the message was
        taken."""
        cur = self.current
        if cur is not None and cur.thread == thread and cur.stage == "choose":
            choice = parse_choice(text)
            if choice == "regenerate":
                asyncio.ensure_future(self.regenerate(cur.id))
                return True
            if choice == "cancel":
                self.cancel(cur.id)
                return True
            if isinstance(choice, int):
                asyncio.ensure_future(self.choose(cur.id, choice))
                return True
        description = parse_request(text)
        if description is None:
            return False
        asyncio.ensure_future(self.begin(thread, description))
        return True

    async def begin(
        self, thread: str, description: str, style: str = DEFAULT_STYLE
    ) -> dict[str, Any]:
        """Open a session: the card with what it will cost, waiting for a tap.

        ``style`` is one of :data:`STYLES` (the phone's list); anything else is Muse's."""
        if style not in STYLES:
            style = DEFAULT_STYLE
        ui = self.svc.ui
        ep = self.endpoint()
        if ep is None:
            if thread:
                ui.emit(
                    {
                        "type": "notice",
                        "level": "warn",
                        "text": NO_IMAGE_MODEL,
                        "code": "no_image_model",
                        "thread": thread,
                    }
                )
            return {"available": False, "message": NO_IMAGE_MODEL}
        if self.current is not None and self.current.stage not in ("done", "cancelled", "failed"):
            self.cancel(self.current.id, quiet=True)
        sid = uuid.uuid4().hex[:10]
        event_id = ""
        if thread:
            # from the chat (or Settings): a card in the chat follows the session; the studio
            # screen has no card and watches the "studio" messages instead
            event = ui.emit(
                {
                    "type": "avatar",
                    "thread": thread,
                    "session": sid,
                    "stage": "estimate",
                    "description": description,
                    "style": style,
                }
            )
            event_id = event["id"]
        session = Session(
            id=sid, thread=thread, event_id=event_id, description=description, style=style
        )
        self.current = session
        session.cost = await self.estimate(ep)
        self._patch(session)
        return session.view()

    async def estimate(self, ep: Endpoint | None = None) -> dict[str, Any]:
        """What a new face costs: the relay's word when the account draws; otherwise the count."""
        ep = ep or self.endpoint()
        if ep is None:
            raise StudioError("no image model")
        clips = len(CLIP_MOODS) if ep.clips else 0
        cost: dict[str, Any] = {
            "pictures": PICTURES_PER_FACE,
            "clips": clips,
            "model": ep.image_model,
            "clip_model": ep.video_model if clips else "",
            "cloud": ep.cloud,
        }
        if not ep.cloud:
            return cost
        hub = self.svc.hub
        hub.cloud.api_key = ep.api_key
        try:
            data = await hub.cloud.estimate(
                PICTURES_PER_FACE, ep.image_model, SIZE, clips=clips, video_model=ep.video_model
            )
        except CloudError as exc:
            cost["error"] = exc.describe()
            return cost
        cost.update(
            {
                "cny": data.get("cny"),
                "usd": data.get("usd"),
                "left_cny": data.get("left_cny"),
                "grant_cny": data.get("grant_cny"),
                "unlimited": bool(data.get("unlimited")),
                "affordable": bool(data.get("affordable", True)),
            }
        )
        return cost

    def _session(self, session_id: str) -> Session:
        cur = self.current
        if cur is None or cur.id != session_id:
            raise StudioError("that session is over")
        return cur

    def _patch(self, session: Session) -> None:
        """The session's state to everyone looking: the card in the chat (when there is one)
        and the studio screen (``{"kind": "studio", "current": …}`` over the socket)."""
        if session.event_id:
            self.svc.ui.patch(session.thread, session.event_id, **session.view())
        if self.current is session:
            self.svc.ui.bus.publish({"kind": "studio", "current": session.view()})

    async def start(self, session_id: str) -> dict[str, Any]:
        """The tap on *Draw*: the four candidates."""
        session = self._session(session_id)
        if session.stage not in ("estimate", "choose", "failed"):
            return session.view()
        ep = self.endpoint()
        if ep is None:
            raise StudioError("no image model")
        session.cancel_tasks()
        session.stage = "drawing"
        session.candidates = [None] * CANDIDATES
        session.errors = []
        session.chosen = None
        session.message = ""
        self._patch(session)
        self._clear_session_dir(session)
        for i in range(CANDIDATES):
            session.tasks.append(asyncio.create_task(self._draw_candidate(session, ep, i)))
        return session.view()

    async def regenerate(self, session_id: str) -> dict[str, Any]:
        return await self.start(session_id)

    async def _draw_candidate(self, session: Session, ep: Endpoint, index: int) -> None:
        try:
            png = await self._generate(ep, build_prompt(session.description, index, session.style))
            rel = f"avatar/sessions/{session.id}/c{index}.webp"
            await asyncio.to_thread(self._save_still, rel, png)
            session.candidates[index] = rel
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("avatar candidate {} failed: {}", index, exc)
            session.errors.append(self._describe(exc))
        finally:
            if session.stage == "drawing":
                done = sum(1 for c in session.candidates if c) + len(session.errors)
                if done >= CANDIDATES:
                    if any(session.candidates):
                        session.stage = "choose"
                    else:
                        session.stage = "failed"
                        session.message = (
                            session.errors[0] if session.errors else "Nothing came back."
                        )
                self._patch(session)

    async def choose(self, session_id: str, index: int) -> dict[str, Any]:
        """One of the four was picked: it is the idle pose; the other four are drawn from it."""
        session = self._session(session_id)
        if session.stage != "choose":
            return session.view()
        if not 0 <= index < CANDIDATES or not session.candidates[index]:
            raise StudioError("that candidate is not there")
        ep = self.endpoint()
        if ep is None:
            raise StudioError("no image model")
        session.cancel_tasks()
        session.chosen = index
        session.stage = "posing"
        session.errors = []
        session.face = f"face-{uuid.uuid4().hex[:8]}"
        session.moods = {}
        session.clips = {}
        self._patch(session)
        session.tasks.append(asyncio.create_task(self._pose(session, ep)))
        return session.view()

    async def redraw_moods(self) -> dict[str, Any]:
        """*Redraw the poses* for the face the profile wears, from its idle still — the phone's
        menu item; what to do when a pose came out wrong or the clips were never made."""
        face = self.svc.profile.avatar
        ws = self.svc.workspace()
        if not face or face == "dragon" or not (ws / "avatar" / face / "idle.webp").is_file():
            raise StudioError("the profile wears no face from the studio")
        ep = self.endpoint()
        if ep is None:
            raise StudioError("no image model")
        if self.current is not None and self.current.stage not in ("done", "cancelled", "failed"):
            self.cancel(self.current.id, quiet=True)
        info = self.face_info() or {}
        session = Session(
            id=uuid.uuid4().hex[:10],
            thread="",
            event_id="",
            description=str(info.get("description") or ""),
            style=str(info.get("style") or DEFAULT_STYLE),
            stage="posing",
            candidates=[f"avatar/{face}/idle.webp", None, None, None],
            chosen=0,
            face=face,
        )
        self.current = session
        self._patch(session)
        session.tasks.append(asyncio.create_task(self._pose(session, ep)))
        return session.view()

    async def _pose(self, session: Session, ep: Endpoint) -> None:
        assert session.face and session.chosen is not None
        ws = self.svc.workspace()
        src = ws / str(session.candidates[session.chosen])
        face_dir = ws / "avatar" / session.face
        try:
            face_dir.mkdir(parents=True, exist_ok=True)
            if src.resolve() != (face_dir / "idle.webp").resolve():
                shutil.copyfile(src, face_dir / "idle.webp")
            session.moods["idle"] = f"avatar/{session.face}/idle.webp"
            self._patch(session)
            png = await asyncio.to_thread(self._png_of, src)

            async def one(mood: str) -> None:
                try:
                    out = await self._edit(ep, png, KEEP + MOOD_INSTRUCTIONS[mood])
                    rel = f"avatar/{session.face}/{mood}.webp"
                    await asyncio.to_thread(self._save_still, rel, out)
                    session.moods[mood] = rel
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # noqa: BLE001
                    logger.warning("avatar pose {} failed: {}", mood, exc)
                    session.errors.append(f"{mood}: {self._describe(exc)}")
                self._patch(session)

            await asyncio.gather(*(one(m) for m in MOODS if m != "idle"))
            # a pose that failed shows the idle still instead, so the face is never blank
            for mood in MOODS:
                if mood not in session.moods:
                    shutil.copyfile(face_dir / "idle.webp", face_dir / f"{mood}.webp")
                    session.moods[mood] = f"avatar/{session.face}/{mood}.webp"
            await asyncio.to_thread(
                (face_dir / "face.json").write_text,
                json.dumps(
                    {
                        "description": session.description,
                        "style": session.style,
                        "model": ep.image_model,
                        "clip_model": ep.video_model if ep.clips else "",
                        "created": time.time(),
                    },
                    ensure_ascii=False,
                ),
                "utf-8",
            )
            self.svc.update_profile({"avatar": session.face})
            if ep.clips:
                # the face is on; the clips come after, one by one, and a failed one leaves its still
                session.stage = "animating"
                session.message = "The new look is on; the clips are being made."
                self._patch(session)
                await self._animate(session, ep, face_dir)
            session.stage = "done"
            session.message = "The new look is on."
            self._patch(session)
            if session.thread:
                self.svc.ui.emit(
                    {
                        "type": "notice",
                        "level": "info",
                        "text": "New look: {description}. Say what to change any time, or pick another under Settings.",
                        "vars": {"description": session.description},
                        "thread": session.thread,
                    }
                )
            self._prune_sessions()
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.exception("avatar pose set failed")
            session.stage = "failed"
            session.message = self._describe(exc)
            self._patch(session)

    async def _animate(self, session: Session, ep: Endpoint, face_dir: Path) -> None:
        """Four clips from the four stills, all at once; each failure is one line, not the end."""
        assert session.face

        async def one(mood: str) -> None:
            try:
                png = await asyncio.to_thread(self._png_of, face_dir / f"{mood}.webp")
                mp4 = await self._clip(ep, png, clip_prompt(mood, session.style))
                rel = f"avatar/{session.face}/{mood}.mp4"
                await asyncio.to_thread((face_dir / f"{mood}.mp4").write_bytes, mp4)
                session.clips[mood] = rel
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                logger.warning("avatar clip {} failed: {}", mood, exc)
                session.errors.append(f"{mood} clip: {self._describe(exc)}")
            self._patch(session)

        await asyncio.gather(*(one(m) for m in CLIP_MOODS))

    def cancel(self, session_id: str, quiet: bool = False) -> dict[str, Any]:
        session = self._session(session_id)
        session.cancel_tasks()
        if session.stage not in ("done",):
            session.stage = "cancelled"
            session.message = "" if quiet else "Not this time."
        self._patch(session)
        self._clear_session_dir(session)
        return session.view()

    # ------------------------------------------------------------------ pictures
    async def _generate(self, ep: Endpoint, prompt: str) -> bytes:
        if ep.dashscope:
            return await self._dashscope(
                ep, ep.image_model, [{"text": prompt}], self._dashscope_params(ep.image_model)
            )
        body = {
            "model": ep.image_model,
            "prompt": prompt,
            "n": 1,
            "size": SIZE,
            "response_format": "b64_json",
        }
        r = await self._post_image(
            f"{ep.base_url}/images/generations", json=body, headers=self._headers(ep)
        )
        return await self._image_of(r)

    async def _edit(self, ep: Endpoint, png: bytes, instruction: str) -> bytes:
        if ep.dashscope:
            model = ep.image_model
            three_x = model.startswith("qwen-image-3") or model.startswith("wan")
            edit_model = model if (three_x or "edit" in model) else "qwen-image-edit-max"
            params = self._dashscope_params(model) if three_x else {"n": 1, "watermark": False}
            content = [
                {"image": "data:image/png;base64," + base64.b64encode(png).decode()},
                {"text": instruction},
            ]
            return await self._dashscope(ep, edit_model, content, params)
        r = await self._post_image(
            f"{ep.base_url}/images/edits",
            data={
                "model": ep.image_model,
                "prompt": instruction,
                "n": "1",
                "size": SIZE,
                "response_format": "b64_json",
            },
            files={"image": ("idle.png", png, "image/png")},
            headers=self._headers(ep),
        )
        return await self._image_of(r)

    @staticmethod
    def _headers(ep: Endpoint) -> dict[str, str]:
        return {"Authorization": f"Bearer {ep.api_key}"} if ep.api_key else {}

    # ------------------------------------------------------------------ clips
    async def _clip(self, ep: Endpoint, png: bytes, prompt: str) -> bytes:
        """One image-to-video clip through the asynchronous video API the phone's VideoGen
        uses: the first frame to the provider's temporary storage, a task, polling, the MP4.
        Two at a time, and a task the provider refuses as too many (429) is submitted again
        after a pause — four at once used to lose two of them."""
        async with self._clip_gate:
            return await self._clip_once(ep, png, prompt)

    async def _clip_once(self, ep: Endpoint, png: bytes, prompt: str) -> bytes:
        host = ep.video_host
        model = ep.video_model.replace("t2v", "i2v")
        headers = self._headers(ep)
        # 1. the first frame, uploaded with a signed policy from the provider
        pr = await self._http.get(
            f"{host}/api/v1/uploads",
            params={"action": "getPolicy", "model": model},
            headers=headers,
        )
        if pr.status_code >= 400:
            raise StudioError("The upload policy was refused: " + self._http_error(pr))
        policy = (pr.json() or {}).get("data") or {}
        if not policy.get("upload_host"):
            raise StudioError("The video provider gave no upload policy.")
        key = f"{policy.get('upload_dir', '')}/first-frame.png"
        form = {
            "OSSAccessKeyId": str(policy.get("oss_access_key_id", "")),
            "Signature": str(policy.get("signature", "")),
            "policy": str(policy.get("policy", "")),
            "x-oss-object-acl": str(policy.get("x_oss_object_acl", "private")),
            "x-oss-forbid-overwrite": str(policy.get("x_oss_forbid_overwrite", "true")),
            "key": key,
        }
        up = await self._http.post(
            str(policy["upload_host"]),
            data=form,
            files={"file": ("first-frame.png", png, "image/png")},
        )
        if up.status_code >= 400:
            raise StudioError(f"The first frame could not be uploaded (HTTP {up.status_code}).")
        # 2. the task
        parameters: dict[str, Any] = {
            "watermark": False,
            "resolution": "480P" if model.startswith("wan2.2") else "720P",
        }
        if model.startswith("wan2.5"):
            parameters["duration"] = 5
        elif model.startswith("wan") and not model.startswith("wan2.2"):
            parameters["duration"] = CLIP_SECONDS
        body = {
            "model": model,
            "input": {"prompt": prompt, "img_url": f"oss://{key}"},
            "parameters": parameters,
        }
        for pause in (*CLIP_PAUSES, None):
            cr = await self._http.post(
                f"{host}/api/v1/services/aigc/video-generation/video-synthesis",
                json=body,
                headers={
                    **headers,
                    "X-DashScope-Async": "enable",
                    "X-DashScope-OssResourceResolve": "enable",
                },
            )
            if cr.status_code != 429 or pause is None:
                break
            logger.info("video provider busy (429); again in {:.0f}s", pause)
            await asyncio.sleep(pause)
        if cr.status_code >= 400:
            raise StudioError(self._http_error(cr))
        task = str(((cr.json() or {}).get("output") or {}).get("task_id") or "")
        if not task:
            raise StudioError("The video provider gave no task.")
        # 3. polling, then the file
        started = time.monotonic()
        while True:
            await asyncio.sleep(CLIP_POLL_S)
            tr = await self._http.get(f"{host}/api/v1/tasks/{task}", headers=headers)
            if tr.status_code >= 400:
                raise StudioError(self._http_error(tr))
            out = (tr.json() or {}).get("output") or {}
            status = str(out.get("task_status") or "")
            if status == "SUCCEEDED":
                url = str(out.get("video_url") or "")
                if not url:
                    raise StudioError("The clip came back without a file.")
                clip = await self._http.get(url)
                if clip.status_code >= 400 or not clip.content:
                    raise StudioError("The clip could not be fetched.")
                return clip.content
            if status in ("FAILED", "CANCELED", "UNKNOWN"):
                raise StudioError(
                    str(out.get("message") or out.get("code") or "The clip failed.")[:200]
                )
            if time.monotonic() - started > CLIP_MAX_WAIT_S:
                raise StudioError("The clip took too long.")

    async def _image_of(self, r: httpx.Response) -> bytes:
        """The picture in an OpenAI images reply: inline, or fetched from the URL given."""
        if r.status_code >= 400:
            raise StudioError(self._http_error(r))
        try:
            data = r.json()
        except ValueError as exc:
            raise StudioError("The image endpoint sent something that is not a picture.") from exc
        first = (data.get("data") or [{}])[0] if isinstance(data, dict) else {}
        if first.get("b64_json"):
            return base64.b64decode(first["b64_json"])
        if first.get("url"):
            img = await self._http.get(str(first["url"]))
            if img.status_code >= 400:
                raise StudioError("The picture could not be fetched.")
            return img.content
        raise StudioError(
            str((data.get("error") or {}).get("message") or "No picture came back.")[:200]
        )

    @staticmethod
    def _dashscope_params(model: str) -> dict[str, Any]:
        params: dict[str, Any] = {"size": SIZE.replace("x", "*"), "watermark": False}
        if model.startswith("qwen-image"):
            params["prompt_extend"] = False
        return params

    async def _dashscope(
        self, ep: Endpoint, model: str, content: list[dict[str, Any]], params: dict[str, Any]
    ) -> bytes:
        """One call to Model Studio's ``multimodal-generation/generation``; the first picture."""
        host = re.split(r"/compatible-mode|/api/v1", ep.base_url, maxsplit=1)[0].rstrip("/")
        body = {
            "model": model,
            "input": {"messages": [{"role": "user", "content": content}]},
            "parameters": params,
        }
        r = await self._post_image(
            f"{host}/api/v1/services/aigc/multimodal-generation/generation",
            json=body,
            headers=self._headers(ep),
        )
        if r.status_code >= 400:
            raise StudioError(self._http_error(r))
        try:
            data = r.json()
            url = data["output"]["choices"][0]["message"]["content"][0]["image"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            msg = ""
            try:
                msg = str(r.json().get("message") or "")
            except ValueError:
                pass
            raise StudioError(msg[:200] or "No picture came back.") from exc
        img = await self._http.get(str(url))
        if img.status_code >= 400:
            raise StudioError("The picture could not be fetched.")
        return img.content

    @staticmethod
    def _http_error(r: httpx.Response) -> str:
        if r.status_code == 429:
            return "The image provider is busy right now — try again in a minute."
        try:
            err = r.json()
            if isinstance(err, dict):
                e = err.get("error")
                if isinstance(e, dict):
                    code = str(e.get("code") or "")
                    if code == "allowance_exhausted":
                        return "The free allowance is used up."
                    return str(e.get("message") or code or f"HTTP {r.status_code}")[:200]
                return str(err.get("message") or f"HTTP {r.status_code}")[:200]
        except ValueError:
            pass
        return f"HTTP {r.status_code}"

    @staticmethod
    def _describe(exc: Exception) -> str:
        if isinstance(exc, StudioError):
            return str(exc)
        if isinstance(exc, httpx.TimeoutException):
            return "The image endpoint did not answer in time."
        if isinstance(exc, httpx.HTTPError):
            return "The image endpoint could not be reached."
        return f"{type(exc).__name__}: {exc}"[:200]

    # ------------------------------------------------------------------ files
    def _save_still(self, rel: str, data: bytes) -> None:
        """A picture into the workspace as a square webp, at most STILL_PX wide."""
        from PIL import Image

        target = self.svc.workspace() / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(io.BytesIO(data)) as opened:
            im = opened.convert("RGB")
        w, h = im.size
        side = min(w, h)
        if w != h:
            left, top = (w - side) // 2, (h - side) // 2
            im = im.crop((left, top, left + side, top + side))
        if side > STILL_PX:
            im = im.resize((STILL_PX, STILL_PX), Image.Resampling.LANCZOS)
        im.save(target, "WEBP", quality=88, method=4)

    @staticmethod
    def _png_of(path: Path) -> bytes:
        from PIL import Image

        with Image.open(path) as im:
            out = io.BytesIO()
            im.convert("RGB").save(out, "PNG")
            return out.getvalue()

    def _clear_session_dir(self, session: Session) -> None:
        d = self.svc.workspace() / "avatar" / "sessions" / session.id
        if d.is_dir():
            shutil.rmtree(d, ignore_errors=True)

    def _prune_sessions(self) -> None:
        """Candidates of sessions older than a day go; faces stay (the profile may name one)."""
        root = self.svc.workspace() / "avatar" / "sessions"
        if not root.is_dir():
            return
        cutoff = time.time() - SESSION_TTL_S
        for d in root.iterdir():
            try:
                if (
                    d.is_dir()
                    and d.stat().st_mtime < cutoff
                    and not (self.current and d.name == self.current.id)
                ):
                    shutil.rmtree(d, ignore_errors=True)
            except OSError:
                pass


__all__ = [
    "CANDIDATES",
    "CLIP_MOODS",
    "DEFAULT_STYLE",
    "MOODS",
    "PICTURES_PER_FACE",
    "STYLES",
    "AvatarStudio",
    "Endpoint",
    "Session",
    "StudioError",
    "build_prompt",
    "parse_choice",
    "parse_request",
]
