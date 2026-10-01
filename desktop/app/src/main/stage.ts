import { BrowserWindow, ipcMain, screen } from "electron";
import { join } from "node:path";
import type { SolidRect, StageConfig, StageReport } from "../shared/types";

/**
 * The stage: a transparent, click-through, always-on-top window over the whole display
 * that draws the ring and the ripple where the hands are about to click — UI-TARS-desktop's
 * ScreenMarker, the way HandsStage did it on Android. It is created hidden at start so its
 * renderer can listen on /ws the whole time, shown while a hands task runs, hidden again
 * after. Only the pill at the top takes the mouse (its Stop button): on macOS and Windows
 * mouse moves are forwarded to the page, which says when the pointer is over the pill and
 * the window stops ignoring clicks for that moment; on Linux the window's shape is the
 * pill, clickable inside and see-through everywhere else.
 */
export class Stage {
  private win: BrowserWindow | null = null;
  private hideTimer: NodeJS.Timeout | null = null;
  report: StageReport = { active: false };

  constructor(
    private readonly config: Omit<StageConfig, "width" | "height">,
    private readonly preload: string,
    private readonly rendererUrl: string | null,
    private readonly rendererFile: string,
    private readonly onReport: (r: StageReport) => void,
  ) {
    ipcMain.on("stage:report", (_e, r: StageReport) => {
      this.report = r;
      if (r.active) this.show();
      else this.hideSoon();
      this.onReport(r);
    });
    ipcMain.on("stage:solid", (_e, rect: SolidRect | null) => this.solid(rect));
  }

  /** The pill (with its Stop) takes the mouse; the rest of the stage stays see-through. */
  private solid(rect: SolidRect | null): void {
    const win = this.win;
    if (!win) return;
    if (process.platform === "linux") {
      // no mouse-move forwarding here: shape the window to the pill instead
      if (rect) {
        win.setIgnoreMouseEvents(false);
        win.setShape([{ x: Math.floor(rect.x), y: Math.floor(rect.y), width: Math.ceil(rect.width), height: Math.ceil(rect.height) }]);
      } else {
        win.setShape([]);
        win.setIgnoreMouseEvents(true, { forward: true });
      }
      return;
    }
    if (rect?.hover) win.setIgnoreMouseEvents(false);
    else win.setIgnoreMouseEvents(true, { forward: true });
  }

  create(): void {
    if (this.win) return;
    const display = screen.getPrimaryDisplay();
    const { x, y, width, height } = display.bounds;
    this.win = new BrowserWindow({
      x,
      y,
      width,
      height,
      show: false,
      frame: false,
      transparent: true,
      hasShadow: false,
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      focusable: false,
      skipTaskbar: true,
      alwaysOnTop: true,
      backgroundColor: "#00000000",
      webPreferences: { preload: this.preload, contextIsolation: true, sandbox: true },
    });
    this.win.setAlwaysOnTop(true, "screen-saver");
    this.win.setIgnoreMouseEvents(true, { forward: true });
    this.win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
    this.win.setMenuBarVisibility(false);
    this.win.webContents.on("did-finish-load", () => {
      const cfg: StageConfig = { ...this.config, width, height };
      this.win?.webContents.send("config", cfg);
    });
    this.win.on("closed", () => {
      this.win = null;
    });
    if (this.rendererUrl) void this.win.loadURL(this.rendererUrl);
    else void this.win.loadFile(this.rendererFile);
  }

  show(): void {
    if (this.hideTimer) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
    if (!this.win) this.create();
    if (this.win && !this.win.isVisible()) this.win.showInactive();
  }

  hideSoon(delayMs = 900): void {
    if (this.hideTimer) clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(() => {
      this.hideTimer = null;
      this.win?.hide();
    }, delayMs);
  }

  destroy(): void {
    if (this.hideTimer) clearTimeout(this.hideTimer);
    this.win?.destroy();
    this.win = null;
  }

  /**
   * Development only (`--stage-demo`): play a scripted hands run into the stage without a
   * runtime moving the real mouse, so the ring, the ripple, the drag line and the pill can
   * be looked at — and, with `--screenshot`, captured. The frames are what /ws would send.
   */
  demo(onDone: (win: BrowserWindow) => Promise<void> | void): void {
    if (!this.win) this.create();
    const win = this.win!;
    const frames: Array<[number, Record<string, unknown>]> = [
      [0, { kind: "hands", event: "begin", text: "Open the budget sheet and add this month's numbers" }],
      [300, { kind: "hands", event: "screen" }],
      [900, { kind: "hands", event: "act", action: "drag", fx: 0.55, fy: 0.5, fx2: 0.72, fy2: 0.62, label: "select the range" }],
      [1300, { kind: "hands", event: "act", action: "type", text: "1240" }],
      [1500, { kind: "hands", event: "act", action: "click", fx: 0.28, fy: 0.36, label: "Budget 2026.xlsx", app: "Files" }],
      [1800, { kind: "hands", event: "act", action: "key", keys: ["ctrl", "s"] }],
    ];
    // UNIBOT_STAGE_DEMO_AT picks the moment (ms into the script) the screenshot is taken
    const at = Number(process.env.UNIBOT_STAGE_DEMO_AT || 2200);
    win.webContents.once("did-finish-load", () => {
      this.show();
      for (const [when, frame] of frames) setTimeout(() => win.webContents.send("hands", frame), 400 + when);
      setTimeout(() => void onDone(win), 400 + at);
    });
  }
}

export function stagePreloadPath(): string {
  return join(__dirname, "../preload/index.js");
}
