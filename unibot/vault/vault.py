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
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet, InvalidToken

PLACEHOLDER_RE = re.compile(r"\{\{\s*vault:([A-Za-z0-9_.-]+)\s*\}\}")


from unibot.fsutil import atomic_write_bytes as _atomic_write
from unibot.logger import logger

try:  # POSIX inter-process lock for the vault file
    import fcntl
except ImportError:  # Windows
    fcntl = None  # type: ignore[assignment]

if fcntl is None:
    try:
        import msvcrt
    except ImportError:  # pragma: no cover
        msvcrt = None  # type: ignore[assignment]
else:
    msvcrt = None  # type: ignore[assignment]


class VaultError(RuntimeError):
    pass


class CredentialVault:
    def __init__(self, path: Path, key_path: Path | None = None):
        self.path = Path(path)
        self.key_path = Path(key_path) if key_path else self.path.with_suffix(".key")
        self._fernet: Fernet | None = None
        self._cache: dict[str, str] | None = None
        # (mtime_ns, size) of the vault file the cache was read from; the cache
        # is only trusted while the file still looks the same. Another process
        # (``unibot vault set`` while the server runs) replaces the file
        # atomically, so a stat change is a reliable "someone else wrote" signal.
        self._cache_stat: tuple[int, int] | None = None

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
    def _stat(self) -> tuple[int, int] | None:
        try:
            st = self.path.stat()
        except OSError:
            return None
        return (st.st_mtime_ns, st.st_size)

    def _load(self) -> dict[str, str]:
        # Never trust a stale cache: when another process rewrote the vault file
        # (``unibot vault set`` while the server runs), the stat differs and the
        # cache is dropped. Without this, a server-side set() would rewrite the
        # file from the stale copy and silently delete the other process's secret.
        if self._cache is not None and self._cache_stat == self._stat():
            return self._cache
        self._cache = None
        if not self.path.exists():
            self._cache = {}
            self._cache_stat = None
            return self._cache
        try:
            raw = self.fernet.decrypt(self.path.read_bytes())
        except InvalidToken as exc:
            raise VaultError(
                f"cannot decrypt {self.path} – wrong key? (key file: {self.key_path})"
            ) from exc
        data = json.loads(raw.decode("utf-8"))
        self._cache = {str(k): str(v) for k, v in data.items()}
        self._cache_stat = self._stat()
        return self._cache

    def _save(self, data: dict[str, str]) -> None:
        token = self.fernet.encrypt(json.dumps(data, ensure_ascii=False).encode("utf-8"))
        _atomic_write(self.path, token, mode=0o600)
        self._cache = dict(data)
        self._cache_stat = self._stat()

    @staticmethod
    def _locked(path: Path):  # noqa: ANN202
        """Best-effort inter-process exclusive lock, for read-modify-write.

        The atomic rename in :meth:`_save` keeps every *read* consistent, but
        two processes doing read-modify-write at the same instant can still
        lose one update. Serializing set()/delete() on a lock file closes that
        window. Same-process threads never block each other on it (POSIX
        locks are per-process), they just serialize like everyone else.
        """
        import contextlib

        @contextlib.contextmanager
        def _lock():
            lock_path = path.with_suffix(".lock")
            try:
                lock_path.parent.mkdir(parents=True, exist_ok=True)
                with open(lock_path, "w") as fh:
                    if fcntl is not None:
                        fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
                    elif msvcrt is not None:  # pragma: no cover - Windows only
                        msvcrt.locking(fh.fileno(), msvcrt.LK_LOCK, 1)
                    try:
                        yield
                    finally:
                        if fcntl is not None:
                            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
                        elif msvcrt is not None:  # pragma: no cover - Windows only
                            msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)
            except OSError as exc:
                # a lock that cannot be taken must never break the write itself
                logger.debug("vault lock unavailable, proceeding unlocked: {}", exc)
                yield

        return _lock()

    # ------------------------------------------------------------------ public API
    def set(self, name: str, value: str) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
            raise VaultError("secret names may only contain letters, digits, '_', '.', '-'")
        # read-modify-write under an inter-process lock: without it, a
        # concurrent writer's secret (e.g. the CLI while the server runs)
        # could be silently dropped by the atomic replace in _save().
        with self._locked(self.path):
            data = dict(self._load())
            data[name] = value
            self._save(data)

    def get(self, name: str) -> str | None:
        return self._load().get(name)

    def delete(self, name: str) -> bool:
        with self._locked(self.path):
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
