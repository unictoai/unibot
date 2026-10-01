import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  type ReactNode,
} from "react";
import { api, AuthError, connectWs, getToken } from "./api";
import { t } from "./i18n";
import { registerWorker, setAppBadge } from "./push";
import type {
  ApprovalEvent,
  AttachmentInfo,
  CodingEvent,
  CodingRun,
  Goal,
  HandsLive,
  HandsStatus,
  HubView,
  Profile,
  SettingsView,
  StateSnapshot,
  StudioSession,
  Status,
  ThreadMeta,
  TimelineEvent,
  WsMessage,
} from "./types";

/** Tab bar: chat · feed · ideas · goals · library. Memory, devices, connections and settings live behind the avatar. */
export type Tab = "chat" | "feed" | "ideas" | "goals" | "library" | "memory" | "devices" | "connections" | "skills" | "you" | "account" | "coding" | "avatar";
const TAB_NAMES: Tab[] = ["chat", "feed", "ideas", "goals", "library", "memory", "devices", "connections", "skills", "you", "account", "coding", "avatar"];

/** A coding run being followed live: the run itself and the steps that arrived so far. */
export interface CodingLive {
  run: CodingRun;
  events: CodingEvent[];
  /** the text the agent is writing right now (partial deltas) */
  current: string;
}

const FEED_SEEN_KEY = "unibot_feed_seen";

export interface Stream {
  id: string;
  text: string;
  ended: boolean;
}

export interface AppState {
  connected: boolean;
  loaded: boolean;
  authError: boolean;
  error: string | null;
  version: string;
  profile: Profile | null;
  status: Status;
  threads: ThreadMeta[];
  activeThread: string;
  events: Record<string, TimelineEvent[]>;
  hasMore: Record<string, boolean>;
  streams: Record<string, Stream | undefined>;
  goals: Goal[];
  settings: SettingsView | null;
  goalsVersion: number;
  memoryVersion: number;
  /** Bumped when a reminder or routine is set, fires or is cancelled. */
  remindersVersion: number;
  calendarVersion: number;
  /** Cards waiting for you, across every thread — the approvals queue. */
  pendingApprovals: ApprovalEvent[];
  /** Bumps whenever something lands in the Feed (background work, cards, artifacts). */
  feedVersion: number;
  /** ISO time of the newest Feed item you have looked at. */
  feedSeenAt: string;
  /** Path of the workspace file open in the viewer, if any. */
  viewer: string | null;
  /** Text to put in the chat composer next time it shows (e.g. "/weekly-review "). */
  draft: string | null;
  /** attachments handed over from outside (the phone's share sheet), already uploaded */
  draftFiles: AttachmentInfo[] | null;
  /** Bumps when a connection (model, email, browser, MCP) changes on the server. */
  connectionsVersion: number;
  /** Bumps when a skill is added, changed, switched or removed on the server. */
  skillsVersion: number;
  /** First-run setup dismissed for this session (the server remembers a finished one). */
  onboardingDismissed: boolean;
  tab: Tab;
  toast: string | null;
  /** When the agent last went from working to idle (ms since epoch; 0 = never). The face is pleased for a moment. */
  finishedAt: number;
  /** When a tool call last failed or was refused (ms since epoch; 0 = never). The face is worried for a moment. */
  mishapAt: number;
  /** This device on the hub and the other devices of the account (null until the first state). */
  hub: HubView | null;
  /** Coding runs followed live, newest last (kept for this page only). */
  codingLive: Record<string, CodingLive>;
  /** A call in progress, as the runtime reports it (null when none). */
  call: { state: string; turns: number; cost_cny: number; video: boolean; source: string } | null;
  /** Open the call screen (set by the chat header; cleared when the call screen closes). */
  /** This computer's own screen and hands. */
  hands: HandsStatus | null;
  /** The latest live step of the hands, for the stage; cleared when the task ends. */
  handsLive: HandsLive | null;
  /** The avatar studio's session as the runtime last reported it (null until one runs). */
  studio: StudioSession | null;
}

type Action =
  | { type: "hello"; state: StateSnapshot }
  | { type: "ws"; msg: WsMessage }
  | { type: "connection"; connected: boolean }
  | { type: "authError" }
  | { type: "error"; error: string | null }
  | { type: "events"; thread: string; events: TimelineEvent[]; hasMore: boolean; prepend?: boolean }
  | { type: "eventsFailed"; thread: string }
  | { type: "activeThread"; thread: string }
  | { type: "goals"; goals: Goal[] }
  | { type: "settings"; settings: SettingsView }
  | { type: "tab"; tab: Tab }
  | { type: "feedSeen"; at: string }
  | { type: "viewer"; path: string | null }
  | { type: "draft"; text: string | null }
  | { type: "draftFiles"; files: AttachmentInfo[] | null }
  | { type: "onboardingDismissed" }
  | { type: "toast"; toast: string | null };

const initial: AppState = {
  connected: false,
  loaded: false,
  authError: false,
  error: null,
  version: "",
  profile: null,
  status: { state: "idle", detail: "", thread: "main" },
  threads: [],
  activeThread: "main",
  events: {},
  hasMore: {},
  streams: {},
  goals: [],
  settings: null,
  goalsVersion: 0,
  memoryVersion: 0,
  remindersVersion: 0,
  calendarVersion: 0,
  pendingApprovals: [],
  feedVersion: 0,
  feedSeenAt: localStorage.getItem(FEED_SEEN_KEY) ?? "",
  viewer: null,
  draft: null,
  draftFiles: null,
  connectionsVersion: 0,
  skillsVersion: 0,
  onboardingDismissed: false,
  tab: "chat",
  toast: null,
  finishedAt: 0,
  mishapAt: 0,
  hub: null,
  codingLive: {},
  call: null,
  hands: null,
  handsLive: null,
  studio: null,
};

function upsertApproval(list: ApprovalEvent[], ev: TimelineEvent): ApprovalEvent[] {
  if (ev.type !== "approval") return list;
  const rest = list.filter((a) => a.id !== ev.id);
  return ev.status === "pending" ? [...rest, ev] : rest;
}

/** Events that belong in the Feed: background work, cards waiting for you, artifacts. */
function isFeedWorthy(ev: TimelineEvent): boolean {
  if (ev.type === "approval" || ev.type === "question") return true;
  return ev.source === "background" || ev.source === "goal";
}

function upsertEvent(list: TimelineEvent[] | undefined, ev: TimelineEvent): TimelineEvent[] {
  const events = list ?? [];
  const idx = events.findIndex((e) => e.id === ev.id);
  if (idx >= 0) {
    const next = events.slice();
    next[idx] = ev;
    return next;
  }
  return [...events, ev];
}

function upsertThread(list: ThreadMeta[], meta: ThreadMeta): ThreadMeta[] {
  const idx = list.findIndex((t) => t.id === meta.id);
  if (idx >= 0) {
    const next = list.slice();
    next[idx] = meta;
    return next;
  }
  return [...list, meta];
}

function reducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case "hello": {
      const s = action.state;
      // a fresh snapshot: what was streaming or working before the socket dropped is gone
      for (const k of Object.keys(perThread)) delete perThread[k];
      return {
        ...state,
        loaded: true,
        authError: false,
        version: s.version,
        profile: s.profile,
        status: s.status,
        streams: {},
        threads: s.threads,
        goals: s.goals,
        settings: s.settings,
        pendingApprovals: s.pending_approvals,
        feedVersion: state.feedVersion + 1,
        activeThread: s.threads.some((t) => t.id === state.activeThread) ? state.activeThread : "main",
        hub: s.hub ?? state.hub,
        hands: s.hands ?? state.hands,
      };
    }
    case "connection":
      return { ...state, connected: action.connected };
    case "authError":
      return { ...state, authError: true, connected: false };
    case "error":
      return { ...state, error: action.error };
    case "events": {
      const existing = state.events[action.thread] ?? [];
      const merged = action.prepend
        ? [...action.events, ...existing.filter((e) => !action.events.some((n) => n.id === e.id))]
        : action.events;
      return {
        ...state,
        events: { ...state.events, [action.thread]: merged },
        hasMore: { ...state.hasMore, [action.thread]: action.hasMore },
      };
    }
    case "eventsFailed":
      // the timeline could not be loaded: show the empty chat (with a toast) instead of nothing
      return {
        ...state,
        events: { ...state.events, [action.thread]: state.events[action.thread] ?? [] },
        toast: t("Could not load the conversation. Pull to retry."),
      };
    case "activeThread":
      return { ...state, activeThread: action.thread, tab: "chat" };
    case "goals":
      return { ...state, goals: action.goals };
    case "settings":
      return { ...state, settings: action.settings, profile: action.settings.profile };
    case "tab":
      return { ...state, tab: action.tab };
    case "feedSeen":
      localStorage.setItem(FEED_SEEN_KEY, action.at);
      return { ...state, feedSeenAt: action.at };
    case "viewer":
      return { ...state, viewer: action.path };
    case "draft":
      return { ...state, draft: action.text };
    case "draftFiles":
      return { ...state, draftFiles: action.files };
    case "onboardingDismissed":
      return { ...state, onboardingDismissed: true };
    case "toast":
      return { ...state, toast: action.toast };
    case "ws":
      return applyWs(state, action.msg);
    default:
      return state;
  }
}

function applyWs(state: AppState, msg: WsMessage): AppState {
  switch (msg.kind) {
    case "hello":
      return reducer(state, { type: "hello", state: msg.state });
    case "event":
    case "update": {
      const ev = msg.event;
      const streams = { ...state.streams };
      const stream = streams[ev.thread];
      if (ev.type === "assistant" && stream && stream.id === ev.id) streams[ev.thread] = undefined;
      const threads = state.threads.map((t) =>
        t.id === ev.thread && msg.kind === "event" ? { ...t, updated_at: ev.ts } : t,
      );
      // Only threads whose history has been loaded get the event merged in; the rest are
      // fetched when opened. The approvals queue and the Feed follow every thread.
      const loaded = state.events[ev.thread] !== undefined;
      const mishap = (ev.type === "tool" && (ev.status === "error" || ev.status === "blocked")) || (ev.type === "notice" && ev.level === "error");
      return {
        ...state,
        streams,
        threads,
        events: loaded ? { ...state.events, [ev.thread]: upsertEvent(state.events[ev.thread], ev) } : state.events,
        pendingApprovals: upsertApproval(state.pendingApprovals, ev),
        feedVersion: isFeedWorthy(ev) ? state.feedVersion + 1 : state.feedVersion,
        mishapAt: mishap ? Date.now() : state.mishapAt,
      };
    }
    case "stream_start":
      return {
        ...state,
        streams: { ...state.streams, [msg.thread]: { id: msg.id, text: "", ended: false } },
      };
    case "delta": {
      const current = state.streams[msg.thread];
      const stream: Stream =
        current && current.id === msg.id
          ? { ...current, text: current.text + msg.text }
          : { id: msg.id, text: msg.text, ended: false };
      return { ...state, streams: { ...state.streams, [msg.thread]: stream } };
    }
    case "stream_end": {
      const current = state.streams[msg.thread];
      if (!current || current.id !== msg.id) return state;
      // Keep it until the persisted assistant event replaces it (avoids flicker);
      // an empty stream, or one the server says was not a reply, can go right away.
      if (msg.discard || !current.text.trim()) return { ...state, streams: { ...state.streams, [msg.thread]: undefined } };
      return { ...state, streams: { ...state.streams, [msg.thread]: { ...current, ended: true } } };
    }
    case "status": {
      const st = msg.status;
      const streams = { ...state.streams };
      const s = streams[st.thread];
      if (s?.ended && st.state !== "working") streams[st.thread] = undefined;
      const overall = pickOverall(state.status, st);
      const finished = state.status.state === "working" && overall.state === "idle";
      return { ...state, status: overall, streams, finishedAt: finished ? Date.now() : state.finishedAt };
    }
    case "thread":
      return { ...state, threads: upsertThread(state.threads, msg.thread) };
    case "thread_deleted": {
      const events = { ...state.events };
      delete events[msg.thread];
      return {
        ...state,
        events,
        threads: state.threads.filter((t) => t.id !== msg.thread),
        pendingApprovals: state.pendingApprovals.filter((a) => a.thread !== msg.thread),
        activeThread: state.activeThread === msg.thread ? "main" : state.activeThread,
      };
    }
    case "thread_cleared":
      return {
        ...state,
        events: { ...state.events, [msg.thread]: [] },
        pendingApprovals: state.pendingApprovals.filter((a) => a.thread !== msg.thread),
      };
    case "goals":
      return { ...state, goalsVersion: state.goalsVersion + 1 };
    case "memory":
      return { ...state, memoryVersion: state.memoryVersion + 1 };
    case "reminders":
    case "triggers":
      return { ...state, remindersVersion: state.remindersVersion + 1 };
    case "calendar":
      return { ...state, calendarVersion: state.calendarVersion + 1 };
    case "feed_posts":
      return { ...state, feedVersion: state.feedVersion + 1 };
    case "profile":
      return {
        ...state,
        profile: msg.profile,
        settings: state.settings ? { ...state.settings, profile: msg.profile } : state.settings,
      };
    case "settings":
      return { ...state, settings: msg.settings, profile: msg.settings.profile };
    case "connections":
      return { ...state, connectionsVersion: state.connectionsVersion + 1 };
    case "hub":
      return { ...state, hub: msg.hub };
    case "coding": {
      const id = msg.run?.id ?? msg.event.run;
      if (!id) return state;
      const prev = state.codingLive[id];
      const run: CodingRun | undefined = msg.run ?? prev?.run;
      if (!run) return state;
      const events = msg.event.kind === "run" ? (prev?.events ?? []) : [...(prev?.events ?? []), msg.event].slice(-400);
      let current = prev?.current ?? "";
      if (msg.event.kind === "text") current = msg.event.partial ? current + (msg.event.text ?? "") : "";
      if (msg.event.kind === "done" || msg.event.kind === "error") current = "";
      const live = { ...state.codingLive, [id]: { run: { ...run, device: msg.device ?? run.device }, events, current } };
      // keep the last 20 runs on the page
      const ids = Object.keys(live);
      if (ids.length > 20) for (const old of ids.sort((a, b) => live[a].run.started_at - live[b].run.started_at).slice(0, ids.length - 20)) delete live[old];
      return { ...state, codingLive: live };
    }
    case "call": {
      if (msg.state === "ended") return { ...state, call: null };
      return {
        ...state,
        call: {
          state: msg.state,
          turns: msg.turns ?? state.call?.turns ?? 0,
          cost_cny: msg.cost_cny ?? state.call?.cost_cny ?? 0,
          video: msg.video ?? state.call?.video ?? false,
          source: msg.source ?? state.call?.source ?? "",
        },
      };
    }
    case "hands_state":
      return { ...state, hands: msg.hands };
    case "studio":
      return { ...state, studio: msg.current };
    case "hands": {
      const { kind: _kind, ...live } = msg;
      void _kind;
      const ended = live.event === "end" || live.event === "stop";
      return { ...state, handsLive: ended ? null : live };
    }
    case "skills":
      return { ...state, skillsVersion: state.skillsVersion + 1 };
    case "error":
      return { ...state, toast: t(msg.error) };
    case "pong":
      return { ...state, status: msg.status };
    default:
      return state;
  }
}

/** Per-thread statuses arrive one at a time; show the busiest one under the avatar. */
const perThread: Record<string, Status> = {};
function pickOverall(prev: Status, incoming: Status): Status {
  perThread[incoming.thread] = incoming;
  const working = Object.values(perThread).filter((s) => s.state !== "idle");
  if (!working.length) return { state: "idle", detail: "", thread: "main" };
  working.sort((a, b) => Number(a.thread !== "main") - Number(b.thread !== "main"));
  return working[0] ?? prev;
}

interface StoreValue {
  state: AppState;
  dispatch: (a: Action) => void;
  send: (thread: string, text: string, files?: string[]) => Promise<void>;
  decide: (id: string, approved: boolean, scope?: string) => Promise<void>;
  loadEvents: (thread: string, before?: string) => Promise<void>;
  refreshGoals: () => Promise<void>;
  refreshSettings: () => Promise<void>;
  refreshHub: () => Promise<void>;
  setTab: (tab: Tab) => void;
  openThread: (thread: string) => void;
  markFeedSeen: (at: string) => void;
  openFile: (path: string | null) => void;
  /** Put text in the chat composer and switch to the chat (a skill's "Use", for one). */
  draft: (text: string | null) => void;
  draftFiles: (files: AttachmentInfo[] | null) => void;
  dismissOnboarding: () => void;
  /** open (voice / video) or close the call screen */
  toast: (text: string) => void;
}

const StoreContext = createContext<StoreValue | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initial);
  const wsRef = useRef<ReturnType<typeof connectWs> | null>(null);

  useEffect(() => {
    getToken();
    const ws = connectWs({
      onMessage: (msg) => dispatch({ type: "ws", msg }),
      onOpen: () => dispatch({ type: "connection", connected: true }),
      onClose: () => dispatch({ type: "connection", connected: false }),
      onAuthError: () => dispatch({ type: "authError" }),
    });
    wsRef.current = ws;
    api.state()
      .then((s) => dispatch({ type: "hello", state: s }))
      .catch((e) => {
        if (e instanceof AuthError) dispatch({ type: "authError" });
        else dispatch({ type: "error", error: String(e.message ?? e) });
      });
    return () => ws.close();
  }, []);

  const loadEvents = useCallback(async (thread: string, before?: string) => {
    try {
      const data = await api.events(thread, 150, before);
      dispatch({ type: "events", thread, events: data.events, hasMore: data.has_more, prepend: !!before });
    } catch (e) {
      if (e instanceof AuthError) dispatch({ type: "authError" });
      else dispatch({ type: "eventsFailed", thread });
    }
  }, []);

  // The account as the runtime sees it, fetched directly: after a sign-in the socket
  // usually brings it, but the gate must not depend on the socket being up.
  const refreshHub = useCallback(async () => {
    try {
      dispatch({ type: "ws", msg: { kind: "hub", hub: await api.hub() } });
    } catch {
      /* offline */
    }
  }, []);

  useEffect(() => {
    if (state.loaded) void loadEvents(state.activeThread);
  }, [state.loaded, state.activeThread, loadEvents]);

  // Reload the current thread after a reconnect so nothing is missed.
  const wasConnected = useRef(false);
  useEffect(() => {
    if (state.connected && wasConnected.current && state.loaded) void loadEvents(state.activeThread);
    wasConnected.current = state.connected;
  }, [state.connected, state.loaded, state.activeThread, loadEvents]);

  const refreshGoals = useCallback(async () => {
    try {
      dispatch({ type: "goals", goals: await api.goals() });
    } catch {
      /* offline */
    }
  }, []);

  useEffect(() => {
    if (state.loaded) void refreshGoals();
  }, [state.goalsVersion, state.loaded, refreshGoals]);

  const refreshSettings = useCallback(async () => {
    try {
      dispatch({ type: "settings", settings: await api.settings() });
    } catch {
      /* offline */
    }
  }, []);

  useEffect(() => {
    if (!state.toast) return;
    const t = window.setTimeout(() => dispatch({ type: "toast", toast: null }), 3500);
    return () => window.clearTimeout(t);
  }, [state.toast]);

  // The service worker (push + app badge) and where a notification tap should land.
  useEffect(() => {
    void registerWorker();
    // `?thread=<id>` opens a chat, `?tab=goals` (feed, ideas, library, connections) a tab —
    // used by notification taps and by links into the app.
    // `?draft=<text>&attach=<json>` puts text and already-uploaded files into the composer
    // without sending — how "Share to unibot" from another app on the phone arrives.
    const openFromUrl = (href: string) => {
      const url = new URL(href, window.location.origin);
      const thread = url.searchParams.get("thread");
      const tab = url.searchParams.get("tab");
      if (thread) dispatch({ type: "activeThread", thread });
      if (tab && TAB_NAMES.includes(tab as Tab) && !thread) dispatch({ type: "tab", tab: tab as Tab });
      else dispatch({ type: "tab", tab: "chat" });
      const text = url.searchParams.get("draft");
      if (text) dispatch({ type: "draft", text });
      const attach = url.searchParams.get("attach");
      if (attach) {
        try {
          const files = JSON.parse(attach) as AttachmentInfo[];
          if (Array.isArray(files) && files.length) dispatch({ type: "draftFiles", files: files.filter((f) => f && typeof f.path === "string") });
        } catch {
          /* not ours */
        }
      }
    };
    const initial = new URL(window.location.href);
    if (["thread", "tab", "draft", "attach"].some((k) => initial.searchParams.has(k))) {
      openFromUrl(initial.href);
      for (const k of ["thread", "tab", "draft", "attach"]) initial.searchParams.delete(k);
      window.history.replaceState({}, "", initial.toString());
    }
    const onMessage = (ev: MessageEvent) => {
      if (ev.data && ev.data.type === "open") openFromUrl(String(ev.data.url || "/"));
    };
    navigator.serviceWorker?.addEventListener("message", onMessage);
    return () => navigator.serviceWorker?.removeEventListener("message", onMessage);
  }, []);

  // The number on the app icon: cards waiting for you.
  useEffect(() => {
    if (state.loaded) setAppBadge(state.pendingApprovals.length);
  }, [state.loaded, state.pendingApprovals.length]);

  const value = useMemo<StoreValue>(
    () => ({
      state,
      dispatch,
      send: async (thread, text, files = []) => {
        if (!text.trim() && files.length === 0) return;
        try {
          // attachments go over REST so a failure (a path gone, a full disk) comes back as an error
          if (files.length > 0 || !wsRef.current?.send({ kind: "send", thread, text })) {
            await api.send(thread, text, files);
          }
        } catch (e) {
          if (e instanceof AuthError) dispatch({ type: "authError" });
          throw e;
        }
      },
      decide: async (id, approved, scope = "once") => {
        try {
          if (!wsRef.current?.send({ kind: "approval", id, approved, scope })) {
            await api.decide(id, approved, scope);
          }
        } catch (e) {
          if (e instanceof AuthError) dispatch({ type: "authError" });
          throw e;
        }
      },
      loadEvents,
      refreshGoals,
      refreshSettings,
      refreshHub,
      setTab: (tab) => dispatch({ type: "tab", tab }),
      openThread: (thread) => dispatch({ type: "activeThread", thread }),
      markFeedSeen: (at) => dispatch({ type: "feedSeen", at }),
      openFile: (path) => dispatch({ type: "viewer", path }),
      draftFiles: (files) => dispatch({ type: "draftFiles", files }),
      draft: (text) => {
        dispatch({ type: "draft", text });
        if (text !== null) dispatch({ type: "tab", tab: "chat" });
      },
      dismissOnboarding: () => dispatch({ type: "onboardingDismissed" }),
      toast: (text) => dispatch({ type: "toast", toast: t(text) }),
    }),
    [state, loadEvents, refreshGoals, refreshSettings, refreshHub],
  );

  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useStore(): StoreValue {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error("useStore outside provider");
  return ctx;
}
