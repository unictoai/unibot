"""Settings, all from the environment (or a `.env` you source before starting).

Every value has a development default so `python -m unibot_cloud` runs on a
laptop with nothing set: codes go to the log, the database is ./data/cloud.db,
and the upstream is whatever UPSTREAM_BASE / UPSTREAM_KEY say (without a key
the proxy answers 503, but sign-up still works, which is what the app tests
need).
"""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class ModelSpec:
    """One model the relay offers. `id` is what the app sees and asks for;
    `upstream` is what the provider is asked for (usually the same)."""

    id: str
    name: str
    upstream: str
    kind: str = "chat"  # chat | image | video
    input_modalities: tuple[str, ...] = ("text",)
    output_modalities: tuple[str, ...] = ("text",)
    # Charged tokens = prompt × in_mult + completion × out_mult. Multipliers let
    # a cheap model stretch the grant further than an expensive one without the
    # account holder seeing money.
    in_mult: float = 1.0
    out_mult: float = 1.0
    # Images have no token count; each one costs this many tokens of grant.
    per_image: int = 0
    # Nor do clips: each accepted video task costs this many (billed per output
    # second upstream, so this is set for the short clips the app makes).
    per_clip: int = 0
    recommended: bool = False
    # What the provider bills the operator, in yuan — the list price of the
    # Beijing region unless CLOUD_MODELS says otherwise. Chat: per million
    # tokens in and out (one yuan per million tokens is one micro-yuan per
    # token, which is how the ledger stores money). Images: per picture, the
    # 2k tier for anything wider than 1k. Video: per output second.
    price_in: float = 0.0
    price_out: float = 0.0
    price_image: float = 0.0
    price_image_2k: float = 0.0
    price_second: float = 0.0
    # The clip length when the app does not say (`parameters.duration` absent):
    # Wan 2.2 always makes five seconds, MiniMax four at the least.
    clip_seconds: float = 4.0

    def to_public(self) -> dict:
        return {
            "id": self.id,
            "object": "model",
            "name": self.name,
            "owned_by": "unibot-cloud",
            "architecture": {
                "input_modalities": list(self.input_modalities),
                "output_modalities": list(self.output_modalities),
            },
            "unibot": {
                "kind": self.kind,
                "recommended": self.recommended,
                "in_mult": self.in_mult,
                "out_mult": self.out_mult,
                "per_image": self.per_image,
                "per_clip": self.per_clip,
                "clip_seconds": self.clip_seconds,
                "price_cny": {
                    "per_m_input": self.price_in,
                    "per_m_output": self.price_out,
                    "per_image": self.price_image,
                    "per_image_2k": self.price_image_2k,
                    "per_second": self.price_second,
                },
            },
        }

    # -- what one request costs, in micro-yuan (1e-6 CNY; integers in the ledger) --

    def chat_cost_uy(self, prompt_tokens: int, completion_tokens: int) -> int:
        return round(max(0, prompt_tokens) * self.price_in + max(0, completion_tokens) * self.price_out)

    def image_cost_uy(self, size: str | None = None) -> int:
        price = self.price_image
        if self.price_image_2k and size and _max_side(size) > 1400:
            price = self.price_image_2k
        return round(price * 1_000_000)

    def video_cost_uy(self, seconds: float) -> int:
        return round(max(0.0, seconds) * self.price_second * 1_000_000)


def _max_side(size: str) -> int:
    """The longer side of "1024x1024" / "1664*928"; 0 when unreadable."""
    try:
        parts = [int(p) for p in size.lower().replace("*", "x").split("x")[:2]]
    except ValueError:
        return 0
    return max(parts) if parts else 0


# The menu a fresh account gets. Checked against the provider's own /models
# list: qwen3.8-27b takes pictures as input (so no separate vision model),
# qwen-image-3.0 draws, Wan 2.2 makes clips through the video API (which the
# provider does not list; the app probes it). Prices are the provider's
# Beijing list prices (help.aliyun.com/zh/model-studio/model-pricing, 2026-09):
# 27B ¥3 / ¥12 per million tokens, Flash ¥0.8 / ¥2.7, qwen-image-3.0 ¥0.18 a
# picture at 1k and 2k alike (the Pro tier is ¥0.25 / ¥0.5), wan2.2-i2v-flash
# ¥0.10 a second at 480P for a fixed five seconds (MiniMax-H3, the 0.3 default,
# was ¥0.5 a second: a new face cost ¥8 in clips, now ¥2). wan2.2-t2v-plus is
# the sibling the app asks for when a clip starts from words: ¥0.14 a second.
DEFAULT_MODELS: tuple[ModelSpec, ...] = (
    ModelSpec(
        id="qwen3.8-27b",
        name="Qwen 3.8 27B",
        upstream="qwen3.8-27b",
        input_modalities=("text", "image"),
        recommended=True,
        price_in=3.0,
        price_out=12.0,
    ),
    ModelSpec(
        id="qwen3.8-flash",
        name="Qwen 3.8 Flash",
        upstream="qwen3.8-flash",
        input_modalities=("text", "image"),
        in_mult=0.3,
        out_mult=0.3,
        price_in=0.8,
        price_out=2.7,
    ),
    ModelSpec(
        id="qwen-image-3.0",
        name="Qwen Image 3.0",
        upstream="qwen-image-3.0",
        kind="image",
        output_modalities=("image",),
        per_image=30_000,
        recommended=True,
        price_image=0.18,
        price_image_2k=0.18,
    ),
    ModelSpec(
        id="wan2.2-i2v-flash",
        name="Wan 2.2 Flash (video)",
        upstream="wan2.2-i2v-flash",
        kind="video",
        input_modalities=("text", "image"),
        output_modalities=("video",),
        per_clip=200_000,
        recommended=True,
        price_second=0.10,
        clip_seconds=5.0,
    ),
    ModelSpec(
        id="wan2.2-t2v-plus",
        name="Wan 2.2 Plus (video from words)",
        upstream="wan2.2-t2v-plus",
        kind="video",
        input_modalities=("text",),
        output_modalities=("video",),
        per_clip=200_000,
        price_second=0.14,
        clip_seconds=5.0,
    ),
)


# Ids older app builds still send, and what answers them now. Video is not
# aliased: a MiniMax-shaped request body does not fit Wan, and the app's probe
# simply finds the old model gone and stops animating.
LEGACY_MODEL_IDS: dict[str, str] = {"qwen-image-3.0-pro": "qwen-image-3.0"}


def _env(name: str, default: str = "") -> str:
    v = os.environ.get(name)
    return default if v is None or v == "" else v


def _int(name: str, default: int) -> int:
    return int(_env(name, str(default)))


def _models_from_env() -> tuple[ModelSpec, ...]:
    raw = _env("CLOUD_MODELS")
    if not raw:
        return DEFAULT_MODELS
    out = []
    for item in json.loads(raw):
        item = dict(item)
        for k in ("input_modalities", "output_modalities"):
            if k in item:
                item[k] = tuple(item[k])
        out.append(ModelSpec(**item))
    return tuple(out)


@dataclass(frozen=True)
class Settings:
    database: str = field(default_factory=lambda: _env("CLOUD_DB", "./data/cloud.db"))
    # HMAC key for hashing phone numbers / e-mail addresses. Required outside
    # development: without it identifiers are hashed with a fixed string and
    # a leaked database would be trivially reversible for phone numbers.
    secret: str = field(default_factory=lambda: _env("CLOUD_SECRET"))
    admin_token: str = field(default_factory=lambda: _env("CLOUD_ADMIN_TOKEN"))
    # What the app should put in the provider's base URL, without /v1.
    public_base: str = field(default_factory=lambda: _env("PUBLIC_BASE", "http://127.0.0.1:8787"))

    upstream_base: str = field(default_factory=lambda: _env("UPSTREAM_BASE", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    upstream_key: str = field(default_factory=lambda: _env("UPSTREAM_KEY"))
    # DashScope's native host, for drawing (its OpenAI-compatible host has no
    # images endpoint). Same key.
    dashscope_base: str = field(default_factory=lambda: _env("DASHSCOPE_BASE", "https://dashscope.aliyuncs.com/api/v1"))
    upstream_timeout_s: float = field(default_factory=lambda: float(_env("UPSTREAM_TIMEOUT_S", "180")))
    # Pictures: DashScope allows an account only a couple of image tasks at a time and
    # answers 429 past that, so the relay draws at most IMAGE_CONCURRENCY pictures at once
    # for everyone together (the rest queue) and, on a 429 or 5xx, tries again up to
    # IMAGE_RETRIES times with growing pauses before telling the app the provider is busy.
    image_concurrency: int = field(default_factory=lambda: _int("IMAGE_CONCURRENCY", 2))
    image_retries: int = field(default_factory=lambda: _int("IMAGE_RETRIES", 4))
    # JSON object merged into every chat request for fields the app did not set.
    # Qwen 3.x models think by default and a one-line answer can cost a
    # thousand reasoning tokens, so the shipped default turns that off; the app
    # can still ask for it explicitly.
    chat_defaults: dict = field(default_factory=lambda: json.loads(_env("CHAT_DEFAULTS", '{"enable_thinking": false}')))

    # The token grant, the older allowance. SIGNUP_TOKENS=0 (the default since
    # the money cap below took over) means no token ceiling: usage is still
    # metered and shown, nothing is refused for lack of tokens. DAILY_CAP_TOKENS=0
    # and PER_MINUTE_REQUESTS=0 likewise switch those two checks off.
    signup_tokens: int = field(default_factory=lambda: _int("SIGNUP_TOKENS", 0))
    daily_cap_tokens: int = field(default_factory=lambda: _int("DAILY_CAP_TOKENS", 0))
    per_minute_requests: int = field(default_factory=lambda: _int("PER_MINUTE_REQUESTS", 30))
    max_request_bytes: int = field(default_factory=lambda: _int("MAX_REQUEST_BYTES", 6 * 1024 * 1024))

    # The hub (multi-device): one WebSocket frame may carry a file or a
    # screenshot, base64-encoded; uvicorn's own cap (--ws-max-size) must be at
    # least this. HUB_ENABLED=0 turns the hub and the console off.
    hub_enabled: bool = field(default_factory=lambda: _env("HUB_ENABLED", "1") not in ("0", "false", "no"))
    hub_frame_limit: int = field(default_factory=lambda: _int("HUB_FRAME_LIMIT", 16 * 1024 * 1024))

    # Money (0.5). Every account has one allowance for its lifetime, not a day:
    # ALLOWANCE_CNY yuan of the operator's provider bill (0 = no limit), counted
    # at the list prices above across chat, pictures and clips alike — a clip is
    # not counted apart, it just costs more. The pool grows by INVITE_BONUS_CNY
    # for each friend who signs up with the account's code, once by
    # CONTRIBUTE_BONUS_CNY when the person joins the co-creation programme
    # (contributes their conversations), and by whatever the operator credits.
    # The members below (ALLOWED_IDENTIFIERS, or flagged by the operator) have
    # no limit. DAY_OFFSET_H only groups the operator's reports by local day
    # (8 = Beijing). USD_CNY is for display: the apps show both currencies.
    allowance_cny: float = field(default_factory=lambda: float(_env("ALLOWANCE_CNY", "10")))
    invite_bonus_cny: float = field(default_factory=lambda: float(_env("INVITE_BONUS_CNY", "5")))
    contribute_bonus_cny: float = field(default_factory=lambda: float(_env("CONTRIBUTE_BONUS_CNY", "10")))
    day_offset_h: int = field(default_factory=lambda: _int("DAY_OFFSET_H", 8))
    usd_cny: float = field(default_factory=lambda: float(_env("USD_CNY", "7.1")))
    # INVITE_URL is the link the apps offer to share; the code is appended.
    # OWN_KEY_DOCS is the guide the apps open when the allowance is used up and
    # the person wants to bring their own model key.
    invite_url: str = field(default_factory=lambda: _env("INVITE_URL", ""))
    own_key_docs: str = field(default_factory=lambda: _env("OWN_KEY_DOCS", "https://github.com/unictoai/unibot/blob/main/docs/own-key.md"))

    code_ttl_s: int = field(default_factory=lambda: _int("CODE_TTL_S", 600))
    code_per_identifier_10m: int = field(default_factory=lambda: _int("CODE_PER_IDENTIFIER_10M", 3))
    # Passwords are optional on top of the code: someone who set one may sign
    # in with it on a new device without waiting for a message. After this
    # many wrong tries in a row the password is locked for LOCKOUT_S seconds
    # (a code still works, and setting a new password clears the lock).
    password_min_len: int = field(default_factory=lambda: _int("PASSWORD_MIN_LEN", 8))
    password_max_attempts: int = field(default_factory=lambda: _int("PASSWORD_MAX_ATTEMPTS", 5))
    lockout_s: int = field(default_factory=lambda: _int("LOCKOUT_S", 900))
    # How long after a code sign-in a password may be set without the old one
    # (the "forgot my password" path: sign in with a code, set a new one).
    password_reset_window_s: int = field(default_factory=lambda: _int("PASSWORD_RESET_WINDOW_S", 1800))
    # Members: phone numbers / e-mail addresses (comma-separated) that have no
    # daily cap — the operator and friends. Normalised like the identifiers
    # themselves, so "139 0000 1111" works. With SIGNUP_OPEN=0 the relay is
    # private and only members may sign in at all (the pre-release behaviour).
    allowed_identifiers: str = field(default_factory=lambda: _env("ALLOWED_IDENTIFIERS"))
    signup_open: bool = field(default_factory=lambda: _env("SIGNUP_OPEN", "1") not in ("0", "false", "no"))
    code_per_ip_hour: int = field(default_factory=lambda: _int("CODE_PER_IP_HOUR", 10))
    code_max_attempts: int = field(default_factory=lambda: _int("CODE_MAX_ATTEMPTS", 5))
    # One phone/e-mail = one grant; a second device signing in with the same
    # identifier shares the account and gets a second key, not a second grant.

    sender: str = field(default_factory=lambda: _env("CODE_SENDER", "log"))  # log | smtp | aliyun | both
    smtp_host: str = field(default_factory=lambda: _env("SMTP_HOST"))
    smtp_port: int = field(default_factory=lambda: _int("SMTP_PORT", 465))
    smtp_user: str = field(default_factory=lambda: _env("SMTP_USER"))
    smtp_password: str = field(default_factory=lambda: _env("SMTP_PASSWORD"))
    smtp_from: str = field(default_factory=lambda: _env("SMTP_FROM"))
    aliyun_access_key_id: str = field(default_factory=lambda: _env("ALIYUN_ACCESS_KEY_ID"))
    aliyun_access_key_secret: str = field(default_factory=lambda: _env("ALIYUN_ACCESS_KEY_SECRET"))
    aliyun_sms_sign: str = field(default_factory=lambda: _env("ALIYUN_SMS_SIGN"))
    aliyun_sms_template: str = field(default_factory=lambda: _env("ALIYUN_SMS_TEMPLATE"))
    # dypns = 号码认证服务 SendSmsVerifyCode (default), dysms = 短信服务 SendSms
    aliyun_sms_api: str = field(default_factory=lambda: _env("ALIYUN_SMS_API", "dypns"))

    models: tuple[ModelSpec, ...] = field(default_factory=_models_from_env)
    # -- the operator's page, beyond the relay's own numbers ------------------------------
    # the site's traffic database (demo/showcase/mirror/traffic.py), mounted read-only into
    # the container; empty = the "visits and downloads" panel says so and shows nothing
    traffic_db: str = field(default_factory=lambda: _env("TRAFFIC_DB"))
    # unibot Web's gateway on the same docker network (http://gateway:8000/api/web/info):
    # how many kept accounts and running sessions; empty = not asked
    web_info_url: str = field(default_factory=lambda: _env("WEB_INFO_URL"))

    def model(self, model_id: str) -> ModelSpec | None:
        for m in self.models:
            if m.id == model_id:
                return m
        # Phones from before 0.4 still ask for the model the menu used to carry;
        # same API shape, so the cheaper sibling answers in its place.
        alias = LEGACY_MODEL_IDS.get(model_id)
        if alias and alias != model_id:
            return self.model(alias)
        return None

    @property
    def unlimited(self) -> bool:
        return self.signup_tokens <= 0

    def day_start(self, t: int) -> int:
        """The start (as a UNIX time) of the local day `t` falls in."""
        off = self.day_offset_h * 3600
        return t - ((t + off) % 86400)

    def uy_to_cny(self, uy: int) -> float:
        return round(uy / 1_000_000, 4)

    @staticmethod
    def cny_to_uy(cny: float) -> int:
        return round(cny * 1_000_000)

    @property
    def allowance_uy(self) -> int:
        return self.cny_to_uy(self.allowance_cny)

    def cny_to_usd(self, cny: float) -> float:
        return round(cny / self.usd_cny, 4) if self.usd_cny > 0 else 0.0

    @property
    def dev_mode(self) -> bool:
        return not self.secret

    @property
    def hmac_key(self) -> bytes:
        return (self.secret or "unibot-cloud-dev-not-secret").encode()

    @property
    def identifier_key(self) -> bytes:
        """32 bytes for AES-GCM over the stored phone numbers / addresses,
        derived from the same secret so one value keeps the whole database."""
        return hashlib.sha256(b"unibot-cloud/identifier:" + self.hmac_key).digest()
