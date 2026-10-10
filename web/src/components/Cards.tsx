import {
  AlertTriangle,
  Ban,
  Bell,
  Bot,
  Brain,
  CalendarDays,
  Check,
  ChevronDown,
  ChevronRight,
  Code2,
  ExternalLink,
  FileText,
  Globe,
  Hand,
  Loader2,
  Mail,
  MessageCircleQuestion,
  Monitor,
  MonitorSmartphone,
  MousePointerClick,
  Search,
  Send,
  ShieldAlert,
  Smartphone,
  Square,
  Target,
  Terminal,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { api, fileUrl, frameUrl } from "../api";
import { AllowanceWays } from "./AllowanceWays";
import { t, useT } from "../i18n";
import type {
  ApprovalEvent,
  ArtifactEvent,
  BrowserEvent,
  HandsEvent,
  NoticeEvent,
  QuestionEvent,
  RiskLevel,
  ToolEvent,
} from "../types";
import { cx, fileKind, timeShort } from "../util";

// ------------------------------------------------------------------ helpers
export function toolIcon(tool: string, size = 15): ReactNode {
  switch (tool) {
    case "web_search":
      return <Search size={size} />;
    case "web_fetch":
    case "browser":
      return <Globe size={size} />;
    case "files":
      return <FileText size={size} />;
    case "shell":
      return <Terminal size={size} />;
    case "python_execute":
      return <Code2 size={size} />;
    case "read_emails":
    case "send_email":
      return <Mail size={size} />;
    case "goals":
      return <Target size={size} />;
    case "remember":
    case "recall":
    case "forget":
      return <Brain size={size} />;
    case "ask_user":
      return <MessageCircleQuestion size={size} />;
    // the other devices (docs/every-device.md)
    case "devices":
      return <MonitorSmartphone size={size} />;
    case "device_shell":
      return <Terminal size={size} />;
    case "device_files":
    case "device_get":
    case "device_put":
      return <FileText size={size} />;
    case "device_open":
      return <ExternalLink size={size} />;
    case "device_screen":
      return <Monitor size={size} />;
    case "device_notify":
      return <Bell size={size} />;
    case "delegate":
      return <Send size={size} />;
    // this computer's screen, and the phone's
    case "computer_screen":
      return <Monitor size={size} />;
    case "computer_act":
      return <MousePointerClick size={size} />;
    case "computer_task":
      return <Hand size={size} />;
    case "phone_screen":
    case "phone_act":
    case "phone_task":
      return <Smartphone size={size} />;
    default:
      return <Bot size={size} />;
  }
}

/** "on Pixel 8" — the small pill that marks a step or a request that ran on another device. */
export function DevicePill({ device, className }: { device: string; className?: string }) {
  const t = useT();
  return (
    <span className={cx("inline-flex max-w-full items-center gap-1 rounded-full bg-accent/12 px-2 py-0.5 text-[11px] font-medium text-accent", className)}>
      <MonitorSmartphone size={11} className="shrink-0" /> <span className="truncate">{t("on {device}", { device })}</span>
    </span>
  );
}

const RISK_STYLE: Record<RiskLevel, string> = {
  safe: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300",
  moderate: "bg-amber-500/15 text-amber-700 dark:text-amber-300",
  sensitive: "bg-rose-500/15 text-rose-700 dark:text-rose-300",
};

export function RiskBadge({ risk }: { risk: RiskLevel }) {
  return (
    <span className={cx("px-2 py-0.5 rounded-full text-[11px] font-semibold uppercase tracking-wide", RISK_STYLE[risk])}>
      {t(risk)}
    </span>
  );
}

function ArgsList({ args }: { args: Record<string, unknown> }) {
  const entries = Object.entries(args ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== "");
  if (!entries.length) return null;
  return (
    <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px]">
      {entries.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-muted">{k}</dt>
          <dd className="break-words whitespace-pre-wrap font-mono text-[12.5px] leading-snug">{String(v)}</dd>
        </div>
      ))}
    </dl>
  );
}

// ------------------------------------------------------------------ tool activity chip
export function ToolChip({ event }: { event: ToolEvent }) {
  const [open, setOpen] = useState(false);
  const icon =
    event.status === "running" ? (
      <Loader2 size={14} className="animate-spin text-accent" />
    ) : event.status === "ok" ? (
      <Check size={14} className="text-emerald-500" />
    ) : event.status === "blocked" ? (
      <Ban size={14} className="text-rose-500" />
    ) : (
      <X size={14} className="text-rose-500" />
    );
  return (
    <div className="rise flex justify-start pr-8">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="max-w-full text-left rounded-2xl bg-surface-2/80 border border-border/60 px-3 py-1.5 text-[13px] text-muted hover:text-fg transition"
      >
        <span className="flex items-center gap-2">
          <span className="text-muted">{toolIcon(event.tool)}</span>
          <span className="truncate flex-1">{event.summary || event.tool}</span>
          {event.device && <DevicePill device={event.device} />}
          {icon}
          {event.output ? open ? <ChevronDown size={13} /> : <ChevronRight size={13} /> : null}
        </span>
        {open && (
          <div className="mt-2 text-fg">
            <ArgsList args={event.args} />
            {event.output && (
              <pre className="mt-2 max-h-52 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-bg p-2 text-[12px] leading-snug">
                {event.output}
              </pre>
            )}
          </div>
        )}
      </button>
    </div>
  );
}

// ------------------------------------------------------------------ approval card
/** What a permission is for, in words: "git commands", "email to alice@…", "example.com". */
export function grantSubject(tool: string, target?: string | null): string {
  if (!target) return tool.replace(/_/g, " ");
  switch (tool) {
    case "shell":
      return t("{what} commands", { what: target.split(",").join(", ") });
    case "send_email":
      return t("email to {who}", { who: target.split(",").join(", ") });
    default:
      return target;
  }
}

export function scopeLabel(scope: string, tool: string, target?: string | null, egressTarget?: string | null): string {
  switch (scope) {
    case "once":
      return t("Once");
    case "task":
      // A "task" grant on a tool with no egress target (e.g. shell) widens to
      // the whole tool for the rest of the run — the card must say so.
      return egressTarget ? t("For this task") : t("For this task (entire tool)");
    case "session":
      return t("Until restart");
    case "24h":
      return t("For 24 hours");
    case "always":
      return t("Always for {subject}", { subject: grantSubject(tool, target) });
    default:
      return scope;
  }
}

export function ApprovalCard({
  event,
  name = "unibot",
  onDecide,
}: {
  event: ApprovalEvent;
  name?: string;
  onDecide: (approved: boolean, scope: string) => void;
}) {
  const [showArgs, setShowArgs] = useState(false);
  const [more, setMore] = useState(false);
  const cardRef = useRef<HTMLDivElement>(null);
  const t = useT();
  const pending = event.status === "pending";
  const sensitive = event.risk === "sensitive";
  const standing = (event.grant_options ?? ["once"]).filter((s) => s !== "once");
  // "at example.com" for the web, "to alice@…" for mail; a program name (shell) is in the summary already
  const host = event.egress_target || (["web_fetch", "browser", "web_search"].includes(event.tool) ? event.target : null);
  const recipient = event.tool === "send_email" ? event.target : null;
  // Expanding the card near the bottom of the chat must not hide the new buttons under the tab bar.
  useEffect(() => {
    if (more || showArgs) cardRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [more, showArgs]);
  return (
    <div className="rise flex justify-start pr-4" ref={cardRef}>
      <div
        className={cx(
          "w-full max-w-md overflow-hidden rounded-[22px] border bg-surface shadow-[0_4px_20px_-10px_rgba(0,0,0,0.18)]",
          pending ? (sensitive ? "border-rose-400/60" : "border-border") : "border-border/70",
        )}
      >
        <div className="flex items-start gap-3 px-4 pt-4 pb-2">
          <div className={cx("mt-0.5 rounded-full p-2", sensitive ? "bg-rose-500/12 text-rose-500" : "bg-surface-2 text-fg/80")}>
            {sensitive ? <ShieldAlert size={18} /> : toolIcon(event.tool, 18)}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-1.5 text-[12.5px] font-medium text-muted">
              <span>{event.remote ? t("{name} on {device} wants to", { name, device: event.remote.name }) : t("{name} wants to", { name })}</span>
              {event.remote && <DevicePill device={event.remote.name} />}
            </div>
            <div className="mt-0.5 break-words text-[15.5px] font-semibold leading-snug">{event.summary}</div>
            {host ? (
              <div className="mt-1 flex items-center gap-1 text-[13px] text-muted">
                <Globe size={12} /> <span className="truncate">{t("at {host}", { host })}</span>
              </div>
            ) : recipient ? (
              <div className="mt-1 flex items-center gap-1 text-[13px] text-muted">
                <Mail size={12} /> <span className="truncate">{t("to {recipient}", { recipient })}</span>
              </div>
            ) : null}
            {event.purpose && <div className="mt-1.5 break-words text-[13px] leading-snug text-muted line-clamp-2">{event.purpose}</div>}
            {sensitive && (
              <div className="mt-1.5">
                <RiskBadge risk={event.risk} />
              </div>
            )}
          </div>
        </div>
        {event.warnings?.length > 0 && (
          <div className="space-y-1 px-4 pb-2">
            {event.warnings.map((w) => (
              <div key={w} className="flex items-start gap-1.5 text-[12.5px] text-rose-600 dark:text-rose-300">
                <AlertTriangle size={13} className="mt-0.5 shrink-0" /> <span>{w}</span>
              </div>
            ))}
          </div>
        )}
        <div className="px-4 pb-2">
          <button type="button" className="flex items-center gap-1 text-[12.5px] text-accent" onClick={() => setShowArgs((s) => !s)}>
            {showArgs ? <ChevronDown size={13} /> : <ChevronRight size={13} />} {t("Exactly what will run")}
          </button>
          {showArgs && (
            <>
              <ArgsList args={event.args} />
              {event.reasons?.length > 0 && (
                <div className="mt-1.5 text-[12px] text-muted">{t("Why it asks: {reasons}", { reasons: event.reasons.join(" · ") })}</div>
              )}
            </>
          )}
        </div>
        {pending ? (
          <div className="flex flex-col gap-2 px-3 pb-3 pt-1">
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => onDecide(false, "once")}
                className="flex-1 rounded-full bg-surface-2 py-2.5 text-[15px] font-semibold transition active:scale-[0.98]"
              >
                {t("Deny")}
              </button>
              <button
                type="button"
                onClick={() => onDecide(true, "once")}
                className="flex-1 rounded-full bg-accent py-2.5 text-[15px] font-semibold text-accent-fg transition active:scale-[0.98]"
              >
                {t("Allow")}
              </button>
            </div>
            {standing.length > 0 ? (
              <>
                <button type="button" className="self-center text-[12.5px] text-muted" onClick={() => setMore((m) => !m)}>
                  {more ? t("Fewer options") : t("Allow {subject} for longer…", { subject: grantSubject(event.tool, event.target) })}
                </button>
                {more && (
                  <div className="grid grid-cols-2 gap-2">
                    {standing.map((scope) => (
                      <button
                        key={scope}
                        type="button"
                        onClick={() => onDecide(true, scope)}
                        className="rounded-2xl bg-surface-2 px-3 py-2 text-left text-[13px] font-medium leading-snug"
                      >
                        {scopeLabel(scope, event.tool, event.target, event.egress_target)}
                      </button>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <div className="self-center text-[12px] text-muted">{t("This kind of action is approved one at a time.")}</div>
            )}
          </div>
        ) : (
          <div
            className={cx(
              "flex items-center gap-1.5 border-t border-border px-4 py-2.5 text-[13px] font-medium",
              event.status === "approved" ? "text-emerald-600 dark:text-emerald-300" : "text-muted",
            )}
          >
            {event.status === "approved" ? <Check size={15} /> : <X size={15} />}
            {event.status === "approved"
              ? `${t("Approved")}${event.scope && event.scope !== "once" ? ` · ${scopeLabel(event.scope, event.tool, event.target, event.egress_target).toLowerCase()}` : ""}`
              : event.status === "denied"
                ? t("Denied")
                : t("Expired without an answer")}
            <span className="ml-auto font-normal">{timeShort(event.updated_ts ?? event.ts)}</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ question card
export function QuestionCard({ event, name }: { event: QuestionEvent; name: string }) {
  const t = useT();
  return (
    <div className="rise flex justify-start pr-8">
      <div className="max-w-full rounded-[20px] rounded-bl-md border border-accent/30 bg-surface px-4 py-3">
        <div className="flex items-center gap-1.5 text-[12.5px] font-medium text-accent">
          <MessageCircleQuestion size={14} /> {t("{name} asks", { name })}
        </div>
        <div className="mt-1 text-[15px] leading-snug whitespace-pre-wrap break-words">{event.text}</div>
        {event.status === "pending" && <div className="mt-1.5 text-[12.5px] text-muted">{t("Reply below to continue.")}</div>}
        {event.status === "answered" && event.answer && (
          <div className="mt-2 text-[13px] text-muted border-t border-border pt-2">
            {t("You:")} <span className="text-fg">{event.answer}</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ notice
/** A line from the runtime — in the person's language when the sentence is one we know;
 * a failed run's raw exception stays one tap away for bug reports. */
export function Notice({ event }: { event: NoticeEvent }) {
  const t = useT();
  const [showDetail, setShowDetail] = useState(false);
  const detail = event.detail && event.detail !== event.text ? event.detail : null;
  // the relay refused the turn for a spent allowance: the sentence, then the three ways on
  if (event.code === "allowance" && event.allowance) {
    return (
      <div className="rise flex flex-col items-center gap-2 px-6">
        <div className="max-w-full rounded-2xl bg-amber-500/12 px-3 py-1.5 text-center text-[12.5px] leading-snug text-amber-700 dark:text-amber-300">{t(event.text)}</div>
        <div className="w-full max-w-[520px]">
          <AllowanceWays info={event.allowance} exhausted compact />
        </div>
      </div>
    );
  }
  return (
    <div className="rise flex justify-center px-6">
      <div
        className={cx(
          "max-w-full rounded-2xl px-3 py-1.5 text-center text-[12.5px] leading-snug",
          event.level === "info" && "bg-surface-2/70 text-muted",
          event.level === "warn" && "bg-amber-500/12 text-amber-700 dark:text-amber-300",
          event.level === "error" && "bg-rose-500/12 text-rose-700 dark:text-rose-300",
        )}
      >
        {t(event.text, event.vars)}
        {detail && (
          <>
            {" "}
            <button type="button" onClick={() => setShowDetail((v) => !v)} className="underline decoration-dotted underline-offset-2 opacity-70 hover:opacity-100">
              {showDetail ? t("Hide details") : t("Details")}
            </button>
            {showDetail && <div className="mt-1.5 break-all text-left font-mono text-[11px] opacity-80">{detail}</div>}
          </>
        )}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ artifact
/** A file the agent made. Opens in the in-app viewer (pages render sandboxed, never with the app's origin). */
/** The agent's browser, as a card: the latest frame, where it is, what it just did. */
export function BrowserCard({ event, onOpen }: { event: BrowserEvent; onOpen: (id: string) => void }) {
  const [gone, setGone] = useState(false);
  const t = useT();
  const live = event.status === "live";
  let host = event.url;
  try {
    host = new URL(event.url).host;
  } catch {
    /* keep the raw url */
  }
  return (
    <div className="rise flex justify-start pr-8">
      <button
        type="button"
        onClick={() => onOpen(event.id)}
        aria-label={t("Browser: {title}", { title: event.title || host })}
        className="w-full max-w-[340px] overflow-hidden rounded-3xl rounded-tl-lg border border-border bg-surface shadow-sm hover:bg-surface-2 transition text-left"
      >
        <div className="relative aspect-[16/10] bg-surface-2">
          {gone ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-1 text-muted">
              <Globe size={22} />
              <span className="text-[12px]">{t("Frame no longer available")}</span>
            </div>
          ) : (
            <img
              key={event.frame}
              src={frameUrl(event.thread, event.frame)}
              alt={event.title || host}
              onError={() => setGone(true)}
              className="absolute inset-0 h-full w-full object-cover object-top"
            />
          )}
          <div className="absolute left-2.5 top-2.5 flex items-center gap-1.5 rounded-full bg-black/60 px-2 py-0.5 text-[10.5px] font-semibold text-white">
            {live ? (
              <>
                <span className="h-1.5 w-1.5 rounded-full bg-rose-400 animate-pulse" /> {t("LIVE")}
              </>
            ) : (
              <>
                <Globe size={11} /> {t("BROWSER")}
              </>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 px-3.5 py-2.5">
          <div className="min-w-0 flex-1">
            <div className="text-[13.5px] font-medium truncate">{event.title || host}</div>
            <div className="text-[12px] text-muted truncate">
              {event.by_user && event.action.startsWith("You") ? t("You") + event.action.slice(3) : event.action} · {host}
            </div>
          </div>
          <ChevronRight size={16} className="text-muted shrink-0" />
        </div>
      </button>
    </div>
  );
}

// ------------------------------------------------------------------ hands
/**
 * This computer's hands at work: what it is doing on the screen, the last click in words,
 * the window in front, and a Stop that takes the mouse back. The ring around the cursor is
 * drawn by the desktop stage (desktop/app); here the card is the account of it.
 */
export function HandsCard({ event, name = "unibot" }: { event: HandsEvent; name?: string }) {
  const t = useT();
  const [stopping, setStopping] = useState(false);
  const live = event.status === "live";
  const last = event.last;
  const where = [event.app, event.title].filter(Boolean).join(" · ");
  const stop = async () => {
    setStopping(true);
    try {
      await api.stopHands();
    } catch {
      /* the card closes when the runtime says so */
    } finally {
      setStopping(false);
    }
  };
  return (
    <div className="rise flex justify-start pr-8">
      <div className={cx("w-full max-w-[360px] overflow-hidden rounded-3xl rounded-tl-lg border bg-surface shadow-sm", live ? "border-accent/50" : "border-border/70")}>
        <div className="flex items-start gap-3 px-4 pt-3.5 pb-2">
          <div className={cx("mt-0.5 rounded-full p-2", live ? "bg-accent/12 text-accent" : "bg-surface-2 text-fg/80")}>
            <Hand size={18} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 text-[12.5px] font-medium text-muted">
              <span>{live ? t("{name} is using this computer", { name }) : event.status === "stopped" ? t("Hands stopped") : t("Hands done")}</span>
              {live && (
                <span className="flex items-center gap-1 rounded-full bg-accent/12 px-2 py-0.5 text-[10.5px] font-semibold text-accent">
                  <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-accent" /> {t("LIVE")}
                </span>
              )}
            </div>
            {event.text && <div className="mt-0.5 break-words text-[15px] font-semibold leading-snug">{event.text}</div>}
            {last?.action && (
              <div className="mt-1.5 flex items-center gap-1.5 text-[13px] text-fg/90">
                <MousePointerClick size={13} className="shrink-0 text-muted" />
                <span className="truncate">{describeHandsStep(last, t)}</span>
              </div>
            )}
            {where && <div className="mt-1 truncate text-[12px] text-muted">{where}</div>}
            {event.notice && <div className="mt-1.5 rounded-2xl bg-amber-500/12 px-3 py-1.5 text-[12.5px] text-amber-700 dark:text-amber-300">{event.notice}</div>}
          </div>
        </div>
        <div className="flex items-center gap-2 border-t border-border/70 px-4 py-2 text-[12px] text-muted">
          <span>{t("{n} steps", { n: event.steps })}</span>
          <span className="ml-auto">{timeShort(event.updated_ts ?? event.ts)}</span>
          {live && (
            <button
              type="button"
              onClick={() => void stop()}
              disabled={stopping}
              className="ml-1 inline-flex items-center gap-1.5 rounded-full bg-fg px-3 py-1 text-[12.5px] font-semibold text-bg transition active:scale-95 disabled:opacity-60"
            >
              <Square size={10} fill="currentColor" /> {t("Stop")}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/** One computer action in words: "Clicked “Save”", "Typed 3 words", "Pressed ctrl+s". */
export function describeHandsStep(last: NonNullable<HandsEvent["last"]>, t: (s: string, v?: Record<string, string | number>) => string): string {
  const label = last.label ? `“${last.label}”` : "";
  switch (last.action) {
    case "click":
      return label ? t("Clicked {label}", { label }) : t("Clicked");
    case "double_click":
      return label ? t("Double-clicked {label}", { label }) : t("Double-clicked");
    case "right_click":
    case "middle_click":
      return label ? t("Right-clicked {label}", { label }) : t("Right-clicked");
    case "move":
      return label ? t("Pointed at {label}", { label }) : t("Moved the mouse");
    case "type":
      return t("Typed {n} characters", { n: (last.text ?? "").length });
    case "key":
      return t("Pressed {keys}", { keys: (last.keys ?? []).join("+") });
    case "scroll":
      return t("Scrolled");
    case "drag":
      return t("Dragged");
    case "open_app":
      return label ? t("Opened {label}", { label }) : t("Opened an application");
    case "wait":
      return t("Waited");
    default:
      return last.action ?? "";
  }
}

export function ArtifactCard({ event, onOpen }: { event: ArtifactEvent; onOpen: (path: string) => void }) {
  const t = useT();
  const kind = fileKind(event.name);
  const what =
    kind === "html" ? t("Page") : kind === "image" ? t("Image") : kind === "data" ? t("Data") : kind === "code" ? t("Code") : kind === "event" ? t("Event") : t("Document");
  const label = event.action === "update" ? `${what} · ${t("updated")}` : what;
  const preview = kind === "html" || kind === "image";
  return (
    <div className="rise flex justify-start pr-8">
      <button
        type="button"
        onClick={() => onOpen(event.path)}
        className={cx(
          "max-w-full overflow-hidden rounded-[20px] border border-border bg-surface text-left shadow-[0_4px_20px_-12px_rgba(0,0,0,0.2)] transition hover:bg-surface-2",
          preview ? "w-[300px]" : "",
        )}
      >
        {preview && <ArtifactPreview path={event.path} kind={kind} />}
        <div className="flex items-center gap-3 px-4 py-3">
          {!preview && (
            <div className="rounded-2xl bg-accent/12 p-2.5 text-accent">
              {kind === "code" ? <Code2 size={20} /> : kind === "event" ? <CalendarDays size={20} /> : <FileText size={20} />}
            </div>
          )}
          <div className="min-w-0 flex-1">
            <div className="text-[11px] font-semibold uppercase tracking-wide text-muted">{label}</div>
            <div className="truncate text-[14.5px] font-medium">{event.name}</div>
            <div className="truncate text-[12px] text-muted">{event.path}</div>
          </div>
          <ChevronRight size={16} className="ml-1 shrink-0 text-muted" />
        </div>
      </button>
    </div>
  );
}

/** A small, non-interactive look at a page or picture the agent made — the file itself opens on tap. */
function ArtifactPreview({ path, kind }: { path: string; kind: "html" | "image" }) {
  if (kind === "image") {
    return <img src={fileUrl(path)} alt="" loading="lazy" className="h-40 w-full object-cover" />;
  }
  return (
    <div className="pointer-events-none relative h-40 w-full overflow-hidden bg-white">
      <iframe
        title={path}
        src={fileUrl(path)}
        sandbox=""
        tabIndex={-1}
        loading="lazy"
        scrolling="no"
        className="absolute left-0 top-0 h-[480px] w-[900px] origin-top-left scale-[0.3333] border-0"
      />
    </div>
  );
}
