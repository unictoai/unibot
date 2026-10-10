"""Email connector (IMAP read / SMTP send).

Credentials are never in the model's context: the connector's configuration
holds ``{{vault:...}}`` placeholders that are resolved from the encrypted vault
at execution time. Incoming mail is scrubbed of one-time passcodes and
password-reset links before the model reads it (as Muse's email connector does).
"""

from __future__ import annotations

import asyncio
import base64
import email
import email.utils
import imaplib
import re
import smtplib
from email.header import decode_header, make_header
from email.message import EmailMessage
from typing import Any

from unibot.config import EmailSettings
from unibot.contacts import ContactBook
from unibot.schema import RiskLevel, ToolResult
from unibot.tools.base import BaseTool, CallAssessment
from unibot.vault import CredentialVault

_OTP_RE = re.compile(
    r"(?i)(verification code|verify code|security code|one[- ]time (?:code|password)|otp|passcode|"
    r"confirmation code|auth(?:entication)? code|验证码|校验码|动态码|驗證碼)"
    r"(.{0,40}?)(?<![0-9A-Za-z])([0-9]{4,8}|[A-Z0-9]{6,8})(?![0-9A-Za-z])"
)
_SENSITIVE_LINK_RE = re.compile(
    r"(?i)https?://[^\s<>\"']*(?:reset|password|passwd|token=|verify|verification|confirm|magic|"
    r"login|signin|auth|unsubscribe)[^\s<>\"']*"
)


def scrub_email_secrets(text: str) -> str:
    text = _OTP_RE.sub(lambda m: f"{m.group(1)}{m.group(2)}[REDACTED-CODE]", text)
    return _SENSITIVE_LINK_RE.sub("[REDACTED-LINK]", text)


def _check_imap_text(value: str, what: str) -> str:
    """Reject control characters that would break out of an IMAP command.

    imaplib joins command arguments with no CR/LF check, so a ``\\r\\n`` in the
    folder or search text injects arbitrary IMAP commands (e.g. STORE +FLAGS
    (\\Deleted) + EXPUNGE wipes the mailbox). Fail the call instead.
    """
    if any(c in value for c in "\r\n\x00"):
        raise ValueError(f"{what} must not contain line breaks")
    return value


def _imap_mailbox(name: str) -> str:
    """A folder name safe to hand to ``imap.select``: modified UTF-7 (the IMAP
    encoding for non-ASCII mailbox names), quoted when it needs it."""
    _check_imap_text(name, "folder")
    if '"' in name:
        raise ValueError('folder must not contain \'"\'')
    out: list[str] = []
    for ch in name:
        o = ord(ch)
        if 0x20 <= o <= 0x7E and ch != "&":
            out.append(ch)
        elif ch == "&":
            out.append("&-")
        else:
            encoded = base64.b64encode(ch.encode("utf-16-be")).decode("ascii")
            out.append("&" + encoded.rstrip("=").replace("/", ",") + "-")
    text = "".join(out)
    if any(c in text for c in " (){%*\"\\"):
        text = f'"{text}"'
    return text


def _decode(value: str | None) -> str:
    if not value:
        return ""
    try:
        return str(make_header(decode_header(value)))
    except Exception:  # noqa: BLE001
        return value


def _body_of(msg: email.message.Message) -> str:
    from unibot.tools.web import html_to_markdown

    plain, html = None, None
    for part in msg.walk():
        ctype = part.get_content_type()
        if part.get_content_disposition() == "attachment":
            continue
        payload = part.get_payload(decode=True)
        if not isinstance(payload, bytes):
            continue
        charset = part.get_content_charset() or "utf-8"
        try:
            text = payload.decode(charset, errors="replace")
        except LookupError:
            text = payload.decode("utf-8", errors="replace")
        if ctype == "text/plain" and plain is None:
            plain = text
        elif ctype == "text/html" and html is None:
            html = text
    if plain:
        return plain.strip()
    if html:
        return html_to_markdown(html)
    return "(no text body)"


class _EmailBase(BaseTool):
    settings: EmailSettings
    vault: CredentialVault | None = None

    def _creds(self) -> tuple[str, str]:
        address, password = self.settings.address, self.settings.password
        if self.vault is not None:
            address = self.vault.resolve(address)
            password = self.vault.resolve(password)
        if "{{" in address or "{{" in password or not address or not password:
            raise RuntimeError(
                "email credentials are not configured. Run `unibot vault set EMAIL_ADDRESS` and "
                "`unibot vault set EMAIL_PASSWORD`, and set connectors.email.* in config.toml"
            )
        return address, password


class ReadEmails(_EmailBase):
    name: str = "read_emails"
    description: str = (
        "Read the user's mailbox over IMAP. Returns the most recent messages (sender, date, subject, "
        "text body). Filters: unread_only, search (IMAP TEXT search), folder (default INBOX)."
    )
    parameters: dict[str, Any] = {
        "type": "object",
        "properties": {
            "folder": {"type": "string"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 30},
            "unread_only": {"type": "boolean"},
            "search": {"type": "string", "description": "Free-text filter applied server-side."},
        },
    }
    risk: RiskLevel = RiskLevel.MODERATE
    reads_private_data: bool = True

    def assess(self, args: dict[str, Any]) -> CallAssessment:
        return CallAssessment(
            risk=RiskLevel.MODERATE,
            reads_private_data=True,
            summary=f"read_emails folder={args.get('folder', 'INBOX')} limit={args.get('limit', 10)}"
            + (" unread" if args.get("unread_only") else "")
            + (f" search='{args['search']}'" if args.get("search") else ""),
        )

    async def execute(
        self,
        folder: str = "INBOX",
        limit: int = 10,
        unread_only: bool = False,
        search: str | None = None,
        **_: Any,
    ) -> ToolResult:
        if not self.settings.enabled or not self.settings.imap_host:
            return ToolResult.fail(
                "email connector is disabled (set connectors.email.enabled=true and imap_host)"
            )
        try:
            address, password = self._creds()
        except RuntimeError as exc:
            return ToolResult.fail(str(exc))
        limit = max(1, min(int(limit or 10), 30))

        def _fetch() -> list[str]:
            with imaplib.IMAP4_SSL(
                self.settings.imap_host, self.settings.imap_port, timeout=30
            ) as imap:
                imap.login(address, password)
                status, _ = imap.select(_imap_mailbox(folder or "INBOX"), readonly=True)
                if status != "OK":
                    raise RuntimeError(f"cannot open folder {folder}")
                criteria: list[str] = []
                if unread_only:
                    criteria.append("UNSEEN")
                if search:
                    # the search goes as a literal with an explicit charset: non-ASCII
                    # (日本語, 中文) works, and quotes/CRLF in it cannot break out of
                    # the command
                    _check_imap_text(search, "search")
                    imap.literal = search.encode("utf-8")
                    status, data = imap.search("UTF-8", "TEXT")
                else:
                    status, data = imap.search(None, *(criteria or ["ALL"]))
                ids = data[0].split() if status == "OK" and data and data[0] else []
                ids = ids[-limit:][::-1]
                out: list[str] = []
                for mid in ids:
                    status, parts = imap.fetch(mid, "(RFC822)")
                    if status != "OK" or not parts or not isinstance(parts[0], tuple):
                        continue
                    msg = email.message_from_bytes(parts[0][1])
                    body = _body_of(msg)
                    subject = _decode(msg.get("Subject"))
                    if self.settings.scrub_secrets:
                        # many services put the code in the subject
                        # ("123456 is your verification code")
                        body = scrub_email_secrets(body)
                        subject = scrub_email_secrets(subject)
                    if len(body) > 3000:
                        body = body[:3000] + "... [truncated]"
                    out.append(
                        f"--- #{mid.decode()} ---\n"
                        f"From: {_decode(msg.get('From'))}\nTo: {_decode(msg.get('To'))}\n"
                        f"Date: {msg.get('Date', '')}\nSubject: {subject}\n\n{body}"
                    )
                return out

        try:
            messages = await asyncio.to_thread(_fetch)
        except (imaplib.IMAP4.error, OSError, RuntimeError, ValueError, UnicodeError) as exc:
            return ToolResult.fail(f"IMAP error: {exc}")
        if not messages:
            return ToolResult(output="No messages matched.")
        return ToolResult(output="\n\n".join(messages))


class SendEmail(_EmailBase):
    name: str = "send_email"
    description: str = (
        "Send an email from the user's account over SMTP. Always show the user the draft (to, subject, "
        "body) before sending unless they explicitly asked you to send without review."
    )
    parameters: dict[str, Any] = {
        "type": "object",
        "properties": {
            "to": {"type": "string", "description": "Comma-separated recipients."},
            "subject": {"type": "string"},
            "body": {"type": "string", "description": "Plain-text body."},
            "cc": {"type": "string"},
        },
        "required": ["to", "subject", "body"],
    }
    risk: RiskLevel = RiskLevel.SENSITIVE
    egress: bool = True
    # the address book, when there is one: the card names the recipient, and a recipient
    # nobody knows is a warning (which asks even in auto mode)
    book: ContactBook | None = None

    def assess(self, args: dict[str, Any]) -> CallAssessment:
        # A standing permission is bound to the recipient(s), never to "any email".
        recipients = sorted(
            {
                addr.strip().lower()
                for field in (args.get("to"), args.get("cc"))
                if field
                for addr in str(field).replace(";", ",").split(",")
                if addr.strip()
            }
        )
        to = str(args.get("to", ""))
        warnings: list[str] = []
        if self.book is not None and self.book.configured and recipients:
            known = {addr: self.book.by_email(addr) for addr in recipients}
            shown = [f"{c.name} <{a}>" if c else a for a, c in known.items()]
            to = ", ".join(shown)
            unknown = [a for a, c in known.items() if c is None]
            if unknown:
                warnings.append(
                    "not in the address book: " + ", ".join(unknown)
                    if len(unknown) > 1
                    else f"{unknown[0]} is not in the address book"
                )
        return CallAssessment(
            risk=RiskLevel.SENSITIVE,
            egress=True,
            egress_target=self.settings.smtp_host or None,
            target=",".join(recipients) or None,
            summary=f"send_email to={to} subject={str(args.get('subject', ''))[:80]!r}",
            warnings=warnings,
        )

    async def execute(
        self, to: str = "", subject: str = "", body: str = "", cc: str | None = None, **_: Any
    ) -> ToolResult:
        if not self.settings.enabled or not self.settings.smtp_host:
            return ToolResult.fail(
                "email connector is disabled (set connectors.email.enabled=true and smtp_host)"
            )
        if not to.strip():
            return ToolResult.fail("`to` is required")
        try:
            address, password = self._creds()
        except RuntimeError as exc:
            return ToolResult.fail(str(exc))

        def _send() -> None:
            msg = EmailMessage()
            msg["From"] = address
            msg["To"] = to
            if cc:
                msg["Cc"] = cc
            msg["Subject"] = subject
            msg["Date"] = email.utils.formatdate(localtime=True)
            msg.set_content(body)
            if self.settings.smtp_starttls:
                with smtplib.SMTP(
                    self.settings.smtp_host, self.settings.smtp_port, timeout=30
                ) as smtp:
                    smtp.ehlo()
                    smtp.starttls()
                    smtp.login(address, password)
                    smtp.send_message(msg)
            else:
                with smtplib.SMTP_SSL(
                    self.settings.smtp_host, self.settings.smtp_port, timeout=30
                ) as smtp:
                    smtp.login(address, password)
                    smtp.send_message(msg)

        try:
            await asyncio.to_thread(_send)
        except (smtplib.SMTPException, OSError) as exc:
            return ToolResult.fail(f"SMTP error: {exc}")
        return ToolResult(
            output=f"Email sent to {to}" + (f" (cc {cc})" if cc else "") + f": {subject}"
        )


__all__ = ["ReadEmails", "SendEmail", "scrub_email_secrets"]
