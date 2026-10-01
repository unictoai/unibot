"""SQLite storage: accounts, keys, codes, the token ledger.

One file, WAL mode, one connection guarded by a lock — the relay is I/O bound
on the upstream, not on SQLite. Identifiers (phone / e-mail) are stored as
their HMAC (the lookup key), a masked hint for the account page, and — so the
operator can tell who is who — encrypted with a key derived from the
deployment secret (`identifier_enc`, see crypto.py); a copied database file
shows none of them.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    id            TEXT PRIMARY KEY,
    id_hash       TEXT NOT NULL UNIQUE,
    channel       TEXT NOT NULL,          -- phone | email
    hint          TEXT NOT NULL,          -- 138****1234 / a***@example.com
    created_at    INTEGER NOT NULL,
    granted       INTEGER NOT NULL DEFAULT 0,
    used          INTEGER NOT NULL DEFAULT 0,
    disabled      INTEGER NOT NULL DEFAULT 0,
    identifier_enc TEXT NOT NULL DEFAULT '',  -- AES-GCM of the phone / address, base64
    unlimited     INTEGER NOT NULL DEFAULT 0,  -- a member: no daily money cap (set by the operator)
    password_hash TEXT NOT NULL DEFAULT '',    -- scrypt$salt$hash, empty = no password (codes only)
    password_set_at INTEGER,
    failed_logins INTEGER NOT NULL DEFAULT 0,  -- wrong passwords in a row
    locked_until  INTEGER,                     -- password sign-in refused until then
    invite_code   TEXT NOT NULL DEFAULT '',    -- what this person gives to friends (made on first ask)
    invited_by    TEXT NOT NULL DEFAULT '',    -- the account whose code was used at sign-up
    invites       INTEGER NOT NULL DEFAULT 0,  -- people who signed up with this account's code
    credit_uy     INTEGER NOT NULL DEFAULT 0,  -- 0.4, unused since 0.5 (credit beyond a daily cap)
    credit_used_uy INTEGER NOT NULL DEFAULT 0, -- 0.4, unused since 0.5
    clips_bonus   INTEGER NOT NULL DEFAULT 0,  -- 0.4, unused since 0.5 (clips are not counted apart)
    grant_uy      INTEGER NOT NULL DEFAULT 0,  -- 0.5: the lifetime pool, micro-yuan (allowance + invites + co-creation + operator)
    contribute_bonus_at INTEGER                -- 0.5: when the one-time co-creation bonus was granted
);
-- accounts_invite_code (unique, partial) is made in _migrate(): the column is
-- added there on databases from before 0.4, and an index in this script would
-- run first and fail on them.
CREATE TABLE IF NOT EXISTS api_keys (
    key_hash      TEXT PRIMARY KEY,
    prefix        TEXT NOT NULL,
    account_id    TEXT NOT NULL REFERENCES accounts(id),
    device        TEXT NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,
    last_used_at  INTEGER,
    revoked_at    INTEGER,
    via           TEXT NOT NULL DEFAULT 'code' -- how this sign-in happened: code | password
);
CREATE INDEX IF NOT EXISTS api_keys_account ON api_keys(account_id);
CREATE TABLE IF NOT EXISTS codes (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    id_hash       TEXT NOT NULL,
    code_hash     TEXT NOT NULL,
    ip            TEXT NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,
    expires_at    INTEGER NOT NULL,
    attempts      INTEGER NOT NULL DEFAULT 0,
    used          INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS codes_id_hash ON codes(id_hash, created_at);
CREATE INDEX IF NOT EXISTS codes_ip ON codes(ip, created_at);
CREATE TABLE IF NOT EXISTS ledger (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id    TEXT NOT NULL REFERENCES accounts(id),
    ts            INTEGER NOT NULL,
    kind          TEXT NOT NULL,          -- grant | chat | image | video | realtime | adjust
    model         TEXT NOT NULL DEFAULT '',
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    charged       INTEGER NOT NULL,       -- positive = spent, negative = granted
    request_id    TEXT NOT NULL DEFAULT '',
    cost_uy       INTEGER NOT NULL DEFAULT 0,  -- what the provider bills for it, in micro-yuan
    extra         TEXT NOT NULL DEFAULT ''     -- JSON: the token split of a call (text/audio/image), seconds…
);
CREATE INDEX IF NOT EXISTS ledger_account_ts ON ledger(account_id, ts);
CREATE INDEX IF NOT EXISTS ledger_ts ON ledger(ts);
CREATE TABLE IF NOT EXISTS events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id    TEXT NOT NULL DEFAULT '',   -- '' for events before an account exists (a failed sign-in)
    ts            INTEGER NOT NULL,
    kind          TEXT NOT NULL,              -- sign_in.code | sign_in.password | sign_in.failed | sign_out |
                                              -- sign_out.all | password.set | account.created | account.deleted |
                                              -- device.joined | upstream.error | budget.refused | call.ended
    detail        TEXT NOT NULL DEFAULT ''    -- a device name, a model, an error code: never message content
);
CREATE INDEX IF NOT EXISTS events_account_ts ON events(account_id, ts);
CREATE INDEX IF NOT EXISTS events_ts ON events(ts);
CREATE TABLE IF NOT EXISTS devices (
    account_id    TEXT NOT NULL REFERENCES accounts(id),
    id            TEXT NOT NULL,           -- chosen by the device, stable across restarts
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL,           -- phone | computer
    os            TEXT NOT NULL DEFAULT '',
    version       TEXT NOT NULL DEFAULT '',
    actions       TEXT NOT NULL DEFAULT '[]',
    first_seen    INTEGER NOT NULL,
    last_seen     INTEGER NOT NULL,
    PRIMARY KEY (account_id, id)
);
CREATE TABLE IF NOT EXISTS video_tasks (
    task_id       TEXT PRIMARY KEY,        -- the provider's id; polled by its owner only
    account_id    TEXT NOT NULL REFERENCES accounts(id),
    model         TEXT NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,
    charged       INTEGER NOT NULL DEFAULT 0,  -- set when the task was first seen SUCCEEDED
    cost_uy       INTEGER NOT NULL DEFAULT 0,  -- priced at submission from the seconds asked for
    probe         INTEGER NOT NULL DEFAULT 0,  -- an empty task the app sent to see if the model exists
    status        TEXT NOT NULL DEFAULT ''     -- the provider's last word: SUCCEEDED / FAILED / ...
);
-- 0.4: conversations an account chose to contribute (accounts.contribute=1). The only
-- place message content ever lands; empty for everyone else, and deleted with the account
-- or on request.
CREATE TABLE IF NOT EXISTS samples (
    id            TEXT PRIMARY KEY,
    account_id    TEXT NOT NULL REFERENCES accounts(id),
    ts            INTEGER NOT NULL,
    model         TEXT NOT NULL DEFAULT '',
    request       TEXT NOT NULL,               -- the messages sent, as JSON (pictures replaced by a marker)
    response      TEXT NOT NULL DEFAULT '',    -- the assistant's reply text
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    meta          TEXT NOT NULL DEFAULT '{}'   -- platform / language hints from the request headers
);
CREATE INDEX IF NOT EXISTS samples_account ON samples(account_id, ts);
CREATE INDEX IF NOT EXISTS samples_ts ON samples(ts);
-- 0.7: the agent's name and look, so every device of an account wears the same one.
-- Never a key or a message: a name, which face (the dragon, an emoji on a colour, or
-- one drawn in the studio) and, for a drawn face, its five stills as WebP.
CREATE TABLE IF NOT EXISTS profiles (
    account_id    TEXT PRIMARY KEY REFERENCES accounts(id),
    rev           INTEGER NOT NULL DEFAULT 0,  -- grows with every PUT; devices compare it
    updated_at    INTEGER NOT NULL,
    device        TEXT NOT NULL DEFAULT '',    -- the device that wrote it (it skips its own echo)
    body          TEXT NOT NULL DEFAULT '{}',  -- name, avatar, emoji, color, style, description
    face          TEXT NOT NULL DEFAULT ''     -- JSON {mood: base64 WebP} when avatar = "face"
);
"""


def now() -> int:
    return int(time.time())


class Database:
    def __init__(self, path: str):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        # columns this open added to an older file ("accounts.grant_uy"): the service seeds
        # what a column's default cannot know (see seed_grants)
        self.added: set[str] = set()
        with self._lock:
            # executescript commits on its own; keep it outside tx().
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
            self._conn.executescript(SCHEMA)
            self._migrate()

    def _migrate(self) -> None:
        """Columns added after the first release; CREATE TABLE IF NOT EXISTS leaves old files alone."""

        def cols(table: str) -> set[str]:
            return {r["name"] for r in self._conn.execute(f"PRAGMA table_info({table})").fetchall()}

        def add(table: str, col: str, ddl: str) -> None:
            if col not in cols(table):
                self._conn.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")
                self.added.add(f"{table}.{col}")

        if "identifier_enc" not in cols("accounts"):
            self._conn.execute("ALTER TABLE accounts ADD COLUMN identifier_enc TEXT NOT NULL DEFAULT ''")
        if "unlimited" not in cols("accounts"):
            self._conn.execute("ALTER TABLE accounts ADD COLUMN unlimited INTEGER NOT NULL DEFAULT 0")
        if "charged" not in cols("video_tasks"):
            self._conn.execute("ALTER TABLE video_tasks ADD COLUMN charged INTEGER NOT NULL DEFAULT 0")
        if "cost_uy" not in cols("video_tasks"):
            self._conn.execute("ALTER TABLE video_tasks ADD COLUMN cost_uy INTEGER NOT NULL DEFAULT 0")
        if "cost_uy" not in cols("ledger"):
            self._conn.execute("ALTER TABLE ledger ADD COLUMN cost_uy INTEGER NOT NULL DEFAULT 0")
        if "extra" not in cols("ledger"):
            self._conn.execute("ALTER TABLE ledger ADD COLUMN extra TEXT NOT NULL DEFAULT ''")
        for col, ddl in (
            ("password_hash", "TEXT NOT NULL DEFAULT ''"),
            ("password_set_at", "INTEGER"),
            ("failed_logins", "INTEGER NOT NULL DEFAULT 0"),
            ("locked_until", "INTEGER"),
        ):
            if col not in cols("accounts"):
                self._conn.execute(f"ALTER TABLE accounts ADD COLUMN {col} {ddl}")
        if "via" not in cols("api_keys"):
            self._conn.execute("ALTER TABLE api_keys ADD COLUMN via TEXT NOT NULL DEFAULT 'code'")
        # 0.4: invitations, credit and the clip allowance
        for col, ddl in (
            ("invite_code", "TEXT NOT NULL DEFAULT ''"),
            ("invited_by", "TEXT NOT NULL DEFAULT ''"),
            ("invites", "INTEGER NOT NULL DEFAULT 0"),
            ("credit_uy", "INTEGER NOT NULL DEFAULT 0"),
            ("credit_used_uy", "INTEGER NOT NULL DEFAULT 0"),
            ("clips_bonus", "INTEGER NOT NULL DEFAULT 0"),
        ):
            if col not in cols("accounts"):
                self._conn.execute(f"ALTER TABLE accounts ADD COLUMN {col} {ddl}")
        self._conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS accounts_invite_code ON accounts(invite_code) WHERE invite_code<>''")
        for col, ddl in (("probe", "INTEGER NOT NULL DEFAULT 0"), ("status", "TEXT NOT NULL DEFAULT ''")):
            if col not in cols("video_tasks"):
                self._conn.execute(f"ALTER TABLE video_tasks ADD COLUMN {col} {ddl}")
        if "contribute" not in cols("accounts"):
            self._conn.execute("ALTER TABLE accounts ADD COLUMN contribute INTEGER NOT NULL DEFAULT 0")
        # 0.5: one lifetime pool instead of a daily cap; the co-creation bonus, once
        add("accounts", "grant_uy", "INTEGER NOT NULL DEFAULT 0")
        add("accounts", "contribute_bonus_at", "INTEGER")

    def seed_grants(self, allowance_uy: int) -> int:
        """Accounts from before 0.5 start the new model with what they have spent so far plus
        the allowance, and keep any 0.4 credit they had not used — nobody wakes up in debt or
        loses what an invite earned. Returns how many accounts were seeded."""
        with self.tx() as c:
            cur = c.execute(
                """UPDATE accounts SET grant_uy = ? + MAX(0, credit_uy - credit_used_uy)
                     + (SELECT COALESCE(SUM(l.cost_uy),0) FROM ledger l WHERE l.account_id=accounts.id AND l.cost_uy>0)
                   WHERE grant_uy = 0""",
                (max(0, int(allowance_uy)),),
            )
            return int(cur.rowcount or 0)

    @contextmanager
    def tx(self):
        with self._lock:
            self._conn.execute("BEGIN")
            try:
                yield self._conn
            except Exception:
                self._conn.execute("ROLLBACK")
                raise
            else:
                self._conn.execute("COMMIT")

    def close(self) -> None:
        self._conn.close()

    # -- codes -------------------------------------------------------------

    def codes_recent_for(self, id_hash: str, since: int) -> int:
        with self._lock:
            r = self._conn.execute("SELECT COUNT(*) FROM codes WHERE id_hash=? AND created_at>=?", (id_hash, since)).fetchone()
        return int(r[0])

    def codes_recent_for_ip(self, ip: str, since: int) -> int:
        with self._lock:
            r = self._conn.execute("SELECT COUNT(*) FROM codes WHERE ip=? AND created_at>=?", (ip, since)).fetchone()
        return int(r[0])

    def insert_code(self, id_hash: str, code_hash: str, ip: str, ttl_s: int) -> None:
        t = now()
        with self.tx() as c:
            # A new code supersedes the old ones for this identifier.
            c.execute("UPDATE codes SET used=1 WHERE id_hash=? AND used=0", (id_hash,))
            c.execute(
                "INSERT INTO codes(id_hash, code_hash, ip, created_at, expires_at) VALUES (?,?,?,?,?)",
                (id_hash, code_hash, ip, t, t + ttl_s),
            )
            c.execute("DELETE FROM codes WHERE expires_at < ?", (t - 86400,))

    def live_code(self, id_hash: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute(
                "SELECT * FROM codes WHERE id_hash=? AND used=0 AND expires_at>=? ORDER BY id DESC LIMIT 1",
                (id_hash, now()),
            ).fetchone()

    def bump_attempts(self, code_id: int) -> int:
        with self.tx() as c:
            c.execute("UPDATE codes SET attempts=attempts+1 WHERE id=?", (code_id,))
            r = c.execute("SELECT attempts FROM codes WHERE id=?", (code_id,)).fetchone()
        return int(r[0])

    def consume_code(self, code_id: int) -> None:
        with self.tx() as c:
            c.execute("UPDATE codes SET used=1 WHERE id=?", (code_id,))

    # -- accounts ------------------------------------------------------------

    def account_by_hash(self, id_hash: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute("SELECT * FROM accounts WHERE id_hash=?", (id_hash,)).fetchone()

    def account(self, account_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()

    @staticmethod
    def new_account_id() -> str:
        return str(uuid.uuid4())

    def create_account(
        self,
        id_hash: str,
        channel: str,
        hint: str,
        grant: int,
        identifier_enc: str = "",
        account_id: str | None = None,
        grant_uy: int = 0,
    ) -> sqlite3.Row:
        """`grant` is the token grant of old (0 = none); `grant_uy` the money the account
        starts with (0.5), written to the ledger as the first line of its statement."""
        account_id = account_id or self.new_account_id()
        t = now()
        grant_uy = max(0, int(grant_uy))
        with self.tx() as c:
            c.execute(
                "INSERT INTO accounts(id, id_hash, channel, hint, created_at, granted, identifier_enc, grant_uy) VALUES (?,?,?,?,?,?,?,?)",
                (account_id, id_hash, channel, hint, t, grant, identifier_enc, grant_uy),
            )
            if grant:
                c.execute(
                    "INSERT INTO ledger(account_id, ts, kind, charged) VALUES (?,?,?,?)",
                    (account_id, t, "grant", -grant),
                )
            if grant_uy:
                c.execute(
                    "INSERT INTO ledger(account_id, ts, kind, charged, extra) VALUES (?,?,?,?,?)",
                    (account_id, t, "credit", 0, json.dumps({"credit_uy": grant_uy, "from": "signup"})),
                )
        return self.account(account_id)  # type: ignore[return-value]

    def delete_account(self, account_id: str) -> None:
        """Everything about one person: keys, ledger, devices, pending codes, the row itself."""
        with self.tx() as c:
            row = c.execute("SELECT id_hash FROM accounts WHERE id=?", (account_id,)).fetchone()
            if row is None:
                return
            c.execute("DELETE FROM codes WHERE id_hash=?", (row["id_hash"],))
            c.execute("DELETE FROM samples WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM events WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM video_tasks WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM devices WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM profiles WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM ledger WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM api_keys WHERE account_id=?", (account_id,))
            c.execute("DELETE FROM accounts WHERE id=?", (account_id,))

    def grant(self, account_id: str, tokens: int, kind: str = "grant") -> None:
        with self.tx() as c:
            c.execute("UPDATE accounts SET granted=granted+? WHERE id=?", (tokens, account_id))
            c.execute(
                "INSERT INTO ledger(account_id, ts, kind, charged) VALUES (?,?,?,?)",
                (account_id, now(), kind, -tokens),
            )

    # -- contributed conversations -------------------------------------------------------------

    def set_contribute(self, account_id: str, on: bool) -> None:
        with self.tx() as c:
            c.execute("UPDATE accounts SET contribute=? WHERE id=?", (1 if on else 0, account_id))

    def add_sample(
        self,
        account_id: str,
        model: str,
        request: str,
        response: str,
        prompt_tokens: int,
        completion_tokens: int,
        meta: str = "{}",
    ) -> str:
        sid = uuid.uuid4().hex
        with self.tx() as c:
            c.execute(
                "INSERT INTO samples(id, account_id, ts, model, request, response, prompt_tokens, completion_tokens, meta) "
                "VALUES (?,?,?,?,?,?,?,?,?)",
                (sid, account_id, now(), model, request, response, prompt_tokens, completion_tokens, meta),
            )
        return sid

    def sample_count(self, account_id: str | None = None) -> int:
        with self._lock:
            if account_id is None:
                return int(self._conn.execute("SELECT COUNT(*) FROM samples").fetchone()[0])
            return int(self._conn.execute("SELECT COUNT(*) FROM samples WHERE account_id=?", (account_id,)).fetchone()[0])

    def samples(self, account_id: str | None = None, since: int = 0, limit: int = 100, before: int = 0) -> list[sqlite3.Row]:
        """Newest first; ``before`` (a ts) pages further back."""
        q = "SELECT * FROM samples WHERE ts>=?"
        args: list = [since]
        if account_id is not None:
            q += " AND account_id=?"
            args.append(account_id)
        if before:
            q += " AND ts<?"
            args.append(before)
        q += " ORDER BY ts DESC LIMIT ?"
        args.append(max(1, min(limit, 1000)))
        with self._lock:
            return self._conn.execute(q, args).fetchall()

    def delete_samples(self, account_id: str) -> int:
        with self.tx() as c:
            return c.execute("DELETE FROM samples WHERE account_id=?", (account_id,)).rowcount

    def contributors(self) -> int:
        with self._lock:
            return int(self._conn.execute("SELECT COUNT(*) FROM accounts WHERE contribute=1").fetchone()[0])

    def set_disabled(self, account_id: str, disabled: bool) -> None:
        with self.tx() as c:
            c.execute("UPDATE accounts SET disabled=? WHERE id=?", (1 if disabled else 0, account_id))

    def set_unlimited(self, account_id: str, unlimited: bool) -> None:
        with self.tx() as c:
            c.execute("UPDATE accounts SET unlimited=? WHERE id=?", (1 if unlimited else 0, account_id))

    # -- invitations and credit -----------------------------------------------------

    def account_by_invite_code(self, code: str) -> sqlite3.Row | None:
        if not code:
            return None
        with self._lock:
            return self._conn.execute("SELECT * FROM accounts WHERE invite_code=?", (code,)).fetchone()

    def set_invite_code(self, account_id: str, code: str) -> bool:
        """Give the account its code, once; False when another account already holds that code."""
        with self.tx() as c:
            try:
                cur = c.execute("UPDATE accounts SET invite_code=? WHERE id=? AND invite_code=''", (code, account_id))
            except sqlite3.IntegrityError:
                return False
            return cur.rowcount == 1

    def record_invite(self, inviter_id: str, invitee_id: str, bonus_uy: int) -> None:
        """A new account signed up with the inviter's code: the inviter's pool grows by the bonus."""
        t = now()
        bonus_uy = max(0, int(bonus_uy))
        with self.tx() as c:
            c.execute("UPDATE accounts SET invited_by=? WHERE id=? AND invited_by=''", (inviter_id, invitee_id))
            c.execute("UPDATE accounts SET invites=invites+1, grant_uy=grant_uy+? WHERE id=?", (bonus_uy, inviter_id))
            c.execute(
                "INSERT INTO ledger(account_id, ts, kind, charged, extra) VALUES (?,?,?,?,?)",
                (inviter_id, t, "credit", 0, json.dumps({"credit_uy": bonus_uy, "from": "invite", "friend": invitee_id[:8]})),
            )

    def add_credit(self, account_id: str, credit_uy: int, note: str = "") -> None:
        """Credit from the operator — a merged pull request, a good issue, a hand at a bad day."""
        credit_uy = max(0, int(credit_uy))
        with self.tx() as c:
            c.execute("UPDATE accounts SET grant_uy=grant_uy+? WHERE id=?", (credit_uy, account_id))
            c.execute(
                "INSERT INTO ledger(account_id, ts, kind, charged, extra) VALUES (?,?,?,?,?)",
                (account_id, now(), "credit", 0, json.dumps({"credit_uy": credit_uy, "from": "operator", "note": note[:200]})),
            )

    def grant_contribute_bonus(self, account_id: str, bonus_uy: int) -> bool:
        """The co-creation bonus, once per account: False when it was granted before."""
        bonus_uy = max(0, int(bonus_uy))
        t = now()
        with self.tx() as c:
            cur = c.execute(
                "UPDATE accounts SET grant_uy=grant_uy+?, contribute_bonus_at=? WHERE id=? AND contribute_bonus_at IS NULL",
                (bonus_uy, t, account_id),
            )
            if not cur.rowcount:
                return False
            c.execute(
                "INSERT INTO ledger(account_id, ts, kind, charged, extra) VALUES (?,?,?,?,?)",
                (account_id, t, "credit", 0, json.dumps({"credit_uy": bonus_uy, "from": "contribute"})),
            )
        return True

    def invitees(self, inviter_id: str, limit: int = 50) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                "SELECT id, hint, created_at FROM accounts WHERE invited_by=? ORDER BY created_at DESC LIMIT ?",
                (inviter_id, limit),
            ).fetchall()

    # -- passwords -----------------------------------------------------------------

    def set_password(self, account_id: str, password_hash: str) -> None:
        """Also clears any lock: a new password is a fresh start."""
        with self.tx() as c:
            c.execute(
                "UPDATE accounts SET password_hash=?, password_set_at=?, failed_logins=0, locked_until=NULL WHERE id=?",
                (password_hash, now() if password_hash else None, account_id),
            )

    def login_failed(self, account_id: str, max_attempts: int, lockout_s: int) -> int:
        """Counts a wrong password; locks the account for `lockout_s` at the
        limit. Returns how many tries are left (0 = locked now)."""
        with self.tx() as c:
            c.execute("UPDATE accounts SET failed_logins=failed_logins+1 WHERE id=?", (account_id,))
            r = c.execute("SELECT failed_logins FROM accounts WHERE id=?", (account_id,)).fetchone()
            failed = int(r[0]) if r else 0
            if failed >= max_attempts:
                c.execute("UPDATE accounts SET locked_until=?, failed_logins=0 WHERE id=?", (now() + lockout_s, account_id))
                return 0
        return max(0, max_attempts - failed)

    def login_succeeded(self, account_id: str) -> None:
        with self.tx() as c:
            c.execute("UPDATE accounts SET failed_logins=0, locked_until=NULL WHERE id=?", (account_id,))

    def list_accounts(self, limit: int = 200) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute("SELECT * FROM accounts ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()

    def admin_accounts(self, day_start: int, limit: int = 500) -> list[sqlite3.Row]:
        """The operator's view: each account with today's spend (tokens and
        money), when it was last seen, how many keys (sign-ins) are live and
        how many devices it remembers."""
        with self._lock:
            return self._conn.execute(
                """SELECT a.*,
                          (SELECT COALESCE(SUM(l.charged),0) FROM ledger l
                             WHERE l.account_id=a.id AND l.ts>=? AND l.charged>0) AS used_today,
                          (SELECT COALESCE(SUM(l.cost_uy),0) FROM ledger l
                             WHERE l.account_id=a.id AND l.ts>=? AND l.cost_uy>0) AS spent_today_uy,
                          (SELECT COALESCE(SUM(l.cost_uy),0) FROM ledger l
                             WHERE l.account_id=a.id AND l.cost_uy>0) AS spent_uy,
                          (SELECT COUNT(*) FROM ledger l
                             WHERE l.account_id=a.id AND l.kind IN ('chat','image','video','realtime')) AS requests,
                          (SELECT MAX(k.last_used_at) FROM api_keys k WHERE k.account_id=a.id) AS last_active_at,
                          (SELECT COUNT(*) FROM api_keys k WHERE k.account_id=a.id AND k.revoked_at IS NULL) AS live_keys,
                          (SELECT COUNT(*) FROM devices d WHERE d.account_id=a.id) AS device_count
                   FROM accounts a ORDER BY a.created_at DESC LIMIT ?""",
                (day_start, day_start, limit),
            ).fetchall()

    def usage_by_day(self, since: int, day_offset_s: int = 0) -> list[sqlite3.Row]:
        """Charged tokens and money per local day and kind, for the admin page's
        totals. `day` is the UNIX time the local day starts."""
        with self._lock:
            return self._conn.execute(
                """SELECT ((ts + ?) - (ts + ?) % 86400 - ?) AS day, kind, COUNT(*) AS requests,
                          SUM(charged) AS charged, SUM(cost_uy) AS cost_uy
                   FROM ledger WHERE ts>=? AND charged>0 GROUP BY day, kind ORDER BY day DESC""",
                (day_offset_s, day_offset_s, day_offset_s, since),
            ).fetchall()

    # -- keys ----------------------------------------------------------------

    def insert_key(self, key_hash: str, prefix: str, account_id: str, device: str, via: str = "code") -> None:
        with self.tx() as c:
            c.execute(
                "INSERT INTO api_keys(key_hash, prefix, account_id, device, created_at, via) VALUES (?,?,?,?,?,?)",
                (key_hash, prefix, account_id, device[:80], now(), via),
            )

    def key(self, key_hash: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute(
                "SELECT k.*, a.disabled AS account_disabled, a.granted, a.used, a.channel, a.hint, a.created_at AS account_created_at, "
                "a.id_hash, a.unlimited AS account_unlimited, a.password_hash, a.password_set_at, "
                "a.invite_code, a.invites, a.grant_uy, a.contribute_bonus_at, a.contribute "
                "FROM api_keys k JOIN accounts a ON a.id=k.account_id WHERE k.key_hash=?",
                (key_hash,),
            ).fetchone()

    def touch_key(self, key_hash: str) -> None:
        with self.tx() as c:
            c.execute("UPDATE api_keys SET last_used_at=? WHERE key_hash=?", (now(), key_hash))

    def revoke_key(self, key_hash: str) -> None:
        with self.tx() as c:
            c.execute("UPDATE api_keys SET revoked_at=? WHERE key_hash=? AND revoked_at IS NULL", (now(), key_hash))

    def revoke_key_by_prefix(self, account_id: str, prefix: str) -> bool:
        """Sign one device out from another; the prefix is what the account page shows."""
        with self.tx() as c:
            cur = c.execute(
                "UPDATE api_keys SET revoked_at=? WHERE account_id=? AND prefix=? AND revoked_at IS NULL",
                (now(), account_id, prefix),
            )
            return cur.rowcount > 0

    def revoke_all_keys(self, account_id: str, keep_hash: str | None = None) -> int:
        with self.tx() as c:
            if keep_hash:
                cur = c.execute(
                    "UPDATE api_keys SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL AND key_hash<>?",
                    (now(), account_id, keep_hash),
                )
            else:
                cur = c.execute("UPDATE api_keys SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", (now(), account_id))
            return cur.rowcount

    def keys_for(self, account_id: str, live_only: bool = False) -> list[sqlite3.Row]:
        with self._lock:
            sql = "SELECT * FROM api_keys WHERE account_id=?"
            if live_only:
                sql += " AND revoked_at IS NULL"
            return self._conn.execute(sql + " ORDER BY created_at", (account_id,)).fetchall()

    # -- events (the account's own history; the operator's audit) -------------------

    def add_event(self, account_id: str, kind: str, detail: str = "") -> None:
        with self.tx() as c:
            c.execute(
                "INSERT INTO events(account_id, ts, kind, detail) VALUES (?,?,?,?)",
                (account_id or "", now(), kind, (detail or "")[:200]),
            )

    def events_for(self, account_id: str, limit: int = 50) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                "SELECT ts, kind, detail FROM events WHERE account_id=? ORDER BY id DESC LIMIT ?", (account_id, limit)
            ).fetchall()

    def events_recent(self, limit: int = 100, kinds: tuple[str, ...] | None = None) -> list[sqlite3.Row]:
        with self._lock:
            if kinds:
                marks = ",".join("?" * len(kinds))
                return self._conn.execute(
                    f"SELECT account_id, ts, kind, detail FROM events WHERE kind IN ({marks}) ORDER BY id DESC LIMIT ?",
                    (*kinds, limit),
                ).fetchall()
            return self._conn.execute("SELECT account_id, ts, kind, detail FROM events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()

    def event_counts(self, since: int) -> dict[str, int]:
        with self._lock:
            rows = self._conn.execute("SELECT kind, COUNT(*) AS n FROM events WHERE ts>=? GROUP BY kind", (since,)).fetchall()
        return {r["kind"]: int(r["n"]) for r in rows}

    def delete_events(self, account_id: str) -> None:
        with self.tx() as c:
            c.execute("DELETE FROM events WHERE account_id=?", (account_id,))

    # -- usage -----------------------------------------------------------------

    def charge(
        self,
        account_id: str,
        kind: str,
        model: str,
        prompt_tokens: int,
        completion_tokens: int,
        charged: int,
        request_id: str,
        cost_uy: int = 0,
        extra: str = "",
    ) -> None:
        """Record one request: the tokens on the account, the line in the ledger. What the
        account has spent is always the ledger's sum, so there is nothing else to keep."""
        charged = max(0, int(charged))
        cost_uy = max(0, int(cost_uy))
        with self.tx() as c:
            c.execute("UPDATE accounts SET used=used+? WHERE id=?", (charged, account_id))
            c.execute(
                "INSERT INTO ledger(account_id, ts, kind, model, prompt_tokens, completion_tokens, charged, request_id, cost_uy, extra) "
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                (account_id, now(), kind, model, prompt_tokens, completion_tokens, charged, request_id, cost_uy, extra or ""),
            )

    USAGE_KINDS = ("chat", "image", "video", "realtime")

    def usage_by_kind(self, since: int, account_id: str | None = None) -> list[sqlite3.Row]:
        """Requests, tokens and money per kind since `since` — one account or everyone."""
        with self._lock:
            where = "ts>=? AND charged>=0 AND kind IN ('chat','image','video','realtime')"
            args: tuple = (since,)
            if account_id is not None:
                where += " AND account_id=?"
                args = (since, account_id)
            return self._conn.execute(
                f"""SELECT kind, COUNT(*) AS requests, SUM(prompt_tokens) AS prompt_tokens,
                           SUM(completion_tokens) AS completion_tokens, SUM(charged) AS charged, SUM(cost_uy) AS cost_uy
                    FROM ledger WHERE {where} GROUP BY kind ORDER BY cost_uy DESC""",
                args,
            ).fetchall()

    def usage_by_model(self, since: int, account_id: str | None = None) -> list[sqlite3.Row]:
        with self._lock:
            where = "ts>=? AND charged>=0 AND kind IN ('chat','image','video','realtime')"
            args: tuple = (since,)
            if account_id is not None:
                where += " AND account_id=?"
                args = (since, account_id)
            return self._conn.execute(
                f"""SELECT model, kind, COUNT(*) AS requests, SUM(prompt_tokens) AS prompt_tokens,
                           SUM(completion_tokens) AS completion_tokens, SUM(charged) AS charged, SUM(cost_uy) AS cost_uy
                    FROM ledger WHERE {where} GROUP BY model, kind ORDER BY cost_uy DESC""",
                args,
            ).fetchall()

    def usage_by_day_for(self, account_id: str, since: int, day_offset_s: int = 0) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                """SELECT ((ts + ?) - (ts + ?) % 86400 - ?) AS day, kind, COUNT(*) AS requests,
                          SUM(charged) AS charged, SUM(cost_uy) AS cost_uy
                   FROM ledger WHERE account_id=? AND ts>=? AND charged>=0 AND kind IN ('chat','image','video','realtime')
                   GROUP BY day, kind ORDER BY day DESC""",
                (day_offset_s, day_offset_s, day_offset_s, account_id, since),
            ).fetchall()

    def active_accounts_since(self, since: int) -> int:
        with self._lock:
            r = self._conn.execute(
                "SELECT COUNT(DISTINCT account_id) FROM ledger WHERE ts>=? AND kind IN ('chat','image','video','realtime')",
                (since,),
            ).fetchone()
        return int(r[0])

    def accounts_created_since(self, since: int) -> int:
        with self._lock:
            r = self._conn.execute("SELECT COUNT(*) FROM accounts WHERE created_at>=?", (since,)).fetchone()
        return int(r[0])

    # -- the operator's time series ----------------------------------------------------

    _DAY = "((ts + ?) - (ts + ?) % 86400 - ?)"

    def events_by_day(self, since: int, day_offset_s: int = 0) -> list[sqlite3.Row]:
        """How many events of each kind per local day since `since`."""
        with self._lock:
            return self._conn.execute(
                f"SELECT {self._DAY} AS day, kind, COUNT(*) AS n FROM events WHERE ts>=? GROUP BY day, kind",
                (day_offset_s, day_offset_s, day_offset_s, since),
            ).fetchall()

    def accounts_by_day(self, since: int, day_offset_s: int = 0) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                f"SELECT {self._DAY.replace('ts', 'created_at')} AS day, COUNT(*) AS n FROM accounts WHERE created_at>=? GROUP BY day",
                (day_offset_s, day_offset_s, day_offset_s, since),
            ).fetchall()

    def active_by_day(self, since: int, day_offset_s: int = 0) -> list[sqlite3.Row]:
        """Accounts that made at least one charged call, per local day."""
        with self._lock:
            return self._conn.execute(
                f"""SELECT {self._DAY} AS day, COUNT(DISTINCT account_id) AS n FROM ledger
                    WHERE ts>=? AND kind IN ('chat','image','video','realtime') GROUP BY day""",
                (day_offset_s, day_offset_s, day_offset_s, since),
            ).fetchall()

    def devices_by_kind_os(self) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute("SELECT kind, os, COUNT(*) AS n FROM devices GROUP BY kind, os ORDER BY n DESC").fetchall()

    def invite_funnel(self) -> dict[str, int]:
        """Who asked for a code, whose code brought someone, who came through one."""
        with self._lock:
            r = self._conn.execute(
                """SELECT SUM(CASE WHEN invite_code<>'' THEN 1 ELSE 0 END) AS with_code,
                          SUM(CASE WHEN invites>0 THEN 1 ELSE 0 END) AS inviters,
                          SUM(CASE WHEN invited_by<>'' THEN 1 ELSE 0 END) AS invited,
                          SUM(CASE WHEN contribute_bonus_at IS NOT NULL THEN 1 ELSE 0 END) AS contribute_bonuses
                   FROM accounts"""
            ).fetchone()
        return {k: int(r[k] or 0) for k in ("with_code", "inviters", "invited", "contribute_bonuses")}

    def account_counts(self) -> dict[str, int]:
        with self._lock:
            r = self._conn.execute(
                """SELECT COUNT(*) AS total, SUM(disabled) AS disabled, SUM(unlimited) AS unlimited,
                          SUM(CASE WHEN password_hash<>'' THEN 1 ELSE 0 END) AS with_password
                   FROM accounts"""
            ).fetchone()
            keys = self._conn.execute("SELECT COUNT(*) FROM api_keys WHERE revoked_at IS NULL").fetchone()
            devices = self._conn.execute("SELECT COUNT(*) FROM devices").fetchone()
        return {
            "total": int(r["total"] or 0),
            "disabled": int(r["disabled"] or 0),
            "unlimited": int(r["unlimited"] or 0),
            "with_password": int(r["with_password"] or 0),
            "live_keys": int(keys[0]),
            "devices": int(devices[0]),
        }

    def totals_since(self, since: int) -> sqlite3.Row:
        with self._lock:
            return self._conn.execute(
                """SELECT COUNT(*) AS requests, COALESCE(SUM(charged),0) AS charged, COALESCE(SUM(cost_uy),0) AS cost_uy,
                          COALESCE(SUM(prompt_tokens),0) AS prompt_tokens, COALESCE(SUM(completion_tokens),0) AS completion_tokens
                   FROM ledger WHERE ts>=? AND kind IN ('chat','image','video','realtime')""",
                (since,),
            ).fetchone()

    def top_accounts_since(self, since: int, limit: int = 10) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                """SELECT account_id, COUNT(*) AS requests, SUM(cost_uy) AS cost_uy, SUM(charged) AS charged
                   FROM ledger WHERE ts>=? AND kind IN ('chat','image','video','realtime')
                   GROUP BY account_id ORDER BY cost_uy DESC LIMIT ?""",
                (since, limit),
            ).fetchall()

    def used_since(self, account_id: str, since: int) -> int:
        with self._lock:
            r = self._conn.execute(
                "SELECT COALESCE(SUM(charged),0) FROM ledger WHERE account_id=? AND ts>=? AND charged>0",
                (account_id, since),
            ).fetchone()
        return int(r[0])

    def spent_since(self, account_id: str, since: int) -> int:
        """Money (micro-yuan) the account cost the operator since `since`."""
        with self._lock:
            r = self._conn.execute(
                "SELECT COALESCE(SUM(cost_uy),0) FROM ledger WHERE account_id=? AND ts>=? AND cost_uy>0",
                (account_id, since),
            ).fetchone()
        return int(r[0])

    def requests_since(self, account_id: str, since: int) -> int:
        with self._lock:
            r = self._conn.execute(
                "SELECT COUNT(*) FROM ledger WHERE account_id=? AND ts>=? AND kind IN ('chat','image','video','realtime')",
                (account_id, since),
            ).fetchone()
        return int(r[0])

    def recent_ledger(self, account_id: str, limit: int = 30) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                "SELECT ts, kind, model, prompt_tokens, completion_tokens, charged, cost_uy, extra FROM ledger WHERE account_id=? ORDER BY id DESC LIMIT ?",
                (account_id, limit),
            ).fetchall()

    # -- devices (the hub) ---------------------------------------------------------

    def upsert_device(self, account_id: str, device_id: str, name: str, kind: str, os: str, version: str, actions: str) -> None:
        t = now()
        with self.tx() as c:
            c.execute(
                """INSERT INTO devices(account_id, id, name, kind, os, version, actions, first_seen, last_seen)
                   VALUES(?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(account_id, id) DO UPDATE SET
                     name=excluded.name, kind=excluded.kind, os=excluded.os, version=excluded.version,
                     actions=excluded.actions, last_seen=excluded.last_seen""",
                (account_id, device_id, name, kind, os, version, actions, t, t),
            )

    def touch_device(self, account_id: str, device_id: str) -> None:
        with self.tx() as c:
            c.execute("UPDATE devices SET last_seen=? WHERE account_id=? AND id=?", (now(), account_id, device_id))

    def devices_for(self, account_id: str) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                "SELECT id, name, kind, os, version, actions, first_seen, last_seen FROM devices WHERE account_id=? ORDER BY last_seen DESC",
                (account_id,),
            ).fetchall()

    def forget_device(self, account_id: str, device_id: str) -> None:
        with self.tx() as c:
            c.execute("DELETE FROM devices WHERE account_id=? AND id=?", (account_id, device_id))

    # -- the profile (name and look shared by the account's devices) -----------------

    def profile(self, account_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute(
                "SELECT rev, updated_at, device, body, face FROM profiles WHERE account_id=?", (account_id,)
            ).fetchone()

    def put_profile(self, account_id: str, device: str, body: str, face: str | None) -> int:
        """Store the profile and return its new rev. ``face`` None keeps the stored face
        (a rename should not cost the pictures a round trip); "" clears it."""
        with self.tx() as c:
            row = c.execute("SELECT rev, face FROM profiles WHERE account_id=?", (account_id,)).fetchone()
            rev = (int(row["rev"]) if row else 0) + 1
            kept = face if face is not None else (str(row["face"]) if row else "")
            c.execute(
                """INSERT INTO profiles(account_id, rev, updated_at, device, body, face) VALUES(?,?,?,?,?,?)
                   ON CONFLICT(account_id) DO UPDATE SET
                     rev=excluded.rev, updated_at=excluded.updated_at, device=excluded.device,
                     body=excluded.body, face=excluded.face""",
                (account_id, rev, now(), device, body, kept),
            )
            return rev

    def delete_profile(self, account_id: str) -> None:
        with self.tx() as c:
            c.execute("DELETE FROM profiles WHERE account_id=?", (account_id,))

    # -- video tasks (the provider's async API, relayed) --------------------------

    def insert_video_task(self, task_id: str, account_id: str, model: str, cost_uy: int = 0, probe: bool = False) -> None:
        t = now()
        with self.tx() as c:
            # a second insert for the same task (a retried poll) must not forget that it was charged
            c.execute(
                "INSERT INTO video_tasks(task_id, account_id, model, created_at, cost_uy, probe) VALUES (?,?,?,?,?,?) "
                "ON CONFLICT(task_id) DO UPDATE SET model=excluded.model, cost_uy=excluded.cost_uy",
                (task_id, account_id, model, t, max(0, int(cost_uy)), 1 if probe else 0),
            )
            c.execute("DELETE FROM video_tasks WHERE created_at < ?", (t - 3 * 86400,))

    def video_task(self, task_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute("SELECT * FROM video_tasks WHERE task_id=?", (task_id,)).fetchone()

    def set_video_status(self, task_id: str, status: str) -> None:
        with self.tx() as c:
            c.execute("UPDATE video_tasks SET status=? WHERE task_id=?", (status[:20], task_id))

    def mark_video_charged(self, task_id: str) -> bool:
        """True the first time only, so a clip is charged once however often it is polled."""
        with self.tx() as c:
            cur = c.execute("UPDATE video_tasks SET charged=1 WHERE task_id=? AND charged=0", (task_id,))
            return cur.rowcount == 1
