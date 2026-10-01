import trayUrl from "../../../resources/tray.png";
import type { HandsLive, StageConfig } from "../../shared/types";

/**
 * The stage. Listens on the runtime's /ws for `kind: "hands"` and draws:
 * - a target where the next click lands: a ring with a turning accent arc, two ripples
 *   that fade over ~1.3 s, and the words under the cursor in a chip next to it
 *   (UI-TARS-desktop's ScreenMarker, HandsStage on Android);
 * - a comet from the previous point to the new one, so the eye follows the hands;
 * - a line with a travelling dot for a drag, chevrons for a scroll;
 * - keycaps under the pill for a key press, a typing chip for typed text (count only —
 *   what is typed never appears on the stage);
 * - a soft gradient rim around the display while a task runs, with a sweep across the
 *   screen for the moment of a screenshot;
 * - a pill at the top saying what is going on, with a Stop button and the shortcut.
 * The window is click-through except for the pill: the page tells main where the pill is
 * and when the pointer is over it (main lets clicks through to it just then). The draw
 * loop runs only while something is on the canvas.
 */

const canvas = document.getElementById("stage") as HTMLCanvasElement;
const ctx = canvas.getContext("2d")!;
const pill = document.getElementById("pill")!;
const what = document.getElementById("what")!;
const step = document.getElementById("step")!;
const hint = document.getElementById("hint")!;
const frame = document.getElementById("frame")!;
const scan = document.getElementById("scan")!;
const keys = document.getElementById("keys")!;
const markPath = document.getElementById("markpath")!;
const stopButton = document.getElementById("stop") as HTMLButtonElement;
(pill.querySelector(".avatar") as HTMLElement).style.backgroundImage = `url(${trayUrl})`;
stopButton.addEventListener("click", () => window.unibotDesktop?.stopHands());

const ACCENT = "#0A66E4";
const CYAN = "#06B6D4";
const RING_MS = 1300;
const TRAIL_MS = 720;
const FONT = "600 13px -apple-system, 'PingFang SC', 'Noto Sans CJK SC', 'Segoe UI', system-ui, sans-serif";

interface Mark {
  x: number;
  y: number;
  x2?: number;
  y2?: number;
  label: string;
  born: number;
  life: number;
  kind: "point" | "drag" | "scroll" | "trail";
}

let marks: Mark[] = [];
/** Where the canvas currently sits (screen coordinates); null when nothing is drawn. */
let dirty: Box | null = null;
let last: { x: number; y: number } | null = null;
let config: StageConfig | null = null;
let active = false;
let zh = false;
let drawing = false;
let keysTimer: ReturnType<typeof setTimeout> | null = null;
let pillTimer: ReturnType<typeof setTimeout> | null = null;

const words = () => ({
  working: zh ? "unibot 正在操作这台电脑" : "unibot is using this computer",
  stop: zh ? "停止" : "Stop",
  looking: zh ? "看一眼屏幕" : "looking at the screen",
  click: zh ? "点击" : "click",
  double: zh ? "双击" : "double-click",
  right: zh ? "右键" : "right-click",
  move: zh ? "移到" : "move to",
  drag: zh ? "拖动" : "drag",
  scroll: zh ? "滚动" : "scroll",
  type: zh ? "输入" : "type",
  chars: (n: number) => (zh ? `${n} 个字符` : `${n} ${n === 1 ? "character" : "characters"}`),
  key: zh ? "按键" : "press",
  open: zh ? "打开" : "open",
  wait: zh ? "等一下" : "wait",
  done: zh ? "完成" : "done",
  stopped: zh ? "已停止" : "stopped",
});

type Box = { x: number; y: number; w: number; h: number };

/**
 * The canvas is only ever as big as what is being drawn: a full-display canvas on a 4K
 * screen without a GPU costs a 33 MB copy per frame, a box around the marks costs a few
 * hundred KB. Drawing code stays in screen coordinates through the transform.
 */
function place(b: Box | null): void {
  if (!b) {
    canvas.width = canvas.height = 0;
    dirty = null;
    return;
  }
  const dpr = window.devicePixelRatio || 1;
  canvas.style.left = `${b.x}px`;
  canvas.style.top = `${b.y}px`;
  canvas.style.width = `${b.w}px`;
  canvas.style.height = `${b.h}px`;
  canvas.width = Math.ceil(b.w * dpr);
  canvas.height = Math.ceil(b.h * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, -b.x * dpr, -b.y * dpr);
  dirty = b;
}
window.addEventListener("resize", () => place(null));

function describe(ev: HandsLive): string {
  const w = words();
  const label = ev.label ? ` “${ev.label}”` : "";
  switch (ev.action) {
    case "click":
      return `${w.click}${label}`;
    case "double_click":
      return `${w.double}${label}`;
    case "right_click":
    case "middle_click":
      return `${w.right}${label}`;
    case "move":
      return `${w.move}${label}`;
    case "drag":
      return `${w.drag}${label}`;
    case "scroll":
      return w.scroll;
    case "type":
      return `${w.type} ${ev.text ? w.chars(ev.text.length) : ""}`.trim();
    case "key":
      return `${w.key} ${(ev.keys ?? []).map(keyName).join("+")}`.trim();
    case "open_app":
      return `${w.open}${label}`;
    case "wait":
      return w.wait;
    default:
      return ev.action ?? "";
  }
}

function setStep(text: string): void {
  if (step.textContent === text) return;
  step.textContent = text;
  step.classList.remove("swap");
  void step.offsetWidth; // restart the animation
  step.classList.add("swap");
  // the pill's width follows the words; the clickable region follows the pill
  if (pill.classList.contains("on")) setTimeout(solid, 350);
}

function showPill(state: "" | "done" | "stopped" = ""): void {
  if (pillTimer) {
    clearTimeout(pillTimer);
    pillTimer = null;
  }
  pill.classList.remove("done", "stopped");
  if (state) {
    pill.classList.add(state);
    markPath.setAttribute("d", state === "done" ? "M3 8.5l3 3 7-7" : "M4 4l8 8M12 4l-8 8");
  }
  pill.classList.add("on");
  frame.classList.add("on");
  stopButton.textContent = words().stop;
  // the pill has just changed size: tell main where it is once it has settled
  setTimeout(solid, 350);
}

function hidePill(afterMs: number): void {
  if (pillTimer) clearTimeout(pillTimer);
  pillTimer = setTimeout(() => {
    pillTimer = null;
    pill.classList.remove("on");
    frame.classList.remove("on");
    keys.classList.remove("on");
    solid();
  }, afterMs);
}

/** Where the pill is and whether the pointer is over it — the part of the stage that takes clicks. */
let hovering = false;
function solid(): void {
  if (!pill.classList.contains("on") || pill.classList.contains("done") || pill.classList.contains("stopped")) {
    hovering = false;
    window.unibotDesktop?.solid(null);
    return;
  }
  const r = pill.getBoundingClientRect();
  window.unibotDesktop?.solid({ x: r.left, y: r.top, width: r.width, height: r.height, hover: hovering });
}
// macOS and Windows forward mouse moves to a click-through window: the pointer over the
// pill makes it solid for that moment, leaving it makes the stage see-through again
document.addEventListener("mousemove", (e) => {
  if (!pill.classList.contains("on")) return;
  const r = pill.getBoundingClientRect();
  const over = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
  if (over !== hovering) {
    hovering = over;
    solid();
  }
});
pill.addEventListener("mouseleave", () => {
  if (hovering) {
    hovering = false;
    solid();
  }
});

function showKeys(html: string, forMs: number): void {
  keys.innerHTML = html;
  keys.classList.remove("on");
  void keys.offsetWidth;
  keys.classList.add("on");
  if (keysTimer) clearTimeout(keysTimer);
  keysTimer = setTimeout(() => keys.classList.remove("on"), forMs);
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c] ?? c);

const KEY_NAMES: Record<string, string> = {
  ctrl: "Ctrl",
  control: "Ctrl",
  cmd: "⌘",
  command: "⌘",
  super: "⌘",
  meta: "⌘",
  alt: "Alt",
  option: "⌥",
  shift: "⇧",
  enter: "↵ Enter",
  return: "↵ Enter",
  tab: "⇥ Tab",
  esc: "Esc",
  escape: "Esc",
  backspace: "⌫",
  delete: "Del",
  space: "␣",
  up: "↑",
  down: "↓",
  left: "←",
  right: "→",
};

const keyName = (k: string) => KEY_NAMES[k.toLowerCase()] ?? (k.length === 1 ? k.toUpperCase() : k);

function keyCaps(list: string[]): string {
  return list.map((k) => `<kbd>${esc(keyName(k))}</kbd>`).join('<span class="plus">+</span>');
}

function addMark(m: Omit<Mark, "born" | "life"> & { life?: number }): void {
  marks.push({ ...m, born: performance.now(), life: m.life ?? RING_MS });
  if (!drawing) {
    drawing = true;
    requestAnimationFrame(draw);
  }
}

function onHands(ev: HandsLive): void {
  const w = words();
  switch (ev.event) {
    case "begin":
      active = true;
      marks = [];
      last = null;
      frame.classList.remove("wait");
      what.textContent = w.working;
      setStep(ev.text ? `· ${ev.text}` : "");
      hint.innerHTML = `<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Esc</kbd>`;
      showPill();
      report(true, ev.text ?? "", "");
      break;
    case "screen":
      frame.classList.add("look");
      scan.classList.remove("go");
      void scan.offsetWidth;
      scan.classList.add("go");
      setTimeout(() => frame.classList.remove("look"), 380);
      if (!active) return;
      setStep(`· ${w.looking}`);
      break;
    case "act": {
      const text = describe(ev);
      frame.classList.remove("wait");
      setStep(text ? `· ${text}` : "");
      if (!active) {
        // a single computer_act outside a task: show the target anyway, briefly
        if (!what.textContent) what.textContent = w.working;
        showPill();
        report(true, "", text);
        hidePill(RING_MS + 500);
      } else {
        report(true, undefined, text);
      }
      if (typeof ev.fx === "number" && typeof ev.fy === "number") {
        const x = ev.fx * window.innerWidth;
        const y = ev.fy * window.innerHeight;
        if (last && Math.hypot(last.x - x, last.y - y) > 48) {
          addMark({ x: last.x, y: last.y, x2: x, y2: y, label: "", kind: "trail", life: TRAIL_MS });
        }
        last = { x, y };
        const kind: Mark["kind"] = ev.action === "drag" ? "drag" : ev.action === "scroll" ? "scroll" : "point";
        addMark({
          x,
          y,
          x2: typeof ev.fx2 === "number" ? ev.fx2 * window.innerWidth : undefined,
          y2: typeof ev.fy2 === "number" ? ev.fy2 * window.innerHeight : undefined,
          label: ev.label ?? "",
          kind,
          life: kind === "drag" ? RING_MS + 400 : RING_MS,
        });
        if (kind === "drag" && typeof ev.fx2 === "number" && typeof ev.fy2 === "number") {
          last = { x: ev.fx2 * window.innerWidth, y: ev.fy2 * window.innerHeight };
        }
      }
      if (ev.action === "key" && ev.keys?.length) {
        showKeys(keyCaps(ev.keys), 1500);
      } else if (ev.action === "type") {
        const n = ev.text?.length ?? 0;
        showKeys(`<span class="typing"><span class="caret"></span>${esc(`${w.type} · ${w.chars(n)}`)}</span>`, 1600);
      }
      break;
    }
    case "notice":
      // the hands have stopped to ask (an approval, a login): the rim turns amber until the run goes on
      frame.classList.add("wait");
      setStep(ev.text ? `· ${ev.text}` : "");
      break;
    case "end":
    case "stop":
      active = false;
      frame.classList.remove("wait");
      setStep(`· ${ev.event === "stop" ? w.stopped : w.done}`);
      showPill(ev.event === "stop" ? "stopped" : "done");
      hidePill(1100);
      report(false);
      break;
    default:
      break;
  }
}

function report(on: boolean, text?: string, label?: string): void {
  window.unibotDesktop?.report({ active: on, text, label });
}

// ------------------------------------------------------------------ drawing
const easeOut = (t: number) => 1 - Math.pow(1 - t, 3);
const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

/** Room a mark needs around its points: ripples, chevrons and the label chip. */
function extent(m: Mark): Box {
  const x1 = Math.max(0, Math.min(m.x, m.x2 ?? m.x) - 640);
  const x2 = Math.min(window.innerWidth, Math.max(m.x, m.x2 ?? m.x) + 640);
  const y1 = Math.max(0, Math.min(m.y, m.y2 ?? m.y) - 150);
  const y2 = Math.min(window.innerHeight, Math.max(m.y, m.y2 ?? m.y) + 150);
  return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
}

function union(a: Box | null, b: Box): Box {
  if (!a) return b;
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return { x, y, w: Math.max(a.x + a.w, b.x + b.w) - x, h: Math.max(a.y + a.h, b.y + b.h) - y };
}

function draw(now: number): void {
  marks = marks.filter((m) => now - m.born < m.life);
  if (!marks.length) {
    place(null);
    drawing = false;
    return;
  }
  let need: Box | null = null;
  for (const m of marks) need = union(need, extent(m));
  if (!dirty || dirty.x !== need!.x || dirty.y !== need!.y || dirty.w !== need!.w || dirty.h !== need!.h) place(need);
  else ctx.clearRect(dirty.x, dirty.y, dirty.w, dirty.h);
  for (const m of marks) {
    const t = (now - m.born) / m.life; // 0 → 1
    switch (m.kind) {
      case "trail":
        drawTrail(m, t);
        break;
      case "drag":
        drawDrag(m, t, now);
        break;
      case "scroll":
        drawPoint(m, t, now);
        drawChevrons(m, now, t);
        break;
      default:
        drawPoint(m, t, now);
    }
    if (m.label && m.kind !== "trail") drawLabel(m, t);
  }
  requestAnimationFrame(draw);
}

function drawPoint(m: Mark, t: number, now: number): void {
  // halo
  const halo = ctx.createRadialGradient(m.x, m.y, 4, m.x, m.y, 40);
  halo.addColorStop(0, `rgba(10, 102, 228, ${0.22 * (1 - t)})`);
  halo.addColorStop(1, "rgba(10, 102, 228, 0)");
  ctx.fillStyle = halo;
  ctx.beginPath();
  ctx.arc(m.x, m.y, 40, 0, Math.PI * 2);
  ctx.fill();
  // two ripples
  for (const delay of [0, 0.16]) {
    const tt = clamp01((t - delay) / (1 - delay));
    if (tt <= 0) continue;
    ctx.beginPath();
    ctx.arc(m.x, m.y, 20 + easeOut(tt) * 72, 0, Math.PI * 2);
    ctx.strokeStyle = `rgba(10, 102, 228, ${(1 - tt) * 0.5})`;
    ctx.lineWidth = 2.5 - tt * 1.5;
    ctx.stroke();
  }
  // ring, with a soft wide stroke under it instead of a (costly) canvas shadow
  const a = Math.min(1, 1.6 - t);
  ctx.beginPath();
  ctx.arc(m.x, m.y, 22, 0, Math.PI * 2);
  ctx.strokeStyle = `rgba(10, 102, 228, ${a * 0.22})`;
  ctx.lineWidth = 10;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(m.x, m.y, 22, 0, Math.PI * 2);
  ctx.strokeStyle = `rgba(10, 102, 228, ${a})`;
  ctx.lineWidth = 3;
  ctx.stroke();
  // turning arc
  const from = (now / 520) % (Math.PI * 2);
  ctx.beginPath();
  ctx.arc(m.x, m.y, 30, from, from + Math.PI * 0.75);
  ctx.strokeStyle = `rgba(6, 182, 212, ${a * 0.95})`;
  ctx.lineWidth = 2;
  ctx.lineCap = "round";
  ctx.stroke();
  // centre
  ctx.beginPath();
  ctx.arc(m.x, m.y, 4.5, 0, Math.PI * 2);
  ctx.fillStyle = ACCENT;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(m.x, m.y, 1.8, 0, Math.PI * 2);
  ctx.fillStyle = "#fff";
  ctx.fill();
}

function drawTrail(m: Mark, t: number): void {
  if (typeof m.x2 !== "number" || typeof m.y2 !== "number") return;
  const p = easeOut(t);
  const hx = m.x + (m.x2 - m.x) * p;
  const hy = m.y + (m.y2 - m.y) * p;
  const grad = ctx.createLinearGradient(m.x, m.y, hx, hy);
  grad.addColorStop(0, "rgba(6, 182, 212, 0)");
  grad.addColorStop(1, `rgba(6, 182, 212, ${0.8 * (1 - t)})`);
  ctx.beginPath();
  ctx.moveTo(m.x, m.y);
  ctx.lineTo(hx, hy);
  ctx.strokeStyle = grad;
  ctx.lineWidth = 2.5;
  ctx.lineCap = "round";
  ctx.stroke();
  if (t < 0.95) {
    ctx.beginPath();
    ctx.arc(hx, hy, 3.5, 0, Math.PI * 2);
    ctx.fillStyle = `rgba(6, 182, 212, ${1 - t})`;
    ctx.fill();
  }
}

function drawDrag(m: Mark, t: number, now: number): void {
  drawPoint(m, Math.min(t * 1.4, 1), now);
  if (typeof m.x2 !== "number" || typeof m.y2 !== "number") return;
  const p = easeOut(clamp01(t * 1.5));
  const ex = m.x + (m.x2 - m.x) * p;
  const ey = m.y + (m.y2 - m.y) * p;
  const fade = 1 - Math.max(0, (t - 0.7) / 0.3);
  const grad = ctx.createLinearGradient(m.x, m.y, m.x2, m.y2);
  grad.addColorStop(0, `rgba(10, 102, 228, ${0.9 * fade})`);
  grad.addColorStop(1, `rgba(6, 182, 212, ${0.9 * fade})`);
  ctx.beginPath();
  ctx.moveTo(m.x, m.y);
  ctx.lineTo(ex, ey);
  ctx.strokeStyle = grad;
  ctx.lineWidth = 3;
  ctx.setLineDash([10, 7]);
  ctx.lineDashOffset = -now / 40;
  ctx.stroke();
  ctx.setLineDash([]);
  // travelling dot and the arrowhead once it arrives
  ctx.beginPath();
  ctx.arc(ex, ey, 5, 0, Math.PI * 2);
  ctx.fillStyle = `rgba(6, 182, 212, ${fade})`;
  ctx.fill();
  if (p >= 1) {
    const ang = Math.atan2(m.y2 - m.y, m.x2 - m.x);
    ctx.beginPath();
    ctx.moveTo(m.x2, m.y2);
    ctx.lineTo(m.x2 - 12 * Math.cos(ang - 0.45), m.y2 - 12 * Math.sin(ang - 0.45));
    ctx.lineTo(m.x2 - 12 * Math.cos(ang + 0.45), m.y2 - 12 * Math.sin(ang + 0.45));
    ctx.closePath();
    ctx.fillStyle = `rgba(6, 182, 212, ${fade})`;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(m.x2, m.y2, 18, 0, Math.PI * 2);
    ctx.strokeStyle = `rgba(6, 182, 212, ${0.8 * fade})`;
    ctx.lineWidth = 2;
    ctx.stroke();
  }
}

function drawChevrons(m: Mark, now: number, t: number): void {
  // a chevron above and one below the point, both drifting outwards
  const phase = (now / 700) % 1;
  ctx.strokeStyle = CYAN;
  ctx.lineWidth = 2.5;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  for (const dir of [-1, 1]) {
    const cy = m.y + dir * (36 + phase * 12);
    ctx.globalAlpha = (1 - t) * (1 - phase * 0.7);
    ctx.beginPath();
    ctx.moveTo(m.x - 9, cy - dir * 5);
    ctx.lineTo(m.x, cy + dir * 5);
    ctx.lineTo(m.x + 9, cy - dir * 5);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
}

function drawLabel(m: Mark, t: number): void {
  if (t >= 0.85) return;
  const text = m.label.length > 40 ? `${m.label.slice(0, 40)}…` : m.label;
  ctx.font = FONT;
  const wdt = ctx.measureText(text).width + 24;
  const h = 28;
  let lx = m.x + 36;
  let ly = m.y - 38;
  if (lx + wdt > window.innerWidth - 8) lx = m.x - 36 - wdt;
  if (ly < 8) ly = m.y + 34;
  const pop = Math.min(1, t / 0.09); // scales in over the first ~120 ms
  const scale = 0.92 + 0.08 * easeOut(pop);
  ctx.save();
  ctx.globalAlpha = Math.min(pop, 1 - Math.max(0, (t - 0.62) / 0.23));
  ctx.translate(lx + wdt / 2, ly + h / 2);
  ctx.scale(scale, scale);
  ctx.translate(-(lx + wdt / 2), -(ly + h / 2));
  // a soft shadow as two translucent plates, cheaper than a canvas blur
  ctx.fillStyle = "rgba(0, 0, 0, 0.08)";
  roundRect(lx - 3, ly + 3, wdt + 6, h + 6, 16);
  ctx.fill();
  ctx.fillStyle = "rgba(0, 0, 0, 0.12)";
  roundRect(lx - 1, ly + 2, wdt + 2, h + 2, 15);
  ctx.fill();
  ctx.fillStyle = "rgba(17, 18, 24, 0.86)";
  roundRect(lx, ly, wdt, h, 14);
  ctx.fill();
  ctx.strokeStyle = "rgba(255, 255, 255, 0.1)";
  ctx.lineWidth = 1;
  roundRect(lx + 0.5, ly + 0.5, wdt - 1, h - 1, 13.5);
  ctx.stroke();
  const bar = ctx.createLinearGradient(0, ly, 0, ly + h);
  bar.addColorStop(0, CYAN);
  bar.addColorStop(1, ACCENT);
  ctx.fillStyle = bar;
  roundRect(lx + 9, ly + 8, 3, h - 16, 1.5);
  ctx.fill();
  ctx.fillStyle = "#fff";
  ctx.textBaseline = "middle";
  ctx.fillText(text, lx + 18, ly + h / 2 + 0.5);
  ctx.restore();
}

function roundRect(x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// ------------------------------------------------------------------ the socket
let socket: WebSocket | null = null;
let backoff = 800;

function connect(): void {
  if (!config) return;
  const url = new URL(config.base.replace(/^http/, "ws"));
  url.pathname = "/ws";
  if (config.token) url.searchParams.set("token", config.token);
  socket = new WebSocket(url.toString());
  socket.onopen = () => {
    backoff = 800;
  };
  socket.onmessage = (e) => {
    let msg: { kind?: string } & Partial<HandsLive>;
    try {
      msg = JSON.parse(String(e.data));
    } catch {
      return;
    }
    if (msg.kind === "hands") onHands(msg as HandsLive);
    else if (msg.kind === "hello") {
      const hands = (msg as { state?: { hands?: { task_active?: boolean; task_text?: string } } }).state?.hands;
      if (hands?.task_active) onHands({ kind: "hands", event: "begin", text: hands.task_text ?? "" });
    }
  };
  socket.onclose = () => {
    socket = null;
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 1.7, 10_000);
  };
  socket.onerror = () => socket?.close();
}

window.unibotDesktop?.onHands((frame) => onHands(frame));
window.unibotDesktop?.onConfig((cfg) => {
  config = cfg;
  zh = (cfg.locale || navigator.language || "").toLowerCase().startsWith("zh");
  socket?.close();
  connect();
});
