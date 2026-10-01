"""The relay's rules: codes, keys, grants, budgets, and what a request costs.

Kept apart from the HTTP layer so the tests can drive it directly and so the
API module stays a thin translation of these calls into status codes.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import logging
import math
import os
import re
import secrets
import sqlite3
import time
from contextlib import closing
from dataclasses import dataclass

from .config import ModelSpec, Settings
from .crypto import IdentifierCrypto
from .db import Database, now
from .identifiers import BadIdentifier, Identifier, parse
from .senders import CodeSender, SendError, make_sender

log = logging.getLogger("unibot_cloud")

KEY_PREFIX = "nm_"

# What one request kind is called in the apps' usage breakdown; the ledger
# keeps the short names.
USAGE_KINDS = ("chat", "image", "video", "realtime")


def hash_password(password: str) -> str:
    """scrypt with a random salt: `scrypt$<salt>$<hash>`, both base64. The
    parameters are the library's recommended interactive ones; a check takes
    a few tens of milliseconds, which is what a sign-in should cost."""
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return "scrypt$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(digest).decode()


def check_password(password: str, stored: str) -> bool:
    try:
        algo, salt_b64, hash_b64 = stored.split("$", 2)
        if algo != "scrypt":
            return False
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
    except (ValueError, TypeError):
        return False
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=len(expected))
    return hmac.compare_digest(digest, expected)


class CloudError(Exception):
    """An error the app should show. `status` is the HTTP status, `code` a stable
    machine-readable name the app can switch on (it has strings for each)."""

    def __init__(self, status: int, code: str, message: str, extra: dict | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        # fields the app can act on beside the words (what is left, where to go next)
        self.extra = dict(extra or {})


def _strip_binary(messages: list) -> list:
    """Messages with pictures (data: URLs) replaced by a marker: the words are the sample."""
    out = []
    for m in messages:
        if not isinstance(m, dict):
            continue
        m = dict(m)
        content = m.get("content")
        if isinstance(content, list):
            parts = []
            for part in content:
                if isinstance(part, dict) and part.get("type") in ("image_url", "input_audio", "video_url", "video"):
                    parts.append({"type": part["type"], "omitted": True})
                elif isinstance(part, dict) and part.get("type") == "text":
                    parts.append({"type": "text", "text": str(part.get("text", ""))})
                else:
                    parts.append(part if isinstance(part, (str, int, float, bool)) else {"type": "other", "omitted": True})
            m["content"] = parts
        out.append(m)
    return out


def _fit_messages(messages: list, max_chars: int) -> tuple[str, bool]:
    """The messages as JSON within ``max_chars`` — whole messages dropped from the middle
    (the system prompt and the last exchange kept), then the longest text cut — so that what
    is stored always parses. Returns the JSON and whether anything was left out."""
    text = json.dumps(messages, ensure_ascii=False)
    if len(text) <= max_chars:
        return text, False
    msgs = [dict(m) for m in messages if isinstance(m, dict)]
    # 1. drop from the middle, oldest first, keeping the first (system) and the last two
    while len(text) > max_chars and len(msgs) > 3:
        del msgs[1]
        text = json.dumps(msgs, ensure_ascii=False)
    # 2. cut the longest text until it fits (a single huge paste, a long system prompt)
    for _ in range(50):
        if len(text) <= max_chars:
            break
        longest, where = -1, None
        for i, m in enumerate(msgs):
            c = m.get("content")
            if isinstance(c, str) and len(c) > longest:
                longest, where = len(c), (i, None)
            elif isinstance(c, list):
                for j, part in enumerate(c):
                    if isinstance(part, dict) and isinstance(part.get("text"), str) and len(part["text"]) > longest:
                        longest, where = len(part["text"]), (i, j)
        if where is None or longest < 200:
            break
        over = len(text) - max_chars
        keep = max(100, longest - over - 40)
        i, j = where
        if j is None:
            msgs[i]["content"] = msgs[i]["content"][:keep] + " […]"
        else:
            parts = list(msgs[i]["content"])
            parts[j] = {**parts[j], "text": parts[j]["text"][:keep] + " […]"}
            msgs[i]["content"] = parts
        text = json.dumps(msgs, ensure_ascii=False)
    return text[:max_chars] if len(text) > max_chars else text, True


def _load_messages(text: str) -> tuple[list, bool]:
    """The stored request back as messages. Samples kept before 0.5.2 were cut at a character
    count and may end mid-string: the messages that are whole are returned and the cut is
    reported, rather than the row (and every export) failing on it."""
    if not text:
        return [], False
    try:
        value = json.loads(text)
        return (value if isinstance(value, list) else [value]), False
    except json.JSONDecodeError:
        pass
    decoder = json.JSONDecoder()
    out: list = []
    i = text.find("[")
    if i < 0:
        return [], True
    i += 1
    while i < len(text):
        while i < len(text) and text[i] in " \n\r\t,":
            i += 1
        if i >= len(text) or text[i] == "]":
            break
        try:
            obj, i = decoder.raw_decode(text, i)
        except json.JSONDecodeError:
            break
        out.append(obj)
    return out, True


def _load_meta(text: str) -> dict:
    try:
        value = json.loads(text) if text else {}
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


@dataclass(frozen=True)
class Caller:
    key_hash: str
    account_id: str
    channel: str
    hint: str
    granted: int
    used: int
    account_created_at: int
    # A member has no daily money cap: on the operator's list (ALLOWED_IDENTIFIERS,
    # matched by the identifier's hash) or flagged on the account by the operator.
    member: bool = False
    has_password: bool = False
    password_set_at: int | None = None
    # How and when this particular key was issued: a recent code sign-in may
    # set a password without knowing the old one (the reset path).
    via: str = "code"
    key_created_at: int = 0
    key_prefix: str = ""
    # Invitations (0.4): the code this person hands out and how many came.
    invite_code: str = ""
    invites: int = 0
    # The lifetime pool (0.5), micro-yuan: the allowance, plus what invites, the co-creation
    # bonus and the operator added. What is spent is the ledger's sum, read when needed.
    grant_uy: int = 0
    contribute_bonus_at: int | None = None
    # The person chose to contribute their conversations (0.4): only then does the
    # relay keep what was said, for the community's own model.
    contribute: bool = False

    @property
    def remaining(self) -> int:
        return max(0, self.granted - self.used)


def _sha256(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


class Cloud:
    def __init__(self, settings: Settings, db: Database | None = None, sender: CodeSender | None = None):
        self.s = settings
        self.db = db or Database(settings.database)
        self.sender = sender or make_sender(settings)
        self.crypto = IdentifierCrypto(settings.identifier_key)
        if settings.dev_mode:
            log.warning("CLOUD_SECRET is not set: development mode, identifiers hashed with a fixed key")
        if settings.unlimited:
            log.warning("SIGNUP_TOKENS=0: no token ceiling, usage is metered only")
        # The members' identifiers, hashed once so a request can be matched
        # against the list without ever seeing the plaintext.
        self.member_hashes: frozenset[str] = frozenset(i.hash(settings.hmac_key) for i in self._listed_identifiers())
        if settings.signup_open:
            log.info(
                "sign-up is open: %d member(s) without a limit, everyone else ¥%.2f in all (+¥%.2f an invite, +¥%.2f for co-creation)",
                len(self.member_hashes),
                settings.allowance_cny,
                settings.invite_bonus_cny,
                settings.contribute_bonus_cny,
            )
        else:
            log.warning("SIGNUP_OPEN=0: private relay, only the %d listed identifier(s) may sign in", len(self.member_hashes))
        if settings.allowance_uy > 0:
            # A database from before 0.5 (or from a spell with ALLOWANCE_CNY=0): every account
            # without a pool starts the lifetime model with the allowance on top of what it has
            # spent, plus any 0.4 credit an invite had earned it. Idempotent: an account with a
            # pool is left alone, so a restart between the column and this line loses nothing.
            n = self.db.seed_grants(settings.allowance_uy)
            if n:
                log.warning("0.5: %d account(s) moved to the lifetime allowance (¥%.2f + what was spent)", n, settings.allowance_cny)

    # -- sign-up -------------------------------------------------------------------

    def _listed_identifiers(self) -> list[Identifier]:
        out = []
        for item in self.s.allowed_identifiers.split(","):
            if not item.strip():
                continue
            try:
                out.append(parse(item))
            except BadIdentifier:
                log.warning("ALLOWED_IDENTIFIERS has an entry that is neither a number nor an address; ignored")
        return out

    def listed(self, ident: Identifier) -> bool:
        """On the operator's list (ALLOWED_IDENTIFIERS)."""
        return ident.hash(self.s.hmac_key) in self.member_hashes

    def allowed(self, ident: Identifier) -> bool:
        """May this identifier sign in? Anyone when sign-up is open; else members only."""
        return self.s.signup_open or self.listed(ident)

    def request_code(self, ident: Identifier, ip: str) -> None:
        if not self.sender.accepts(ident):
            # the honest answer up front: a Hong Kong or overseas number gets no SMS from
            # 号码认证, so the person should not wait for one — e-mail works everywhere
            if ident.channel == "phone" and not ident.value.startswith("+86"):
                raise CloudError(
                    400,
                    "phone_region",
                    "Codes reach mainland China numbers only for now; elsewhere, sign in with an e-mail address",
                )
            raise CloudError(
                400,
                "channel_unsupported",
                f"This relay does not send codes by {'SMS' if ident.channel == 'phone' else 'e-mail'}",
            )
        if not self.allowed(ident):
            raise CloudError(403, "not_invited", "This relay is private; that address is not on its list")
        t = now()
        if self.db.codes_recent_for(ident.hash(self.s.hmac_key), t - 600) >= self.s.code_per_identifier_10m:
            raise CloudError(429, "code_too_often", "Too many codes for this address; wait a few minutes")
        if ip and self.db.codes_recent_for_ip(ip, t - 3600) >= self.s.code_per_ip_hour:
            raise CloudError(429, "code_too_often", "Too many codes from this network; wait an hour")
        code = f"{secrets.randbelow(1_000_000):06d}"
        self.db.insert_code(ident.hash(self.s.hmac_key), _sha256(code), ip, self.s.code_ttl_s)
        try:
            self.sender.send(ident, code)
        except SendError as e:
            raise CloudError(502, "send_failed", f"Could not send the code ({e})") from e

    def verify_code(self, ident: Identifier, code: str, device: str, invite: str = "") -> tuple[str, Caller, bool]:
        """Returns (api_key, caller, created). The key is shown once. `invite` is
        a friend's code; it counts only when this sign-in creates the account."""
        if not self.allowed(ident):
            raise CloudError(403, "not_invited", "This relay is private; that address is not on its list")
        id_hash = ident.hash(self.s.hmac_key)
        row = self.db.live_code(id_hash)
        if row is None:
            raise CloudError(400, "code_expired", "The code has expired; ask for a new one")
        attempts = self.db.bump_attempts(int(row["id"]))
        if attempts > self.s.code_max_attempts:
            self.db.consume_code(int(row["id"]))
            raise CloudError(400, "code_expired", "Too many tries; ask for a new code")
        if not secrets.compare_digest(row["code_hash"], _sha256(code.strip())):
            raise CloudError(400, "code_wrong", "That code is not right")
        self.db.consume_code(int(row["id"]))
        return self.sign_in(ident, device, "code", invite)

    def sign_in(self, ident: Identifier, device: str, via: str, invite: str = "") -> tuple[str, Caller, bool]:
        """An identifier that has just been proven (a code, the carrier's word) becomes a
        signed-in device: the account is made on first sight, a key is issued.
        Returns (api_key, caller, created)."""
        if not self.allowed(ident):
            raise CloudError(403, "not_invited", "This relay is private; that address is not on its list")
        id_hash = ident.hash(self.s.hmac_key)
        account = self.db.account_by_hash(id_hash)
        created = account is None
        if account is None:
            account_id = self.db.new_account_id()
            enc = self.crypto.encrypt(account_id, ident.value)
            account = self.db.create_account(
                id_hash, ident.channel, ident.hint, self.s.signup_tokens, enc, account_id=account_id, grant_uy=self.s.allowance_uy
            )
            self.db.add_event(account_id, "account.created", ident.channel)
            self._accept_invite(account_id, invite)
        elif account["disabled"]:
            raise CloudError(403, "account_disabled", "This account is disabled")

        key, caller = self._issue_key(account["id"], device, via=via)
        self.db.add_event(account["id"], f"sign_in.{via}", device)
        return key, caller, created

    # -- invitations ------------------------------------------------------------------

    @staticmethod
    def normalize_invite(code: str) -> str:
        """Codes are eight letters and digits, shown in upper case; typed any way."""
        return re.sub(r"[^A-Z0-9]", "", (code or "").upper())[:8]

    def _accept_invite(self, new_account_id: str, invite: str) -> None:
        code = self.normalize_invite(invite)
        if not code:
            return
        inviter = self.db.account_by_invite_code(code)
        if inviter is None or inviter["id"] == new_account_id or inviter["disabled"]:
            # a wrong code is not an error: the person is signed in either way
            self.db.add_event(new_account_id, "invite.unknown")
            return
        bonus_uy = self.s.cny_to_uy(self.s.invite_bonus_cny)
        self.db.record_invite(inviter["id"], new_account_id, bonus_uy)
        self.db.add_event(inviter["id"], "invite.accepted", new_account_id[:8])
        self.db.add_event(new_account_id, "invite.used", inviter["id"][:8])
        log.info("invite: %s brought %s (+¥%.2f)", inviter["id"][:8], new_account_id[:8], self.s.invite_bonus_cny)

    def invite_code_for(self, caller: Caller) -> str:
        """The account's code, made on first ask (no confusable letters)."""
        if caller.invite_code:
            return caller.invite_code
        alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        for _ in range(20):
            code = "".join(secrets.choice(alphabet) for _ in range(8))
            if self.db.set_invite_code(caller.account_id, code):
                return code
            row = self.db.account(caller.account_id)
            if row is not None and row["invite_code"]:
                return str(row["invite_code"])
        raise CloudError(500, "invite_code", "Could not make an invite code; try again")

    def invite_view(self, caller: Caller) -> dict:
        code = self.invite_code_for(caller)
        earned = self.s.uy_to_cny(caller.invites * self.s.cny_to_uy(self.s.invite_bonus_cny))
        return {
            "code": code,
            "url": self.s.invite_url + code if self.s.invite_url else "",
            "invites": caller.invites,
            "bonus_cny": self.s.invite_bonus_cny,
            "earned_cny": earned,
            # 0.4 names, one more version: the money invites brought (there is no separate
            # credit any more, it is all one pool) and no clips to count
            "credit_cny": earned,
            "credit_left_cny": earned,
            "clips_per_invite": 0,
            "friends": [{"hint": r["hint"], "joined_at": int(r["created_at"])} for r in self.db.invitees(caller.account_id)],
        }

    @staticmethod
    def clips_view() -> dict:
        """0.5 counts no clips apart — a clip is just the most expensive thing on the one
        allowance. Kept in the shape 0.4 phones read so they show 「不限」."""
        return {"unlimited": True, "allowed": None, "used": 0, "left": None, "per_face": 4}

    def _issue_key(self, account_id: str, device: str, via: str) -> tuple[str, Caller]:
        key = KEY_PREFIX + secrets.token_urlsafe(30)
        self.db.insert_key(_sha256(key), key[:10], account_id, device, via=via)
        caller = self._caller(_sha256(key))
        assert caller is not None
        return key, caller

    # -- passwords --------------------------------------------------------------------

    def _check_new_password(self, password: str, ident_value: str = "") -> None:
        if len(password) < self.s.password_min_len:
            raise CloudError(400, "password_short", f"Use at least {self.s.password_min_len} characters")
        if len(password) > 128:
            raise CloudError(400, "password_long", "That password is too long")
        if len(set(password)) < 3:
            raise CloudError(400, "password_weak", "Use a few different characters")
        if ident_value and password.strip().lower() == ident_value.strip().lower():
            raise CloudError(400, "password_weak", "The password cannot be the address itself")

    def set_password(self, caller: Caller, password: str, current: str | None = None) -> None:
        """Set or change the password. When one is already set, the old one is
        needed — unless this key came from a code sign-in a few minutes ago,
        which is the "forgot it" path (the code proved the phone or mailbox)."""
        account = self.db.account(caller.account_id)
        if account is None:
            raise CloudError(404, "no_account", "No such account")
        self._check_new_password(password, self.crypto.decrypt(caller.account_id, account["identifier_enc"] or "") or "")
        stored = account["password_hash"] or ""
        if stored:
            fresh_code = caller.via == "code" and now() - caller.key_created_at <= self.s.password_reset_window_s
            if not fresh_code:
                if not current:
                    raise CloudError(400, "password_required", "Enter the current password (or sign in with a code first)")
                if not check_password(current, stored):
                    left = self.db.login_failed(caller.account_id, self.s.password_max_attempts, self.s.lockout_s)
                    raise CloudError(
                        400,
                        "password_wrong",
                        f"That is not the current password ({left} tries left)"
                        if left
                        else "Too many wrong tries; sign in with a code to reset it",
                    )
        self.db.set_password(caller.account_id, hash_password(password))
        self.db.add_event(caller.account_id, "password.set" if not stored else "password.changed")

    def clear_password(self, caller: Caller, current: str) -> None:
        account = self.db.account(caller.account_id)
        if account is None or not account["password_hash"]:
            return
        if not check_password(current, account["password_hash"]):
            raise CloudError(400, "password_wrong", "That is not the current password")
        self.db.set_password(caller.account_id, "")
        self.db.add_event(caller.account_id, "password.cleared")

    def login_password(self, ident: Identifier, password: str, device: str) -> tuple[str, Caller]:
        """Sign in on a new device with the password instead of waiting for a code."""
        if not self.allowed(ident):
            raise CloudError(403, "not_invited", "This relay is private; that address is not on its list")
        account = self.db.account_by_hash(ident.hash(self.s.hmac_key))
        if account is None:
            # Same answer as a wrong password: the sign-in form must not tell
            # whether a number has an account.
            hash_password("x")  # keep the timing alike
            self.db.add_event("", "sign_in.failed", ident.channel)
            raise CloudError(401, "bad_credentials", "That address and password do not match")
        if account["disabled"]:
            raise CloudError(403, "account_disabled", "This account is disabled")
        locked_until = account["locked_until"]
        if locked_until and int(locked_until) > now():
            raise CloudError(429, "locked", "Too many wrong passwords; wait a while or sign in with a code")
        if not account["password_hash"]:
            raise CloudError(400, "no_password", "This account has no password yet; sign in with a code and set one under Account")
        if not check_password(password, account["password_hash"]):
            left = self.db.login_failed(account["id"], self.s.password_max_attempts, self.s.lockout_s)
            self.db.add_event(account["id"], "sign_in.failed", device)
            if left == 0:
                raise CloudError(429, "locked", "Too many wrong passwords; wait a while or sign in with a code")
            raise CloudError(401, "bad_credentials", "That address and password do not match")
        self.db.login_succeeded(account["id"])
        key, caller = self._issue_key(account["id"], device, via="password")
        self.db.add_event(account["id"], "sign_in.password", device)
        return key, caller

    # -- keys -------------------------------------------------------------------------

    def _caller(self, key_hash: str) -> Caller | None:
        row = self.db.key(key_hash)
        if row is None or row["revoked_at"] is not None:
            return None
        return (
            Caller(
                key_hash=key_hash,
                account_id=row["account_id"],
                channel=row["channel"],
                hint=row["hint"],
                granted=int(row["granted"]),
                used=int(row["used"]),
                account_created_at=int(row["account_created_at"]),
                member=bool(row["account_unlimited"]) or row["id_hash"] in self.member_hashes,
                has_password=bool(row["password_hash"]),
                password_set_at=int(row["password_set_at"]) if row["password_set_at"] else None,
                via=row["via"] or "code",
                key_created_at=int(row["created_at"]),
                key_prefix=row["prefix"],
                invite_code=row["invite_code"] or "",
                invites=int(row["invites"] or 0),
                grant_uy=int(row["grant_uy"] or 0),
                contribute_bonus_at=int(row["contribute_bonus_at"]) if row["contribute_bonus_at"] else None,
                contribute=bool(row["contribute"]),
            )
            if not row["account_disabled"]
            else None
        )

    def authenticate(self, bearer: str | None) -> Caller:
        if not bearer or not bearer.startswith(KEY_PREFIX):
            raise CloudError(401, "bad_key", "Sign in again in the app")
        caller = self._caller(_sha256(bearer))
        if caller is None:
            raise CloudError(401, "bad_key", "This key is no longer valid; sign in again in the app")
        self.db.touch_key(caller.key_hash)
        return caller

    def sign_out(self, caller: Caller) -> None:
        self.db.revoke_key(caller.key_hash)
        self.db.add_event(caller.account_id, "sign_out")

    def sign_out_all(self, caller: Caller, keep_current: bool = True) -> int:
        """Every other device (or every device) signed out at once — what to do
        after losing a phone."""
        n = self.db.revoke_all_keys(caller.account_id, keep_hash=caller.key_hash if keep_current else None)
        self.db.add_event(caller.account_id, "sign_out.all", str(n))
        return n

    def revoke_session(self, caller: Caller, prefix: str) -> None:
        if not self.db.revoke_key_by_prefix(caller.account_id, prefix[:10]):
            raise CloudError(404, "no_session", "No such sign-in")
        self.db.add_event(caller.account_id, "sign_out", prefix[:10])

    def sessions(self, caller: Caller) -> list[dict]:
        """The live sign-ins of this account — one per device that has a key —
        with the one making the request marked."""
        out = []
        for k in self.db.keys_for(caller.account_id, live_only=True):
            out.append(
                {
                    "prefix": k["prefix"],
                    "device": k["device"],
                    "via": k["via"] or "code",
                    "created_at": int(k["created_at"]),
                    "last_used_at": int(k["last_used_at"]) if k["last_used_at"] else None,
                    "current": k["key_hash"] == caller.key_hash,
                }
            )
        out.sort(key=lambda s: (not s["current"], -(s["last_used_at"] or s["created_at"])))
        return out

    def events(self, caller: Caller, limit: int = 50) -> list[dict]:
        return [dict(r) for r in self.db.events_for(caller.account_id, max(1, min(limit, 200)))]

    def delete_account(self, caller: Caller) -> None:
        """The person's own request: every key stops working and nothing about them stays."""
        self.db.delete_account(caller.account_id)

    def usage(self, account_id: str, day_start: int) -> dict:
        """The breakdown behind the totals: by kind (chat, pictures, clips,
        calls) today and overall, and by model overall."""

        def rows(rs) -> list[dict]:
            out = []
            for r in rs:
                d = dict(r)
                d["cost_cny"] = self.s.uy_to_cny(int(d.pop("cost_uy", 0) or 0))
                for k in ("requests", "prompt_tokens", "completion_tokens", "charged"):
                    d[k] = int(d.get(k) or 0)
                out.append(d)
            return out

        return {
            "today": {"by_kind": rows(self.db.usage_by_kind(day_start, account_id))},
            "total": {
                "by_kind": rows(self.db.usage_by_kind(0, account_id)),
                "by_model": rows(self.db.usage_by_model(0, account_id)),
            },
            "kinds": list(USAGE_KINDS),
        }

    # -- the allowance (0.5) ----------------------------------------------------------------
    #
    # One pool for the account's lifetime: `grant_uy` on the account, what was spent is the
    # ledger's sum. Members (the operator's list, or flagged) have no limit; so has everyone
    # when ALLOWANCE_CNY is 0.

    def limited(self, caller: Caller) -> bool:
        return not caller.member and self.s.allowance_cny > 0

    def allowance(self, caller: Caller, spent_uy: int | None = None) -> dict:
        """The numbers behind every allowance view: spent, the pool, what is left, and
        whether it is time for the 80 % heads-up."""
        if spent_uy is None:
            spent_uy = self.db.spent_since(caller.account_id, 0)
        limited = self.limited(caller)
        grant = caller.grant_uy if limited else 0
        left = max(0, grant - spent_uy) if limited else None
        return {
            "limited": limited,
            "spent_uy": spent_uy,
            "grant_uy": grant,
            "left_uy": left,
            "warn": bool(limited and grant > 0 and spent_uy * 5 >= grant * 4),
            "contribute_bonus_available": self.s.contribute_bonus_cny > 0 and caller.contribute_bonus_at is None,
        }

    def _exhausted(self, caller: Caller, a: dict) -> CloudError:
        ways = [f"invite a friend (+¥{self.s.invite_bonus_cny:g} each)"]
        if a["contribute_bonus_available"]:
            ways.append(f"join the co-creation programme (+¥{self.s.contribute_bonus_cny:g}, once)")
        ways.append("add your own model key (Alibaba Cloud Bailian has a free tier)")
        message = (
            f"Your free allowance (¥{self.s.uy_to_cny(a['grant_uy']):g}) is used up. "
            f"Three ways on: {'; '.join(ways)}. Your sign-in and your devices keep working either way."
        )
        return CloudError(
            429,
            "allowance_exhausted",
            message,
            extra={
                "left": self.s.uy_to_cny(a["left_uy"] or 0),
                "grant": self.s.uy_to_cny(a["grant_uy"]),
                "invite_url": (self.s.invite_url + self.invite_code_for(caller)) if self.s.invite_url else "",
                "invite_bonus_cny": self.s.invite_bonus_cny,
                "contribute_bonus_available": a["contribute_bonus_available"],
                "contribute_bonus_cny": self.s.contribute_bonus_cny,
                "own_key_docs": self.s.own_key_docs,
            },
        )

    def me(self, caller: Caller) -> dict:
        t = now()
        day_start = self.s.day_start(t)
        spent_today_uy = self.db.spent_since(caller.account_id, day_start)
        spent_uy = self.db.spent_since(caller.account_id, 0)
        a = self.allowance(caller, spent_uy)
        grant_cny = self.s.uy_to_cny(a["grant_uy"])
        left_cny = None if a["left_uy"] is None else self.s.uy_to_cny(a["left_uy"])
        return {
            "account": {
                # an opaque id (not the identifier): what unibot Web keys a person's kept
                # Muse to, so a sign-in from another browser lands in the same one
                "id": caller.account_id,
                "channel": caller.channel,
                "hint": caller.hint,
                "created_at": caller.account_created_at,
                "member": caller.member,
                "has_password": caller.has_password,
                "password_set_at": caller.password_set_at,
                "sessions": len(self.db.keys_for(caller.account_id, live_only=True)),
                "signed_in_via": caller.via,
            },
            "usage": self.usage(caller.account_id, day_start),
            "tokens": {
                # `unlimited` first: when it is true the app shows 「不限」 and
                # ignores granted/remaining (kept so older builds still parse).
                "unlimited": self.s.unlimited,
                "granted": caller.granted,
                "used": caller.used,
                "remaining": caller.remaining,
                "used_today": self.db.used_since(caller.account_id, day_start),
                "daily_cap": self.s.daily_cap_tokens,
            },
            # Money, as the provider bills the operator: what the account has spent in all
            # against its pool (0 = no limit, which is what members get), what is left, and
            # the rate the apps use to show dollars next to yuan. `warn` turns on at 80 %.
            "spend": {
                "currency": "CNY",
                "total": self.s.uy_to_cny(spent_uy),
                "grant": grant_cny,
                "left": left_cny,
                "unlimited": not a["limited"],
                "warn": a["warn"],
                "usd_cny": self.s.usd_cny,
                "total_usd": self.s.cny_to_usd(self.s.uy_to_cny(spent_uy)),
                "grant_usd": self.s.cny_to_usd(grant_cny),
                "left_usd": None if left_cny is None else self.s.cny_to_usd(left_cny),
                "today": self.s.uy_to_cny(spent_today_uy),
                "today_usd": self.s.cny_to_usd(self.s.uy_to_cny(spent_today_uy)),
                # how the pool grows, for the account page
                "allowance_cny": self.s.allowance_cny,
                "invite_bonus_cny": self.s.invite_bonus_cny,
                "contribute_bonus_cny": self.s.contribute_bonus_cny,
                "contribute_bonus_available": a["contribute_bonus_available"],
                "own_key_docs": self.s.own_key_docs,
                # 0.4 names, one more version: apps from before 0.5 draw a "today / cap" bar;
                # with the pool in `daily_cap` and the total in `today` that bar is the right
                # one, and with no `resets_at` they print no midnight.
                "daily_cap": grant_cny,
                "daily_cap_usd": self.s.cny_to_usd(grant_cny),
                "day_offset_h": self.s.day_offset_h,
                "resets_at": 0,
                "credit_left": 0,
                "left_today": left_cny,
            },
            "invite": self.invite_view(caller),
            "clips": self.clips_view(),
            "contribute": {
                "on": caller.contribute,
                "samples": self.db.sample_count(caller.account_id) if caller.contribute else 0,
                "bonus_cny": self.s.contribute_bonus_cny,
                "bonus_available": a["contribute_bonus_available"],
                "bonus_at": caller.contribute_bonus_at,
            },
            "models": [m.to_public() for m in self.s.models],
            "base_url": self.s.public_base,
            "recent": [self._ledger_row(r) for r in self.db.recent_ledger(caller.account_id)],
        }

    def _ledger_row(self, r) -> dict:
        d = dict(r)
        d["cost_cny"] = self.s.uy_to_cny(int(d.pop("cost_uy", 0) or 0))
        extra = d.pop("extra", "") or ""
        if extra:
            try:
                d["detail"] = json.loads(extra)
            except ValueError:
                pass
        return d

    # -- budget -------------------------------------------------------------------------

    def model_for(self, model_id: str, kind: str) -> ModelSpec:
        m = self.s.model(model_id)
        if m is None or m.kind != kind:
            offered = ", ".join(x.id for x in self.s.models if x.kind == kind)
            raise CloudError(404, "model_not_offered", f"unibot Cloud does not offer {model_id!r}; choose one of: {offered}")
        return m

    def check_budget(self, caller: Caller, minimum: int = 1, cost_uy: int = 0) -> None:
        """Each limit is off when its setting is 0; the per-minute one guards the
        operator's bill against a runaway loop even on an unlimited relay.
        `cost_uy` is what the request is known to cost up front (a picture, a
        clip) so it is refused before the money is spent rather than after."""
        try:
            self._check_budget(caller, minimum, cost_uy)
        except CloudError as e:
            if e.code in ("out_of_tokens", "daily_cap", "allowance_exhausted"):
                self.db.add_event(caller.account_id, "budget.refused", e.code)
            raise

    def _check_budget(self, caller: Caller, minimum: int, cost_uy: int) -> None:
        if not self.s.unlimited and caller.remaining < minimum:
            raise CloudError(
                402,
                "out_of_tokens",
                "Your unibot Cloud grant is used up. Add your own model key under Settings → Providers to keep going.",
            )
        t = now()
        if self.s.per_minute_requests > 0 and self.db.requests_since(caller.account_id, t - 60) >= self.s.per_minute_requests:
            raise CloudError(429, "rate_limited", "Too many requests; slow down a little")
        if self.s.daily_cap_tokens > 0 and self.db.used_since(caller.account_id, self.s.day_start(t)) >= self.s.daily_cap_tokens:
            raise CloudError(429, "daily_cap", "Today's share of the grant is used up; it resets at midnight")
        if self.limited(caller):
            # the one pool: a chat starts while anything is left (it is priced after the
            # fact), a picture or a clip only when its known price fits
            a = self.allowance(caller)
            spent, grant = a["spent_uy"], a["grant_uy"]
            if spent >= grant or (cost_uy > 0 and spent + cost_uy > grant):
                raise self._exhausted(caller, a)

    def charge_chat(self, caller: Caller, model: ModelSpec, prompt_tokens: int, completion_tokens: int, request_id: str) -> int:
        charged = math.ceil(prompt_tokens * model.in_mult + completion_tokens * model.out_mult)
        cost = model.chat_cost_uy(prompt_tokens, completion_tokens)
        self.db.charge(caller.account_id, "chat", model.id, prompt_tokens, completion_tokens, charged, request_id, cost_uy=cost)
        return charged

    def charge_image(self, caller: Caller, model: ModelSpec, n: int, request_id: str, size: str | None = None) -> int:
        charged = model.per_image * max(1, n)
        cost = model.image_cost_uy(size) * max(1, n)
        self.db.charge(caller.account_id, "image", model.id, 0, 0, charged, request_id, cost_uy=cost)
        return charged

    def charge_video(self, caller: Caller, model: ModelSpec, request_id: str, cost_uy: int = 0) -> int:
        """One accepted task = one clip; the provider bills per output second, so
        `per_clip` is set for the short clips the app asks for and the money
        was priced from the seconds asked for when the task was submitted."""
        charged = model.per_clip
        self.db.charge(caller.account_id, "video", model.id, 0, 0, charged, request_id, cost_uy=cost_uy)
        return charged

    def note(self, account_id: str, kind: str, detail: str = "") -> None:
        """An event on the account's timeline (never message content)."""
        self.db.add_event(account_id, kind, detail)

    # -- contributed conversations ---------------------------------------------------------
    #
    # Off for everyone until they turn it on. With it on, each chat request's messages and
    # the model's reply are kept for the community's own model — nothing else changes, and
    # the person can turn it off and delete what they gave at any time. Never the person's
    # identity: samples carry the account id only, and are exported without it.

    SAMPLE_MAX_CHARS = 200_000

    # -- the profile: the agent's name and look, the same on every device ----------------

    PROFILE_MOODS = ("idle", "working", "waiting", "happy", "error")
    PROFILE_AVATARS = ("dragon", "emoji", "face")
    # one still, and the five together, as stored bytes (512 px WebP stills run 20–60 KB)
    PROFILE_STILL_BYTES = 200 * 1024
    PROFILE_FACE_BYTES = 800 * 1024

    def profile(self, caller: Caller, with_face: bool = True) -> dict:
        """What the account's devices wear: ``rev`` 0 and empty fields until one of them writes."""
        row = self.db.profile(caller.account_id)
        if row is None:
            return {
                "rev": 0,
                "updated_at": None,
                "device": "",
                "name": "",
                "avatar": "",
                "emoji": "",
                "color": "",
                "style": "",
                "description": "",
                "face": None,
            }
        body = json.loads(row["body"] or "{}")
        out = {
            "rev": int(row["rev"]),
            "updated_at": int(row["updated_at"]),
            "device": str(row["device"]),
            "name": str(body.get("name") or ""),
            "avatar": str(body.get("avatar") or ""),
            "emoji": str(body.get("emoji") or ""),
            "color": str(body.get("color") or ""),
            "style": str(body.get("style") or ""),
            "description": str(body.get("description") or ""),
            "has_face": bool(row["face"]),
            # the hash of the idle still: a device that already wears these pictures keeps
            # its own copy (and the clips it made) instead of downloading them again
            "face_id": self._face_id(row["face"]),
        }
        if with_face:
            out["face"] = json.loads(row["face"]) if row["face"] else None
        return out

    @staticmethod
    def _face_id(face_json: str | None) -> str:
        if not face_json:
            return ""
        try:
            idle = json.loads(face_json).get("idle") or ""
            return hashlib.sha1(base64.b64decode(idle)).hexdigest()[:12] if idle else ""
        except (ValueError, binascii.Error, AttributeError):
            return ""

    def put_profile(self, caller: Caller, device: str, data: dict) -> dict:
        """Last writer wins. Only the look is kept — never a key, a message or a setting that
        could reach the network. ``face`` absent keeps the stored pictures, null clears them,
        a {mood: base64 WebP} map replaces them (``avatar`` "face" needs one or the other)."""
        name = str(data.get("name") or "").strip()
        avatar = str(data.get("avatar") or "").strip()
        emoji = str(data.get("emoji") or "").strip()
        color = str(data.get("color") or "").strip()
        style = str(data.get("style") or "").strip()
        description = str(data.get("description") or "").strip()
        if not name or len(name) > 60:
            raise CloudError(400, "bad_request", "name is 1–60 characters")
        if avatar not in self.PROFILE_AVATARS:
            raise CloudError(400, "bad_request", "avatar is dragon, emoji or face")
        if len(emoji) > 16 or len(style) > 20 or len(description) > 200:
            raise CloudError(400, "bad_request", "emoji, style or description too long")
        if color and not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
            raise CloudError(400, "bad_request", "color is #rrggbb")
        face: str | None
        if "face" not in data:
            face = None
        elif data["face"] is None:
            face = ""
        else:
            face = self._check_face(data["face"])
        row = self.db.profile(caller.account_id)
        if avatar == "face" and not (face or (face is None and row is not None and row["face"])):
            raise CloudError(400, "bad_request", "a drawn face needs its pictures")
        body = json.dumps(
            {"name": name, "avatar": avatar, "emoji": emoji, "color": color, "style": style, "description": description},
            ensure_ascii=False,
        )
        rev = self.db.put_profile(caller.account_id, device[:80], body, face)
        self.note(caller.account_id, "profile.put", avatar)
        return {"rev": rev, "device": device[:80]}

    def _check_face(self, face: object) -> str:
        if not isinstance(face, dict) or not face:
            raise CloudError(400, "bad_request", "face is a {mood: base64} map")
        kept: dict[str, str] = {}
        total = 0
        for mood, pic in face.items():
            if mood not in self.PROFILE_MOODS or not isinstance(pic, str):
                raise CloudError(400, "bad_request", f"face has an unknown mood {mood!r}")
            try:
                raw = base64.b64decode(pic, validate=True)
            except (ValueError, binascii.Error):
                raise CloudError(400, "bad_request", f"face.{mood} is not base64") from None
            if not (raw.startswith(b"RIFF") and raw[8:12] == b"WEBP") and not raw.startswith(b"\x89PNG"):
                raise CloudError(400, "bad_request", f"face.{mood} is not WebP or PNG")
            if len(raw) > self.PROFILE_STILL_BYTES:
                raise CloudError(413, "too_large", f"face.{mood} is over {self.PROFILE_STILL_BYTES // 1024} KB")
            total += len(raw)
            kept[mood] = pic
        if "idle" not in kept:
            raise CloudError(400, "bad_request", "face needs at least the idle still")
        if total > self.PROFILE_FACE_BYTES:
            raise CloudError(413, "too_large", f"the face is over {self.PROFILE_FACE_BYTES // 1024} KB in all")
        return json.dumps(kept)

    def delete_profile(self, caller: Caller) -> None:
        self.db.delete_profile(caller.account_id)
        self.note(caller.account_id, "profile.clear")

    def set_contribute(self, caller: Caller, on: bool) -> dict:
        """Joining the co-creation programme (turning contribution on) adds CONTRIBUTE_BONUS_CNY
        to the pool the first time — once for the account's lifetime, so switching it off and
        on again earns nothing more, and the samples already given are not taken back."""
        granted = False
        if on != caller.contribute:
            self.db.set_contribute(caller.account_id, on)
            self.note(caller.account_id, "contribute.on" if on else "contribute.off")
        if on and self.s.contribute_bonus_cny > 0 and caller.contribute_bonus_at is None:
            bonus_uy = self.s.cny_to_uy(self.s.contribute_bonus_cny)
            granted = self.db.grant_contribute_bonus(caller.account_id, bonus_uy)
            if granted:
                self.note(caller.account_id, "contribute.bonus", f"¥{self.s.contribute_bonus_cny:g}")
        row = self.db.account(caller.account_id)
        bonus_at = int(row["contribute_bonus_at"]) if row is not None and row["contribute_bonus_at"] else None
        return {
            "on": on,
            "samples": self.db.sample_count(caller.account_id) if on else 0,
            "bonus_cny": self.s.contribute_bonus_cny,
            "bonus_granted": granted,
            "bonus_available": self.s.contribute_bonus_cny > 0 and bonus_at is None,
            "bonus_at": bonus_at,
        }

    def delete_samples(self, caller: Caller) -> int:
        n = self.db.delete_samples(caller.account_id)
        self.note(caller.account_id, "contribute.deleted", f"{n} conversations")
        return n

    def keep_sample(
        self,
        caller: Caller,
        model: str,
        messages: list,
        response: str,
        prompt_tokens: int,
        completion_tokens: int,
        meta: dict | None = None,
    ) -> None:
        """Called after a chat turn for a contributing account; anything else is a no-op."""
        if not caller.contribute:
            return
        request, cut = _fit_messages(_strip_binary(messages), self.SAMPLE_MAX_CHARS)
        meta = dict(meta or {})
        if cut or len(response) > self.SAMPLE_MAX_CHARS:
            meta["truncated"] = True
        self.db.add_sample(
            caller.account_id,
            model,
            request,
            response[: self.SAMPLE_MAX_CHARS],
            prompt_tokens,
            completion_tokens,
            json.dumps(meta, ensure_ascii=False),
        )

    def admin_samples(self, account_id: str | None = None, since: int = 0, limit: int = 100, before: int = 0) -> list[dict]:
        out = []
        for r in self.db.samples(account_id, since, limit, before):
            d = dict(r)
            d["request"], cut = _load_messages(d["request"])
            d["meta"] = _load_meta(d["meta"])
            if cut:
                d["meta"]["truncated"] = True
            out.append(d)
        return out

    def export_samples(self, since: int = 0):
        """Every contributed conversation as JSON lines, without the account id: the
        training set is about what was said, not who said it."""
        before = 0
        while True:
            rows = self.db.samples(None, since, 1000, before)
            if not rows:
                return
            for r in rows:
                messages, cut = _load_messages(r["request"])
                meta = _load_meta(r["meta"])
                if cut:
                    meta["truncated"] = True
                d = {
                    "id": r["id"],
                    "ts": int(r["ts"]),
                    "model": r["model"],
                    "messages": messages,
                    "response": r["response"],
                    "prompt_tokens": int(r["prompt_tokens"]),
                    "completion_tokens": int(r["completion_tokens"]),
                    "meta": meta,
                }
                yield json.dumps(d, ensure_ascii=False) + "\n"
            before = int(rows[-1]["ts"])
            if len(rows) < 1000:
                return

    def estimate(
        self, caller: Caller, images: int = 0, clips: int = 0, image_model: str = "", video_model: str = "", size: str | None = None
    ) -> dict:
        """What a job would cost before it is started — the app asks before a new
        face (five pictures and, with video on, four clips) and shows the person
        the number next to what they have left today. Nothing is charged."""
        images = max(0, min(images, 50))
        clips = max(0, min(clips, 20))
        parts = []
        cost = 0
        if images:
            m = self.model_for(image_model, "image") if image_model else next((x for x in self.s.models if x.kind == "image"), None)
            if m is None:
                raise CloudError(404, "model_not_offered", "unibot Cloud offers no image model")
            c = m.image_cost_uy(size) * images
            cost += c
            parts.append({"kind": "image", "model": m.id, "count": images, "cny": self.s.uy_to_cny(c)})
        if clips:
            m = self.model_for(video_model, "video") if video_model else next((x for x in self.s.models if x.kind == "video"), None)
            if m is None:
                raise CloudError(404, "model_not_offered", "unibot Cloud offers no video model")
            c = m.video_cost_uy(m.clip_seconds) * clips
            cost += c
            parts.append({"kind": "video", "model": m.id, "count": clips, "seconds": m.clip_seconds, "cny": self.s.uy_to_cny(c)})
        a = self.allowance(caller)
        room = a["left_uy"]
        left_cny = None if room is None else self.s.uy_to_cny(room)
        grant_cny = self.s.uy_to_cny(a["grant_uy"])
        return {
            "cny": self.s.uy_to_cny(cost),
            "usd": self.s.cny_to_usd(self.s.uy_to_cny(cost)),
            "parts": parts,
            "left_cny": left_cny,
            "grant_cny": grant_cny,
            "unlimited": room is None,
            "affordable": room is None or cost <= room,
            # 0.4 names, one more version
            "left_today_cny": left_cny,
            "credit_left_cny": 0,
            "clips_ok": True,
            "clips": self.clips_view(),
            "daily_cap_cny": grant_cny,
        }

    # -- admin ------------------------------------------------------------------------------

    def admin_credit(self, account_id: str, cny: float, note: str = "") -> dict:
        """Credit from the operator: a merged pull request, a reported bug, a
        promised refund. It goes into the account's pool and never expires."""
        if self.db.account(account_id) is None:
            raise CloudError(404, "no_account", "No such account")
        if cny < 0 or cny > 1000:
            raise CloudError(400, "bad_request", "Credit is between ¥0 and ¥1000")
        self.db.add_credit(account_id, self.s.cny_to_uy(cny), note=note)
        self.db.add_event(account_id, "credit.granted", f"¥{cny:g}")
        a = dict(self.db.account(account_id))  # type: ignore[arg-type]
        a["identifier"] = self.crypto.decrypt(account_id, a.pop("identifier_enc", "")) or ""
        a["member"] = bool(a.get("unlimited")) or a.pop("id_hash", None) in self.member_hashes
        a.pop("id_hash", None)
        a.pop("password_hash", None)
        a["spent_cny"] = self.s.uy_to_cny(self.db.spent_since(account_id, 0))
        self._pool_fields(a, spent_cny=a["spent_cny"])
        return a

    def admin_resolve(self, account_id: str = "", identifier: str = "") -> str:
        """Operators may name an account by its id or by the phone/e-mail itself."""
        if identifier:
            try:
                ident = parse(identifier)
            except BadIdentifier as e:
                raise CloudError(400, "bad_identifier", str(e)) from e
            row = self.db.account_by_hash(ident.hash(self.s.hmac_key))
        else:
            row = self.db.account(account_id)
        if row is None:
            raise CloudError(404, "no_account", "No such account")
        return row["id"]

    def admin_grant(self, account_id: str, tokens: int) -> dict:
        if self.db.account(account_id) is None:
            raise CloudError(404, "no_account", "No such account")
        self.db.grant(account_id, tokens, kind="adjust")
        a = dict(self.db.account(account_id))  # type: ignore[arg-type]
        a["identifier"] = self.crypto.decrypt(account_id, a.pop("identifier_enc", "")) or ""
        a.pop("id_hash", None)
        return a

    def admin_accounts(self) -> list[dict]:
        """With the identifier in clear (decrypted here, for the admin token
        only); the hash and ciphertext stay out of the reply. `member` says
        whether the account escapes the daily cap, and why."""
        t = now()
        out = []
        for r in self.db.admin_accounts(self.s.day_start(t)):
            d = dict(r)
            d["identifier"] = self.crypto.decrypt(d["id"], d.pop("identifier_enc", "")) or ""
            id_hash = d.pop("id_hash", None)
            d["has_password"] = bool(d.pop("password_hash", ""))
            d.pop("failed_logins", None)
            locked_until = d.pop("locked_until", None)
            d["locked"] = bool(locked_until and int(locked_until) > t)
            d["disabled"] = bool(d["disabled"])
            d["unlimited"] = bool(d.get("unlimited"))
            d["listed"] = id_hash in self.member_hashes
            d["member"] = d["unlimited"] or d["listed"]
            d["spent_today_cny"] = self.s.uy_to_cny(int(d.pop("spent_today_uy", 0) or 0))
            d["spent_cny"] = self.s.uy_to_cny(int(d.pop("spent_uy", 0) or 0))
            self._pool_fields(d, spent_cny=d["spent_cny"])
            d["contribute"] = bool(d.get("contribute"))
            out.append(d)
        return out

    def _pool_fields(self, d: dict, spent_cny: float) -> None:
        """The account's pool in yuan (`grant_cny`), what is left of it (`left_cny`, None for a
        member), the invites that fed it and whether the co-creation bonus was taken; the
        0.4 credit columns leave the reply."""
        for k in ("credit_uy", "credit_used_uy", "clips_bonus"):
            d.pop(k, None)
        grant = int(d.pop("grant_uy", 0) or 0)
        d["grant_cny"] = self.s.uy_to_cny(grant)
        member = bool(d.get("member"))
        d["left_cny"] = None if member or self.s.allowance_cny <= 0 else round(max(0.0, d["grant_cny"] - spent_cny), 4)
        d["invites"] = int(d.get("invites") or 0)
        d["contribute_bonus_at"] = int(d["contribute_bonus_at"]) if d.get("contribute_bonus_at") else None

    def admin_usage(self, days: int = 14) -> list[dict]:
        t = now()
        since = self.s.day_start(t) - 86400 * max(0, days - 1)
        out = []
        for r in self.db.usage_by_day(since, self.s.day_offset_h * 3600):
            d = dict(r)
            d["cost_cny"] = self.s.uy_to_cny(int(d.pop("cost_uy", 0) or 0))
            out.append(d)
        return out

    def _rows_cny(self, rs) -> list[dict]:
        out = []
        for r in rs:
            d = dict(r)
            d["cost_cny"] = self.s.uy_to_cny(int(d.pop("cost_uy", 0) or 0))
            for k in ("requests", "prompt_tokens", "completion_tokens", "charged"):
                if k in d:
                    d[k] = int(d.get(k) or 0)
            out.append(d)
        return out

    def admin_overview(self, days: int = 30) -> dict:
        """The operator's dashboard in one call: how many people, how active,
        what it costs — today, this week, over `days` — split by kind and by
        model, plus the last sign-ins and refusals. Identifiers appear only as
        the masked hint; the detail endpoint decrypts one account at a time."""
        t = now()
        day_start = self.s.day_start(t)
        week_start = day_start - 6 * 86400
        since = day_start - 86400 * max(0, days - 1)

        def totals(s: int) -> dict:
            r = self.db.totals_since(s)
            return {
                "requests": int(r["requests"] or 0),
                "charged": int(r["charged"] or 0),
                "prompt_tokens": int(r["prompt_tokens"] or 0),
                "completion_tokens": int(r["completion_tokens"] or 0),
                "cost_cny": self.s.uy_to_cny(int(r["cost_uy"] or 0)),
                "active_accounts": self.db.active_accounts_since(s),
                "new_accounts": self.db.accounts_created_since(s),
                "by_kind": self._rows_cny(self.db.usage_by_kind(s)),
            }

        counts = self.db.event_counts(day_start)
        hints = {r["id"]: r["hint"] for r in self.db.list_accounts(limit=5000)}
        top = []
        for r in self.db.top_accounts_since(since):
            top.append(
                {
                    "account_id": r["account_id"],
                    "hint": hints.get(r["account_id"], "?"),
                    "requests": int(r["requests"] or 0),
                    "charged": int(r["charged"] or 0),
                    "cost_cny": self.s.uy_to_cny(int(r["cost_uy"] or 0)),
                }
            )
        events = []
        for r in self.db.events_recent(60):
            d = dict(r)
            d["hint"] = hints.get(d["account_id"], "") if d["account_id"] else ""
            events.append(d)
        return {
            "generated_at": t,
            "day_start": day_start,
            "accounts": self.db.account_counts(),
            "today": totals(day_start),
            "week": totals(week_start),
            "period": {"days": days, **totals(since), "by_model": self._rows_cny(self.db.usage_by_model(since))},
            "signals_today": {
                "sign_ins": counts.get("sign_in.code", 0) + counts.get("sign_in.password", 0),
                "sign_in_failures": counts.get("sign_in.failed", 0),
                "budget_refusals": counts.get("budget.refused", 0),
                "upstream_errors": counts.get("upstream.error", 0),
                "calls": counts.get("call.ended", 0),
            },
            "top_accounts": top,
            "events": events,
            # what the community chose to give: how many accounts contribute, how many turns so far
            "contributions": {"accounts": self.db.contributors(), "samples": self.db.sample_count()},
            "settings": self.admin_settings(),
        }

    def admin_series(self, days: int = 30) -> dict:
        """The relay's own numbers by local day, for the operator's charts: sign-ins,
        new and active accounts, invites taken up, co-creation joins, calls, refusals —
        one row per day of the period, zeros where nothing happened — plus the devices
        by kind and system and the invite funnel as they stand now."""
        t = now()
        off = self.s.day_offset_h * 3600
        since = self.s.day_start(t) - 86400 * max(0, days - 1)
        by_day: dict[int, dict[str, int]] = {}
        for r in self.db.events_by_day(since, off):
            by_day.setdefault(int(r["day"]), {})[str(r["kind"])] = int(r["n"])
        new = {int(r["day"]): int(r["n"]) for r in self.db.accounts_by_day(since, off)}
        active = {int(r["day"]): int(r["n"]) for r in self.db.active_by_day(since, off)}
        rows = []
        for i in range(days):
            d = since + i * 86400
            e = by_day.get(d, {})
            rows.append(
                {
                    "day": d,
                    "sign_ins": e.get("sign_in.code", 0) + e.get("sign_in.password", 0),
                    "sign_in_failures": e.get("sign_in.failed", 0),
                    "new_accounts": new.get(d, 0),
                    "active_accounts": active.get(d, 0),
                    "invites_used": e.get("invite.used", 0),
                    "contribute_on": e.get("contribute.on", 0),
                    "calls": e.get("call.ended", 0),
                    "budget_refusals": e.get("budget.refused", 0),
                    "upstream_errors": e.get("upstream.error", 0),
                }
            )
        devices = [{"kind": r["kind"], "os": r["os"] or "", "count": int(r["n"])} for r in self.db.devices_by_kind_os()]
        return {
            "generated_at": t,
            "days": rows,
            "devices": devices,
            "invites": self.db.invite_funnel(),
            "contributors": self.db.contributors(),
        }

    def admin_traffic(self, days: int = 30) -> dict:
        """The site's visits and downloads, read from the traffic database that
        demo/showcase/mirror/traffic.py keeps (TRAFFIC_DB, mounted read-only). Daily
        counts only — the script never wrote an address, so there is none to show."""
        path = self.s.traffic_db
        if not path or not os.path.exists(path):
            return {"available": False}
        off = self.s.day_offset_h * 3600
        first = now() + off - 86400 * max(0, days - 1)
        since = time.strftime("%Y-%m-%d", time.gmtime(first))
        try:
            conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2)
        except sqlite3.Error:
            return {"available": False}
        conn.row_factory = sqlite3.Row
        with closing(conn):
            try:
                have = {r["day"]: dict(r) for r in conn.execute("SELECT * FROM days WHERE day>=? ORDER BY day", (since,))}
                # one row a day, zeros where the log had nothing, so the charts line up
                day_rows = []
                for i in range(days):
                    d = time.strftime("%Y-%m-%d", time.gmtime(first + i * 86400))
                    day_rows.append(
                        have.get(d) or {"day": d, "requests": 0, "pages": 0, "visitors": 0, "bots": 0, "downloads": 0, "bytes": 0}
                    )

                def top(table: str, col: str, limit: int = 15) -> list[dict]:
                    return [
                        {"name": r[col], "hits": int(r["hits"])}
                        for r in conn.execute(
                            f"SELECT {col}, SUM(hits) AS hits FROM {table} WHERE day>=? GROUP BY {col} ORDER BY hits DESC LIMIT ?",
                            (since, limit),
                        )
                    ]

                pages, referrers = top("pages", "path"), top("referrers", "host")
                downloads = [
                    {"name": r["file"], "hits": int(r["hits"]), "bytes": int(r["bytes"] or 0)}
                    for r in conn.execute(
                        "SELECT file, SUM(hits) AS hits, SUM(bytes) AS bytes FROM downloads WHERE day>=? GROUP BY file ORDER BY hits DESC LIMIT 40",
                        (since,),
                    )
                ]
                gh_days = [
                    {"day": r["day"], "stars": int(r["stars"]), "downloads": int(r["downloads"])}
                    for r in conn.execute("SELECT day, stars, downloads FROM github WHERE day>=? ORDER BY day", (since,))
                ]
                latest = conn.execute("SELECT * FROM github ORDER BY day DESC LIMIT 1").fetchone()
                last_run = conn.execute("SELECT value FROM state WHERE key='last_run'").fetchone()
            except sqlite3.Error:
                return {"available": False}
        github: dict = {"days": gh_days}
        if latest is not None:
            try:
                assets = json.loads(latest["assets"] or "{}")
            except ValueError:
                assets = {}
            github.update(
                {
                    "day": latest["day"],
                    "stars": int(latest["stars"]),
                    "forks": int(latest["forks"]),
                    "watchers": int(latest["watchers"]),
                    "downloads": int(latest["downloads"]),
                    "assets": assets,
                }
            )
        return {
            "available": True,
            "since": since,
            "updated_at": int(last_run["value"]) if last_run else 0,
            "days": day_rows,
            "pages": pages,
            "referrers": referrers,
            "downloads": downloads,
            "github": github,
        }

    def admin_account(self, account_id: str, days: int = 30) -> dict:
        """Everything the relay knows about one account, for the operator:
        the identifier in clear, spend by kind / model / day, the live
        sign-ins (device names only), the remembered devices, its timeline.
        What the person said to the model is not here — it was never kept."""
        row = self.db.account(account_id)
        if row is None:
            raise CloudError(404, "no_account", "No such account")
        t = now()
        day_start = self.s.day_start(t)
        since = day_start - 86400 * max(0, days - 1)
        a = dict(row)
        a["identifier"] = self.crypto.decrypt(account_id, a.pop("identifier_enc", "") or "") or ""
        id_hash = a.pop("id_hash", None)
        a.pop("password_hash", None)
        a["has_password"] = bool(row["password_hash"])
        a["disabled"] = bool(a["disabled"])
        a["unlimited"] = bool(a.get("unlimited"))
        a["listed"] = id_hash in self.member_hashes
        a["member"] = a["unlimited"] or a["listed"]
        a["locked"] = bool(row["locked_until"] and int(row["locked_until"]) > t)
        spent_total = self.db.spent_since(account_id, 0)
        self._pool_fields(a, spent_cny=self.s.uy_to_cny(spent_total))
        a["invited"] = [{"id": r["id"], "hint": r["hint"], "created_at": int(r["created_at"])} for r in self.db.invitees(account_id)]
        a["contribute"] = bool(a.get("contribute"))
        a["samples"] = self.db.sample_count(account_id) if a["contribute"] else 0
        spent_today = self.db.spent_since(account_id, day_start)
        return {
            "account": a,
            "spend": {
                "today_cny": self.s.uy_to_cny(spent_today),
                "total_cny": self.s.uy_to_cny(spent_total),
                "grant_cny": a["grant_cny"],
                "left_cny": a["left_cny"],
                "used_today": self.db.used_since(account_id, day_start),
                "requests_total": self.db.requests_since(account_id, 0),
            },
            "usage": {
                "today": {"by_kind": self._rows_cny(self.db.usage_by_kind(day_start, account_id))},
                "period": {
                    "days": days,
                    "by_kind": self._rows_cny(self.db.usage_by_kind(since, account_id)),
                    "by_model": self._rows_cny(self.db.usage_by_model(since, account_id)),
                    "by_day": self._rows_cny(self.db.usage_by_day_for(account_id, since, self.s.day_offset_h * 3600)),
                },
                "total": {"by_kind": self._rows_cny(self.db.usage_by_kind(0, account_id))},
            },
            "sessions": [
                {
                    "prefix": k["prefix"],
                    "device": k["device"],
                    "via": k["via"] or "code",
                    "created_at": int(k["created_at"]),
                    "last_used_at": int(k["last_used_at"]) if k["last_used_at"] else None,
                    "revoked_at": int(k["revoked_at"]) if k["revoked_at"] else None,
                }
                for k in self.db.keys_for(account_id)
            ],
            "devices": [dict(d) for d in self.db.devices_for(account_id)],
            "recent": [self._ledger_row(r) for r in self.db.recent_ledger(account_id, limit=60)],
            "events": [dict(r) for r in self.db.events_for(account_id, 80)],
        }

    def admin_events(self, limit: int = 200, kinds: tuple[str, ...] | None = None) -> list[dict]:
        hints = {r["id"]: r["hint"] for r in self.db.list_accounts(limit=5000)}
        out = []
        for r in self.db.events_recent(max(1, min(limit, 1000)), kinds):
            d = dict(r)
            d["hint"] = hints.get(d["account_id"], "") if d["account_id"] else ""
            out.append(d)
        return out

    def admin_settings(self) -> dict:
        return {
            "unlimited": self.s.unlimited,
            "signup_tokens": self.s.signup_tokens,
            "daily_cap_tokens": self.s.daily_cap_tokens,
            "per_minute_requests": self.s.per_minute_requests,
            "signup_open": self.s.signup_open,
            "allowance_cny": self.s.allowance_cny,
            "allowance_usd": self.s.cny_to_usd(self.s.allowance_cny),
            "invite_bonus_cny": self.s.invite_bonus_cny,
            "contribute_bonus_cny": self.s.contribute_bonus_cny,
            "usd_cny": self.s.usd_cny,
            "day_offset_h": self.s.day_offset_h,
            "invite_url": self.s.invite_url,
            "own_key_docs": self.s.own_key_docs,
            "contributors": self.db.contributors(),
            "allowed_identifiers": [s.strip() for s in self.s.allowed_identifiers.split(",") if s.strip()],
            "sender": self.s.sender,
            "password_min_len": self.s.password_min_len,
            "models": [m.id for m in self.s.models],
            "model_kinds": {m.id: m.kind for m in self.s.models},
            "prices": {m.id: m.to_public()["unibot"]["price_cny"] for m in self.s.models},
        }

    def admin_disable(self, account_id: str, disabled: bool) -> None:
        if self.db.account(account_id) is None:
            raise CloudError(404, "no_account", "No such account")
        self.db.set_disabled(account_id, disabled)

    def admin_unlimited(self, account_id: str, unlimited: bool) -> None:
        """Make an account a member (no daily cap) without touching the server's
        environment — the operator's way of letting one more person in fully."""
        if self.db.account(account_id) is None:
            raise CloudError(404, "no_account", "No such account")
        self.db.set_unlimited(account_id, unlimited)

    def admin_delete(self, account_id: str) -> None:
        if self.db.account(account_id) is None:
            raise CloudError(404, "no_account", "No such account")
        self.db.delete_account(account_id)


def estimate_tokens(text: str) -> int:
    """When the upstream sends no usage: a rough count, erring high.
    CJK runs at about one token per character, English at about one per four."""
    if not text:
        return 0
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    return cjk + math.ceil((len(text) - cjk) / 3.5)


def prompt_chars(messages: list) -> int:
    total = 0
    for m in messages or []:
        c = m.get("content") if isinstance(m, dict) else None
        if isinstance(c, str):
            total += len(c)
        elif isinstance(c, list):
            for part in c:
                if isinstance(part, dict):
                    if part.get("type") == "text":
                        total += len(part.get("text") or "")
                    elif part.get("type") == "image_url":
                        total += 4000  # one image ≈ a thousand tokens of context
    return total


def usage_from_json(obj: dict) -> tuple[int, int] | None:
    u = obj.get("usage") if isinstance(obj, dict) else None
    if not isinstance(u, dict):
        return None
    try:
        return int(u.get("prompt_tokens") or 0), int(u.get("completion_tokens") or 0)
    except (TypeError, ValueError):
        return None


def dumps(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
