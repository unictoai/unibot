"""Credential vault.

Secrets live encrypted on disk (Fernet / AES-128-CBC + HMAC). The model only
ever sees placeholders such as ``{{vault:EMAIL_PASSWORD}}``; the Sentinel
resolves them for tools that opted in (``accepts_secrets``) and connectors
resolve their own configuration right before use. Tool output is scrubbed of
any secret value before it is handed back to the model.

Key management: ``$UNIBOT_VAULT_KEY`` (base64 Fernet key) or a key file at
``~/.unibot/vault.key`` created on first use with ``0600`` permissions.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet, InvalidToken

PLACEHOLDER_RE = re.compile(r"\{\{\s*vault:([A-Za-z0-9_.-]+)\s*\}\}")


def _atomic_write(target: Path, data: bytes, mode: int | None = None) -> None:
    """Write ``data`` to ``target`` atomically.

    The payload goes to a uniquely-named temp file in the *same* directory
    (so the rename stays on one filesystem and is atomic), is fsynced to
    disk, and is then moved into place with :func:`os.replace`. ``mode`` is
    applied to the temp file *before* the rename, so the target never exists
    with looser permissions; ``None`` keeps the default ``0o666 & ~umask``
    behavior of a plain write. The temp file is removed if anything fails.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        dir=target.parent, prefix=target.name + ".", suffix=".tmp"
    )
    tmp = Path(tmp_name)
    try:
        if mode is None:
            prev_umask = os.umask(0)
            os.umask(prev_umask)
            mode = 0o666 & ~prev_umask
        os.fchmod(fd, mode)
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, target)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


class VaultError(RuntimeError):
    pass


class CredentialVault:
    def __init__(self, path: Path, key_path: Path | None = None):
        self.path = Path(path)
        self.key_path = Path(key_path) if key_path else self.path.with_suffix(".key")
        self._fernet: Fernet | None = None
        self._cache: dict[str, str] | None = None

    # ------------------------------------------------------------------ key handling
    def _key(self) -> bytes:
        env_key = os.environ.get("UNIBOT_VAULT_KEY")
        if env_key:
            return env_key.encode()
        if self.key_path.exists():
            return self.key_path.read_bytes().strip()
        key = Fernet.generate_key()
        # atomic + 0o600 from the start: the key must never be readable by others,
        # not even briefly between creation and chmod
        _atomic_write(self.key_path, key, mode=0o600)
        return key

    @property
    def fernet(self) -> Fernet:
        if self._fernet is None:
            self._fernet = Fernet(self._key())
        return self._fernet

    # ------------------------------------------------------------------ persistence
    def _load(self) -> dict[str, str]:
        if self._cache is not None:
            return self._cache
        if not self.path.exists():
            self._cache = {}
            return self._cache
        try:
            raw = self.fernet.decrypt(self.path.read_bytes())
        except InvalidToken as exc:
            raise VaultError(
                f"cannot decrypt {self.path} – wrong key? (key file: {self.key_path})"
            ) from exc
        data = json.loads(raw.decode("utf-8"))
        self._cache = {str(k): str(v) for k, v in data.items()}
        return self._cache

    def _save(self, data: dict[str, str]) -> None:
        token = self.fernet.encrypt(json.dumps(data, ensure_ascii=False).encode("utf-8"))
        _atomic_write(self.path, token, mode=0o600)
        self._cache = dict(data)

    # ------------------------------------------------------------------ public API
    def set(self, name: str, value: str) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
            raise VaultError("secret names may only contain letters, digits, '_', '.', '-'")
        data = dict(self._load())
        data[name] = value
        self._save(data)

    def get(self, name: str) -> str | None:
        return self._load().get(name)

    def delete(self, name: str) -> bool:
        data = dict(self._load())
        existed = data.pop(name, None) is not None
        if existed:
            self._save(data)
        return existed

    def names(self) -> list[str]:
        return sorted(self._load().keys())

    def has_placeholders(self, value: Any) -> bool:
        return bool(PLACEHOLDER_RE.search(json.dumps(value, ensure_ascii=False, default=str)))

    def resolve(self, value: Any, strict: bool = True) -> Any:
        """Replace ``{{vault:NAME}}`` placeholders inside strings / dicts / lists."""
        if isinstance(value, str):

            def repl(m: re.Match[str]) -> str:
                secret = self.get(m.group(1))
                if secret is None:
                    if strict:
                        raise VaultError(
                            f"secret '{m.group(1)}' is not in the vault "
                            f"(add it with: unibot vault set {m.group(1)})"
                        )
                    return m.group(0)
                return secret

            return PLACEHOLDER_RE.sub(repl, value)
        if isinstance(value, dict):
            return {k: self.resolve(v, strict) for k, v in value.items()}
        if isinstance(value, list):
            return [self.resolve(v, strict) for v in value]
        return value

    def redact(self, text: str) -> str:
        """Replace any stored secret value appearing in ``text``.

        Secrets are applied longest-first so a short secret can't corrupt a
        longer one's placeholder. Values of 6+ characters are replaced
        wherever they occur; 4–5 character values (PINs) only on word
        boundaries, so an innocent "12345" isn't mangled by a "1234" PIN.
        Shorter values are skipped — redacting 1–3 characters would destroy
        ordinary text, so prefer a longer secret.
        """
        if not text:
            return text
        secrets = sorted(
            ((name, s) for name, s in self._load().items() if s),
            key=lambda kv: len(kv[1]),
            reverse=True,
        )
        for name, secret in secrets:
            if len(secret) >= 6:
                if secret in text:
                    text = text.replace(secret, f"[REDACTED:{name}]")
            elif len(secret) >= 4:
                text = re.sub(
                    r"(?<!\w)" + re.escape(secret) + r"(?!\w)",
                    f"[REDACTED:{name}]",
                    text,
                )
        return text


__all__ = ["CredentialVault", "VaultError", "PLACEHOLDER_RE"]
