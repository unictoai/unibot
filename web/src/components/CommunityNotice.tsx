import { Bug, ExternalLink, Github, HeartHandshake } from "lucide-react";
import { useT } from "../i18n";
import { cx } from "../util";

export const REPO_URL = "https://github.com/unictoai/unibot";
export const ISSUES_URL = "https://github.com/unictoai/unibot/issues/new/choose";
/** The notice on the site, with the whole story: who pays, what is kept, how to help. */
export const NOTICE_URL = "https://github.com/unictoai/unibot#readme";

/**
 * The community notice, said plainly wherever the project is introduced: free, open source,
 * non-profit; what the allowance is and who pays; what is kept; and the invitation to report
 * bugs, ask for features and send fixes — with the repository one click away. The title opens
 * the same notice on the site.
 */
export function CommunityNotice({ compact = false, className }: { compact?: boolean; className?: string }) {
  const t = useT();
  return (
    <section className={cx("rounded-[22px] border border-accent/25 bg-accent/[0.06] p-4 dark:bg-accent/[0.10]", className)}>
      <a href={NOTICE_URL} target="_blank" rel="noreferrer" className="flex items-start gap-2 text-[13.5px] font-semibold hover:text-accent">
        <HeartHandshake size={16} className="mt-0.5 shrink-0 text-accent" />
        <span className="min-w-0 flex-1 leading-snug">{t("Free, open source, non-profit — open source, built together: a personal agent for all")}</span>
        <ExternalLink size={12} className="mt-1 shrink-0 text-muted" />
      </a>
      <p className={cx("mt-1.5 leading-relaxed text-fg/85", compact ? "text-[12.5px]" : "text-[13px]")}>
        {t(
          "unibot is a non-profit open-source community project — free, forever. Sign in with a phone number or an e-mail and the model comes with a free allowance, paid by the developer; when it is gone, use your own key (Alibaba Cloud Bailian is a good start). Messages are not stored unless you choose to contribute them; nothing is sold. Delete the account whenever you like.",
        )}
      </p>
      <p className={cx("mt-1.5 leading-relaxed text-fg/85", compact ? "text-[12.5px]" : "text-[13px]")}>
        {t("Report a bug, ask for a feature, send a pull request — every one brings a personal agent within everyone's reach.")}
      </p>
      <div className="mt-3 flex flex-wrap gap-2 text-[12.5px] font-medium">
        <a href={REPO_URL} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 rounded-full bg-fg px-3 py-1.5 text-bg hover:opacity-90">
          <Github size={13} /> {t("Star on GitHub")}
        </a>
        <a href={ISSUES_URL} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 rounded-full border border-border bg-surface px-3 py-1.5 text-fg hover:bg-surface-2">
          <Bug size={13} /> {t("Report a bug or ask for a feature")}
        </a>
      </div>
    </section>
  );
}
