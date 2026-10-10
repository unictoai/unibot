---
name: daily-brief
description: Build the user's morning briefing: today's calendar, due reminders, unread priority mail, and anything from memory worth surfacing. Use when the user asks for a morning briefing, what is on today, or to catch up on the day.
version: 1.0.0
author: unibot
tools: datetime
---

# Daily brief

Assemble a short, skimmable morning briefing. It should take under a minute to read. If a source is not connected, skip its section silently — never invent entries.

## Gather

- `datetime` for today's date and the user's local time.
- Calendar: today's events with times, in order. Flag the first event and anything starting within the hour.
- Reminders: anything due today or overdue, shortest first.
- Mail: only the priority slice — unread mail from people (not newsletters, not notifications). Three items max; if there is more, say how many more.
- Memory: one thing worth resurfacing if relevant today (a deadline mentioned before, someone they were waiting on). At most one; skip if nothing fits.

## Write

Keep the whole brief under 200 words. Format:

**Today — <weekday, date>**
- First up: <event, time>
- Then: <other events, one per line, time first>
- Due: <reminders, one per line>
- Inbox: <up to 3 priority mails, sender — subject>
- Heads-up: <the one memory item, or nothing>

No greeting paragraph, no sign-off, no emoji. Times in the user's local timezone. If the day is empty, say "Nothing scheduled today." and stop — do not pad.

## Offer, do not do

After the brief, offer at most two follow-ups as plain questions (e.g. "Want me to draft replies for the two priority mails?"). Take no further action until the user answers.
