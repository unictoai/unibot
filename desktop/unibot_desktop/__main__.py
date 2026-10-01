"""`unibot-desktop` — the computer's Muse, from the command line.

unibot-desktop                      sign in if needed, connect to the hub, talk on the terminal
unibot-desktop serve                connect and stay; no terminal chat (for a service or a tray)
unibot-desktop sign-in [--cloud URL]
unibot-desktop sign-out
unibot-desktop status
unibot-desktop devices
unibot-desktop ask "<text>" [--on <device>]
unibot-desktop call <device> <action> ['<json args>']
unibot-desktop rename "<name>"
unibot-desktop set remote_control on|off | approvals ask|allow | model <id> | language <text>
unibot-desktop console              open the web console for this computer in the browser
"""

from __future__ import annotations

import argparse
import json
import logging
import platform
import sys
import webbrowser

from . import __version__, guard
from .app import Desktop, apply_name, utf8_console
from .cloud import Cloud, CloudError, describe
from .config import DEFAULT_CLOUD, Config, home, load, save
from .hub import HubError


def console_url(cfg: Config) -> str:
    return f"{cfg.cloud_base.rstrip('/')}/app/?device={cfg.device_id}"


def sign_in(cfg: Config, cloud_base: str | None = None) -> bool:
    if cloud_base:
        cfg.cloud_base = cloud_base.rstrip("/")
    else:
        # The trial relay is not the default one; Enter keeps whatever is configured.
        try:
            typed = input(f"unibot Cloud server [{cfg.cloud_base}]: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return False
        if typed:
            cfg.cloud_base = (typed if "://" in typed else "https://" + typed).rstrip("/")
    cloud = Cloud(cfg.cloud_base)
    try:
        identifier = input("Phone number or e-mail address: ").strip()
    except (EOFError, KeyboardInterrupt):
        print()
        return False
    if not identifier:
        return False
    try:
        cloud.request_code(identifier)
    except CloudError as e:
        print(f"! {describe(e)}")
        return False
    print("A six-digit code is on its way.")
    for _ in range(3):
        try:
            code = input("Code: ").strip().replace(" ", "")
        except (EOFError, KeyboardInterrupt):
            print()
            return False
        try:
            reply = cloud.verify(identifier, code, f"{cfg.name} · unibot Desktop {__version__} ({platform.system()})")
        except CloudError as e:
            print(f"! {describe(e)}")
            if e.code in ("code_expired", "code_too_often", "send_failed", "offline"):
                return False
            continue
        cfg.api_key = str(reply.get("api_key") or "")
        cfg.account_hint = str((reply.get("account") or {}).get("hint") or "")
        cfg.model = cfg.model or Cloud(cfg.cloud_base, cfg.api_key).recommended_model(reply)
        save(cfg)
        print(f"Signed in as {cfg.account_hint}; {allowance(reply.get('tokens') or {})}. This computer is “{cfg.name}”.")
        return True
    return False


def need_sign_in(cfg: Config) -> bool:
    if cfg.signed_in:
        return True
    print("This computer is not signed in to unibot Cloud yet.")
    return sign_in(cfg)


def cmd_status(cfg: Config) -> int:
    print(f"unibot Desktop {__version__} on {platform.system()} {platform.release()}")
    print(f"  config:   {home() / 'desktop.json'}")
    print(f"  cloud:    {cfg.cloud_base}")
    print(f"  device:   {cfg.name}  ({cfg.device_id})")
    print(f"  remote control: {'on' if cfg.remote_control else 'off'}   approvals: {cfg.approvals}   model: {cfg.model or '(recommended)'}")
    if not cfg.signed_in:
        print("  account:  not signed in")
        return 0
    try:
        me = Cloud(cfg.cloud_base, cfg.api_key).me()
    except CloudError as e:
        print(f"  account:  {cfg.account_hint or '?'} — {describe(e)}")
        return 1
    print(f"  account:  {(me.get('account') or {}).get('hint')}  {allowance(me.get('tokens') or {})}")
    return 0


def allowance(t: dict) -> str:
    """One phrase for the token line: a relay without a ceiling says so instead of `0 of 0 left`."""
    if t.get("unlimited"):
        return f"no limit on this account; {t.get('used', 0)} tokens used so far, {t.get('used_today', 0)} today"
    cap = f" (cap {t.get('daily_cap')})" if t.get("daily_cap") else ""
    return f"{t.get('remaining')} of {t.get('granted')} tokens left, {t.get('used_today')} used today{cap}"


def cmd_devices(cfg: Config) -> int:
    if not cfg.signed_in:
        print("not signed in")
        return 1
    try:
        devices = Cloud(cfg.cloud_base, cfg.api_key).devices()
    except CloudError as e:
        print(f"! {describe(e)}")
        return 1
    if not devices:
        print("no device has connected to the hub yet")
    for d in devices:
        me = " (this computer)" if d.get("id") == cfg.device_id else ""
        print(f"  {'●' if d.get('online') else '○'} {d['name']:<28} {d['kind']:<9} {d.get('os', ''):<18}{me}")
    return 0


def with_hub(cfg: Config, quiet: bool = True) -> Desktop | None:
    desktop = Desktop(cfg, quiet=quiet)
    desktop.start()
    if not desktop.hub.wait_connected(20):
        print(f"! could not connect to the hub at {desktop.cloud.hub_url}: {desktop.hub.last_error or desktop.state_detail}")
        desktop.stop()
        return None
    return desktop


def cmd_ask(cfg: Config, text: str, on: str | None) -> int:
    desktop = with_hub(cfg)
    if desktop is None:
        return 1
    try:
        if on:
            d = desktop.hub.find(on)
            if d is None or not d.get("online"):
                print(f"! no online device matches “{on}”")
                return 1

            def on_event(body: dict) -> None:
                if body.get("stage") == "tool":
                    print(f"  {d['name']} ↳ {body.get('name')} {body.get('summary', '')}", flush=True)
                elif body.get("stage") == "approval":
                    ok = desktop.ask_terminal(
                        str(body.get("preview", "")), guard.Risk(str(body.get("risk") or guard.DESTRUCTIVE), str(body.get("reason") or ""))
                    )
                    desktop.hub.call(d["id"], "approve", {"approval_id": body.get("approval_id"), "allow": ok}, timeout=30)
                elif body.get("stage") == "image":
                    path = desktop._save_image({**body, "from": d["name"]})
                    if path:
                        print(f"  ▣ picture saved: {path}")

            try:
                r = desktop.hub.call(d["id"], "task", {"text": text, "from": cfg.name}, timeout=600, on_event=on_event)
            except HubError as e:
                print(f"! {d['name']}: {e.message}")
                return 1
            print(f"\n{d['name']} › {r.get('text') or r}")
            return 0
        result = desktop.ask(text)
        print(f"\nunibot › {result.text}")
        return 0
    finally:
        desktop.stop()


def cmd_call(cfg: Config, device: str, action: str, raw_args: str | None) -> int:
    try:
        args = json.loads(raw_args) if raw_args else {}
    except ValueError:
        print('! args must be JSON, e.g. \'{"command": "uname -a"}\'')
        return 2
    desktop = with_hub(cfg)
    if desktop is None:
        return 1
    try:
        d = desktop.hub.find(device)
        if d is None:
            print(f"! no device matches “{device}”; known: " + ", ".join(x["name"] for x in desktop.hub.others()))
            return 1
        try:
            r = desktop.hub.call(
                d["id"],
                action,
                args,
                timeout=float(args.get("timeout") or 120) + 30,
                on_event=lambda b: print(f"  · {json.dumps(b, ensure_ascii=False)[:200]}"),
            )
        except HubError as e:
            print(f"! {e.code}: {e.message}")
            return 1
        if isinstance(r.get("data"), str) and len(r["data"]) > 200:
            r = {**r, "data": f"<{len(r['data'])} base64 chars>"}
        print(json.dumps(r, ensure_ascii=False, indent=2))
        return 0
    finally:
        desktop.stop()


def cmd_run(cfg: Config, serve: bool, open_console: bool) -> int:
    if not need_sign_in(cfg):
        return 1
    desktop = Desktop(cfg, quiet=False)
    desktop.start()
    print(f"unibot Desktop {__version__} — “{cfg.name}” · account {cfg.account_hint or '?'} · console: {console_url(cfg)}")
    if not desktop.hub.wait_connected(20):
        print(f"· not connected yet ({desktop.hub.last_error or desktop.state_detail}); retrying in the background")
    try:
        if serve or not sys.stdin.isatty():
            print("· serving; Ctrl-C to stop")
            import threading

            threading.Event().wait()
            return 0
        desktop.repl(opener=(lambda: webbrowser.open(console_url(cfg))) if open_console else None)
        return 0
    except KeyboardInterrupt:
        print()
        return 0
    finally:
        desktop.stop()


def main(argv: list[str] | None = None) -> int:
    utf8_console()
    ap = argparse.ArgumentParser(prog="unibot-desktop", description="unibot Desktop — the computer's Muse")
    ap.add_argument("--version", action="version", version=f"unibot Desktop {__version__}")
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    run = sub.add_parser("run", help="sign in if needed, connect, chat on the terminal (default)")
    run.add_argument("--open", action="store_true", help="also open the web console in the browser")
    sub.add_parser("serve", help="connect and stay, without a terminal chat")
    si = sub.add_parser("sign-in")
    si.add_argument("--cloud", default=None, help=f"the cloud's base URL (default {DEFAULT_CLOUD})")
    sub.add_parser("sign-out")
    sub.add_parser("status")
    sub.add_parser("devices")
    ask = sub.add_parser("ask")
    ask.add_argument("text")
    ask.add_argument("--on", default=None, help="delegate to this device instead")
    call = sub.add_parser("call")
    call.add_argument("device")
    call.add_argument("action")
    call.add_argument("args", nargs="?", default=None)
    rn = sub.add_parser("rename")
    rn.add_argument("name")
    st = sub.add_parser("set")
    st.add_argument("key", choices=["remote_control", "approvals", "model", "language", "cloud_base", "downloads"])
    st.add_argument("value")
    sub.add_parser("console", help="open the web console for this computer")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    cfg = load()
    if not (home() / "desktop.json").exists():
        save(cfg)  # fixes the device id

    if args.cmd in (None, "run"):
        return cmd_run(cfg, serve=False, open_console=bool(getattr(args, "open", False)))
    if args.cmd == "serve":
        return cmd_run(cfg, serve=True, open_console=False)
    if args.cmd == "sign-in":
        return 0 if sign_in(cfg, args.cloud) else 1
    if args.cmd == "sign-out":
        if cfg.signed_in:
            try:
                Cloud(cfg.cloud_base, cfg.api_key).sign_out()
            except CloudError:
                pass
        cfg.api_key, cfg.account_hint = "", ""
        save(cfg)
        print("signed out")
        return 0
    if args.cmd == "status":
        return cmd_status(cfg)
    if args.cmd == "devices":
        return cmd_devices(cfg)
    if args.cmd == "ask":
        return cmd_ask(cfg, args.text, args.on) if need_sign_in(cfg) else 1
    if args.cmd == "call":
        return cmd_call(cfg, args.device, args.action, args.args) if need_sign_in(cfg) else 1
    if args.cmd == "rename":
        apply_name(cfg, args.name)
        print(f"this computer is now “{cfg.name}” (takes effect on the next connection)")
        return 0
    if args.cmd == "set":
        v = args.value
        if args.key == "remote_control":
            cfg.remote_control = v.lower() in ("on", "true", "1", "yes")
        elif args.key == "approvals":
            if v not in ("ask", "allow"):
                print("approvals: ask | allow")
                return 2
            cfg.approvals = v
        elif args.key == "cloud_base":
            cfg.cloud_base = v.rstrip("/")
        else:
            setattr(cfg, args.key, v)
        save(cfg)
        print(f"{args.key} = {getattr(cfg, args.key)}")
        return 0
    if args.cmd == "console":
        url = console_url(cfg)
        print(url)
        webbrowser.open(url)
        return 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print()
        sys.exit(130)
    except Exception as e:  # a clean last line instead of a traceback for the user
        if "--verbose" in sys.argv or "-v" in sys.argv:
            raise
        print(f"! {type(e).__name__}: {e}  (run with -v for details)", file=sys.stderr)
        sys.exit(1)
