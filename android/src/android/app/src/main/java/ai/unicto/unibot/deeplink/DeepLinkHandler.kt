package ai.unicto.unibot.deeplink

import android.net.Uri
import android.util.Log
import ai.unicto.unibot.privacy.UrlTokenAudit
import ai.unicto.unibot.ui.navigation.Routes

/**
 * Parses unibot:// deep link URIs into navigation actions.
 *
 * Supported routes (matching iOS):
 *   unibot://share                              → open share flow
 *   unibot://views/alarm                        → open alarm list
 *   unibot://open_terminal?init_command=...      → open terminal with command
 *   unibot://session/<id>                        → open specific session
 *   unibot://settings                            → Settings home
 *   unibot://settings/providers                  → Provider list
 *   unibot://settings/providers/<instanceId>     → Provider detail
 *   unibot://settings/providers/add[?preset=bailian] → Add a provider (unibot: pre-filled)
 *   unibot://settings/model-groups               → Model Groups (incl. Agent Loop section)
 *   unibot://settings/model-groups/<groupId>     → Model Group detail
 *   unibot://settings/usage                      → Token usage
 *   unibot://settings/skills                     → Skills management
 *   unibot://settings/background_agents         → Background agents
 *   unibot://settings/question_cards             → Question cards
 *   unibot://settings/slash_commands             → Slash commands
 *   unibot://settings/portable_profile           → Portable profile
 *   unibot://settings/memory                     → Memory management
 *   unibot://settings/storage                    → Storage management
 *   unibot://settings/mount-external             → Mount External Folders list
 *   unibot://settings/mounts                     → alias for mount-external
 *   unibot://settings/shared-folders             → Shared Folders list (T235)
 *   unibot://settings/shared_folders             → alias for shared-folders
 *   unibot://settings/logs                       → Log management
 *   unibot://settings/appearance                 → Appearance
 *   unibot://settings/background                 → Background settings
 *   unibot://settings/about                      → About
 *   unibot://settings/permissions                → Permissions
 *   unibot://settings/environments[?create_key=...&create_value=...&create_note=...]
 *                                               → Environment variables
 *   unibot://settings/rootfs                     → Rootfs management (mirror config lives here)
 *   unibot://settings/mirrors                    → alias for rootfs (mirrors live inside Rootfs UI)
 *
 * Unknown settings paths fall back to Settings home rather than
 * Unknown — matches iOS's "best-effort land somewhere reasonable"
 * behavior so an LLM-generated link can never strand the user.
 *
 * Resource-class URIs (`unibot://workspace/...`, `unibot://skills/...`,
 * etc.) are intentionally NOT handled here — they resolve to on-disk
 * files and go through `ChatLinkResolver` at the chat-view layer. This
 * parser only handles *navigation* targets that change the app's top-
 * level screen.
 */
sealed class DeepLinkAction {
    data object OpenShare : DeepLinkAction()
    data object OpenAlarmList : DeepLinkAction()
    data class OpenTerminal(val initCommand: String?) : DeepLinkAction()
    data class CreateEnvironmentVariable(val key: String, val value: String, val note: String) : DeepLinkAction()
    data object OpenPermissionSettings : DeepLinkAction()
    data class OpenSession(val sessionId: String) : DeepLinkAction()
    /**
     * v1.4.0 item 11 — open the swarm space. Posted by
     * SwarmCompletionNotifier so tapping a mission-complete notification
     * lands on the report.
     */
    data object OpenSwarm : DeepLinkAction()

    /**
     * App-icon long-press quick actions (mirrors iOS QuickActionRouter).
     *
     *  - [NewChat] — plain new draft chat
     *  - [NewVoiceChat] — new chat + auto-trigger voice input mic on first compose
     *  - [NewCameraChat] — new chat + auto-launch camera attachment on first compose
     *
     * Encoded as `unibot://action/<name>` so the static shortcuts XML can
     * point to them via plain Intent.data without any custom extras —
     * matches the existing unibot:// deep-link conventions.
     */
    data object NewChat : DeepLinkAction()
    data object NewVoiceChat : DeepLinkAction()
    data object NewCameraChat : DeepLinkAction()

    /**
     * v0.2.0 P3 — quick-ask entry: `unibot://ask?text=...`.
     * Opens the home shell's main chat with [text] prefilled in the
     * composer (via HomeBus, same path as the profile page's pre-typed
     * request). Used by the Quick-ask home-screen widget and the
     * `ai.unicto.unibot.ASK` Tasker broadcast.
     *
     * v1.4.0 item 73 — `&new=1`: open a FRESH draft chat with [text]
     * prefilled instead of reusing the main chat, so a question asked from
     * the widget never pollutes the main conversation. Defaults to false,
     * so every existing caller keeps the old behaviour.
     */
    data class Ask(val text: String, val newChat: Boolean = false) : DeepLinkAction()
    /**
     * Item 35: custom-scheme OAuth redirect target for the Outlook
     * connector. Carries no navigation — MainActivity resumes the pending
     * [ai.unicto.unibot.connectors.outlook.OutlookOAuth] authorize call.
     */
    data class OutlookOAuthCallback(val code: String, val state: String?) : DeepLinkAction()

    /**
     * T183: any settings screen reachable by route string. Extends the
     * sealed family so consumers don't have to learn 14 new case types
     * — they `safeNavigate(action.route)` and the route table in
     * [ai.unicto.unibot.ui.navigation.Routes] does the rest. Routes
     * with arguments (provider detail, model-group detail) carry the
     * already-formatted route ("provider/<id>", "model_group/<id>").
     */
    data class OpenSettingsScreen(val route: String) : DeepLinkAction()

    /**
     * Launch the in-chat HTML preview, fullscreen, for a pinned home-screen
     * shortcut.
     *
     * Encoded as `unibot://session/<sessionId>/<resource-path>` —
     * [sessionId] selects which chat to land in; [resourcePath] is the
     * resource path under `/var/minis/` (e.g. `/browser/snake.html`).
     * [title] is the cached page title at pin time, used as the fallback
     * while WebView re-reports its own.
     */
    data class OpenHtmlPreview(
        val sessionId: String,
        val resourcePath: String,
        val title: String,
    ) : DeepLinkAction()

    data object Unknown : DeepLinkAction()
}

object DeepLinkHandler {
    /**
     * v1.4.0 item 73 — pure predicate for the `unibot://ask` `new` query
     * parameter, kept separate so it stays unit-testable on the JVM
     * (android.net.Uri is unavailable in unit tests).
     */
    fun isNewChatParam(value: String?): Boolean =
        value?.trim()?.lowercase() == "1" || value?.trim()?.lowercase() == "true"

    fun parse(uri: Uri?): DeepLinkAction {
        // Accept the legacy minis:// scheme too: old chats may still contain
        // minis:// links. Everything generated is unibot://.
        if (uri == null || (uri.scheme != "unibot" && uri.scheme != "minis")) {
            return DeepLinkAction.Unknown
        }
        // Privacy item 64 — audit the incoming deep link for credential-like
        // parameters. Parameter NAMES are logged at warning level; values
        // never are. The link itself is left intact: first-party flows (e.g.
        // environments?create_key=…) intentionally carry one-time values,
        // and redacting them here would break the flow the user just tapped.
        val leaks = UrlTokenAudit.auditDeepLink(uri.toString())
        if (leaks.isNotEmpty()) {
            Log.w(
                "DeepLinkAudit",
                "incoming deep link carries credential-like parameter(s): " +
                    leaks.joinToString() + " — values never logged",
            )
        }
        val host = uri.host ?: return DeepLinkAction.Unknown
        val path = uri.path.orEmpty()

        return when (host) {
            "share" -> DeepLinkAction.OpenShare
            // Item 35: custom-scheme OAuth redirect for the Outlook
            // connector (`unibot://oauth/outlook?code=…&state=…`), used when
            // the user configured a unibot:// redirect URI in the Azure app
            // slot instead of the default loopback addresses.
            "oauth" -> when (path.removePrefix("/")) {
                "outlook" -> DeepLinkAction.OutlookOAuthCallback(
                    code = uri.getQueryParameter("code").orEmpty(),
                    state = uri.getQueryParameter("state"),
                )
                else -> DeepLinkAction.Unknown
            }
            // v1.4.0 item 11 — `unibot://swarm` opens the swarm space
            // (mission-complete notification tap target).
            "swarm" -> DeepLinkAction.OpenSwarm
            "views" -> when (path) {
                "/alarm" -> DeepLinkAction.OpenAlarmList
                else -> DeepLinkAction.Unknown
            }
            "open_terminal" -> DeepLinkAction.OpenTerminal(
                initCommand = uri.getQueryParameter("init_command")
            )
            // Quick-actions surface (app-icon long-press). Path drives which
            // pending action ChatScreen consumes on first compose. Mirrors iOS
            // QuickActionRouter.swift action ids 1:1.
            "action" -> when (path.removePrefix("/")) {
                "new_chat" -> DeepLinkAction.NewChat
                "voice_chat" -> DeepLinkAction.NewVoiceChat
                "camera_chat" -> DeepLinkAction.NewCameraChat
                else -> DeepLinkAction.Unknown
            }
            // v0.2.0 P3: quick-ask — `unibot://ask?text=...` (widget / Tasker).
            // The text is prefilled, never auto-sent: the user reviews it
            // in the composer before anything runs.
            // v1.4.0 item 73: `&new=1` opens a fresh draft chat with the
            // text instead of prefilling the main chat.
            "ask" -> DeepLinkAction.Ask(
                text = uri.getQueryParameter("text").orEmpty(),
                newChat = isNewChatParam(uri.getQueryParameter("new")),
            )
            "settings" -> parseSettingsPath(uri)
            // Item 46 — one-tap "add to calendar" from any message:
            // `unibot://calendar/add?text=Dentist+tomorrow+3pm` opens a chat
            // with the quick-add prompt prefilled (never auto-sent — the
            // user reviews it in the composer, same contract as `ask`).
            "calendar" -> when (path.removePrefix("/")) {
                "add" -> {
                    val text = uri.getQueryParameter("text").orEmpty()
                    DeepLinkAction.Ask(
                        text = if (text.isBlank()) "" else "Add to calendar: $text",
                    )
                }
                else -> DeepLinkAction.Unknown
            }
            "session" -> {
                // unibot://session/<sessionId>                → OpenSession
                // unibot://session/<sessionId>/<resource-path> → OpenHtmlPreview
                val segments = path.removePrefix("/")
                    .split('/')
                    .filter { it.isNotEmpty() }
                val sid = segments.firstOrNull()
                when {
                    sid.isNullOrBlank() -> DeepLinkAction.Unknown
                    segments.size == 1 -> DeepLinkAction.OpenSession(sid)
                    else -> {
                        val resourcePath = "/" + segments.drop(1).joinToString("/")
                        val title = uri.getQueryParameter("title").orEmpty()
                        DeepLinkAction.OpenHtmlPreview(sid, resourcePath, title)
                    }
                }
            }
            else -> DeepLinkAction.Unknown
        }
    }

    /**
     * T183: walk a `unibot://settings/<path>` URI to the right NavHost
     * route. Two cases stay distinct:
     *
     *  - `environments?create_key=…` — keeps the dedicated
     *    [DeepLinkAction.CreateEnvironmentVariable] case because the
     *    target screen needs the parameters wired through
     *    `DeepLinkCoordinator.setPendingEnvVarCreate`.
     *  - `permissions` — keeps [DeepLinkAction.OpenPermissionSettings]
     *    so existing dispatch logic stays unchanged.
     *
     * Everything else funnels through [DeepLinkAction.OpenSettingsScreen]
     * carrying a route string from [Routes]. Unknown paths land on
     * Settings home rather than [DeepLinkAction.Unknown] so an LLM-
     * generated link can never strand the user; iOS does the same.
     */
    private fun parseSettingsPath(uri: Uri): DeepLinkAction {
        val path = uri.path?.trimStart('/').orEmpty()
        if (path.isEmpty()) return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val segments = path.split('/').filter { it.isNotEmpty() }
        val head = segments.firstOrNull() ?: return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val arg = segments.getOrNull(1)?.takeIf { it.isNotBlank() }

        return when (head) {
            "providers" ->
                if (arg == "add") DeepLinkAction.OpenSettingsScreen(Routes.addProvider(uri.getQueryParameter("preset"))) // unibot: pre-filled provider form
                else if (arg != null) DeepLinkAction.OpenSettingsScreen(Routes.providerDetail(arg))
                else DeepLinkAction.OpenSettingsScreen(Routes.PROVIDER_LIST)
            "model-groups", "model_groups" ->
                if (arg != null) DeepLinkAction.OpenSettingsScreen(Routes.modelGroupDetail(arg))
                else DeepLinkAction.OpenSettingsScreen(Routes.MODEL_GROUPS)
            "usage", "usage-stats", "usage_stats" ->
                DeepLinkAction.OpenSettingsScreen(Routes.USAGE_STATS)
            "skills" -> DeepLinkAction.OpenSettingsScreen(Routes.SKILLS)
            // v1.4.0 power-user & automation theme.
            "background-agents", "background_agents" ->
                DeepLinkAction.OpenSettingsScreen(Routes.BACKGROUND_AGENTS)
            "question-cards", "question_cards" ->
                DeepLinkAction.OpenSettingsScreen(Routes.QUESTION_CARDS)
            "slash-commands", "slash_commands" ->
                DeepLinkAction.OpenSettingsScreen(Routes.SLASH_COMMANDS)
            "portable-profile", "portable_profile" ->
                DeepLinkAction.OpenSettingsScreen(Routes.PORTABLE_PROFILE)
            "memory" -> DeepLinkAction.OpenSettingsScreen(Routes.MEMORY)
            "storage" -> DeepLinkAction.OpenSettingsScreen(Routes.STORAGE)
            "mount-external", "mount_external", "mounts", "mounted-folders", "mounted_folders" ->
                DeepLinkAction.OpenSettingsScreen(Routes.MOUNTED_FOLDERS)
            "shared-folders", "shared_folders" ->
                DeepLinkAction.OpenSettingsScreen(Routes.SHARED_FOLDERS)
            "logs" -> {
                // Optional ?tab=… selects the segmented control on the
                // Logs screen. Currently recognized: "logs" (default),
                // "config-audit". Pushed onto DeepLinkCoordinator so the
                // screen can read it on appear and clear. Mirrors iOS
                // DeepLinkRouter.handleSettings logs handling.
                DeepLinkCoordinator.setPendingLogsTab(uri.getQueryParameter("tab"))
                DeepLinkAction.OpenSettingsScreen(Routes.LOGS)
            }
            "appearance" -> DeepLinkAction.OpenSettingsScreen(Routes.APPEARANCE)
            "soul" -> DeepLinkAction.OpenSettingsScreen(Routes.SOUL) // unibot: the name pill links here
            "avatar", "face" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.avatar.ROUTE_AVATAR_STUDIO) // unibot: the avatar studio
            "media", "models" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.media.ROUTE_MEDIA_MODELS) // unibot: image & video models
            "hands", "screen" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.hands.ROUTE_HANDS) // unibot: the screen as a hand
            "computers", "pc" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.reach.ROUTE_COMPUTERS) // unibot: the account's computers
            "cloud", "devices" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.cloud.ROUTE_CLOUD_ACCOUNT) // unibot: the account and its devices on the hub
            "coding", "agents" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.coding.ROUTE_CODING) // unibot: the coding agents on the account's computers
            "profile" -> DeepLinkAction.OpenSettingsScreen(ai.unicto.unibot.ui.profile.ROUTE_AGENT_PROFILE) // unibot: the face links here
            "background" -> DeepLinkAction.OpenSettingsScreen(Routes.BACKGROUND)
            // v0.2.0 P3: the Tasks home-screen widget taps through here.
            // `?task=<id>` opens that routine's editor directly.
            "routines", "scheduled", "scheduled-tasks", "scheduled_tasks" -> {
                val taskId = uri.getQueryParameter("task")
                if (!taskId.isNullOrBlank()) {
                    DeepLinkAction.OpenSettingsScreen(Routes.scheduledTaskEdit(taskId))
                } else {
                    DeepLinkAction.OpenSettingsScreen(Routes.SCHEDULED_TASKS)
                }
            }
            "about" -> DeepLinkAction.OpenSettingsScreen(Routes.ABOUT)
            "permissions" -> DeepLinkAction.OpenPermissionSettings
            // mirrors live as a section inside Rootfs management — no
            // standalone destination, so route both /mirrors and /rootfs
            // there. The user lands on the same screen; mirror config is
            // visible as the "Mirrors" section inside.
            "mirrors", "rootfs", "rootfs-management", "rootfs_management" ->
                DeepLinkAction.OpenSettingsScreen(Routes.ROOTFS_MANAGEMENT)
            "environments" -> {
                // iOS parity (AIChatView.swift L1407-1411): only `create_key`
                // is required. Missing `create_value`/`create_note` default
                // to empty string so a link like
                //   unibot://settings/environments?create_key=GH_TOKEN&create_value=
                // (where `create_value=` is present-but-blank) still opens
                // the prefilled form. Without create_key, plain navigation
                // to the Env Vars list.
                val key = uri.getQueryParameter("create_key")
                if (!key.isNullOrEmpty()) {
                    DeepLinkAction.CreateEnvironmentVariable(
                        key = key,
                        value = uri.getQueryParameter("create_value") ?: "",
                        note = uri.getQueryParameter("create_note") ?: "",
                    )
                } else {
                    DeepLinkAction.OpenSettingsScreen(Routes.ENV_VARS)
                }
            }
            // Unknown path — land on Settings home rather than failing,
            // so the user can find what they wanted by browsing.
            else -> DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        }
    }
}
