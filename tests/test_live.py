"""Live smoke test against the configured model (skipped unless UNIBOT_LIVE=1).

UNIBOT_LIVE=1 UNIBOT_CONFIG=config/config.toml pytest -m live -s
"""

from __future__ import annotations

import os

import pytest

from unibot.app import UnibotApp
from unibot.config import load_settings
from unibot.ui import HeadlessUI

pytestmark = pytest.mark.live


@pytest.mark.skipif(os.environ.get("UNIBOT_LIVE") != "1", reason="set UNIBOT_LIVE=1 to run")
async def test_live_tool_use(tmp_path):
    settings = load_settings()
    settings.data_dir = tmp_path / "data"
    settings.agent.workspace = tmp_path / "ws"
    settings.sentinel.mode = "auto"
    settings.agent.max_steps = 8
    ui = HeadlessUI(verbose=True)
    async with UnibotApp(settings, ui) as muse:
        answer = await muse.run(
            "Use python_execute to compute the 25th Fibonacci number (F(1)=F(2)=1), "
            "then finish with terminate and put only the number in the summary."
        )
    assert "75025" in answer
    tool_events = [e for e in ui.events if e[0] == "tool_call"]
    assert any("python_execute" in e[1] for e in tool_events)
