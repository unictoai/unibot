"""What a shell command is about to do, judged before it runs — the desktop's
version of the phone's ShellGuard. Same ladder:

    SAFE         reads, computes, writes the user's own files   → runs
    INSTALL      fetches software                               → runs, and the user is told
    DESTRUCTIVE  removes files or history, rewrites shared state → asks
    OUTBOUND     sends something out                            → asks
    SYSTEM       disks, power, permissions, curl | sh            → asks
    MONEY        looks like a payment                           → never

Heuristics over a shell line, not a proof: the point is that nothing that
deletes, sends or pays runs without the user having seen it.
"""

from __future__ import annotations

import re
import shlex
from dataclasses import dataclass

SAFE, INSTALL, DESTRUCTIVE, OUTBOUND, SYSTEM, MONEY = "safe", "install", "destructive", "outbound", "system", "money"
RANK = {SAFE: 0, INSTALL: 1, DESTRUCTIVE: 2, OUTBOUND: 3, SYSTEM: 4, MONEY: 5}


@dataclass(frozen=True)
class Risk:
    level: str = SAFE
    reason: str = ""
    target: str = ""

    @property
    def asks(self) -> bool:
        return self.level in (DESTRUCTIVE, OUTBOUND, SYSTEM)

    @property
    def refused(self) -> bool:
        return self.level == MONEY

    def merge(self, other: Risk) -> Risk:
        return self if RANK[self.level] >= RANK[other.level] else other


WRAPPERS = {"sudo", "doas", "nohup", "exec", "time", "env", "command", "busybox", "nice", "ionice", "stdbuf", "unbuffer", "timeout"}
SHELLS = {"sh", "bash", "ash", "dash", "zsh", "ksh", "fish", "cmd", "cmd.exe", "powershell", "pwsh"}
INTERPRETERS = {"python", "python3", "python2", "perl", "ruby", "node", "nodejs", "php", "lua"}
INSTALLERS = {
    "apk",
    "apt",
    "apt-get",
    "dnf",
    "yum",
    "pacman",
    "zypper",
    "pip",
    "pip3",
    "pipx",
    "uv",
    "brew",
    "conda",
    "mamba",
    "npx",
    "bunx",
    "pnpx",
    "gem",
    "cpan",
    "cpanm",
    "luarocks",
    "opam",
    "nix-env",
    "winget",
    "choco",
    "scoop",
    "snap",
    "flatpak",
}
PUBLISHERS = {"twine", "npm", "pnpm", "yarn", "cargo", "docker", "podman", "flyctl", "vercel", "netlify", "wrangler", "heroku"}
REMOTE = {"ssh", "scp", "sftp", "rsync", "ftp", "lftp", "telnet", "nc", "ncat", "netcat", "socat"}
MAILERS = {"mail", "mailx", "sendmail", "msmtp", "mutt", "neomutt", "swaks", "ssmtp", "s-nail"}
HTTP = {"curl", "wget", "http", "https", "xh", "httpie", "Invoke-WebRequest", "iwr", "Invoke-RestMethod", "irm"}
CLOUD = {"aws", "gcloud", "az", "oss", "ossutil", "coscli", "qshell", "rclone"}
SENDERS = re.compile(r"(send|notify|sms|push|mailer|telegram|whatsapp|wechat|weixin|dingtalk|wecom|slack|discord|twilio|pushover|bark|ntfy)", re.I)
MONEY_WORDS = re.compile(
    r"(\bpay\b|payment|checkout|purchase|\border\b|transfer|alipay|wxpay|wechatpay|weixin.?pay|stripe|paypal|billing|subscribe|topup|top-up|recharge|refund|支付|付款|下单|转账|充值|购买|结算|退款)",
    re.I,
)
DATA_FLAGS = {
    "-d",
    "--data",
    "--data-raw",
    "--data-binary",
    "--data-urlencode",
    "--data-ascii",
    "-F",
    "--form",
    "--form-string",
    "--json",
    "-T",
    "--upload-file",
    "--post-data",
    "--post-file",
    "--body",
    "--body-file",
    "-Body",
    "-InFile",
}
WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
WARNINGS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\bmkfs\b|\bdd\s+(if|of)=|\bdiskpart\b|\bformat\s+[a-z]:", re.I), "disk-level operation"),
    (re.compile(r">\s*/dev/(sd|nvme|mmcblk|block)"), "writes to a block device"),
    (re.compile(r"\b(shutdown|reboot|halt|poweroff|Restart-Computer|Stop-Computer)\b", re.I), "system-level command"),
    (re.compile(r"\bchmod\s+(-R\s+)?(777|a\+rwx)\b|\bchmod\s+-R\s+.*\s/(\s|$)"), "world-writable permissions"),
    (re.compile(r"\bgit\s+push\b[^;&|]*(--force\b|\s-f\b|--force-with-lease|\s\+\S)"), "force push"),
    (re.compile(r":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"), "fork bomb"),
    (
        re.compile(r"\b(curl|wget|iwr|irm|Invoke-WebRequest|Invoke-RestMethod)\b[^|]*\|\s*(sudo\s+)?(ba|z|a|da)?sh\b|\|\s*iex\b|Invoke-Expression", re.I),
        "pipes a download into a shell",
    ),
    (re.compile(r"\b(curl|wget)\b[^|]*\|\s*(python3?|perl|node)\b"), "pipes a download into an interpreter"),
    (re.compile(r"\b(launchctl|systemctl)\s+(disable|mask|stop)\b|\breg\s+(add|delete)\b|\bdefaults\s+write\b", re.I), "changes system services or settings"),
    (
        re.compile(r"\b(rm|Remove-Item|rmdir|rd|del)\b\s+.*(-rf?|-fr|-Recurse|/s)\b.*\s(/|~|\$HOME|%USERPROFILE%|[A-Za-z]:\\)\s*$", re.I),
        "removes a whole tree at the root of the user's world",
    ),
]
CODE_DELETES = re.compile(
    r"\b(shutil\.rmtree|os\.(remove|unlink|rmdir|removedirs)|\.unlink\(|fs\.(rm|rmSync|rmdir|rmdirSync|unlink|unlinkSync)|rimraf|File\.delete|FileUtils\.rm)\b"
)
CODE_SENDS = re.compile(
    r"\b(requests\.(post|put|patch|delete)|urllib\.request\.urlopen\([^)]*data|httpx\.(post|put|patch|delete)|smtplib|sendmail|axios\.(post|put|patch|delete)|fetch\([^)]*method\s*:\s*['\"](POST|PUT|PATCH|DELETE)|nodemailer|twilio|SendGrid|boto3[^\n]*\.(put_object|send_email|publish)|lark|feishu|dingtalk|wecom|telegram|slack_sdk)\b",
    re.I,
)


def split_commands(text: str) -> list[str]:
    """Split on ; | && || and newlines outside quotes, so inline code stays whole."""
    parts: list[str] = []
    buf: list[str] = []
    quote = ""
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            buf.append(ch)
            if ch == "\\" and quote == '"' and i + 1 < len(text):
                buf.append(text[i + 1])
                i += 1
            elif ch == quote:
                quote = ""
        elif ch in ("'", '"'):
            quote = ch
            buf.append(ch)
        elif ch == "\\" and i + 1 < len(text):
            buf.append(ch)
            buf.append(text[i + 1])
            i += 1
        elif ch in (";", "|", "\n") or (ch == "&" and i + 1 < len(text) and text[i + 1] == "&"):
            parts.append("".join(buf))
            buf = []
            if ch in ("|", "&") and i + 1 < len(text) and text[i + 1] == ch:
                i += 1
        else:
            buf.append(ch)
        i += 1
    parts.append("".join(buf))
    return [p.strip() for p in parts if p.strip()]


def assess(command: str) -> Risk:
    text = (command or "").strip()
    if not text:
        return Risk()
    risk = Risk()
    for pattern, reason in WARNINGS:
        if pattern.search(text):
            risk = risk.merge(Risk(SYSTEM, reason, text[:60]))
    for part in split_commands(text):
        risk = risk.merge(_simple(part, 0))
    if risk.level == OUTBOUND and MONEY_WORDS.search(text):
        risk = Risk(MONEY, "looks like a payment: " + risk.reason, risk.target)
    return risk


def _tokens(text: str) -> list[str]:
    try:
        return shlex.split(text, posix=True)
    except ValueError:
        return text.split()


def _base(tok: str) -> str:
    return tok.replace("\\", "/").rsplit("/", 1)[-1]


def _simple(text: str, depth: int) -> Risk:
    if depth > 3:
        return Risk()
    tokens = _tokens(text)
    while tokens and (re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", tokens[0]) or _base(tokens[0]) in WRAPPERS):
        if _base(tokens[0]) == "timeout" and len(tokens) > 1:
            tokens = tokens[1:]
        tokens = tokens[1:]
    if not tokens:
        return Risk()
    program = _base(tokens[0])
    args = tokens[1:]
    low = program.lower()

    if low in SHELLS:
        for flag in ("-c", "-lc", "-ec", "-euc", "/c", "-Command", "-command", "-EncodedCommand"):
            if flag in args and args.index(flag) + 1 < len(args):
                return assess_depth(args[args.index(flag) + 1], depth + 1)
        return Risk()
    if low in INTERPRETERS:
        for flag in ("-c", "-e", "--eval", "-r"):
            if flag in args and args.index(flag) + 1 < len(args):
                return _inline(args[args.index(flag) + 1])
        if "-m" in args and args.index("-m") + 1 < len(args):
            return _simple(" ".join(shlex.quote(a) for a in args[args.index("-m") + 1 :]), depth + 1)
        return Risk()
    if low == "xargs":
        rest = [a for a in args if not a.startswith("-")]
        return _simple(" ".join(rest) + " ?", depth + 1) if rest else Risk()
    if low in ("rm", "del", "erase", "rd", "rmdir", "remove-item", "ri"):
        return _rm(args)
    if low == "find" and any(a in ("-delete", "-exec") for a in args):
        return Risk(DESTRUCTIVE, "find with -delete/-exec", _target(args))
    if low in ("shred", "wipe", "srm"):
        return Risk(DESTRUCTIVE, "destroys files", _target(args))
    if low == "truncate":
        return Risk(DESTRUCTIVE, "empties a file", _target(args))
    if low == "crontab" and "-r" in args:
        return Risk(DESTRUCTIVE, "removes all scheduled jobs", "crontab")
    if low == "git":
        return _git(args)
    if program in HTTP or low in {h.lower() for h in HTTP}:
        return _http(program, args)
    if low in REMOTE:
        return Risk(OUTBOUND, f"{program} reaches another machine", next((a for a in args if not a.startswith("-")), program))
    if low in MAILERS:
        return Risk(OUTBOUND, "sends an email", next((a for a in args if "@" in a), "email"))
    if low in ("gh", "glab", "hub"):
        if (
            args
            and args[0] in ("pr", "issue", "release", "repo", "api", "gist")
            and len(args) > 1
            and args[1] in ("create", "merge", "close", "comment", "delete", "edit", "upload", "publish")
        ):
            return Risk(OUTBOUND, f"gh {args[0]} {args[1]}", " ".join(args[:3]))
        if args and args[0] == "api" and any(a in ("-X", "--method") for a in args):
            return Risk(OUTBOUND, "gh api with a write method", "github")
        return Risk()
    if low in CLOUD:
        if any(a in ("cp", "sync", "mv", "rm", "put", "upload", "delete", "copy", "move") for a in args):
            return Risk(OUTBOUND, f"{program} changes remote storage", " ".join(args[:2]))
        return Risk()
    if low in PUBLISHERS:
        if any(a in ("publish", "upload", "push", "deploy", "release") for a in args):
            return Risk(OUTBOUND, f"{program} publishes", " ".join(args[:2]))
        if any(a in ("install", "add", "i", "ci", "pull", "build") for a in args):
            return Risk(INSTALL, f"{program} installs", " ".join(args[:2]))
        return Risk()
    if low in INSTALLERS:
        if any(a in ("remove", "uninstall", "purge", "autoremove", "erase", "rm", "-Rns", "-R") for a in args):
            return Risk(DESTRUCTIVE, f"{program} removes software", " ".join(args[:2]))
        if any(a in ("install", "add", "i", "upgrade", "update", "dist-upgrade", "sync", "-S", "-Syu", "run", "tool") for a in args):
            return Risk(INSTALL, f"{program} installs", " ".join(args[:2]))
        return Risk()
    if low in ("kill", "killall", "pkill", "taskkill", "stop-process"):
        return Risk(DESTRUCTIVE, "ends processes", " ".join(args[:2]))
    if low in ("mv", "move", "move-item", "ren", "rename") and args:
        return Risk(DESTRUCTIVE, "moves or renames", args[-1]) if any(a.startswith("-f") or a.startswith("--force") for a in args) else Risk()
    if SENDERS.search(program) and low not in ("notify-send", "unibot-notify"):
        return Risk(OUTBOUND, f"{program} sends a message", program)
    return Risk()


def assess_depth(command: str, depth: int) -> Risk:
    risk = Risk()
    for part in split_commands(command):
        risk = risk.merge(_simple(part, depth))
    return risk


def _inline(code: str) -> Risk:
    risk = Risk()
    if CODE_DELETES.search(code):
        risk = risk.merge(Risk(DESTRUCTIVE, "the code removes files", "inline code"))
    if CODE_SENDS.search(code):
        risk = risk.merge(Risk(OUTBOUND, "the code sends something out", "inline code"))
    if re.search(r"(^|\n|;|&&)\s*(rm|curl|wget|git|apt|pip|npm|find|gh|ssh|scp|rsync)\b", code):
        risk = risk.merge(assess_depth(code, 1))
    return risk


def _target(args: list[str]) -> str:
    for a in reversed(args):
        if not a.startswith("-"):
            return a
    return ""


def _rm(args: list[str]) -> Risk:
    paths = [a for a in args if not a.startswith("-")]
    if not paths:
        return Risk()
    scratch = ("/tmp/", "/var/tmp/", "/dev/shm/", "$TMPDIR/", "${TMPDIR}/", "%TEMP%", "$env:TEMP")
    if all(p.startswith(scratch) or p.startswith("./node_modules") or p.startswith("node_modules") or p.startswith("__pycache__") for p in paths):
        return Risk()
    recursive = any(a in ("-r", "-rf", "-fr", "-R", "-Rf", "--recursive", "-Recurse", "/s", "/S") for a in args)
    return Risk(DESTRUCTIVE, "removes " + ("a whole folder" if recursive else "files"), paths[-1])


def _git(args: list[str]) -> Risk:
    if not args:
        return Risk()
    sub = args[0]
    rest = args[1:]
    if sub == "push":
        return Risk(OUTBOUND, "git push", " ".join(rest[:2]) or "origin")
    if sub in ("clean",) and any(a.startswith("-f") or a in ("-x", "-d", "-fd", "-fdx", "-xdf") for a in rest):
        return Risk(DESTRUCTIVE, "git clean removes untracked files", "worktree")
    if sub == "reset" and "--hard" in rest:
        return Risk(DESTRUCTIVE, "git reset --hard discards changes", " ".join(rest[:2]))
    if sub == "checkout" and ("--" in rest or "." in rest or any(a == "-f" for a in rest)):
        return Risk(DESTRUCTIVE, "git checkout discards changes", " ".join(rest[:2]))
    if sub in ("branch", "tag") and any(a in ("-D", "-d", "--delete") for a in rest):
        return Risk(DESTRUCTIVE, f"git {sub} delete", " ".join(rest[:2]))
    if sub == "stash" and rest and rest[0] in ("drop", "clear"):
        return Risk(DESTRUCTIVE, f"git stash {rest[0]}", "stash")
    if sub == "rebase" or (sub == "commit" and "--amend" in rest):
        return Risk(DESTRUCTIVE, f"git {sub} rewrites history", " ".join(rest[:1]))
    if sub in ("remote",) and rest and rest[0] in ("rm", "remove", "set-url"):
        return Risk(DESTRUCTIVE, "git remote change", " ".join(rest[:2]))
    return Risk()


def _http(program: str, args: list[str]) -> Risk:
    url = next((a for a in args if "://" in a), "")
    host = re.sub(r"^\w+://", "", url).split("/")[0] if url else program
    sends = any(a in DATA_FLAGS or a.split("=", 1)[0] in DATA_FLAGS for a in args)
    for i, a in enumerate(args):
        if a in ("-X", "--request", "--method", "-Method", "-m") and i + 1 < len(args) and args[i + 1].upper() in WRITE_METHODS:
            sends = True
    if program.lower() in ("http", "https", "xh", "httpie") and any(a.upper() in WRITE_METHODS for a in args[:2]):
        sends = True
    if program.lower() in ("http", "https", "xh", "httpie") and any(re.match(r"^[\w-]+[:=]=?\S", a) and "://" not in a for a in args):
        sends = True
    if sends:
        return Risk(OUTBOUND, f"{program} sends data", host)
    return Risk()


def describe(risk: Risk) -> str:
    if risk.level == SAFE:
        return "safe"
    return f"{risk.level}: {risk.reason}" + (f" ({risk.target})" if risk.target else "")
