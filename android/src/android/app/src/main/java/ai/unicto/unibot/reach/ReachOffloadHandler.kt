package ai.unicto.unibot.reach

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.sandbox.NativeOffloadHandler
import ai.unicto.unibot.sandbox.NativeOffloadRequest
import ai.unicto.unibot.sandbox.NativeOffloadResult
import ai.unicto.unibot.sandbox.PRootKernel
import ai.unicto.unibot.guard.GateOutcome
import ai.unicto.unibot.guard.GuardKind
import ai.unicto.unibot.guard.RiskAssessment
import ai.unicto.unibot.guard.RiskClass
import ai.unicto.unibot.guard.RiskGate
import ai.unicto.unibot.guard.ShellGuard
import ai.unicto.unibot.media.MediaOffloadHandler
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `unibot-pc` — the phone drives the account's other devices (through the hub), from the sandbox shell:
 *
 *     unibot-pc status
 *     unibot-pc run "<command>" [--on <computer>] [--cwd <dir>] [--timeout <s>]
 *     unibot-pc ls [<path>] [--on <computer>]
 *     unibot-pc get <remote-path> [--name <file>] [--on <computer>]
 *     unibot-pc put <local-path> <remote-path> [--force] [--on <computer>]
 *     unibot-pc open <url> [--on <computer>]
 *     unibot-pc screen [--on <computer>]
 *
 * A command is judged by [ShellGuard] exactly like one for the phone's own shell and, when it
 * needs it, waits for the approval card — here, on the phone, before it is sent. Registered in
 * `UnibotApp` next to `unibot-hands`.
 */
class ReachOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = MediaOffloadHandler.Args(request.argv.drop(1))
        val sub = args.positional.firstOrNull()
        if (sub == null || sub == "help" || sub == "--help" || sub == "-h") return NativeOffloadResult(0, HELP)
        if (sub == "status" || sub == "devices") return ok(status())
        val computer = Computers.resolve(context, args.get("on"))
            ?: return refused(
                "no_computer",
                when {
                    Computers.all(context).isEmpty() && !ai.unicto.unibot.hub.Hub.isConnected ->
                        "No other device is connected. Computers join by running unibot Desktop signed in to the same unibot Cloud account; this phone is not signed in to unibot Cloud yet."
                    Computers.all(context).isEmpty() -> "No other device is online right now. Computers join by running unibot Desktop signed in to the same unibot Cloud account."
                    else -> "No connected device matches “${args.get("on")}”. Connected: " + Computers.all(context).joinToString(", ") { it.name } + "."
                },
            )
        return try {
            when (sub) {
                "run" -> run(args, computer, request.sessionId)
                "ls" -> ls(args, computer)
                "get" -> get(args, computer, request.sessionId)
                "put" -> put(args, computer, request.sessionId)
                "open" -> open(args, computer)
                "screen" -> screen(computer, request.sessionId)
                "notify" -> notify(args, computer)
                "task" -> task(args, computer, request.sessionId)
                else -> NativeOffloadResult(2, "unibot-pc: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Computers.ReachException) {
            failed(sub, computer, "${computer.name} answered ${e.code}: ${e.message}")
        } catch (e: java.io.IOException) {
            AppLogger.warning(TAG, "$sub on ${computer.name}: ${e.message}")
            failed(sub, computer, "${computer.name} does not answer: ${e.message ?: e.javaClass.simpleName}. Is unibot still running there?")
        }
    }

    private fun status(): JSONObject {
        val arr = JSONArray()
        Computers.all(context).forEach { c ->
            arr.put(
                JSONObject().put("name", c.name).put("kind", c.kind).put("os", c.os).put("via", "hub").put("address", c.address).put("reachable", c.online)
                    .put("last_seen", if (c.lastSeen > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(c.lastSeen)) else JSONObject.NULL),
            )
        }
        val hub = ai.unicto.unibot.hub.Hub
        return JSONObject().put("devices", arr).put("computers", arr).put("this_phone", hub.name(context)).put("hub", hub.isConnected).put("settings", Computers.DEEP_LINK)
    }

    private fun notify(args: MediaOffloadHandler.Args, c: Computers.Computer): NativeOffloadResult {
        val text = args.positional.drop(1).joinToString(" ").trim()
        if (text.isEmpty()) return NativeOffloadResult(2, "unibot-pc notify: text is required\n")
        val r = Computers.notify(c, text, args.get("title"))
        return ok(JSONObject().put("ok", r.optBoolean("ok", true)).put("computer", c.name).put("shown", r.optBoolean("shown", true)).apply { if (r.has("message")) put("message", r.optString("message")) })
    }

    /**
     * A whole task for the Muse on the other device. Its approval questions come back as
     * `approval` events and go through this phone's own card — [RiskGate] with the device's name
     * on it — so the person decides here, where they are, and the answer is relayed back.
     */
    private fun task(args: MediaOffloadHandler.Args, c: Computers.Computer, sessionId: String?): NativeOffloadResult {
        val text = args.positional.drop(1).joinToString(" ").trim()
        if (text.isEmpty()) return NativeOffloadResult(2, "unibot-pc task: say what to do, in words\n")
        val steps = JSONArray()
        val r = Computers.task(context, c, text) { ev ->
            when (ev.optString("stage")) {
                "approval" -> {
                    val id = ev.optString("approval_id")
                    val preview = ev.optString("preview").ifBlank { ev.optString("summary") }
                    val assessment = RiskAssessment(riskClass(ev.optString("risk")), ev.optString("reason").ifBlank { "asked by ${c.name}" }, "task:" + ev.optString("action").ifBlank { preview.take(60) })
                    val gate = approve(sessionId, c, assessment, "${c.name}: $preview")
                    Computers.approve(c, id, allow = gate.denied == null)
                    steps.put(JSONObject().put("approval", preview).put("allowed", gate.denied == null).apply { gate.notice?.let { put("notice", it) } })
                }
                // unibot: the runtime's progress stages (docs/hub.md) — a tool starting, a tool
                // done, a line of interim text; the terminal binary's older "step"/"thinking" too
                "tool" -> (ev.optString("summary").ifBlank { ev.optString("name") }.ifBlank { ev.optString("text") }.ifBlank { ev.optString("tool") })
                    .takeIf { it.isNotBlank() }?.let { steps.put(JSONObject().put("step", it.take(200))) }
                "tool_result" -> {
                    val name = ev.optString("name")
                    val summary = ev.optString("summary")
                    val line = (if (ev.optBoolean("ok", true)) "" else "failed: ") + listOf(name, summary).filter { it.isNotBlank() }.joinToString(" — ")
                    if (line.isNotBlank()) steps.put(JSONObject().put("done", line.take(200)))
                }
                "approval_result" -> ev.optString("status").takeIf { it.isNotBlank() }?.let { steps.put(JSONObject().put("approval_result", it)) }
                "step", "thinking", "text" -> ev.optString("text").ifBlank { ev.optString("tool") }.takeIf { it.isNotBlank() }?.let { steps.put(JSONObject().put("step", it.take(200))) }
            }
        }
        return ok(
            JSONObject().put("ok", true).put("computer", c.name).put("answer", r.optString("text"))
                .put("steps", steps).put("conversation", r.optString("conversation"))
                .put("tell_user", "Relay ${c.name}'s answer to the user in their language, as coming from ${c.name}."),
        )
    }

    private fun riskClass(risk: String): RiskClass = when (risk.lowercase()) {
        "destructive", "delete" -> RiskClass.DESTRUCTIVE
        "outbound", "send", "network" -> RiskClass.OUTBOUND
        "money", "payment" -> RiskClass.MONEY
        "install" -> RiskClass.INSTALL
        else -> RiskClass.DESTRUCTIVE // "system" and anything unnamed: ask, with the reason on the card
    }

    private fun run(args: MediaOffloadHandler.Args, c: Computers.Computer, sessionId: String?): NativeOffloadResult {
        val command = args.positional.drop(1).joinToString(" ").trim()
        if (command.isEmpty()) return NativeOffloadResult(2, "unibot-pc run: a command is required\n")
        val timeout = args.get("timeout")?.toIntOrNull()?.coerceIn(1, 900) ?: 120
        val gate = approve(sessionId, c, ShellGuard.assess(command), "on ${c.name}: $command")
        gate.denied?.let { return denied(it) }
        val r = Computers.shell(c, command, args.get("cwd"), timeout)
        val exit = r.optInt("exit_code", 1)
        val body = JSONObject()
            .put("ok", exit == 0).put("computer", c.name).put("exit_code", exit)
            .put("stdout", r.optString("stdout")).put("stderr", r.optString("stderr"))
            .put("timed_out", r.optBoolean("timed_out")).put("duration_ms", r.optInt("duration_ms"))
        gate.notice?.let { body.put("notice", it) }
        return NativeOffloadResult(if (exit == 0) 0 else 1, body.toString(2) + "\n")
    }

    private fun ls(args: MediaOffloadHandler.Args, c: Computers.Computer): NativeOffloadResult {
        val r = Computers.files(c, args.positional.getOrNull(1))
        return ok(JSONObject().put("ok", true).put("computer", c.name).put("path", r.optString("path")).put("entries", r.optJSONArray("entries") ?: JSONArray()))
    }

    private fun get(args: MediaOffloadHandler.Args, c: Computers.Computer, sessionId: String?): NativeOffloadResult {
        val remote = args.positional.getOrNull(1) ?: return NativeOffloadResult(2, "unibot-pc get: a remote path is required\n")
        val name = (args.get("name") ?: remote.substringAfterLast('/').substringAfterLast('\\')).ifBlank { "file" }.replace(Regex("[/\\\\]"), "_")
        val dir = attachmentsDir(sessionId) ?: return failed("get", c, "cannot write to /var/minis/attachments")
        val dest = File(dir, name)
        val bytes = Computers.getFile(c, remote, dest)
        val body = JSONObject().put("ok", true).put("computer", c.name).put("path", "/var/minis/attachments/$name").put("bytes", bytes)
        if (Regex("(?i)\\.(png|jpe?g|gif|webp)$").containsMatchIn(name)) body.put("markdown", "![${name}](minis://attachments/$name)")
        return ok(body)
    }

    private fun put(args: MediaOffloadHandler.Args, c: Computers.Computer, sessionId: String?): NativeOffloadResult {
        val local = args.positional.getOrNull(1) ?: return NativeOffloadResult(2, "unibot-pc put: a local path and a remote path are required\n")
        val remote = args.positional.getOrNull(2) ?: return NativeOffloadResult(2, "unibot-pc put: a remote path is required\n")
        val file = resolveLocal(local, sessionId) ?: return NativeOffloadResult(2, "unibot-pc put: cannot read '$local'\n")
        // Never overwrite quietly: an existing file needs --force, and --force needs the card.
        val exists = try { Computers.files(c, remote); true } catch (e: Computers.ReachException) { if (e.code == 404) false else throw e }
        var notice: String? = null
        if (exists) {
            if (args.get("force") != "true") return refused("exists", "$remote already exists on ${c.name}; add --force to replace it.")
            val gate = approve(sessionId, c, RiskAssessment(RiskClass.DESTRUCTIVE, "replaces $remote", remote), "replace $remote on ${c.name}")
            gate.denied?.let { return denied(it) }
            notice = gate.notice
        }
        val r = Computers.putFile(c, file, remote)
        return ok(JSONObject().put("ok", true).put("computer", c.name).put("path", r.optString("path")).put("bytes", r.optLong("bytes")).apply { notice?.let { put("notice", it) } })
    }

    private fun open(args: MediaOffloadHandler.Args, c: Computers.Computer): NativeOffloadResult {
        val url = args.positional.getOrNull(1)?.trim() ?: return NativeOffloadResult(2, "unibot-pc open: a URL is required\n")
        val r = Computers.open(c, url)
        return ok(JSONObject().put("ok", r.optBoolean("ok")).put("computer", c.name).put("url", url))
    }

    private fun screen(c: Computers.Computer, sessionId: String?): NativeOffloadResult {
        val shot = Computers.screen(c) ?: return refused("no_screen", "${c.name} cannot take a screenshot; `pip install mss pillow` there helps.")
        val ext = if (shot.second.contains("jpeg", true)) "jpg" else "png"
        val name = "pc-screen-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".$ext"
        val dir = attachmentsDir(sessionId) ?: return failed("screen", c, "cannot write to /var/minis/attachments")
        dir.mkdirs()
        File(dir, name).writeBytes(shot.first)
        return ok(
            JSONObject().put("ok", true).put("computer", c.name).put("path", "/var/minis/attachments/$name")
                .put("markdown", "![${c.name}](minis://attachments/$name)").put("bytes", shot.first.size),
        )
    }

    // ── approval ───────────────────────────────────────────────────────────

    /** [denied] is null when the action may go ahead; [notice] is for the model (a remembered approval). */
    private data class Gate(val denied: String? = null, val notice: String? = null)

    /** The card for an action on the computer. Grants are kept apart from the phone's. */
    private fun approve(sessionId: String?, c: Computers.Computer, assessment: RiskAssessment, preview: String): Gate {
        val scoped = assessment.copy(target = assessment.target?.let { "pc:$it" })
        val outcome = runBlocking { RiskGate.check(sessionId ?: "pc", GuardKind.COMPUTER, scoped, preview, pageUrl = c.name) }
        return when (outcome) {
            is GateOutcome.Allowed -> Gate(notice = outcome.notice)
            is GateOutcome.Denied -> Gate(denied = outcome.message)
        }
    }

    // ── files ──────────────────────────────────────────────────────────────

    private fun attachmentsDir(sessionId: String?): File? =
        if (sessionId != null) File(context.filesDir, "minis-sessions/$sessionId/attachments").apply { mkdirs() }
        else PRootKernel.resolveHostPath("/var/minis/attachments")?.apply { mkdirs() }

    private fun resolveLocal(path: String, sessionId: String?): File? {
        val linux = when {
            path.startsWith("minis://attachments/") -> "/var/minis/attachments/" + path.removePrefix("minis://attachments/")
            path.startsWith("minis://") -> path.removePrefix("minis://")
            else -> path
        }
        val file = when {
            linux.startsWith("/var/minis/attachments/") && sessionId != null ->
                File(context.filesDir, "minis-sessions/$sessionId/attachments/" + linux.removePrefix("/var/minis/attachments/"))
            sessionId != null -> PRootKernel.resolveSessionHostPath(sessionId, linux, context)
            else -> PRootKernel.resolveHostPath(linux)
        } ?: File(linux)
        return file.takeIf { it.isFile } ?: File(linux).takeIf { it.isFile }
    }

    // ── results ────────────────────────────────────────────────────────────

    private fun ok(body: JSONObject): NativeOffloadResult = NativeOffloadResult(0, body.toString(2) + "\n")

    private fun denied(message: String): NativeOffloadResult = NativeOffloadResult(
        4,
        JSONObject().put("ok", false).put("error", "denied").put("message", message)
            .put("tell_user", "The user did not allow it. Say so in one line and do not try another way.").toString(2) + "\n",
    )

    private fun refused(error: String, message: String): NativeOffloadResult = NativeOffloadResult(
        3,
        JSONObject().put("ok", false).put("error", error).put("message", message)
            .put("tell_user", "Tell the user in one line, in their language; the account's devices are at ${Computers.DEEP_LINK}.").toString(2) + "\n",
    )

    private fun failed(kind: String, c: Computers.Computer, message: String): NativeOffloadResult = NativeOffloadResult(
        1,
        JSONObject().put("ok", false).put("error", "${kind}_failed").put("computer", c.name).put("message", message)
            .put("tell_user", "Tell the user what went wrong in their language; the computers are at ${Computers.DEEP_LINK}.").toString(2) + "\n",
    )

    companion object {
        private const val TAG = "unibot-pc"
        const val HELP = """unibot-pc — this phone drives the user's other devices: a computer's shell, files, browser and screen, or a whole task for the Muse there

Usage:
  unibot-pc devices                                   what is connected (computers and phones, any network)
  unibot-pc run "<command>" [--on <device>] [--cwd <dir>] [--timeout <s>]
  unibot-pc ls [<path>] [--on <device>]
  unibot-pc get <remote-path> [--name <file>] [--on <device>]
  unibot-pc put <local-path> <remote-path> [--force] [--on <device>]
  unibot-pc open <url> [--on <device>]
  unibot-pc screen [--on <device>]
  unibot-pc notify "<text>" [--title <t>] [--on <device>]
  unibot-pc task "<what to do, in words>" --on <device>   the Muse on that device does it and answers

Commands are judged like the phone's own shell and wait for the approval card on this phone before they are sent;
a task's own approvals show here too. Devices join by signing in to the same unibot Cloud account (any network).
Exit codes: 0 ok · 1 failed · 2 usage · 3 no device / refused · 4 the user said no.
"""
    }
}
