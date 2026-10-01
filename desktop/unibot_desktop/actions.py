"""This computer's hands: the same set the 0.1.13 host offered a phone on the
same network — the shell, the files, the browser, a look at the screen — plus a
notification. Each action takes a dict of args and returns a JSON-able dict;
file bytes travel base64 in `data`.

Nothing here decides whether an action *should* run; see guard.py and the
caller. Everything runs as the signed-in user, like the user at the keyboard.
"""

from __future__ import annotations

import base64
import getpass
import io
import os
import platform
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import webbrowser
from pathlib import Path

from . import __version__

OUTPUT_LIMIT = 200_000
FILE_LIMIT = 8 * 1024 * 1024  # base64 inside one hub frame
SCREEN_MAX_WIDTH = 1600

ACTIONS = ("info", "shell", "files", "file.get", "file.put", "open", "screen", "notify", "task", "approve")


class ActionError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def expand(path: str | None) -> Path:
    return Path(os.path.expandvars(os.path.expanduser(path or "~"))).resolve()


def shell_name() -> str:
    if os.name == "nt":
        return os.environ.get("COMSPEC", "cmd.exe")
    return os.environ.get("SHELL", "/bin/sh")


def info() -> dict:
    return {
        "name": socket.gethostname(),
        "os": platform.system(),
        "os_version": platform.release(),
        "arch": platform.machine(),
        "user": getpass.getuser(),
        "home": str(Path.home()),
        "cwd": os.getcwd(),
        "shell": shell_name(),
        "python": platform.python_version(),
        "desktop": __version__,
        "actions": list(ACTIONS),
    }


def _text(b: bytes) -> str:
    s = b.decode("utf-8", errors="replace")
    if len(s) > OUTPUT_LIMIT:
        s = s[:OUTPUT_LIMIT] + f"\n… [{len(s) - OUTPUT_LIMIT} more characters not shown]"
    return s


def _kill_tree(proc: subprocess.Popen) -> None:
    """Stop the shell and everything it started, so a timed-out command cannot linger."""
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
        return
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        proc.kill()


def shell(command: str, cwd: str | None = None, timeout: float = 120) -> dict:
    if not command or not command.strip():
        raise ActionError("usage", "a command is required")
    timeout = max(1.0, min(float(timeout or 120), 900.0))
    started = time.monotonic()
    timed_out = False
    try:
        proc = subprocess.Popen(
            command,
            shell=True,
            cwd=str(expand(cwd)) if cwd else None,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=os.name != "nt",
        )
    except (OSError, ValueError) as e:
        return {"exit_code": 127, "stdout": "", "stderr": str(e), "timed_out": False, "duration_ms": 0}
    try:
        out, err = proc.communicate(timeout=timeout)
        code = proc.returncode
    except subprocess.TimeoutExpired:
        timed_out = True
        _kill_tree(proc)
        try:
            out, err = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            out, err = b"", b""
        code = 124
    return {
        "exit_code": code,
        "stdout": _text(out),
        "stderr": _text(err),
        "timed_out": timed_out,
        "duration_ms": int((time.monotonic() - started) * 1000),
    }


def _entry(p: Path, st: os.stat_result) -> dict:
    kind = "link" if p.is_symlink() else "dir" if p.is_dir() else "file"
    return {"name": p.name, "type": kind, "size": st.st_size, "mtime": int(st.st_mtime)}


def files(path: str | None = None) -> dict:
    p = expand(path)
    if not p.exists():
        raise ActionError("not_found", f"{p} does not exist")
    if p.is_file():
        return {"path": str(p), "entries": [_entry(p, p.stat())]}
    entries = []
    try:
        children = sorted(p.iterdir(), key=lambda c: (not c.is_dir(), c.name.lower()))
    except PermissionError as e:
        raise ActionError("forbidden", f"cannot read {p}") from e
    for child in children:
        try:
            entries.append(_entry(child, child.lstat()))
        except OSError:
            continue
        if len(entries) >= 2000:
            break
    return {"path": str(p), "entries": entries}


def file_get(path: str) -> dict:
    p = expand(path)
    if not p.is_file():
        raise ActionError("not_found", f"{p} is not a file")
    size = p.stat().st_size
    if size > FILE_LIMIT:
        raise ActionError("too_large", f"{p.name} is {size} bytes; the limit over the hub is {FILE_LIMIT}")
    data = p.read_bytes()
    return {"path": str(p), "name": p.name, "bytes": size, "mime": _mime(p.name), "data": base64.b64encode(data).decode()}


def file_put(path: str, data: str, force: bool = False) -> dict:
    p = expand(path)
    raw = base64.b64decode(data or "")
    if p.is_dir():
        raise ActionError("is_dir", f"{p} is a folder")
    if p.exists() and not force:
        raise ActionError("exists", f"{p} already exists; force replaces it")
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + ".unibot-part")
    tmp.write_bytes(raw)
    os.replace(tmp, p)
    return {"path": str(p), "bytes": len(raw)}


def open_target(url: str) -> dict:
    url = (url or "").strip()
    if not url:
        raise ActionError("usage", "a URL or a path is required")
    if "://" not in url:
        p = expand(url)
        if not p.exists():
            raise ActionError("not_found", f"{p} does not exist")
        url = p.as_uri()
    ok = False
    try:
        if sys.platform == "darwin":
            ok = subprocess.run(["open", url], capture_output=True, timeout=20).returncode == 0
        elif os.name == "nt":
            os.startfile(url)  # type: ignore[attr-defined]
            ok = True
        elif shutil.which("xdg-open"):
            ok = subprocess.run(["xdg-open", url], capture_output=True, timeout=20).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        ok = False
    if not ok:
        ok = webbrowser.open(url)
    return {"ok": bool(ok), "url": url}


def screen() -> dict:
    shot = take_screenshot()
    if shot is None:
        raise ActionError("no_screen", "this computer cannot take a screenshot (no display, or no tool for it)")
    data, mime = shot
    return {"mime": mime, "bytes": len(data), "data": base64.b64encode(data).decode()}


def take_screenshot() -> tuple[bytes, str] | None:
    """A picture of the screen, at most SCREEN_MAX_WIDTH wide when Pillow is around."""
    try:  # the good path: mss + Pillow, no external programs
        import mss  # type: ignore[import-not-found]
        from PIL import Image  # type: ignore[import-not-found]

        with mss.mss() as sct:
            shot = sct.grab(sct.monitors[1])
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        if img.width > SCREEN_MAX_WIDTH:
            img = img.resize((SCREEN_MAX_WIDTH, int(img.height * SCREEN_MAX_WIDTH / img.width)))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=80)
        return buf.getvalue(), "image/jpeg"
    except Exception:
        pass
    data: bytes | None = None
    with tempfile.TemporaryDirectory() as d:
        out = Path(d) / "screen.png"
        cmds: list[list[str]] = []
        if sys.platform == "darwin":
            cmds.append(["screencapture", "-x", "-t", "png", str(out)])
        elif os.name == "nt":
            ps = (
                "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
                "$b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds;"
                "$bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height;"
                "$g=[System.Drawing.Graphics]::FromImage($bmp);"
                "$g.CopyFromScreen($b.Location,[System.Drawing.Point]::Empty,$b.Size);"
                f"$bmp.Save('{out}',[System.Drawing.Imaging.ImageFormat]::Png)"
            )
            cmds.append(["powershell", "-NoProfile", "-Command", ps])
        else:
            for tool, argv in (
                ("grim", ["grim", str(out)]),
                ("gnome-screenshot", ["gnome-screenshot", "-f", str(out)]),
                ("spectacle", ["spectacle", "-b", "-n", "-o", str(out)]),
                ("import", ["import", "-window", "root", str(out)]),
                ("scrot", ["scrot", str(out)]),
            ):
                if shutil.which(tool):
                    cmds.append(argv)
        for argv in cmds:
            try:
                subprocess.run(argv, capture_output=True, timeout=20, check=False)
            except (OSError, subprocess.TimeoutExpired):
                continue
            if out.exists() and out.stat().st_size > 0:
                data = out.read_bytes()
                break
    if data is None:
        return None
    return data, "image/png"


def notify(text: str, title: str = "unibot") -> dict:
    text = (text or "").strip()
    if not text:
        raise ActionError("usage", "text is required")
    ok = False
    try:
        if sys.platform == "darwin":
            script = f'display notification "{_osa(text)}" with title "{_osa(title)}"'
            ok = subprocess.run(["osascript", "-e", script], capture_output=True, timeout=20).returncode == 0
        elif os.name == "nt":
            ps = (
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null;"
                "$t=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
                "$x=$t.GetElementsByTagName('text');"
                f"$x.Item(0).AppendChild($t.CreateTextNode('{_ps(title)}')) | Out-Null;"
                f"$x.Item(1).AppendChild($t.CreateTextNode('{_ps(text)}')) | Out-Null;"
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('unibot').Show([Windows.UI.Notifications.ToastNotification]::new($t))"
            )
            ok = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, timeout=20).returncode == 0
        elif shutil.which("notify-send"):
            ok = subprocess.run(["notify-send", "-a", "unibot", title, text], capture_output=True, timeout=20).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        ok = False
    if not ok:
        print(f"\n[{title}] {text}\n", flush=True)
    return {"ok": True, "shown": ok, "printed": not ok}


def _osa(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')[:400]


def _ps(s: str) -> str:
    return s.replace("'", "''")[:400]


def _mime(name: str) -> str:
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    return {
        "png": "image/png",
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
        "gif": "image/gif",
        "webp": "image/webp",
        "pdf": "application/pdf",
        "txt": "text/plain",
        "md": "text/markdown",
        "json": "application/json",
        "csv": "text/csv",
        "zip": "application/zip",
    }.get(ext, "application/octet-stream")


def run(action: str, args: dict) -> dict:
    """Dispatch by the hub's action name. `task` and `approve` are the agent's, not ours."""
    if action == "info":
        return info()
    if action == "shell":
        return shell(str(args.get("command", "")), args.get("cwd"), float(args.get("timeout") or 120))
    if action == "files":
        return files(args.get("path"))
    if action == "file.get":
        return file_get(str(args.get("path", "")))
    if action == "file.put":
        return file_put(str(args.get("path", "")), str(args.get("data", "")), bool(args.get("force")))
    if action == "open":
        return open_target(str(args.get("url", "")))
    if action == "screen":
        return screen()
    if action == "notify":
        return notify(str(args.get("text", "")), str(args.get("title") or "unibot"))
    raise ActionError("unknown_action", f"this computer does not do '{action}'")
