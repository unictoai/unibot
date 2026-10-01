import { Check, Globe, Hand, MessageSquare, Monitor, Pencil, RefreshCw, Smartphone, Square, Trash2, Wifi } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { api } from "../api";
import { BackBar } from "../components/BackBar";
import { CloudCard } from "../components/CloudCard";
import { Card, Toggle, inputCls, primaryBtn, secondaryBtn } from "../components/Form";
import { useT } from "../i18n";
import { useStore } from "../store";
import type { HandsStatus, HubDevice, HubView } from "../types";
import { cx, relativeTime } from "../util";

/**
 * Devices — the same screen as on the phone (docs/every-device.md): the account, this
 * device on the hub (its name, whether the others may operate it, its own hands), and the
 * other devices of yours, each with an "Ask" that opens a chat addressed to it.
 */
export function DevicesScreen() {
  const { state, dispatch, openThread, setTab, toast } = useStore();
  const t = useT();
  const hub = state.hub;
  const hands = state.hands;

  const reload = async () => {
    try {
      const [h, hs] = await Promise.all([api.hub(), api.hands()]);
      dispatch({ type: "ws", msg: { kind: "hub", hub: h } });
      dispatch({ type: "ws", msg: { kind: "hands_state", hands: hs } });
    } catch {
      /* offline; the live socket fills it in */
    }
  };
  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const ask = async (d: HubDevice) => {
    try {
      const r = await api.askDevice(d.id);
      dispatch({ type: "ws", msg: { kind: "thread", thread: r.thread } });
      openThread(r.thread.id);
      setTab("chat");
    } catch (e) {
      toast((e as Error).message);
    }
  };

  const others = (hub?.devices ?? []).filter((d) => !d.this && d.kind !== "web");
  const signedIn = !!hub?.account.signed_in;

  return (
    <div className="flex h-full flex-col">
      <header className="safe-top shrink-0 px-5 pt-2 pb-3">
        <BackBar />
        <h1 className="text-[24px] font-bold tracking-tight">{t("Devices")}</h1>
        <p className="text-[13px] text-muted">
          {t("Devices signed in to the same account see each other; one can ask another to do something where it is.")}
        </p>
      </header>
      <div className="flex-1 overflow-y-auto px-4 pb-8 space-y-4">
        <CloudCard account={hub?.account ?? null} onChange={() => void reload()} />
        {hub && <ThisDeviceCard hub={hub} onChange={() => void reload()} />}
        {hands && !hands.reason?.includes("phone") && <HandsCard hands={hands} onChange={() => void reload()} />}

        <section className="rounded-3xl bg-surface border border-border/70 shadow-sm">
          <div className="flex items-center gap-3 px-4 py-3.5">
            <span className="text-accent">
              <Wifi size={20} />
            </span>
            <span className="min-w-0 flex-1">
              <span className="text-[15px] font-semibold">{t("Your other devices")}</span>
              <span className="block text-[12.5px] text-muted truncate">
                {!signedIn
                  ? t("Sign in above to see them.")
                  : hub?.state !== "connected"
                    ? hubStateLabel(hub?.state ?? "", t)
                    : others.length
                      ? t("{n} online", { n: others.filter((d) => d.online).length })
                      : hub?.account.hint
                        ? t("None yet — sign in on your phone as {hint}, the account this device uses.", { hint: hub.account.hint })
                        : t("None yet — open unibot on your phone and sign in with the same account.")}
              </span>
            </span>
            {signedIn && (
              <button
                type="button"
                aria-label={t("Refresh")}
                onClick={() => void api.refreshHub().then((h) => dispatch({ type: "ws", msg: { kind: "hub", hub: h } })).catch(() => undefined)}
                className="p-2 text-muted hover:text-fg"
              >
                <RefreshCw size={16} />
              </button>
            )}
          </div>
          {others.length > 0 && (
            <ul className="divide-y divide-border/70 border-t border-border/70">
              {others.map((d) => (
                <li key={d.id} className="flex items-center gap-3 px-4 py-3">
                  <span className={cx("rounded-2xl p-2", d.online ? "bg-accent/12 text-accent" : "bg-surface-2 text-muted")}>{kindIcon(d.kind)}</span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2 text-[15px] font-medium">
                      <span className="truncate">{d.name}</span>
                      <span className={cx("h-2 w-2 shrink-0 rounded-full", d.online ? "bg-emerald-500" : "bg-border")} />
                    </span>
                    <span className="block truncate text-[12.5px] text-muted">
                      {d.os || d.kind}
                      {d.online ? ` · ${t("online")}` : d.last_seen ? ` · ${t("last seen {when}", { when: relativeTime(d.last_seen) })}` : ` · ${t("offline")}`}
                    </span>
                  </span>
                  {d.online ? (
                    <button type="button" onClick={() => void ask(d)} className={cx(primaryBtn, "px-3 py-2 text-[13px]")}>
                      <MessageSquare size={14} /> {t("Ask")}
                    </button>
                  ) : (
                    <button
                      type="button"
                      aria-label={t("Forget")}
                      onClick={() => {
                        if (!window.confirm(t("Forget {name}? It can join again by signing in.", { name: d.name }))) return;
                        void api.forgetDevice(d.id).then((h) => dispatch({ type: "ws", msg: { kind: "hub", hub: h } })).catch((e: Error) => toast(e.message));
                      }}
                      className="p-2 text-muted hover:text-rose-500"
                    >
                      <Trash2 size={16} />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
        <p className="px-1 text-[12px] text-muted leading-relaxed">
          {t("A chat addressed to a device runs there; you see every step here and answer its approvals.")}
        </p>
      </div>
    </div>
  );
}

function ThisDeviceCard({ hub, onChange }: { hub: HubView; onChange: () => void }) {
  const { dispatch, toast } = useStore();
  const t = useT();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(hub.device.name);
  const [busy, setBusy] = useState(false);
  const signedIn = hub.account.signed_in;

  const update = async (body: { enabled?: boolean; remote_control?: boolean; name?: string }) => {
    setBusy(true);
    try {
      const h = await api.updateHub(body);
      dispatch({ type: "ws", msg: { kind: "hub", hub: h } });
      onChange();
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const status = !signedIn
    ? { text: t("Signed out"), tone: "off" }
    : !hub.enabled
      ? { text: t("Off"), tone: "off" }
      : hub.state === "connected"
        ? { text: t("On the hub"), tone: "ok" }
        : hub.state === "refused"
          ? { text: t("Refused"), tone: "warn" }
          : { text: t("Connecting…"), tone: "warn" };

  return (
    <Card icon={<Monitor size={20} />} title={t("This device")} summary={hub.device.name} status={status} open>
      <div className="space-y-3">
        {editing ? (
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (!draft.trim()) return;
              void update({ name: draft.trim() }).then(() => setEditing(false));
            }}
          >
            <input value={draft} onChange={(e) => setDraft(e.target.value)} maxLength={60} autoFocus className={inputCls} placeholder={t("A name your other devices will see")} />
            <button type="submit" disabled={busy || !draft.trim()} className={primaryBtn}>
              <Check size={15} />
            </button>
          </form>
        ) : (
          <button type="button" onClick={() => setEditing(true)} className="flex items-center gap-1.5 text-[13px] text-muted hover:text-fg">
            <Pencil size={13} /> {t("Rename this device")}
          </button>
        )}
        <Toggle
          label={t("Join the hub")}
          hint={signedIn ? t("Reachable by your other devices while this runs.") : t("Sign in first.")}
          checked={hub.enabled && signedIn}
          disabled={!signedIn || busy}
          onChange={(v) => void update({ enabled: v })}
        />
        <Toggle
          label={t("My other devices may operate it")}
          hint={t("They can run commands, read files and see its screen here; each step still goes through the Sentinel.")}
          checked={hub.remote_control}
          disabled={busy}
          onChange={(v) => void update({ remote_control: v })}
        />
        {(hub.state === "refused" || hub.state === "disconnected") && hub.detail && <div className="rounded-2xl bg-amber-500/12 px-3 py-2 text-[12.5px] text-amber-700 dark:text-amber-300">{hub.detail}</div>}
      </div>
    </Card>
  );
}

function HandsCard({ hands, onChange }: { hands: HandsStatus; onChange: () => void }) {
  const { dispatch, toast } = useStore();
  const t = useT();
  const [busy, setBusy] = useState(false);

  const set = async (body: { enabled?: boolean; backend?: string }) => {
    setBusy(true);
    try {
      const h = await api.setHands(body);
      dispatch({ type: "ws", msg: { kind: "hands_state", hands: h } });
      onChange();
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const status = hands.task_active
    ? { text: t("Working"), tone: "warn" }
    : hands.enabled
      ? hands.available
        ? { text: t("On"), tone: "ok" }
        : { text: t("Unavailable"), tone: "warn" }
      : { text: t("Off"), tone: "off" };

  return (
    <Card
      icon={<Hand size={20} />}
      title={t("Hands on this computer")}
      summary={
        hands.task_active
          ? hands.task_text || t("On the screen now")
          : hands.available
            ? t("Its own screen, mouse and keyboard, when the shell and the browser are not enough.")
            : hands.reason || t("Nothing can drive this screen yet.")
      }
      status={status}
      open
    >
      <div className="space-y-3">
        <Toggle
          label={t("Let it use this computer's screen")}
          hint={t("It looks at a screenshot and clicks by position; paying, sending and deleting ask you first.")}
          checked={hands.enabled}
          disabled={busy}
          onChange={(v) => void set({ enabled: v })}
        />
        <div className="flex items-center gap-2 text-[12.5px] text-muted">
          <span>{t("Driver")}:</span>
          {(["auto", "pyautogui", "xdotool"] as const).map((b) => (
            <button
              key={b}
              type="button"
              disabled={busy}
              onClick={() => void set({ backend: b })}
              className={cx("rounded-full px-2.5 py-1", (hands.backend ?? "auto") === b || (b === "auto" && !hands.backend) ? "bg-fg text-bg" : "bg-surface-2")}
            >
              {b}
            </button>
          ))}
        </div>
        {hands.enabled && hands.device?.platform === "darwin" && (
          <p className="text-[12px] text-muted">{t("On a Mac, allow unibot under System Settings → Privacy & Security → Accessibility and Screen Recording when macOS asks; without them clicks do nothing and the screenshot is black.")}</p>
        )}
        {!hands.available && hands.reason && <div className="rounded-2xl bg-amber-500/12 px-3 py-2 text-[12.5px] text-amber-700 dark:text-amber-300">{hands.reason}</div>}
        {hands.task_active && (
          <button type="button" onClick={() => void api.stopHands().catch((e: Error) => toast(e.message))} className={cx(secondaryBtn, "w-full")}>
            <Square size={13} fill="currentColor" /> {t("Stop the hands")}
          </button>
        )}
      </div>
    </Card>
  );
}

export function kindIcon(kind: string, size = 18): ReactNode {
  switch (kind) {
    case "phone":
      return <Smartphone size={size} />;
    case "computer":
      return <Monitor size={size} />;
    case "web":
      return <Globe size={size} />;
    default:
      return <Wifi size={size} />;
  }
}

export function hubStateLabel(state: string, t: (s: string) => string): string {
  switch (state) {
    case "connected":
      return t("On the hub");
    case "connecting":
    case "disconnected":
      return t("Connecting to the hub…");
    case "refused":
      return t("The hub refused this device — sign in again.");
    case "signed_out":
      return t("Not signed in.");
    case "off":
    case "stopped":
      return t("The hub is off on this device.");
    default:
      return state;
  }
}
