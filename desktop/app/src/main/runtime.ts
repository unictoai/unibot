import { app } from "electron";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, openSync, readFileSync, statSync } from "node:fs";
import { get as httpGet } from "node:http";
import { connect as tcpConnect, createServer as tcpServer } from "node:net";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

/**
 * The runtime behind the window: `unibot serve` on this machine. The shell attaches
 * to one that is already running (the tray app, a terminal, the binary's `serve`) and
 * starts one of its own otherwise, stopping it again when the window quits.
 *
 * Where things are, in order of preference:
 * - UNIBOT_HOME — the data dir (~/.unibot): server_token, app-settings.json, logs, and the
 *   workspace under it (the runtime is started there, with UNIBOT_DATA_DIR / UNIBOT_WORKSPACE set)
 * - UNIBOT_PORT — the port (8787)
 * - UNIBOT_BIN — the `unibot` executable; else the runtime the packaged app carries
 *   (resources/runtime/), else the repo's .venv, else PATH
 * - UNIBOT_CONFIG — a config.toml to pass with -c
 */
export class Runtime {
  readonly home: string;
  readonly port: number;
  /** the loopback the runtime listens on: 127.0.0.1, or ::1 on a machine where IPv4 loopback is intercepted */
  host = "127.0.0.1";
  private child: ChildProcess | null = null;
  /** true when this shell started the runtime (and should stop it on quit) */
  owned = false;
  /** called when a runtime this shell started stops on its own (not through stop()) */
  onCrash: ((code: number | null) => void) | null = null;
  private stopping = false;
  /** where this session's lines begin in the append-only log (older sessions stay above it) */
  private logStart = 0;

  constructor() {
    this.home = process.env.UNIBOT_HOME || join(homedir(), ".unibot");
    this.port = Number(process.env.UNIBOT_PORT || 8787);
  }

  get base(): string {
    return this.host.includes(":") ? `http://[${this.host}]:${this.port}` : `http://${this.host}:${this.port}`;
  }

  /** The access token the runtime generated (empty with --no-auth). */
  get token(): string {
    try {
      return readFileSync(join(this.home, "server_token"), "utf8").trim();
    } catch {
      return "";
    }
  }

  /** Why the last health probe failed (an error code, an HTTP status), for the log. */
  lastProbe = "";

  /**
   * GET /api/health over Node's own http client — a plain socket to 127.0.0.1, no proxy, no
   * fetch machinery — answering within 1.5 s. The runtime reports what it is still starting
   * (`starting`) while its services come up.
   */
  health(): Promise<{ ok: boolean; auth?: boolean; version?: string; starting?: string } | null> {
    return new Promise((resolve) => {
      let done = false;
      const finish = (value: { ok: boolean; auth?: boolean; version?: string; starting?: string } | null, why: string) => {
        if (done) return;
        done = true;
        if (why) this.lastProbe = why;
        resolve(value);
      };
      try {
        const req = httpGet(`${this.base}/api/health`, { timeout: 1500 }, (res) => {
          const chunks: Buffer[] = [];
          res.on("data", (c: Buffer) => chunks.push(c));
          res.on("end", () => {
            if (res.statusCode !== 200) return finish(null, `HTTP ${res.statusCode}`);
            try {
              finish(JSON.parse(Buffer.concat(chunks).toString("utf8")), "");
            } catch {
              finish(null, "unreadable body");
            }
          });
          res.on("error", (e: NodeJS.ErrnoException) => finish(null, e.code || e.message));
        });
        req.on("timeout", () => {
          req.destroy();
          finish(null, "no answer in 1.5 s");
        });
        req.on("error", (e: NodeJS.ErrnoException) => finish(null, e.code || e.message));
      } catch (e) {
        finish(null, e instanceof Error ? e.message : String(e));
      }
    });
  }

  /** Whether anything at all listens on the port (a bare TCP connect), for the log when HTTP does not answer. */
  private tcpOpen(): Promise<string> {
    return new Promise((resolve) => {
      const sock = tcpConnect({ host: this.host, port: this.port });
      const end = (r: string) => {
        sock.destroy();
        resolve(r);
      };
      sock.setTimeout(1500, () => end("tcp: timeout"));
      sock.once("connect", () => end("tcp: open"));
      sock.once("error", (e: NodeJS.ErrnoException) => end(`tcp: ${e.code || e.message}`));
    });
  }

  /** Who listens on the port, by the system's own account (for the log; Windows and macOS/Linux). */
  private listeners(): string {
    try {
      if (process.platform === "win32") {
        // a busy machine lists far more than the default 1 MB buffer holds (ENOBUFS otherwise)
        const out = execFileSync("netstat", ["-ano", "-p", "tcp"], { encoding: "utf8", timeout: 5000, windowsHide: true, maxBuffer: 64 * 1024 * 1024 });
        return out.split(/\r?\n/).filter((l) => l.includes(`:${this.port} `) || l.includes(`:${this.port}\t`)).join("\n") || "(netstat: nothing on the port)";
      }
      const out = execFileSync("lsof", ["-nP", `-iTCP:${this.port}`, "-sTCP:LISTEN"], { encoding: "utf8", timeout: 5000 });
      return out.trim() || "(lsof: nothing on the port)";
    } catch (e) {
      return `(listeners: ${e instanceof Error ? e.message.split("\n")[0] : String(e)})`;
    }
  }

  /** Attach to a running runtime or start one; resolves once /api/health answers. */
  async ensure(onLog: (line: string) => void): Promise<void> {
    if (await this.health()) {
      onLog(`attached to unibot serve at ${this.base}`);
      return;
    }
    const bin = this.findBinary();
    if (!bin) {
      throw new Error(
        "unibot is not installed here. Set UNIBOT_BIN to the executable, or run `unibot serve` yourself and open the window again.",
      );
    }
    // Can this machine connect to itself at all? Proxy clients that route every connection
    // (Proxifier, a TUN mode) and some security software swallow connections to 127.0.0.1;
    // the runtime's own event loop needs one before it can listen, and the window needs one to
    // reach it. Often only IPv4 is taken: then everything goes over ::1 instead. Nothing at all:
    // say so now, not after two minutes of probing.
    if (!(await loopbackWorks("127.0.0.1"))) {
      if (await loopbackWorks("::1")) {
        this.host = "::1";
        onLog("connections to 127.0.0.1 are intercepted on this machine; using ::1");
      } else {
        onLog("connections to 127.0.0.1 and ::1 are both intercepted on this machine");
        throw new Error(loopbackBlocked(this.home));
      }
    }
    const args = ["serve", "--no-qr", "--port", String(this.port), "--host", this.host];
    if (process.env.UNIBOT_CONFIG) args.push("-c", process.env.UNIBOT_CONFIG);
    mkdirSync(this.home, { recursive: true });
    // The log is appended to across sessions; a marker line opens this one, and what a dialog
    // quotes is read from here on — a failure from last week is not this morning's.
    const logPath = join(this.home, "desktop-app.log");
    try {
      appendFileSync(logPath, `\n=== unibot desktop ${app.getVersion()} · ${process.platform} ${process.arch} · ${new Date().toISOString()} ===\n`);
      this.logStart = statSync(logPath).size;
    } catch {
      this.logStart = 0;
    }
    const log = openSync(logPath, "a");
    onLog(`starting ${bin} ${args.join(" ")}`);
    // The runtime is told where to keep everything: the data folder and the workspace under
    // UNIBOT_HOME. Left to its defaults it would put the workspace under the current directory,
    // which for an app started from Finder is / and from a Windows shortcut may be Program Files
    // or System32 — neither writable, and the runtime would stop before it was ready.
    // PYTHONUTF8 keeps the log readable on a Chinese or Japanese Windows (the console code page
    // would otherwise garble the runtime's error messages).
    this.child = spawn(bin, args, {
      cwd: this.home,
      env: {
        ...process.env,
        UNIBOT_HOME: this.home,
        UNIBOT_DATA_DIR: this.home,
        UNIBOT_WORKSPACE: join(this.home, "workspace"),
        PYTHONUNBUFFERED: "1",
        PYTHONUTF8: "1",
      },
      stdio: ["ignore", log, log],
      detached: false,
      // no console window for the runtime on Windows (its output goes to the log anyway)
      windowsHide: true,
    });
    this.owned = true;
    this.stopping = false;
    let ready = false;
    this.child.on("exit", (code) => {
      onLog(`unibot serve exited (${code})`);
      this.child = null;
      if (ready && !this.stopping) this.onCrash?.(code);
    });
    // The first start of a packaged runtime is slow — the bundle unpacks, Windows Defender reads
    // every file, a cold disk — so the wait is long, and longer on Windows; as long as the
    // process is alive it is given the whole of it.
    const patience = process.platform === "win32" ? 120_000 : 75_000;
    const started = Date.now();
    let nextNote = 10_000;
    let lastStarting = "";
    while (Date.now() - started < patience) {
      const h = await this.health();
      if (h) {
        if (h.starting) {
          // the socket is open but the services are still coming up: say so, and go on waiting a little
          if (h.starting !== lastStarting) onLog(`unibot serve is starting: ${h.starting}`);
          lastStarting = h.starting;
        }
        ready = true;
        onLog(`unibot serve is up after ${((Date.now() - started) / 1000).toFixed(1)} s`);
        return;
      }
      if (!this.child) throw new Error(this.explainExit());
      const waited = Date.now() - started;
      if (waited >= nextNote) {
        // every ten seconds: what the probe saw, and whether the port is open at all — the
        // difference between a runtime still loading and one that answers on another address
        nextNote += 10_000;
        onLog(`still waiting after ${Math.round(waited / 1000)} s: health probe ${this.lastProbe || "no answer"}, ${await this.tcpOpen()}, pid ${this.child.pid}`);
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    const zh = (app.getLocale() || "").toLowerCase().startsWith("zh");
    const secs = Math.round((Date.now() - started) / 1000);
    onLog(`gave up after ${secs} s: health probe ${this.lastProbe || "no answer"}, ${await this.tcpOpen()}; listeners:\n${this.listeners()}`);
    throw new Error(
      this.explainExit(
        zh
          ? `unibot serve 启动了，但 ${secs} 秒内没有在 /api/health 上应答（最后一次探测：${this.lastProbe || "无应答"}）。`
          : `unibot serve started but did not answer on /api/health within ${secs} s (last probe: ${this.lastProbe || "no answer"}).`,
      ),
    );
  }

  /** The last lines of this session's log, so a dialog can say why instead of "see the log". */
  logTail(lines = 12): string {
    try {
      const path = join(this.home, "desktop-app.log");
      const size = statSync(path).size;
      // only what was written since this session's marker; a shorter file means it was rotated away
      const text = readFileSync(path, "utf8");
      const mine = size >= this.logStart ? text.slice(Buffer.byteLength(text, "utf8") - (size - this.logStart)) : text;
      return mine.trimEnd().split("\n").slice(-lines).join("\n");
    } catch {
      return "";
    }
  }

  /** What a bug report needs alongside the message: versions, platform, where the log is. */
  details(): string {
    return [
      `unibot desktop ${app.getVersion()} · Electron ${process.versions.electron} · ${process.platform} ${process.arch}`,
      `home: ${this.home}`,
      `port: ${this.port}${this.owned ? "" : " (attached)"}`,
      `log: ${join(this.home, "desktop-app.log")}`,
    ].join("\n");
  }

  /** A stopped runtime, in words (the system's language): the usual causes are recognised in its log. */
  private explainExit(lead?: string): string {
    const zh = (app.getLocale() || "").toLowerCase().startsWith("zh");
    // a lead means the runtime is alive and silent, not stopped: a different set of causes
    const alive = lead !== undefined;
    lead ??= zh ? "unibot serve 在就绪前就停止了。" : "unibot serve stopped before it was ready.";
    const tail = this.logTail(40);
    let hint = "";
    if (/loopback blocked:/.test(tail)) {
      hint = loopbackBlocked(this.home, false);
    } else if (alive && /still not serving after/.test(tail)) {
      // the runtime's own watchdog wrote where every thread is; that is the report to send
      hint = zh
        ? "运行时进程还在，但一直没有开始监听。多半是安全软件或防火墙拦住了它在本机 127.0.0.1 上开端口——把 unibot（resources\\runtime\\unibot.exe）加入白名单后再试。日志末尾记录了它卡住时每个线程的位置，报告问题时请一并附上。"
        : "The runtime process is alive but never began to listen. Most often security software or a firewall is holding the port it opens on 127.0.0.1 — allow unibot (resources\\runtime\\unibot.exe) there and try again. The end of the log records where every thread was when it stalled; please include it in a report.";
    } else if (alive && !/starting: /.test(tail)) {
      hint = zh
        ? "运行时进程还在，但一直没有开始监听，也没有再写日志。多半是安全软件或防火墙拦住了它在本机 127.0.0.1 上开端口——把 unibot（resources\\runtime\\unibot.exe）加入白名单后再试。"
        : "The runtime process is alive but never began to listen, and wrote nothing more. Most often security software or a firewall is holding the port it opens on 127.0.0.1 — allow unibot (resources\\runtime\\unibot.exe) there and try again.";
    } else if (/address already in use|EADDRINUSE|Errno 98|Errno 10048/i.test(tail)) {
      hint = zh
        ? `端口 ${this.port} 被其他程序（或另一个 unibot）占用。退出它，或设置 UNIBOT_PORT。`
        : `Port ${this.port} is taken by another program (or another unibot). Quit it, or set UNIBOT_PORT.`;
    } else if (/permission denied|Errno 13|WinError 5|not writable|cannot write|cannot create/i.test(tail)) {
      hint = zh
        ? `数据文件夹 ${this.home} 不可写。修复它的权限，或设置 UNIBOT_HOME。`
        : `The data folder ${this.home} is not writable. Fix its permissions or set UNIBOT_HOME.`;
    } else if (/cannot open display|DISPLAY|xdotool/i.test(tail)) {
      hint = zh ? "没有可用的显示器，Hands 需要一个桌面会话。" : "No display is available for the hands; the runtime still needs a desktop session.";
    } else if (/TOMLDecodeError|does not understand|config\.toml has \d+ setting/i.test(tail)) {
      // ("config.toml" alone also appears in the runtime's ordinary "no API key" advice)
      hint = zh ? "config.toml 读不出来；日志里有字段和行号。" : "config.toml could not be read; the log has the field and the line.";
    } else if (/ModuleNotFoundError|ImportError|No module named/i.test(tail)) {
      hint = zh
        ? "运行时缺少一个 Python 包；重新安装 unibot，或把 UNIBOT_BIN 指向一个可用的运行时。"
        : "The runtime is missing a Python package; reinstall unibot or point UNIBOT_BIN at a working one.";
    } else if (/WinError 1455|MemoryError|paging file/i.test(tail)) {
      hint = zh ? "内存不够运行时启动；关掉一些程序再试。" : "Not enough memory for the runtime to start; close some programs and try again.";
    } else if (/GLIBC_[0-9.]+' not found/.test(tail)) {
      hint = zh
        ? "这台电脑的系统库（glibc）比自带运行时要求的旧。0.1.24 起的版本在 Ubuntu 20.04 / Debian 11 及更新的系统上都能运行——请安装最新版；更老的系统请用 pip 安装 unibot 后自行运行 unibot serve。"
        : "This computer's system library (glibc) is older than the bundled runtime needs. Releases from 0.1.24 on run on Ubuntu 20.04 / Debian 11 and newer — install the latest; on an older system, pip install unibot and run unibot serve yourself.";
    } else if (/Failed to load Python DLL|_MEIPASS|PyInstaller|Cannot open self/i.test(tail)) {
      hint = zh
        ? "自带的运行时没能解包——安全软件可能拦住了它。把 unibot 加入白名单，或重新安装。"
        : "The bundled runtime could not unpack — security software may have stopped it. Allow unibot there, or reinstall.";
    }
    const where = `${zh ? "日志" : "Log"}: ${join(this.home, "desktop-app.log")}`;
    const last = tail.split("\n").filter(Boolean).slice(-3).join("\n");
    return [lead, hint, where, last ? `\n${last}` : ""].filter(Boolean).join("\n");
  }

  /** Stop the runtime we started (a runtime we attached to is left alone). */
  stop(): void {
    if (this.child && this.owned) {
      this.stopping = true;
      this.child.kill("SIGTERM");
      this.child = null;
    }
  }

  /** Something small against the API, e.g. a Stop. */
  async post(path: string, body: unknown = {}): Promise<boolean> {
    try {
      const headers: Record<string, string> = { "content-type": "application/json" };
      if (this.token) headers.authorization = `Bearer ${this.token}`;
      const r = await fetch(`${this.base}${path}`, { method: "POST", headers, body: JSON.stringify(body) });
      return r.ok;
    } catch {
      return false;
    }
  }

  /** The web app, with the token the way the QR code hands it over. */
  appUrl(): string {
    const q = new URLSearchParams({ desktop: "1" });
    if (this.token) q.set("token", this.token);
    return `${this.base}/?${q.toString()}`;
  }

  private findBinary(): string | null {
    const candidates: string[] = [];
    if (process.env.UNIBOT_BIN) candidates.push(process.env.UNIBOT_BIN);
    // the packaged app carries its own runtime (scripts/desktop-app/build-runtime.py) next
    // to the app's resources; in development the same folder may sit under desktop/app
    const exe = process.platform === "win32" ? "unibot.exe" : "unibot";
    if (process.resourcesPath) candidates.push(join(process.resourcesPath, "runtime", exe));
    candidates.push(resolve(__dirname, "..", "..", "runtime", exe));
    // the repo checkout this app lives in: desktop/app → ../../.venv
    const repo = resolve(__dirname, "..", "..", "..", "..");
    candidates.push(
      process.platform === "win32" ? join(repo, ".venv", "Scripts", "unibot.exe") : join(repo, ".venv", "bin", "unibot"),
    );
    for (const c of candidates) if (existsSync(c)) return c;
    // PATH: let spawn resolve it, if `unibot --version` can be found
    return which("unibot");
  }
}

/**
 * Whether a TCP connection from this machine to itself on `host` completes: a listener on a
 * free port, a connect to it, both ends seeing each other within 3 s. False when the connect is
 * refused, times out, or lands somewhere else (a proxy answering in the listener's place).
 */
export function loopbackWorks(host: string, timeoutMs = 3000): Promise<boolean> {
  return new Promise((resolve) => {
    let done = false;
    let accepted = false;
    let connected = false;
    const server = tcpServer();
    let client: ReturnType<typeof tcpConnect> | null = null;
    const finish = (ok: boolean) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      client?.destroy();
      server.close();
      resolve(ok);
    };
    const timer = setTimeout(() => finish(false), timeoutMs);
    server.on("error", () => finish(false));
    server.on("connection", (sock) => {
      sock.destroy();
      accepted = true;
      if (connected) finish(true);
    });
    server.listen(0, host, () => {
      const addr = server.address();
      if (!addr || typeof addr === "string") return finish(false);
      client = tcpConnect({ host, port: addr.port });
      client.on("connect", () => {
        connected = true;
        if (accepted) finish(true);
      });
      client.on("error", () => finish(false));
    });
  });
}

/** The explanation when no loopback connection completes, in the system's language. */
function loopbackBlocked(home: string, withLog = true): string {
  const zh = (app.getLocale() || "").toLowerCase().startsWith("zh");
  const text = zh
    ? "这台电脑连不上它自己：到 127.0.0.1（以及 ::1）的本机连接一直完成不了，unibot 的运行时因此无法启动。多半是某个把所有连接都接管的代理客户端——Proxifier，或 Clash / V2Ray / Surge 等开着 TUN 模式（虚拟网卡）、游戏加速器——或者安全软件在拦截。请让 127.0.0.1 和 localhost 直连（Proxifier：Profile → Proxification Rules → Localhost 设为 Direct；Clash：关闭 TUN 模式或把 127.0.0.1 加入绕过列表），或把 unibot 加入它的例外，然后重新打开 unibot。"
    : "This computer cannot connect to itself: connections to 127.0.0.1 (and to ::1) never complete, so the unibot runtime cannot start. Most often a proxy client that routes every connection — Proxifier; Clash, V2Ray or Surge in TUN mode; a game accelerator — or security software is intercepting them. Make 127.0.0.1 and localhost connect directly (Proxifier: Profile → Proxification Rules → Localhost → Direct; Clash: turn TUN mode off or exclude 127.0.0.1), or add unibot to its exceptions, then open unibot again.";
  return withLog ? `${text}
${zh ? "日志" : "Log"}: ${join(home, "desktop-app.log")}` : text;
}

function which(name: string): string | null {
  const exts = process.platform === "win32" ? [".exe", ".cmd", ""] : [""];
  for (const dir of (process.env.PATH || "").split(process.platform === "win32" ? ";" : ":")) {
    for (const ext of exts) {
      const p = join(dir, name + ext);
      if (dir && existsSync(p)) return p;
    }
  }
  return null;
}
