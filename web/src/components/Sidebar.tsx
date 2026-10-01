import { LayoutGrid, Lightbulb, MessageCircle, MonitorSmartphone, Newspaper, SlidersHorizontal, SquareCheckBig, TerminalSquare, UserRound } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useT } from "../i18n";
import { ThreadList } from "../screens/ChatScreen";
import { MuseSheet } from "../screens/MuseSheet";
import { useStore, type Tab } from "../store";
import { cx } from "../util";
import { Avatar } from "./Avatar";

/**
 * The left rail on a wide screen (the desktop window, a browser at full width): what the
 * phone spreads over the tab bar, the chats sheet and the avatar menu sits side by side —
 * the agent and its status on top, the five sections, the chats and the devices a chat
 * can be addressed to, Settings at the bottom. Same store, same screens; nothing here is
 * a second implementation of anything.
 */
export function Sidebar() {
  const { state, setTab, openThread } = useStore();
  const t = useT();
  const [activityOpen, setActivityOpen] = useState(false);
  const { profile, status } = state;
  const name = profile?.name ?? "unibot";

  const pendingApprovals = state.pendingApprovals.length;
  const proposals = state.goals.filter((g) => g.proposal && g.status !== "cancelled").length;
  const feedUnseen = state.pendingApprovals.filter((a) => a.ts > state.feedSeenAt).length;
  const online = (state.hub?.devices ?? []).filter((d) => !d.this && d.kind !== "web" && d.online).length;

  const statusLine =
    pendingApprovals > 0
      ? pendingApprovals > 1
        ? t("{n} approvals waiting for you", { n: pendingApprovals })
        : t("1 approval waiting for you")
      : status.state === "idle"
        ? t("Idle")
        : status.detail || (status.state === "waiting" ? t("Waiting for you") : t("Working…"));

  const items: Array<{ id: Tab; label: string; icon: ReactNode; badge?: number; hint?: string }> = [
    { id: "chat", label: "Chat", icon: <MessageCircle size={18} />, badge: pendingApprovals },
    { id: "feed", label: "Feed", icon: <Newspaper size={18} />, badge: feedUnseen },
    { id: "ideas", label: "Ideas", icon: <Lightbulb size={18} /> },
    { id: "goals", label: "Goals", icon: <SquareCheckBig size={18} />, badge: proposals },
    { id: "library", label: "Library", icon: <LayoutGrid size={18} /> },
    {
      id: "devices",
      label: "Devices",
      icon: <MonitorSmartphone size={18} />,
      hint: state.hub?.state === "connected" ? (online > 0 ? t("{n} online", { n: online }) : t("Only this one")) : undefined,
    },
    { id: "coding", label: "Coding", icon: <TerminalSquare size={18} /> },
  ];

  return (
    <aside className="flex h-full w-[272px] shrink-0 flex-col border-r border-border bg-surface/70">
      <div className="titlebar-room" />
      <button
        type="button"
        onClick={() => setActivityOpen(true)}
        className="flex items-center gap-3 px-4 pb-3 pt-4 text-left hover:bg-surface-2/60"
        aria-label={t("Menu")}
      >
        <Avatar profile={profile} status={status} size={40} />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[14px] font-semibold leading-tight">{name}</span>
          <span className={cx("mt-0.5 block truncate text-[12px] leading-tight", status.state === "idle" && pendingApprovals === 0 ? "text-muted" : "text-accent")}>
            {statusLine}
          </span>
        </span>
      </button>

      <nav className="px-2.5">
        <ul className="space-y-0.5">
          {items.map((item) => {
            const active = state.tab === item.id;
            return (
              <li key={item.id}>
                <button
                  type="button"
                  onClick={() => setTab(item.id)}
                  aria-current={active ? "page" : undefined}
                  className={cx(
                    "flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-[13.5px] font-medium transition",
                    active ? "bg-surface-2 text-fg" : "text-fg/75 hover:bg-surface-2/60 hover:text-fg",
                  )}
                >
                  <span className={cx(active ? "text-accent" : "text-fg/60")}>{item.icon}</span>
                  <span className="flex-1 truncate text-left">{t(item.label)}</span>
                  {item.hint && <span className="text-[11.5px] font-normal text-muted">{item.hint}</span>}
                  {item.badge ? (
                    <span className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-rose-500 px-1 text-[10.5px] font-bold text-white">
                      {item.badge}
                    </span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      </nav>

      <div className="mt-4 min-h-0 flex-1 overflow-y-auto px-2.5 pb-2">
        <div className="px-2.5 pb-1.5 text-[11.5px] font-semibold uppercase tracking-wide text-muted">{t("Chats")}</div>
        <ThreadList
          threads={state.threads}
          active={state.tab === "chat" ? state.activeThread : ""}
          onPick={(id) => {
            openThread(id);
            setTab("chat");
          }}
          compact
        />
      </div>

      <div className="border-t border-border px-2.5 py-2 space-y-0.5">
        <button
          type="button"
          onClick={() => setTab("account")}
          aria-current={state.tab === "account" ? "page" : undefined}
          className={cx(
            "flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-[13.5px] font-medium transition",
            state.tab === "account" ? "bg-surface-2 text-fg" : "text-fg/75 hover:bg-surface-2/60 hover:text-fg",
          )}
        >
          <UserRound size={18} className={state.tab === "account" ? "text-accent" : "text-fg/60"} />
          <span className="flex-1 truncate text-left">{state.hub?.account.signed_in ? state.hub.account.hint : t("Account")}</span>
        </button>
        <button
          type="button"
          onClick={() => setTab("you")}
          aria-current={state.tab === "you" ? "page" : undefined}
          className={cx(
            "flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-[13.5px] font-medium transition",
            state.tab === "you" ? "bg-surface-2 text-fg" : "text-fg/75 hover:bg-surface-2/60 hover:text-fg",
          )}
        >
          <SlidersHorizontal size={18} className={state.tab === "you" ? "text-accent" : "text-fg/60"} />
          <span className="flex-1 text-left">{t("Settings")}</span>
          {state.version && <span className="text-[11px] font-normal text-muted">v{state.version}</span>}
        </button>
      </div>

      <MuseSheet open={activityOpen} onClose={() => setActivityOpen(false)} />
    </aside>
  );
}
