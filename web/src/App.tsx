import { LayoutGrid, Lightbulb, Loader2, MessageCircle, Newspaper, SquareCheckBig, WifiOff } from "lucide-react";
import { lazy, Suspense, useEffect, useState, type ReactNode } from "react";
import { setToken } from "./api";
import { dragonUrl } from "./avatars";
import { Avatar } from "./components/Avatar";
import { DesktopRemoteHint } from "./components/DesktopRemoteHint";
import { FileViewer } from "./components/FileViewer";
import { FirstSignInSteps, useFirstSignIn } from "./components/FirstSignInSteps";
import { Sidebar } from "./components/Sidebar";
import { ChatScreen } from "./screens/ChatScreen";
import { FeedScreen } from "./screens/FeedScreen";
import { SignInGate } from "./screens/SignInGate";
import { useStore, type Tab } from "./store";
import { useT } from "./i18n";
import { cx } from "./util";

// The chat and the feed are what opens first; every other screen arrives as its own chunk
// the first time it is shown, so the first paint does not carry the provider form or the
// coding console.
const AccountScreen = lazy(() => import("./screens/AccountScreen").then((m) => ({ default: m.AccountScreen })));
const AvatarStudioScreen = lazy(() => import("./screens/AvatarStudioScreen").then((m) => ({ default: m.AvatarStudioScreen })));
const CodingScreen = lazy(() => import("./screens/CodingScreen").then((m) => ({ default: m.CodingScreen })));
const ConnectionsScreen = lazy(() => import("./screens/ConnectionsScreen").then((m) => ({ default: m.ConnectionsScreen })));
const DevicesScreen = lazy(() => import("./screens/DevicesScreen").then((m) => ({ default: m.DevicesScreen })));
const GoalsScreen = lazy(() => import("./screens/GoalsScreen").then((m) => ({ default: m.GoalsScreen })));
const IdeasScreen = lazy(() => import("./screens/IdeasScreen").then((m) => ({ default: m.IdeasScreen })));
const LibraryScreen = lazy(() => import("./screens/LibraryScreen").then((m) => ({ default: m.LibraryScreen })));
const MemoryScreen = lazy(() => import("./screens/MemoryScreen").then((m) => ({ default: m.MemoryScreen })));
const Onboarding = lazy(() => import("./screens/Onboarding").then((m) => ({ default: m.Onboarding })));
const SettingsScreen = lazy(() => import("./screens/SettingsScreen").then((m) => ({ default: m.SettingsScreen })));
const SkillsScreen = lazy(() => import("./screens/SkillsScreen").then((m) => ({ default: m.SkillsScreen })));

function Loading() {
  return (
    <div className="flex h-full min-h-[40vh] items-center justify-center text-muted">
      <Loader2 className="animate-spin" size={20} />
    </div>
  );
}

/** The tab bar: five icons in a floating pill, as in Muse. Memory and Settings are behind the avatar. */
const TABS: Array<{ id: Tab; label: string; icon: (active: boolean) => ReactNode }> = [
  { id: "chat", label: "Chat", icon: (a) => <MessageCircle size={22} strokeWidth={a ? 2.2 : 1.8} /> },
  { id: "feed", label: "Feed", icon: (a) => <Newspaper size={22} strokeWidth={a ? 2.2 : 1.8} /> },
  { id: "ideas", label: "Ideas", icon: (a) => <Lightbulb size={22} strokeWidth={a ? 2.2 : 1.8} /> },
  { id: "goals", label: "Goals", icon: (a) => <SquareCheckBig size={22} strokeWidth={a ? 2.2 : 1.8} /> },
  { id: "library", label: "Library", icon: (a) => <LayoutGrid size={22} strokeWidth={a ? 2.2 : 1.8} /> },
];

export default function App() {
  const { state, setTab, openFile } = useStore();
  const firstSignIn = useFirstSignIn();
  const t = useT();

  // The document title follows the agent's name.
  useEffect(() => {
    const name = state.profile?.name?.trim();
    document.title = name && name.toLowerCase() !== "unibot" ? `${name} · unibot` : "unibot";
  }, [state.profile?.name]);

  // `#devices` etc. opens a section straight away — the desktop tray menu links here.
  useEffect(() => {
    const jump = () => {
      const tab = window.location.hash.slice(1) as Tab;
      if (tab && TABS.some((x) => x.id === tab)) setTab(tab);
      else if (tab === "devices" || tab === "you" || tab === "memory" || tab === "connections" || tab === "skills" || tab === "account" || tab === "coding" || tab === "avatar") setTab(tab);
    };
    jump();
    window.addEventListener("hashchange", jump);
    return () => window.removeEventListener("hashchange", jump);
  }, [setTab]);

  // A freshly created account is owed two short steps (a password, the co-creation
  // programme); they float over whatever comes next — setup or the chat.
  const firstSteps = firstSignIn && state.hub?.account.signed_in ? <FirstSignInSteps /> : null;

  if (state.authError) return <TokenGate />;
  // Nothing has arrived from the runtime yet: say so, instead of an empty chat.
  if (!state.loaded) return <Connecting error={state.error} />;
  // The account comes first: this runtime asks for one (cloud.required) and none is signed in.
  if (state.hub && state.hub.account.required && !state.hub.account.signed_in) {
    return <SignInGate />;
  }
  // First run: the server has not seen setup finish and nothing has been said yet.
  if (state.settings && !state.settings.onboarded && !state.onboardingDismissed && !state.threads.some((t) => t.events > 0)) {
    return (
      <Suspense fallback={<Loading />}>
        <Onboarding />
        {firstSteps}
      </Suspense>
    );
  }

  const pendingApprovals = state.pendingApprovals.length;
  // a plan change waiting for your answer is worth a red badge; the count of goals is not
  const proposals = state.goals.filter((g) => g.proposal && g.status !== "cancelled").length;
  const feedUnseen = state.pendingApprovals.filter((a) => a.ts > state.feedSeenAt).length;

  // One column on the phone; on a wide screen (a full browser) and in the desktop app at any
  // width, a sidebar takes over from the tab bar and the chats sheet — same screens either side.
  return (
    <div className="mx-auto flex h-[100dvh] max-w-[760px] flex-col bg-bg sm:border-x sm:border-border wide:max-w-none wide:flex-row wide:border-x-0">
      <div className="hidden wide:block wide:h-full">
        <Sidebar />
      </div>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {!state.connected && state.loaded && (
          <div className="flex items-center justify-center gap-2 bg-amber-500/15 text-amber-700 dark:text-amber-300 text-[12.5px] py-1">
            <WifiOff size={14} /> {t("Reconnecting to your unibot…")}
          </div>
        )}
        <DesktopRemoteHint />
        <main className="mx-auto min-h-0 w-full flex-1 wide:max-w-[900px]">
          <Suspense fallback={<Loading />}>
            {state.tab === "chat" && <ChatScreen />}
            {state.tab === "feed" && <FeedScreen />}
            {state.tab === "ideas" && <IdeasScreen />}
            {state.tab === "goals" && <GoalsScreen />}
            {state.tab === "library" && <LibraryScreen />}
            {state.tab === "memory" && <MemoryScreen />}
            {state.tab === "skills" && <SkillsScreen />}
            {state.tab === "connections" && <ConnectionsScreen />}
            {state.tab === "devices" && <DevicesScreen />}
            {state.tab === "you" && <SettingsScreen />}
            {state.tab === "account" && <AccountScreen />}
            {state.tab === "coding" && <CodingScreen />}
            {state.tab === "avatar" && <AvatarStudioScreen />}
          </Suspense>
        </main>
        <nav className="safe-bottom shrink-0 bg-bg px-4 pb-2.5 pt-1.5 wide:hidden">
        <ul className="mx-auto flex w-fit items-center gap-1 rounded-full border border-border/70 bg-surface p-1.5 shadow-[0_6px_24px_-8px_rgba(0,0,0,0.18)]">
          {TABS.map((tab) => {
            const active = state.tab === tab.id;
            const badge = tab.id === "chat" ? pendingApprovals : tab.id === "feed" ? feedUnseen : tab.id === "goals" ? proposals : 0;
            return (
              <li key={tab.id}>
                <button
                  type="button"
                  onClick={() => setTab(tab.id)}
                  aria-label={t(tab.label)}
                  aria-current={active ? "page" : undefined}
                  className={cx(
                    "relative flex h-11 w-[52px] items-center justify-center rounded-full transition",
                    active ? "bg-surface-2 text-fg" : "text-fg/65 hover:text-fg",
                  )}
                >
                  {tab.icon(active)}
                  {badge > 0 && (
                    <span className="absolute right-2 top-1 flex h-[17px] min-w-[17px] items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white">
                      {badge}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      </nav>
      </div>
      <FileViewer path={state.viewer} onClose={() => openFile(null)} />
      {firstSteps}
      {state.toast && (
        <div className="pointer-events-none fixed inset-x-0 bottom-24 z-[70] flex justify-center px-4">
          <div className="rise rounded-2xl bg-fg text-bg px-4 py-2 text-[13.5px] shadow-lg max-w-sm text-center">{state.toast}</div>
        </div>
      )}
    </div>
  );
}

/** The first seconds: the socket is on its way, or the runtime cannot be reached at all. */
function Connecting({ error }: { error: string | null }) {
  const t = useT();
  return (
    <div className="mx-auto flex h-[100dvh] max-w-sm flex-col items-center justify-center px-6 text-center">
      <Avatar profile={null} size={72} still />
      {error ? (
        <>
          <p className="mt-6 text-[15px] font-medium">{t("Could not reach your unibot.")}</p>
          <p className="mt-1.5 text-[13px] text-muted">{t(error)}</p>
          <button type="button" onClick={() => window.location.reload()} className="mt-5 rounded-full bg-fg px-5 py-2 text-[14px] font-medium text-bg">
            {t("Try again")}
          </button>
        </>
      ) : (
        <p className="mt-6 flex items-center gap-2 text-[14px] text-muted">
          <Loader2 className="animate-spin" size={16} /> {t("Connecting to your unibot…")}
        </p>
      )}
    </div>
  );
}

function TokenGate() {
  const [value, setValue] = useState("");
  const t = useT();
  return (
    <div className="mx-auto flex h-[100dvh] max-w-md flex-col items-center justify-center px-6 text-center">
      <img src={dragonUrl("idle")} alt="" draggable={false} className="h-24 w-24 rounded-full bg-[#f1efeb] object-cover" />
      <h1 className="mt-4 text-[22px] font-bold">{t("Connect to your unibot")}</h1>
      <p className="mt-2 text-[14px] text-muted">
        {t("This app talks to the unibot server you run yourself. Scan the QR code printed by")}{" "}
        <code className="rounded bg-surface-2 px-1">unibot serve</code>{t(", or paste the access token below.")}
      </p>
      <form
        className="mt-5 w-full flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (!value.trim()) return;
          setToken(value.trim());
          window.location.reload();
        }}
      >
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          aria-label={t("Access token")}
          placeholder={t("Access token")}
          className="flex-1 rounded-2xl bg-surface-2 px-4 py-2.5 text-[15px] outline-none focus:ring-2 focus:ring-accent/40"
        />
        <button type="submit" className="rounded-2xl bg-accent text-accent-fg px-4 font-medium">
          {t("Connect")}
        </button>
      </form>
    </div>
  );
}
