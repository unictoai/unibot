"""What the desktop remembers: the cloud key, this device's id and name, a few
preferences. One JSON file, mode 0600, under ~/.unibot (UNIBOT_HOME)."""

from __future__ import annotations

import json
import os
import platform
import socket
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path

DEFAULT_CLOUD = ""  # unibot: no hosted relay; the user enters their own


def home() -> Path:
    p = Path(os.environ.get("UNIBOT_HOME") or (Path.home() / ".unibot"))
    p.mkdir(parents=True, exist_ok=True)
    return p


def default_name() -> str:
    host = socket.gethostname().split(".")[0] or "Computer"
    return f"{host} ({platform.system()})"


@dataclass
class Config:
    cloud_base: str = DEFAULT_CLOUD
    api_key: str = ""
    account_hint: str = ""
    device_id: str = field(default_factory=lambda: "pc-" + uuid.uuid4().hex[:12])
    name: str = field(default_factory=default_name)
    model: str = ""  # empty: the cloud's recommended chat model
    remote_control: bool = True  # other devices of the account may drive this one
    approvals: str = "ask"  # ask | allow (careful commands run without a card; destructive ones still ask)
    language: str = ""  # hint for the agent; empty = follow the user
    downloads: str = ""  # where device_get puts files; empty = ~/Downloads/unibot

    @property
    def signed_in(self) -> bool:
        return bool(self.api_key)

    def downloads_dir(self) -> Path:
        p = Path(self.downloads) if self.downloads else Path.home() / "Downloads" / "unibot"
        p.mkdir(parents=True, exist_ok=True)
        return p


PATH_NAME = "desktop.json"


def load() -> Config:
    p = home() / PATH_NAME
    if not p.exists():
        return Config()
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return Config()
    cfg = Config()
    for k, v in raw.items():
        if hasattr(cfg, k) and not k.startswith("_"):
            setattr(cfg, k, v)
    return cfg


def save(cfg: Config) -> None:
    p = home() / PATH_NAME
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(asdict(cfg), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, p)
