"""Settings, all from the environment (see ``../.env.example``)."""

from __future__ import annotations

import os
from dataclasses import dataclass

# Model providers a visitor may point their own key at. Anything else is refused so that the
# gateway cannot be used to reach arbitrary hosts from the server.
DEFAULT_BYOK_HOSTS = (
    "dashscope.aliyuncs.com",
    "maas.aliyuncs.com",  # 百炼 dedicated endpoints: <id>.<region>.maas.aliyuncs.com
    "api.deepseek.com",
    "api.openai.com",
    "open.bigmodel.cn",
    "api.moonshot.cn",
    "api.siliconflow.cn",
    "ark.cn-beijing.volces.com",
    "api.minimax.chat",
    "openrouter.ai",
    "api.anthropic.com",
    "generativelanguage.googleapis.com",
    "api.groq.com",
    "api.mistral.ai",
)


def _str(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _int(name: str, default: int) -> int:
    raw = _str(name)
    return int(raw) if raw else default


def _float(name: str, default: float) -> float:
    raw = _str(name)
    return float(raw) if raw else default


def _bool(name: str, default: bool) -> bool:
    raw = _str(name).lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Lane:
    """One upstream model: the main one, or the one that operates the phone."""

    provider: str  # "openai" (chat completions) or "openai_responses"
    model: str
    base_url: str
    api_key: str

    @property
    def configured(self) -> bool:
        return bool(self.model and self.base_url and self.api_key)


@dataclass(frozen=True)
class Settings:
    # --- how the outside reaches us
    public_scheme: str
    site_host: str
    session_domain: str  # sessions live at <id>.<session_domain>
    public_port: str  # "" on 80/443; ":8000" in development
    listen_host: str
    listen_port: int
    trust_proxy: bool  # take the visitor's address from X-Forwarded-For (set by Caddy)
    # development only — in production Caddy serves both
    site_dir: str  # the built MobileGym
    cdn_dir: str  # MobileGym's companion dataset, for /cdn/*

    # --- the containers
    sessions_network: str
    image: str
    internal_url: str  # how a container reaches this gateway; "" → the network's gateway address
    container_port: int
    memory: str
    cpus: str
    pids: int
    extra_env: dict[str, str]

    # --- session policy
    session_ttl_s: int
    idle_ttl_s: int
    start_timeout_s: int
    max_sessions: int
    per_ip_active: int
    per_ip_daily: int

    # --- the models and their budget
    main: Lane
    gui: Lane
    session_requests: int
    session_tokens: int
    daily_requests: int
    daily_tokens: int
    byok_enabled: bool
    byok_hosts: tuple[str, ...]

    # --- trial credentials for the phone app (see trials.py)
    trial_enabled: bool
    trial_db: str
    trial_tokens: int  # lifetime budget of one trial
    trial_per_ip_daily: int  # new trials per address per day
    trial_daily_new: int  # new trials per day, all told
    trial_daily_tokens: int  # what all trials together may spend in a day
    trial_rpm: int  # requests per minute per trial

    # unibot Web: a kept Muse per Cloud account (accounts.py)
    web_enabled: bool
    web_relay_url: str  # the relay as the gateway calls it for sign-in
    web_relay_internal_url: str  # the relay as the containers reach it (same Docker network)
    web_network: str  # a network with a way out, shared with the relay
    web_db: str
    web_image: str  # "" → the showcase image
    web_max_accounts: int
    web_max_running: int
    web_idle_stop_s: int
    web_memory: str
    web_cpus: str
    web_device_name: str
    web_slug_salt: str

    @classmethod
    def from_env(cls) -> Settings:
        main = Lane(
            provider=_str("MAIN_PROVIDER", "openai"),
            model=_str("MAIN_MODEL", "qwen3.8-27b"),
            base_url=_str("MAIN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            api_key=_str("MAIN_API_KEY"),
        )
        gui = Lane(
            provider=_str("GUI_PROVIDER", main.provider),
            model=_str("GUI_MODEL", main.model),
            base_url=_str("GUI_BASE_URL", main.base_url),
            api_key=_str("GUI_API_KEY", main.api_key),
        )
        extra: dict[str, str] = {}
        for item in _str("SESSION_EXTRA_ENV").split(","):
            if "=" in item:
                key, _, value = item.partition("=")
                extra[key.strip()] = value.strip()
        hosts = tuple(h.strip().lower() for h in _str("BYOK_ALLOWED_HOSTS").split(",") if h.strip())
        return cls(
            public_scheme=_str("PUBLIC_SCHEME", "https"),
            site_host=_str("SITE_HOST", "localhost"),
            session_domain=_str("SESSION_DOMAIN", "s.localhost"),
            public_port=_str("PUBLIC_PORT"),
            listen_host=_str("LISTEN_HOST", "0.0.0.0"),
            listen_port=_int("LISTEN_PORT", 8000),
            trust_proxy=_bool("TRUST_PROXY", True),
            site_dir=_str("SITE_DIR"),
            cdn_dir=_str("CDN_DIR"),
            sessions_network=_str("SESSIONS_NETWORK", "unibot-sessions"),
            image=_str("UNIBOT_IMAGE", "ghcr.io/unictoai/unibot:latest"),
            internal_url=_str("INTERNAL_URL"),
            container_port=_int("CONTAINER_PORT", 8787),
            memory=_str("CONTAINER_MEMORY", "512m"),
            cpus=_str("CONTAINER_CPUS", "1"),
            pids=_int("CONTAINER_PIDS", 256),
            extra_env=extra,
            session_ttl_s=_int("SESSION_TTL_S", 1800),
            idle_ttl_s=_int("IDLE_TTL_S", 600),
            start_timeout_s=_int("START_TIMEOUT_S", 40),
            max_sessions=_int("MAX_SESSIONS", 20),
            per_ip_active=_int("PER_IP_ACTIVE", 1),
            per_ip_daily=_int("PER_IP_DAILY", 6),
            main=main,
            gui=gui,
            session_requests=_int("SESSION_LLM_REQUESTS", 60),
            session_tokens=_int("SESSION_LLM_TOKENS", 300_000),
            daily_requests=_int("DAILY_LLM_REQUESTS", 3000),
            daily_tokens=_int("DAILY_LLM_TOKENS", 6_000_000),
            byok_enabled=_bool("BYOK_ENABLED", True),
            byok_hosts=hosts or DEFAULT_BYOK_HOSTS,
            trial_enabled=_bool("TRIAL_ENABLED", False),
            trial_db=_str("TRIAL_DB", "/data/trials.db"),
            trial_tokens=_int("TRIAL_TOKENS", 1_000_000),
            trial_per_ip_daily=_int("TRIAL_PER_IP_DAILY", 5),
            trial_daily_new=_int("TRIAL_DAILY_NEW", 200),
            trial_daily_tokens=_int("TRIAL_DAILY_TOKENS", 20_000_000),
            trial_rpm=_int("TRIAL_RPM", 30),
            web_enabled=_bool("WEB_ENABLED", False),
            web_relay_url=_str("WEB_RELAY_URL", "https://cloud.unibot.cn").rstrip("/"),
            web_relay_internal_url=_str(
                "WEB_RELAY_INTERNAL_URL", "http://unibot-relay:8787"
            ).rstrip("/"),
            web_network=_str("WEB_NETWORK", "unibot-web"),
            web_db=_str("WEB_DB", "/data/web.db"),
            web_image=_str("WEB_IMAGE"),
            web_max_accounts=_int("WEB_MAX_ACCOUNTS", 60),
            web_max_running=_int("WEB_MAX_RUNNING", 12),
            web_idle_stop_s=_int("WEB_IDLE_STOP_S", 6 * 3600),
            web_memory=_str("WEB_CONTAINER_MEMORY", "640m"),
            web_cpus=_str("WEB_CONTAINER_CPUS", "1"),
            web_device_name=_str("WEB_DEVICE_NAME", "Web"),
            web_slug_salt=_str("WEB_SLUG_SALT", "unibot-web"),
        )

    def session_origin(self, sid: str) -> str:
        return f"{self.public_scheme}://{sid}.{self.session_domain}{self.public_port}"

    def trial_base_url(self, trial_id: str, lane: str = "main") -> str:
        """Where a phone points its OpenAI-compatible client for a trial."""
        return (
            f"{self.public_scheme}://{self.site_host}{self.public_port}/llm/trial/{trial_id}/{lane}"
        )

    def lane(self, name: str) -> Lane | None:
        return {"main": self.main, "gui": self.gui}.get(name)
