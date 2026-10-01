import { Cloud, Loader2, LogOut } from "lucide-react";
import { useState } from "react";
import { api } from "../api";
import { useT } from "../i18n";
import { useStore } from "../store";
import type { CloudAccount } from "../types";
import { cx } from "../util";
import { Card, inputCls, primaryBtn, secondaryBtn } from "./Form";
import { identifierInputMode } from "./SignIn";

/**
 * unibot Cloud: the free account that gives every device of yours one place to meet
 * (the hub) and a model to start with (the relay). Sign in with a phone number or an
 * e-mail address and a code; the key lands in the vault on this machine.
 */
export function CloudCard({
  account,
  onChange,
  compact = false,
  /** after signing in, make the relay the model provider (first-run setup) */
  useAsModel = false,
}: {
  account: CloudAccount | null;
  onChange: () => void;
  compact?: boolean;
  useAsModel?: boolean;
}) {
  const { toast } = useStore();
  const t = useT();
  const [open, setOpen] = useState(compact || !account?.signed_in);
  const [identifier, setIdentifier] = useState("");
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState<"code" | "verify" | "model" | "out" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const signedIn = !!account?.signed_in;

  const sendCode = async () => {
    const id = identifier.trim();
    if (!id) return;
    setBusy("code");
    setError(null);
    try {
      await api.cloudCode(id);
      setSent(true);
    } catch (e) {
      setError(t((e as Error).message));
    } finally {
      setBusy(null);
    }
  };

  const verify = async () => {
    const id = identifier.trim();
    if (!id || code.trim().length < 4) return;
    setBusy("verify");
    setError(null);
    try {
      await api.cloudVerify(id, code.trim());
      if (useAsModel) {
        setBusy("model");
        await api.cloudUseAsModel();
      }
      setCode("");
      setSent(false);
      toast(t("Signed in to unibot Cloud."));
      onChange();
    } catch (e) {
      setError(t((e as Error).message));
    } finally {
      setBusy(null);
    }
  };

  const makeModel = async () => {
    setBusy("model");
    setError(null);
    try {
      await api.cloudUseAsModel();
      toast(t("The Cloud model is in use."));
      onChange();
    } catch (e) {
      setError(t((e as Error).message));
    } finally {
      setBusy(null);
    }
  };

  const signOut = async () => {
    if (!window.confirm(t("Sign out of unibot Cloud on this device? The hub and the Cloud model stop working here until you sign in again."))) return;
    setBusy("out");
    try {
      await api.cloudSignOut();
      onChange();
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const status = signedIn ? { text: t("Signed in"), tone: "ok" } : { text: t("Not signed in"), tone: "off" };
  const summary = signedIn
    ? account?.is_model
      ? t("{hint} · model and hub", { hint: account.hint })
      : t("{hint} · hub", { hint: account?.hint ?? "" })
    : t("Free. One account for all your devices, and a model to start with.");

  return (
    <Card
      icon={<Cloud size={20} />}
      title={t("unibot Cloud")}
      summary={summary}
      status={status}
      open={open}
      onToggle={compact ? undefined : () => setOpen((o) => !o)}
    >
      {signedIn ? (
        <div className="space-y-3">
          <p className="text-[13px] text-muted leading-relaxed">
            {t("Signed in as {hint}. Your devices meet here; the Cloud model comes with a free allowance.", { hint: account?.hint ?? "" })}
          </p>
          <div className="flex flex-wrap gap-2">
            {!account?.is_model && (
              <button type="button" disabled={busy !== null} onClick={() => void makeModel()} className={primaryBtn}>
                {busy === "model" ? <Loader2 size={15} className="animate-spin" /> : null} {t("Use the Cloud model")}
              </button>
            )}
            <button type="button" disabled={busy !== null} onClick={() => void signOut()} className={secondaryBtn}>
              <LogOut size={15} /> {t("Sign out")}
            </button>
          </div>
          {error && <ErrorLine text={error} />}
        </div>
      ) : (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            void (sent ? verify() : sendCode());
          }}
        >
          <p className="text-[13px] text-muted leading-relaxed">
            {t("A code goes to your phone or inbox; the key stays in the vault on this machine.")}
          </p>
          <div>
            <label className="text-[12px] text-muted">{t("Mainland China phone number or e-mail")}</label>
            <input
              value={identifier}
              onChange={(e) => {
                setIdentifier(e.target.value);
                setSent(false);
              }}
              inputMode={identifierInputMode(identifier)}
              autoComplete="username"
              placeholder={t("138 0000 0000 or you@example.com")}
              className={cx(inputCls, "mt-1")}
            />
          </div>
          {sent && (
            <div>
              <label className="text-[12px] text-muted">{t("The code you received")}</label>
              <input
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 8))}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="123456"
                autoFocus
                className={cx(inputCls, "mt-1 tracking-[0.3em]")}
              />
            </div>
          )}
          {error && <ErrorLine text={error} />}
          <div className="flex gap-2">
            {sent ? (
              <>
                <button type="button" disabled={busy !== null} onClick={() => void sendCode()} className={secondaryBtn}>
                  {t("Send again")}
                </button>
                <button type="submit" disabled={busy !== null || code.trim().length < 4} className={cx(primaryBtn, "flex-1")}>
                  {busy ? <Loader2 size={15} className="animate-spin" /> : null} {useAsModel ? t("Sign in and use its model") : t("Sign in")}
                </button>
              </>
            ) : (
              <button type="submit" disabled={busy !== null || !identifier.trim()} className={cx(primaryBtn, "flex-1")}>
                {busy === "code" ? <Loader2 size={15} className="animate-spin" /> : null} {t("Send me a code")}
              </button>
            )}
          </div>
        </form>
      )}
    </Card>
  );
}

function ErrorLine({ text }: { text: string }) {
  return <div className="rounded-2xl bg-rose-500/12 px-3 py-2 text-[12.5px] text-rose-700 dark:text-rose-300">{text}</div>;
}
