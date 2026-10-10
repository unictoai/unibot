package ai.unicto.unibot.ui.settings

/**
 * Backlog item 99 — the in-app help center's bundled content.
 *
 * Fully offline: every article lives in this file, no network involved.
 * Articles are plain English text; keep answers short, honest, and
 * actionable. When behaviour changes, update the article here — there is no
 * remote copy to drift from.
 */
data class HelpArticle(
    val id: String,
    val title: String,
    val category: String,
    val body: String,
)

object HelpArticles {
    const val CAT_START = "Getting started"
    const val CAT_PROVIDERS = "Providers & keys"
    const val CAT_SWARM = "Swarm"
    const val CAT_PRIVACY = "Privacy"
    const val CAT_TROUBLE = "Troubleshooting"

    val all: List<HelpArticle> = listOf(
        HelpArticle(
            id = "what-is-unibot",
            title = "What is unibot?",
            category = CAT_START,
            body = "unibot is your personal AI agent on your phone. It chats with frontier " +
                "language models, runs research missions with a crew of agents (the swarm), " +
                "and connects to your Gmail, calendar, and files.\n\n" +
                "There is one big difference from other AI apps: you bring your own API " +
                "key. There is no unibot account, no subscription, and no middleman " +
                "marking up your usage — you pay your model provider directly, often " +
                "nothing at all on a free tier.",
        ),
        HelpArticle(
            id = "first-steps",
            title = "First steps after installing",
            category = CAT_START,
            body = "1. Add a provider key. The guided setup (Settings → Guided key setup) " +
                "walks you through it and checks the key before saving.\n\n" +
                "2. Pick a model from the chip above the composer and say hello.\n\n" +
                "3. Take the two-minute tour (Help center → Replay guided tour) to see " +
                "chat, providers, the swarm, and privacy in one pass.\n\n" +
                "No key yet? Several providers — Groq, Cerebras, Mistral, DeepSeek — " +
                "hand out free API keys with no card required.",
        ),
        HelpArticle(
            id = "switch-models",
            title = "Switching models mid-chat",
            category = CAT_START,
            body = "Tap the model chip above the composer at any time to switch models. " +
                "The conversation history is kept, so the new model sees everything " +
                "the old one did.\n\n" +
                "Different models have different strengths: use a fast cheap model for " +
                "drafts and a reasoning model when the answer has to be right.",
        ),
        HelpArticle(
            id = "byok-explained",
            title = "How bring-your-own-key works",
            category = CAT_PROVIDERS,
            body = "unibot never hosts models itself. When you add a provider, your API " +
                "key is stored encrypted on this phone, and every chat request goes " +
                "straight from your phone to that provider's API.\n\n" +
                "Billing, rate limits, and quotas are between you and the provider. " +
                "unibot cannot see your key, your chats, or your bill.",
        ),
        HelpArticle(
            id = "free-tier",
            title = "Which providers are free?",
            category = CAT_PROVIDERS,
            body = "These providers offer a free tier with no card required, and appear " +
                "first in the guided setup: Groq, Cerebras, Mistral, DeepSeek, Z.AI, " +
                "Nebius, Chutes, SambaNova, NVIDIA NIM, and GitHub Models.\n\n" +
                "Free tiers have daily limits. If a free key starts failing, check " +
                "the provider's dashboard — you have probably hit the day's quota.",
        ),
        HelpArticle(
            id = "key-rejected",
            title = "My key was rejected — what now?",
            category = CAT_PROVIDERS,
            body = "The guided setup checks your key before saving it. If it fails:\n\n" +
                "• Rejected / unauthorised: you pasted the wrong key, or only part of " +
                "it. Copy it again from the provider's dashboard — keys are long and " +
                "easy to truncate.\n\n" +
                "• Forbidden: the key is valid but the plan or region blocks this " +
                "endpoint. Check the provider's dashboard.\n\n" +
                "• Could not reach the provider: check your connection or VPN.\n\n" +
                "Never share your key with anyone, including in screenshots you post online.",
        ),
        HelpArticle(
            id = "multiple-providers",
            title = "Using several providers at once",
            category = CAT_PROVIDERS,
            body = "Add as many providers as you like from Settings → Providers. Each " +
                "chat picks one provider's model, and you can switch mid-chat.\n\n" +
                "A handy pattern: a free-tier provider for everyday chats, and a paid " +
                "frontier model for the questions that matter.",
        ),
        HelpArticle(
            id = "swarm-basics",
            title = "What the swarm does",
            category = CAT_SWARM,
            body = "The swarm is a crew of agents for work that is too big for one " +
                "chat turn: research, comparisons, deep dives. You write a mission; a " +
                "manager plans it, workers run the plan in parallel, and a verifier " +
                "checks the result before it is stitched together for you.\n\n" +
                "Open it from the Swarm button in the navigation drawer (below " +
                "Devices) or the pill at the top of chat.",
        ),
        HelpArticle(
            id = "swarm-missions",
            title = "Writing a good mission",
            category = CAT_SWARM,
            body = "Be specific about the outcome: \"Compare these three phones on " +
                "battery life and camera, with prices in PKR\" beats \"research " +
                "phones\".\n\n" +
                "The composer offers three presets — Research, Content, Deep dive — " +
                "or write a custom mission from scratch. You can pause, resume, or " +
                "cancel a mission at any time; progress is checkpointed, so killing " +
                "the app resumes as paused, not lost.",
        ),
        HelpArticle(
            id = "swarm-cost",
            title = "How much does a swarm mission cost?",
            category = CAT_SWARM,
            body = "Missions use your provider key, so they cost whatever your " +
                "provider charges for the tokens the crew uses — unibot adds " +
                "nothing. Every step shows its token cost, and the mission totals " +
                "it, so there are no surprises.\n\n" +
                "Tip: set a token budget per mission to stay inside free-tier " +
                "rate limits.",
        ),
        HelpArticle(
            id = "privacy-model",
            title = "Where does my data go?",
            category = CAT_PRIVACY,
            body = "To your model provider, and nowhere else. There is no unibot " +
                "account, no analytics pipeline, and no ads. Your chats live in the " +
                "app's database on this phone.\n\n" +
                "Sceptical? Good. Settings → Privacy → Traffic log shows every " +
                "network request the app makes, per chat, so you can verify it " +
                "yourself.",
        ),
        HelpArticle(
            id = "privacy-keys",
            title = "How are my API keys protected?",
            category = CAT_PRIVACY,
            body = "Keys are stored in the app's encrypted storage and only ever " +
                "sent to the provider they belong to — never to unibot servers " +
                "(there are none), never in URLs, never in crash logs.\n\n" +
                "Treat your key like a password: anyone holding it can spend your " +
                "quota. If a key leaks, revoke it in the provider's dashboard and " +
                "paste the replacement here.",
        ),
        HelpArticle(
            id = "privacy-controls",
            title = "Privacy controls worth knowing",
            category = CAT_PRIVACY,
            body = "• App lock: gate the app behind biometrics or PIN.\n\n" +
                "• Auto-delete: chats older than your chosen window are deleted on " +
                "launch.\n\n" +
                "• Incognito chat: a session that never touches disk.\n\n" +
                "• Permission audit: every permission, why it is needed, one-tap " +
                "revoke.\n\n" +
                "Find them under Settings → Privacy.",
        ),
        HelpArticle(
            id = "error-cards",
            title = "Understanding error cards",
            category = CAT_TROUBLE,
            body = "When a request fails you get a plain-language card, not raw " +
                "JSON:\n\n" +
                "• Invalid key → update the key in provider settings. Retry is " +
                "hidden on purpose — retrying a bad key never helps.\n\n" +
                "• Quota / rate limit → wait, or switch to another provider.\n\n" +
                "• Sign-in expired (OAuth) → sign in again; it is not an invalid " +
                "key.\n\n" +
                "Long-press any error to copy the raw detail for a bug report.",
        ),
        HelpArticle(
            id = "no-provider",
            title = "\"No provider configured\" — fixing it",
            category = CAT_TROUBLE,
            body = "unibot cannot chat without at least one provider key. Tap the " +
                "Set up button on the banner, or go to Settings → Guided key " +
                "setup — the wizard validates the key before saving, so you know " +
                "it works before you chat.",
        ),
        HelpArticle(
            id = "slow-or-stuck",
            title = "Replies are slow or stuck",
            category = CAT_TROUBLE,
            body = "1. Check your connection — the app talks to the provider directly.\n\n" +
                "2. The provider may be rate-limiting a free tier; wait a minute or " +
                "switch providers.\n\n" +
                "3. Very long chats slow down as context grows; start a fresh chat " +
                "for a new topic.\n\n" +
                "4. On a 4GB phone, keep parallel swarm workers low (1–3).",
        ),
        HelpArticle(
            id = "retired-models",
            title = "A model disappeared or stopped working",
            category = CAT_TROUBLE,
            body = "Providers retire model ids without warning. Settings → Check " +
                "for retired models compares the app's built-in model list against " +
                "the live provider catalog and flags ids that look retired.\n\n" +
                "Flagged ids are not deleted automatically — pick the provider's " +
                "current model in the chat model picker instead.",
        ),
        HelpArticle(
            id = "offline",
            title = "Using unibot offline",
            category = CAT_TROUBLE,
            body = "Without a connection, cloud models cannot answer — your phone " +
                "talks to the provider directly. Two offline options:\n\n" +
                "• On-device models (Settings → On-device models): download a small " +
                "model once, chat with zero network.\n\n" +
                "• The help center, your saved chats, and settings all work fully " +
                "offline.",
        ),
    )

    /** Case-insensitive search over titles, categories, and bodies. Pure; unit-tested. */
    fun search(query: String): List<HelpArticle> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return all
        return all.filter { a ->
            a.title.lowercase().contains(q) ||
                a.category.lowercase().contains(q) ||
                a.body.lowercase().contains(q)
        }
    }

    fun categories(): List<String> = all.map { it.category }.distinct()
}
