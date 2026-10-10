"""Small filesystem helpers shared across unibot.

Atomic writes: every JSON/state file the app owns goes through
:func:`atomic_write_bytes` (or :func:`atomic_write_text`) so a crash or a full
disk mid-write can never leave a truncated file behind. The payload goes to a
uniquely-named temp file in the *same* directory (so the rename stays on one
filesystem and is atomic), is fsynced to disk, and is then moved into place
with :func:`os.replace`. ``mode`` is applied to the temp file *before* the
rename, so the target never exists with looser permissions.

Corrupt reads: :func:`load_json` quarantines a file that no longer parses
(``<name>.corrupt-<timestamp>``) and logs it, instead of silently resetting
the caller's state to empty — which would make the *next* save cement the loss.
"""

from __future__ import annotations

import json
import os
import tempfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from unibot.logger import logger


def atomic_write_bytes(target: Path, data: bytes, mode: int | None = None) -> None:
    """Write ``data`` to ``target`` atomically (see module docstring)."""
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=target.parent, prefix=target.name + ".", suffix=".tmp")
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


def atomic_write_text(target: Path, text: str, mode: int | None = None) -> None:
    """Write ``text`` (UTF-8) to ``target`` atomically (see module docstring)."""
    atomic_write_bytes(target, text.encode("utf-8"), mode=mode)


def load_json(path: Path, default: Any = None) -> Any:
    """Read a JSON file, quarantining it aside when it no longer parses.

    Returns ``default`` when the file is missing or corrupt. A corrupt file is
    moved to ``<name>.corrupt-<UTC timestamp>`` next to the original and the
    incident is logged, so the damage is visible instead of silently turning
    into an empty state on the next save.
    """
    try:
        return json.loads(path.read_text("utf-8"))
    except FileNotFoundError:
        return default
    except (OSError, json.JSONDecodeError) as exc:
        stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S")
        quarantine = path.with_name(f"{path.name}.corrupt-{stamp}")
        try:
            path.rename(quarantine)
            logger.warning("quarantined corrupt JSON file {} -> {} ({})", path, quarantine, exc)
        except OSError as rename_exc:  # pragma: no cover
            logger.warning("could not parse {} and could not quarantine it: {}", path, rename_exc)
        return default


__all__ = ["atomic_write_bytes", "atomic_write_text", "load_json"]
