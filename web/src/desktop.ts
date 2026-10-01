/**
 * The desktop app (desktop/) shows this web app in an Electron window and exposes a little of
 * itself as `window.unibotDesktop` (desktop/app/src/preload/index.ts). Knowing it is in that
 * shell, the app lays itself out like a desktop program at any window width — the sidebar
 * always, the content across the window — and on a Mac leaves the traffic lights room.
 */
export interface DesktopBridge {
  /** Node's process.platform: "darwin", "win32", "linux" */
  platform: string;
  version: string;
}

declare global {
  interface Window {
    unibotDesktop?: DesktopBridge;
  }
}

export function desktopBridge(): DesktopBridge | null {
  return window.unibotDesktop ?? null;
}

export function isDesktopApp(): boolean {
  return !!window.unibotDesktop;
}

/** The desktop title bar is hidden on macOS ("hiddenInset"): the window's controls sit over our page. */
export function hasInsetTitleBar(): boolean {
  return window.unibotDesktop?.platform === "darwin";
}

/** Marks <html> so the stylesheet's `wide:` variant and the title-bar spacing apply (index.css). */
export function markDesktopShell(): void {
  const bridge = desktopBridge();
  if (!bridge) return;
  document.documentElement.classList.add("desktop");
  document.documentElement.dataset.platform = bridge.platform;
}
