---
name: inbox-triage
description: Go through recent mail and sort it into reply-needed, waiting-on-them, to-do, read-later and ignore, with draft replies ready for review. Use when the user asks to triage, sort, catch up on or clear their inbox. Never sends anything on its own.
version: 1.0.0
author: unibot
---

# Inbox triage

Needs the Gmail connector (`gmail_search`, `gmail_read`). If it is not connected, say so and stop — do not invent mail.

## Read

- `gmail_search` with `unread_only=true` first (up to 50). If there are fewer than 10 unread, also read the last 3 days so nothing waiting since yesterday is missed.
- For each sender, use names not addresses in what you write.
- Check memory for the user's inbox rules: senders that always matter, newsletters they keep, how they like replies to sound.

## Sort

Every message lands in exactly one group:

1. **Reply needed** — someone asked the user something, or is waiting on them.
2. **Waiting on them** — the user asked, no answer yet; note for how long.
3. **To do** — no reply needed, but an action is: pay, book, read, show up somewhere.
4. **Read later** — newsletters, notifications, receipts.
5. **Ignore** — clear spam, expired offers, automated noise.

Threads with the same subject count once. Anything about money, a deadline, a contract or health goes to the top of its group.

## Draft, do not send

- For each *Reply needed* mail, write a reply the user could send as-is: their voice (from memory), short, answering the actual question. Put all drafts in one file, `mail/drafts-YYYY-MM-DD.md`, one section per thread with *To*, *Subject*, the draft, and a one-line note on anything you were unsure about.
- Do not call `gmail_send` in this job unless the user tells you, mail by mail, to send. A reply the user approves later is a separate step.

## Report

In chat, a short list per group: sender name — subject — what it is about in a few words (groups 4 and 5 as counts only). Then the drafts file, and the two or three things that should not wait. Deadlines or events found in the mail: offer to set a reminder or draft a calendar event; only do it when the user says yes.

If the user corrects a sorting decision ("newsletters from X I read"), remember it so the next triage gets it right.
