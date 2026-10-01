"""Calendar connector: ``.ics`` feeds read, expanded and searched; events drafted as files."""

from unibot.calendar.feeds import CalendarFeeds, FeedState, Slot
from unibot.calendar.ics import Event, Occurrence, expand, make_ics, parse_ics

__all__ = [
    "CalendarFeeds",
    "Event",
    "FeedState",
    "Occurrence",
    "Slot",
    "expand",
    "make_ics",
    "parse_ics",
]
