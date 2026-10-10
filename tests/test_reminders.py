from __future__ import annotations

import asyncio
import os
import time
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from unibot.goals.store import next_check_in
from unibot.reminders import ReminderStore, parse_when, user_timezone
from unibot.tools import Reminders


def test_parse_when_reads_what_the_model_writes():
    tz = datetime.now().astimezone().tzinfo
    assert parse_when("2026-09-23 18:00") == datetime(2026, 9, 23, 18, 0, tzinfo=tz)
    assert parse_when("2026-09-23T18:00") == datetime(2026, 9, 23, 18, 0, tzinfo=tz)
    assert parse_when("2026-09-23 18:00:30") == datetime(2026, 9, 23, 18, 0, 30, tzinfo=tz)
    # an explicit offset is kept
    assert parse_when("2026-09-23T18:00:00+02:00").utcoffset() == timedelta(hours=2)
    assert parse_when("tomorrow at six") is None
    assert parse_when("") is None


def test_reminders_fire_once_and_routines_repeat(tmp_path: Path):
    store = ReminderStore(tmp_path / "r.db")
    with pytest.raises(ValueError):
        store.create("call mum", at="yesterday")
    with pytest.raises(ValueError):
        store.create("call mum", at="2020-01-01 10:00")  # in the past
    with pytest.raises(ValueError):
        store.create("standup", repeat="every other tuesday")
    with pytest.raises(ValueError):
        store.create("   ", at="2999-01-01 10:00")

    soon = (datetime.now().astimezone() + timedelta(minutes=5)).strftime("%Y-%m-%d %H:%M")
    once = store.create("call mum", at=soon, thread="t_1")
    assert once.kind == "remind" and once.status == "active" and not once.repeating
    assert once.thread == "t_1"
    assert datetime.fromisoformat(once.next_at) > datetime.now(UTC)
    assert store.due() == []
    later = datetime.now(UTC) + timedelta(minutes=10)
    assert [r.id for r in store.due(later)] == [once.id]
    fired = store.mark_fired(once.id, later)
    assert fired.status == "done" and fired.next_at == "" and fired.fired == 1
    assert store.due(later + timedelta(days=1)) == []
    # a finished one-off shows in the full list, not the active one
    assert store.list("active") == []
    assert [r.id for r in store.list(None)] == [once.id]
    assert store.mark_fired(once.id) is None  # already done

    routine = store.create("summarise unread email", repeat="daily 08:00", kind="task")
    assert routine.repeating and routine.kind == "task"
    first = datetime.fromisoformat(routine.next_at)
    fired = store.mark_fired(routine.id, first + timedelta(seconds=30))
    assert fired.status == "active" and fired.fired == 1
    assert datetime.fromisoformat(fired.next_at) - first == timedelta(days=1)
    # "at" is ignored for routines: the cadence decides
    weekly = store.create("weekly review", at="2020-01-01 10:00", repeat="weekly mon 09:00")
    assert datetime.fromisoformat(weekly.next_at).astimezone().weekday() == 0

    # cancel: only active items, and never twice
    cancelled = store.cancel(routine.id)
    assert cancelled.status == "cancelled" and cancelled.next_at == ""
    assert store.cancel(routine.id) is None
    assert store.cancel("r_nope") is None
    assert [r.id for r in store.list("active")] == [weekly.id]
    assert store.delete(weekly.id) and store.get(weekly.id) is None
    assert store.list("active") == []
    assert once.render().startswith(f"[{once.id}] ") and "call mum" in once.render()
    store.close()


def test_reminders_tool(tmp_path: Path):
    store = ReminderStore(tmp_path / "r.db")
    changes: list[int] = []
    tool = Reminders(store=store)
    tool.thread_of = lambda: "t_9"
    tool.on_change = lambda: changes.append(1)

    run = asyncio.run
    r = run(tool.execute(action="create", text="call mum"))
    assert not r.ok and "at" in (r.error or "")
    r = run(tool.execute(action="create", text="call mum", at="not a time"))
    assert not r.ok and "could not read" in (r.error or "")

    soon = (datetime.now().astimezone() + timedelta(hours=1)).strftime("%Y-%m-%d %H:%M")
    r = run(tool.execute(action="create", text="call mum", at=soon))
    assert r.ok and r.output.startswith("Reminder set.") and changes == [1]
    item = store.list("active")[0]
    assert item.thread == "t_9" and item.kind == "remind"

    r = run(
        tool.execute(action="create", text="check the weather", repeat="daily 07:00", kind="task")
    )
    assert r.ok and r.output.startswith("Routine set.")
    r = run(tool.execute(action="list"))
    assert "call mum" in r.output and "daily 07:00" in r.output
    r = run(tool.execute(action="cancel", reminder_id=item.id))
    assert r.ok and r.output.startswith("Cancelled.") and len(changes) == 3
    r = run(tool.execute(action="cancel", reminder_id=item.id))
    assert not r.ok
    assert tool.assess({"action": "create", "text": "call mum", "at": soon}).summary.startswith(
        f"reminders create: {soon}"
    )
    store.close()


# ----------------------------------------------------------------- user timezone
@pytest.fixture(
    params=["UTC", "Pacific/Kiritimati", "America/New_York"],
    ids=["host-utc", "host-plus14", "host-newyork"],
)
def host_tz(request, monkeypatch: pytest.MonkeyPatch):
    """Run the test under several host timezones: resolution in the user's zone must
    not move, e.g. a Karachi 9am must not fire five hours off on a UTC host."""
    if not hasattr(time, "tzset"):  # POSIX only
        pytest.skip("needs tzset")
    old = os.environ.get("TZ")
    monkeypatch.setenv("TZ", request.param)
    time.tzset()
    yield request.param
    if old is None:
        os.environ.pop("TZ", None)
    else:
        os.environ["TZ"] = old
    time.tzset()


def test_naive_reminder_resolves_in_user_timezone(tmp_path: Path, host_tz: str):
    store = ReminderStore(tmp_path / "r.db", tz="Asia/Karachi")
    khi = ZoneInfo("Asia/Karachi")
    # 9am the day after tomorrow in Karachi, as the model would write it
    target = (datetime.now(khi) + timedelta(days=2)).replace(
        hour=9, minute=0, second=0, microsecond=0
    )
    item = store.create("call mum", at=target.strftime("%Y-%m-%d %H:%M"))
    fired_utc = datetime.fromisoformat(item.next_at)
    assert fired_utc == target.astimezone(UTC)
    assert (fired_utc.hour, fired_utc.minute) == (4, 0)  # 09:00 at +05:00
    assert "09:00" in item.render(tz="Asia/Karachi")
    store.close()


def test_routine_occurrences_stay_in_user_timezone(tmp_path: Path, host_tz: str):
    store = ReminderStore(tmp_path / "r.db", tz="Asia/Karachi")
    khi = ZoneInfo("Asia/Karachi")
    routine = store.create("standup", repeat="daily 09:00", kind="task")
    first = datetime.fromisoformat(routine.next_at)
    assert (first.astimezone(khi).hour, first.astimezone(khi).minute) == (9, 0)
    nxt = store.mark_fired(routine.id, first + timedelta(seconds=30))
    second = datetime.fromisoformat(nxt.next_at)
    assert second - first == timedelta(days=1)
    assert (second.astimezone(khi).hour, second.astimezone(khi).minute) == (9, 0)
    store.close()


def test_next_check_in_keeps_an_aware_after_in_its_zone():
    khi = ZoneInfo("Asia/Karachi")
    after = datetime(2026, 10, 10, 10, 0, tzinfo=khi)  # a Saturday, after 9am
    nxt = next_check_in("daily 09:00", after)
    assert nxt is not None and nxt.tzinfo == khi
    assert (nxt.hour, nxt.minute) == (9, 0) and nxt.date() == date(2026, 10, 11)


def test_unknown_timezone_is_rejected(tmp_path: Path):
    with pytest.raises(ValueError, match="unknown timezone"):
        user_timezone("Mars/Olympus")
    with pytest.raises(ValueError, match="unknown timezone"):
        parse_when("2026-09-23 18:00", tz="Mars/Olympus")
    with pytest.raises(ValueError, match="unknown timezone"):
        ReminderStore(tmp_path / "r.db", tz="Mars/Olympus")
    # empty stays valid: the system local zone (today's behaviour)
    assert user_timezone("").utcoffset(None) == user_timezone(None).utcoffset(None)
