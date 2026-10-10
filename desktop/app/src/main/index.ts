import { app, BrowserWindow, clipboard, dialog, globalShortcut, ipcMain, Menu, nativeImage, Notification, screen, shell, Tray } from "electron";
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { StageReport } from "../shared/types";
import { Runtime } from "./runtime";
import { Stage } from "./stage";

/**
 * unibot for the desktop — the window, and what a browser tab cannot do.
 *
 * The app you see is the web app the runtime serves (web/ → unibot/server/static),
 * loaded from 127.0.0.1 with the same token the phone gets from the QR code. Around it:
 * a tray that keeps the runtime going when the window is closed, a global Stop that takes
 * the mouse back from the hands, native notifications, and the stage (stage.ts) that
 * shows where the hands are about to click.
 *
 * `npm run dev` here runs it against the runtime's .venv next door; `npm run dist` (and
 * .github/workflows/desktop-app.yml on every `v*` tag) packages it with a bundled runtime
 * as unibot-Desktop-<version>-… for Windows, macOS and Linux (docs/desktop.md).
 */

const runtime = new Runtime();
let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let stage: Stage | null = null;
let quitting = false;
const logs: string[] = [];

const STOP_SHORTCUT = "CommandOrControl+Shift+Escape";
const SHOW_SHORTCUT = "CommandOrControl+Shift+M";
/** dev flag: `--screenshot=/tmp/x.png` writes the window and quits (a headless check) */
const screenshotFlag = process.argv.find((a) => a.startsWith("--screenshot="))?.slice("--screenshot=".length);
/** dev flag: `--stage-demo` plays a scripted hands run into the stage (with --screenshot: captures it) */
const stageDemo = process.argv.includes("--stage-demo");

function log(line: string): void {
  const stamped = `${new Date().toISOString().slice(11, 19)} ${line}`;
  logs.push(stamped);
  if (logs.length > 200) logs.shift();
  console.log(`[unibot-desktop] ${line}`);
  // the same file the runtime writes to, so one log tells the whole story of a start
  try {
    appendFileSync(join(runtime.home, "desktop-app.log"), `[desktop] ${stamped}\n`);
  } catch {
    /* the folder may not exist yet; the line is still in memory for the dialog */
  }
}

const zh = (app.getLocale() || "").toLowerCase().startsWith("zh");
const T = {
  show: zh ? "打开 unibot" : "Open unibot",
  devices: zh ? "设备" : "Devices",
  stop: zh ? "停止操作（Ctrl+Shift+Esc）" : "Stop the hands (Ctrl+Shift+Esc)",
  browser: zh ? "在浏览器中打开" : "Open in the browser",
  logs: zh ? "打开日志文件夹" : "Open the log folder",
  quit: zh ? "退出（同时停止 unibot serve）" : "Quit (stops unibot serve too)",
  quitAttached: zh ? "退出（unibot serve 继续运行）" : "Quit (unibot serve keeps running)",
  working: zh ? "正在操作这台电脑" : "using this computer",
  stopped: zh ? "已把鼠标交还给你。" : "The mouse is yours again.",
  stoppedTitle: zh ? "操作已停止" : "Hands stopped",
  notReady: zh ? "unibot 没能启动" : "unibot could not start",
  crashed: zh ? "unibot 的运行时停止了" : "The unibot runtime stopped",
  crashedBody: zh ? "正在重新启动…" : "Starting it again…",
  crashedAgain: zh ? "unibot 的运行时再次停止，没有自动重启。" : "The unibot runtime stopped again and was not restarted.",
  restart: zh ? "重新启动" : "Restart",
  openLog: zh ? "打开日志" : "Open the log",
  copyDetails: zh ? "复制详情" : "Copy details",
  copied: zh ? "已复制。到 GitHub 发一个 issue 时贴上即可。" : "Copied. Paste it into a GitHub issue.",
  openLogFolder: zh ? "打开日志文件夹" : "Open the log folder",
  quitPlain: zh ? "退出" : "Quit",
  reportHint: zh ? "「复制详情」会把这段话和日志末尾复制下来，发 issue 时贴上：" : "Copy details puts this and the end of the log on the clipboard for an issue at",
  about: zh ? "关于 unibot" : "About unibot",
  checkUpdates: zh ? "检查更新" : "Check for updates",
  upToDate: zh ? "已是最新版本。" : "You are on the latest version.",
  newer: zh ? "有新版本" : "A newer version is available",
  download: zh ? "打开下载页" : "Open the download page",
  later: zh ? "以后再说" : "Later",
  updateFailed: zh ? "现在查不到最新版本；稍后再试。" : "Could not look up the latest version right now; try again later.",
  shortcuts: zh ? "快捷键：Ctrl+Shift+Esc 停止操作 · Ctrl+Shift+M 打开窗口" : "Shortcuts: Ctrl+Shift+Esc stops the hands · Ctrl+Shift+M opens the window",
};

const RELEASES_API = "https://api.github.com/repos/unictoai/unibot/releases/latest";
const RELEASES_PAGE = "https://github.com/unictoai/unibot/releases/latest";
const ISSUES_PAGE = "https://github.com/unictoai/unibot/issues";

/**
 * The runtime did not come up: say why in words, and give the two things that help — the
 * details on the clipboard for an issue, and the folder the log is in. A plain error box
 * with an OK button left Windows users with a screenshot and nothing to send.
 */
async function reportStartupFailure(exc: unknown): Promise<void> {
  const message = String((exc as Error).message ?? exc);
  const details = [
    message,
    "",
    runtime.details(),
    "",
    "--- desktop shell ---",
    logs.slice(-30).join("\n"),
    "",
    "--- desktop-app.log (this session) ---",
    runtime.logTail(40),
  ].join("\n");
  for (;;) {
    const { response } = await dialog.showMessageBox({
      type: "error",
      title: T.notReady,
      message: T.notReady,
      detail: `${message}\n\n${T.reportHint} ${ISSUES_PAGE}`,
      buttons: [T.copyDetails, T.openLogFolder, T.quitPlain],
      defaultId: 0,
      cancelId: 2,
      noLink: true,
    });
    if (response === 0) {
      clipboard.writeText(details);
      await dialog.showMessageBox({ type: "info", title: "unibot", message: T.copied, buttons: ["OK"] });
      continue;
    }
    if (response === 1) {
      shell.showItemInFolder(join(runtime.home, "desktop-app.log"));
      continue;
    }
    return;
  }
}

function iconPath(name: string): string {
  // out/main → ../../resources in dev and in the built app alike
  return join(__dirname, "..", "..", "resources", name);
}

type Bounds = { x?: number; y?: number; width: number; height: number };
const boundsFile = (): string => join(runtime.home, "desktop-window.json");

/** Where the window was last time, if that spot is still on a screen. */
function savedBounds(): Bounds {
  const fallback: Bounds = { width: 1180, height: 820 };
  try {
    const b = JSON.parse(readFileSync(boundsFile(), "utf8")) as Bounds;
    if (!b || typeof b.width !== "number" || typeof b.height !== "number") return fallback;
    if (typeof b.x === "number" && typeof b.y === "number") {
      const onScreen = screen.getAllDisplays().some((d) => {
        const a = d.workArea;
        return b.x! >= a.x - 50 && b.y! >= a.y - 50 && b.x! < a.x + a.width - 100 && b.y! < a.y + a.height - 100;
      });
      if (!onScreen) return { width: b.width, height: b.height };
    }
    return b;
  } catch {
    return fallback;
  }
}

function rememberBounds(win: BrowserWindow): void {
  if (win.isMinimized() || win.isFullScreen()) return;
  try {
    writeFileSync(boundsFile(), JSON.stringify(win.getNormalBounds()));
  } catch {
    /* a read-only home: the size is simply not kept */
  }
}

function createMainWindow(): BrowserWindow {
  const win = new BrowserWindow({
    ...savedBounds(),
    minWidth: 720,
    minHeight: 560,
    title: "unibot",
    icon: iconPath("icon.png"),
    backgroundColor: "#F3F3F5",
    autoHideMenuBar: true,
    titleBarStyle: process.platform === "darwin" ? "hiddenInset" : "default",
    webPreferences: {
      preload: join(__dirname, "../preload/index.js"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });
  win.setMenuBarVisibility(false);
  let boundsTimer: NodeJS.Timeout | null = null;
  const onBounds = () => {
    if (boundsTimer) clearTimeout(boundsTimer);
    boundsTimer = setTimeout(() => rememberBounds(win), 400);
  };
  win.on("resize", onBounds);
  win.on("move", onBounds);
  void win.loadURL(runtime.appUrl());
  // links to elsewhere open in the system browser; the app stays on its own origin.
  // Only safe web schemes may leave the app — never file:, javascript:, data:, etc.
  const openExternalSafe = (url: string) => {
    try {
      if (["http:", "https:", "mailto:"].includes(new URL(url).protocol)) void shell.openExternal(url);
    } catch {
      /* malformed URL: ignore */
    }
  };
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(runtime.base)) openExternalSafe(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith(runtime.base)) {
      e.preventDefault();
      openExternalSafe(url);
    }
  });
  win.on("close", (e) => {
    // closing the window keeps the Muse working; the tray brings it back
    if (!quitting && tray) {
      e.preventDefault();
      win.hide();
    }
  });
  win.on("closed", () => {
    mainWindow = null;
  });
  return win;
}

function showMain(): void {
  if (!mainWindow) mainWindow = createMainWindow();
  else {
    mainWindow.show();
    mainWindow.focus();
  }
}

function openDevices(): void {
  showMain();
  // the web app reads the hash on load and switches tabs (no-op on older builds)
  void mainWindow?.loadURL(`${runtime.appUrl()}#devices`);
}

async function stopHands(): Promise<void> {
  const ok = await runtime.post("/api/hands/stop");
  log(`stop hands → ${ok}`);
  if (Notification.isSupported()) new Notification({ title: T.stoppedTitle, body: T.stopped, silent: true }).show();
}

async function checkForUpdates(quiet = false): Promise<void> {
  try {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 8000);
    const r = await fetch(RELEASES_API, { signal: ctl.signal, headers: { accept: "application/vnd.github+json", "user-agent": `unibot-Desktop/${app.getVersion()}` } });
    clearTimeout(t);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const data = (await r.json()) as { tag_name?: string; html_url?: string };
    const latest = (data.tag_name ?? "").replace(/^v/, "");
    if (!latest) throw new Error("no tag");
    if (newerThan(latest, app.getVersion())) {
      const { response } = await dialog.showMessageBox({
        type: "info",
        title: T.newer,
        message: `unibot ${latest}`,
        detail: `${T.newer}: ${latest} (${zh ? "当前" : "installed"}: ${app.getVersion()})`,
        buttons: [T.download, T.later],
        defaultId: 0,
        cancelId: 1,
      });
      if (response === 0) void shell.openExternal(data.html_url || RELEASES_PAGE);
    } else if (!quiet) {
      await dialog.showMessageBox({ type: "info", title: "unibot", message: T.upToDate, detail: `unibot ${app.getVersion()}` });
    }
  } catch (exc) {
    log(`update check failed: ${String(exc)}`);
    if (!quiet) await dialog.showMessageBox({ type: "warning", title: "unibot", message: T.updateFailed });
  }
}

/** "0.1.21" > "0.1.20"; anything unparsable is not newer. */
function newerThan(a: string, b: string): boolean {
  const pa = a.split(".").map((x) => parseInt(x, 10));
  const pb = b.split(".").map((x) => parseInt(x, 10));
  if (pa.some(Number.isNaN) || pb.some(Number.isNaN)) return false;
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (x !== y) return x > y;
  }
  return false;
}

async function showAbout(): Promise<void> {
  const health = await runtime.health();
  const { response } = await dialog.showMessageBox({
    type: "info",
    title: T.about,
    message: `unibot ${app.getVersion()}`,
    detail: [
      `${zh ? "运行时" : "Runtime"}: ${health?.version ?? "—"} @ ${runtime.base}${runtime.owned ? "" : zh ? "（外部启动）" : " (attached)"}`,
      T.shortcuts,
      zh ? "开源、非营利的社区项目，GPL-3.0。unibot 与 Meta 无关；Muse 是 Meta 的商标。" : "An open-source, non-profit community project, GPL-3.0. Not affiliated with Meta; Muse is a trademark of Meta.",
    ].join("\n"),
    buttons: [T.checkUpdates, "OK"],
    defaultId: 1,
    cancelId: 1,
  });
  if (response === 0) void checkForUpdates();
}

let restarts = 0;
/** The runtime this shell started died: start it once more; if it dies again, ask. */
async function onRuntimeCrash(code: number | null): Promise<void> {
  if (quitting) return;
  log(`runtime crashed (${code}); restarts so far: ${restarts}`);
  if (restarts < 1) {
    restarts += 1;
    if (Notification.isSupported()) new Notification({ title: T.crashed, body: T.crashedBody, silent: true }).show();
    await new Promise((r) => setTimeout(r, 1500));
    try {
      await runtime.ensure(log);
      mainWindow?.webContents.reload();
      setTimeout(() => (restarts = 0), 10 * 60_000); // a clean ten minutes forgives the first crash
      return;
    } catch (exc) {
      log(String(exc));
    }
  }
  const { response } = await dialog.showMessageBox({
    type: "error",
    title: T.crashed,
    message: T.crashedAgain,
    detail: runtime.logTail(8),
    buttons: [T.restart, T.openLog, T.quit],
    defaultId: 0,
    cancelId: 1,
  });
  if (response === 0) {
    restarts = 0;
    try {
      await runtime.ensure(log);
      mainWindow?.webContents.reload();
    } catch (exc) {
      await reportStartupFailure(exc);
    }
  } else if (response === 1) {
    void shell.openPath(join(runtime.home, "desktop-app.log"));
  } else {
    quitting = true;
    app.quit();
  }
}

function buildTray(): void {
  const img = nativeImage.createFromPath(iconPath("tray.png"));
  tray = new Tray(process.platform === "darwin" ? img.resize({ width: 18, height: 18 }) : img);
  tray.setToolTip("unibot");
  refreshTray({ active: false });
  tray.on("click", () => showMain());
  tray.on("double-click", () => showMain());
}

function refreshTray(report: StageReport): void {
  if (!tray) return;
  const busy = report.active ? ` — ${T.working}${report.text ? `: ${report.text}` : ""}` : "";
  tray.setToolTip(`unibot${busy}`);
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: T.show, click: () => showMain() },
      { label: T.devices, click: () => openDevices() },
      { type: "separator" },
      { label: T.stop, enabled: report.active, click: () => void stopHands() },
      { type: "separator" },
      { label: T.browser, click: () => void shell.openExternal(runtime.appUrl()) },
      { label: T.logs, click: () => void shell.openPath(runtime.home) },
      { type: "separator" },
      { label: T.checkUpdates, click: () => void checkForUpdates() },
      { label: T.about, click: () => void showAbout() },
      { type: "separator" },
      {
        label: runtime.owned ? T.quit : T.quitAttached,
        click: () => {
          quitting = true;
          app.quit();
        },
      },
    ]),
  );
}

app.setName("unibot");
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => showMain());

  app.whenReady().then(async () => {
    try {
      await runtime.ensure(log);
    } catch (exc) {
      log(String(exc));
      await reportStartupFailure(exc);
      app.quit();
      return;
    }
    const health = await runtime.health();
    if (health?.auth && !runtime.token) {
      log(`warning: the runtime wants a token and none was found in ${runtime.home}/server_token`);
    }

    ipcMain.on("hands:stop", () => void stopHands());
    ipcMain.on("app:version", (event) => {
      event.returnValue = app.getVersion();
    });
    runtime.onCrash = (code) => void onRuntimeCrash(code);
    if (process.platform === "darwin") {
      app.setAboutPanelOptions({ applicationName: "unibot", applicationVersion: app.getVersion(), copyright: "GPL-3.0 · unictoai community" });
    }

    const devUrl = process.env.ELECTRON_RENDERER_URL ? `${process.env.ELECTRON_RENDERER_URL}/stage/index.html` : null;
    stage = new Stage(
      { base: runtime.base, token: runtime.token, locale: app.getLocale() || "en" },
      join(__dirname, "../preload/index.js"),
      devUrl,
      join(__dirname, "../renderer/stage/index.html"),
      (r) => refreshTray(r),
    );
    stage.create();

    if (stageDemo) {
      stage.demo(async (win) => {
        if (screenshotFlag) {
          const img = await win.webContents.capturePage();
          writeFileSync(screenshotFlag, img.toPNG());
          log(`stage screenshot → ${screenshotFlag}`);
          quitting = true;
          app.quit();
        }
      });
    }

    buildTray();
    mainWindow = createMainWindow();

    if (!globalShortcut.register(STOP_SHORTCUT, () => void stopHands())) log(`could not register ${STOP_SHORTCUT}`);
    if (!globalShortcut.register(SHOW_SHORTCUT, () => showMain())) log(`could not register ${SHOW_SHORTCUT}`);

    if (screenshotFlag && !stageDemo) {
      mainWindow.webContents.once("did-finish-load", () => {
        setTimeout(async () => {
          try {
            const img = await mainWindow!.webContents.capturePage();
            writeFileSync(screenshotFlag, img.toPNG());
            log(`screenshot → ${screenshotFlag}`);
          } finally {
            quitting = true;
            app.quit();
          }
        }, 2500);
      });
    }
  });

  app.on("activate", () => showMain());

  app.on("window-all-closed", () => {
    // the tray keeps the app alive; without a tray (unsupported desktop) closing quits
    if (!tray) app.quit();
  });

  app.on("before-quit", () => {
    quitting = true;
    globalShortcut.unregisterAll();
    stage?.destroy();
    runtime.stop();
  });
}
