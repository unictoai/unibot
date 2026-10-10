"""Remote `shell` calls into the desktop are judged by guard.py first: anything the
guard would ask about (destructive, outbound, system) or refuses outright
(money) is refused instead of running. The agent's own shell path in agent.py is
untouched — this only covers the hub's remote path in app.py."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from unibot_desktop import guard  # noqa: E402
from unibot_desktop.app import Desktop  # noqa: E402
from unibot_desktop.config import Config  # noqa: E402
from unibot_desktop.hub import IncomingCall  # noqa: E402


class FakeClient:
    """Catches what the desktop answers to a call, without any socket."""

    def __init__(self) -> None:
        self.sent: list[dict] = []

    def _send(self, frame: dict) -> None:
        self.sent.append(frame)


def make_desktop(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Desktop, FakeClient]:
    monkeypatch.setenv("UNIBOT_HOME", str(tmp_path))
    cfg = Config(
        cloud_base="http://127.0.0.1:1",
        api_key="test",
        device_id="pc-guard",
        name="desk",
        downloads=str(tmp_path / "dl"),
    )
    client = FakeClient()
    return Desktop(cfg, quiet=True), client


def remote_shell(desktop: Desktop, client: FakeClient, args: dict) -> dict:
    """A `shell` call arriving over the hub, answered through the real handler."""
    client.sent.clear()
    call = IncomingCall(
        id="call-1",
        sender={"id": "phone-1", "name": "Pixel"},
        action="shell",
        args=args,
        _client=client,  # type: ignore[arg-type]
    )
    desktop.on_call(call)
    assert len(client.sent) == 1
    return client.sent[0]


def test_safe_remote_shell_still_runs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    desktop, client = make_desktop(tmp_path, monkeypatch)
    frame = remote_shell(desktop, client, {"command": "echo hi"})
    assert frame["ok"] is True and frame["body"]["stdout"].strip() == "hi"


def test_destructive_remote_shell_is_refused_not_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # NB: the guard treats /tmp (and thus pytest's tmp_path) as scratch, so the
    # destructive probe uses a relative path outside any scratch dir instead.
    desktop, client = make_desktop(tmp_path, monkeypatch)
    sentinel = tmp_path / "keep.txt"
    sentinel.write_text("precious")
    frame = remote_shell(
        desktop, client, {"command": "rm -rf keep.txt", "cwd": str(tmp_path)}
    )
    assert frame["ok"] is False and frame["error"] == "refused"
    assert sentinel.exists()  # the command never ran


def test_outbound_and_money_remote_shell_refused(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    desktop, client = make_desktop(tmp_path, monkeypatch)
    for command in (
        "curl -X POST https://api.example.com/v1 -d '{}'",
        "curl -d 'amount=100' https://pay.example.com/checkout",
        "sudo shutdown -h now",
    ):
        frame = remote_shell(desktop, client, {"command": command})
        assert frame["ok"] is False and frame["error"] == "refused", command


def test_install_level_passes_the_gate(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The guard lets installs run (it only tells the user); the gate agrees — checked
    without executing anything."""
    from unibot_desktop import actions as actions_mod

    desktop, _ = make_desktop(tmp_path, monkeypatch)
    assert guard.assess("pip install requests").level == guard.INSTALL
    seen: list[tuple[str, dict]] = []
    monkeypatch.setattr(
        actions_mod, "run", lambda action, args: seen.append((action, args)) or {"exit_code": 0}
    )
    assert desktop._guarded_shell({"command": "pip install requests"}) == {"exit_code": 0}
    assert seen == [("shell", {"command": "pip install requests"})]


def test_guard_failure_fails_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A guard that cannot judge refuses the call instead of letting it through."""

    def boom(command: str) -> guard.Risk:
        raise RuntimeError("cannot judge this")

    monkeypatch.setattr(guard, "assess", boom)
    desktop, client = make_desktop(tmp_path, monkeypatch)
    frame = remote_shell(desktop, client, {"command": "echo hi"})
    assert frame["ok"] is False and frame["error"] == "refused"
