"""Atomic writes for secrets and grants.

The vault key file, the vault data file, and the grants file must be written
via temp-file + fsync + os.replace in the same directory, so a crash mid-write
can never leave a truncated or half-permissioned file behind.
"""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path

import pytest

from unibot.sentinel.grants import GrantStore
from unibot.vault import CredentialVault


def _mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def _replace_spy(calls: list):
    real_replace = os.replace

    def spy(src, dst):
        calls.append((Path(src), Path(dst)))
        return real_replace(src, dst)

    return spy


def _default_file_mode() -> int:
    prev = os.umask(0)
    os.umask(prev)
    return 0o666 & ~prev


# ------------------------------------------------------------------ vault


def test_vault_key_file_written_atomically(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    calls: list = []
    monkeypatch.setattr(os, "replace", _replace_spy(calls))
    vault = CredentialVault(tmp_path / "vault.enc", tmp_path / "vault.key")
    vault.set("EMAIL_PASSWORD", "hunter2-secret")
    assert calls, "expected os.replace to be used"
    src, dst = calls[0]
    # the key file must arrive via a temp file in the same directory...
    assert dst == tmp_path / "vault.key"
    assert src.parent == dst.parent
    # ...already 0o600 at rename time (no readable window), and no temp left behind
    assert _mode(tmp_path / "vault.key") == 0o600
    assert list(tmp_path.glob("*.tmp")) == []
    assert vault.get("EMAIL_PASSWORD") == "hunter2-secret"


def test_vault_data_file_written_atomically(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    calls: list = []
    monkeypatch.setattr(os, "replace", _replace_spy(calls))
    vault = CredentialVault(tmp_path / "vault.enc", tmp_path / "vault.key")
    vault.set("A", "secret-value-1")
    data_calls = [c for c in calls if c[1] == tmp_path / "vault.enc"]
    assert data_calls, "expected os.replace to be used for the vault data file"
    assert data_calls[0][0].parent == tmp_path
    assert _mode(tmp_path / "vault.enc") == 0o600
    assert list(tmp_path.glob("*.tmp")) == []
    assert b"secret-value-1" not in (tmp_path / "vault.enc").read_bytes()


def test_vault_failed_write_keeps_original(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    vault = CredentialVault(tmp_path / "vault.enc", tmp_path / "vault.key")
    vault.set("A", "original-secret")
    before = (tmp_path / "vault.enc").read_bytes()

    def boom(*args, **kwargs):
        raise OSError("disk exploded")

    monkeypatch.setattr("unibot.vault.vault.tempfile.mkstemp", boom)
    with pytest.raises(OSError, match="disk exploded"):
        vault.set("B", "new-secret")
    assert (tmp_path / "vault.enc").read_bytes() == before
    assert list(tmp_path.glob("*.tmp")) == []
    assert vault.get("A") == "original-secret"


def test_vault_key_write_failure_leaves_no_key_file(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    def boom(*args, **kwargs):
        raise OSError("disk exploded")

    monkeypatch.setattr("unibot.vault.vault.tempfile.mkstemp", boom)
    vault = CredentialVault(tmp_path / "vault.enc", tmp_path / "vault.key")
    with pytest.raises(OSError, match="disk exploded"):
        vault.set("A", "secret-value-1")
    assert not (tmp_path / "vault.key").exists()
    assert list(tmp_path.glob("*.tmp")) == []


# ------------------------------------------------------------------ grants


def test_grant_store_save_is_atomic(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    calls: list = []
    monkeypatch.setattr(os, "replace", _replace_spy(calls))
    store = GrantStore(tmp_path / "grants.json")
    store.add("web_fetch", "example.com", "always")
    assert calls, "expected os.replace to be used for the grants file"
    src, dst = calls[0]
    assert dst == tmp_path / "grants.json"
    assert src.parent == dst.parent
    assert list(tmp_path.glob("*.tmp")) == []
    data = json.loads((tmp_path / "grants.json").read_text("utf-8"))
    assert data["grants"][0]["key"] == "web_fetch:example.com"
    # grants keep the same permissions a plain write would have produced
    assert _mode(tmp_path / "grants.json") == _default_file_mode()


def test_grant_store_failed_write_keeps_original(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    store = GrantStore(tmp_path / "grants.json")
    store.add("web_fetch", "example.com", "always")
    before = (tmp_path / "grants.json").read_bytes()

    def boom(*args, **kwargs):
        raise OSError("disk exploded")

    monkeypatch.setattr("unibot.sentinel.grants.tempfile.mkstemp", boom)
    with pytest.raises(OSError, match="disk exploded"):
        store.add("send_email", "alice@example.com", "always")
    assert (tmp_path / "grants.json").read_bytes() == before
    assert list(tmp_path.glob("*.tmp")) == []
