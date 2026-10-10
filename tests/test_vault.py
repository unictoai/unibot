from __future__ import annotations

import os
import stat
from pathlib import Path

import pytest

from unibot.vault import CredentialVault
from unibot.vault.vault import VaultError


def test_vault_roundtrip(tmp_path: Path):
    vault = CredentialVault(tmp_path / "vault.enc", tmp_path / "vault.key")
    vault.set("EMAIL_PASSWORD", "hunter2-secret")
    assert vault.get("EMAIL_PASSWORD") == "hunter2-secret"
    assert vault.names() == ["EMAIL_PASSWORD"]
    # file on disk is encrypted
    assert b"hunter2" not in (tmp_path / "vault.enc").read_bytes()
    if os.name != "nt":
        mode = stat.S_IMODE((tmp_path / "vault.key").stat().st_mode)
        assert mode == 0o600
    # fresh instance with same key can read it
    again = CredentialVault(tmp_path / "vault.enc", tmp_path / "vault.key")
    assert again.get("EMAIL_PASSWORD") == "hunter2-secret"
    assert again.delete("EMAIL_PASSWORD")
    assert again.get("EMAIL_PASSWORD") is None


def test_resolve_and_redact(tmp_path: Path):
    vault = CredentialVault(tmp_path / "v.enc", tmp_path / "v.key")
    vault.set("TOKEN", "tok-1234567890")
    resolved = vault.resolve(
        {"headers": {"Authorization": "Bearer {{vault:TOKEN}}"}, "list": ["{{ vault:TOKEN }}"]}
    )
    assert resolved["headers"]["Authorization"] == "Bearer tok-1234567890"
    assert resolved["list"] == ["tok-1234567890"]
    assert vault.redact("leak tok-1234567890 here") == "leak [REDACTED:TOKEN] here"
    with pytest.raises(VaultError):
        vault.resolve("{{vault:MISSING}}")
    assert vault.resolve("{{vault:MISSING}}", strict=False) == "{{vault:MISSING}}"


def test_wrong_key_is_reported(tmp_path: Path):
    vault = CredentialVault(tmp_path / "v.enc", tmp_path / "k1.key")
    vault.set("A", "value-123456")
    other = CredentialVault(tmp_path / "v.enc", tmp_path / "k2.key")
    with pytest.raises(VaultError):
        other.get("A")


def test_redact_short_pin(tmp_path: Path):
    """A 4-character PIN must not leak into tool output (was: skipped by the len>=6 cutoff)."""
    vault = CredentialVault(tmp_path / "v.enc", tmp_path / "v.key")
    vault.set("PIN", "1234")
    assert vault.redact("your PIN is 1234, keep it safe") == "your PIN is [REDACTED:PIN], keep it safe"


def test_redact_short_pin_preserves_innocent_substrings(tmp_path: Path):
    """Word-boundary redaction must not mangle innocent text containing the PIN."""
    vault = CredentialVault(tmp_path / "v.enc", tmp_path / "v.key")
    vault.set("PIN", "1234")
    assert vault.redact("order 12345 shipped") == "order 12345 shipped"
    assert vault.redact("code 1234.") == "code [REDACTED:PIN]."


def test_redact_longest_first(tmp_path: Path):
    """Overlapping secrets apply longest-first so placeholders aren't corrupted."""
    vault = CredentialVault(tmp_path / "v.enc", tmp_path / "v.key")
    vault.set("SHORT", "1234")
    vault.set("LONG", "123456")
    out = vault.redact("codes 1234 and 123456")
    assert out == "codes [REDACTED:SHORT] and [REDACTED:LONG]"
    assert "1234" not in out.replace("[REDACTED:SHORT]", "").replace("[REDACTED:LONG]", "")
