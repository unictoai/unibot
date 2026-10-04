package ai.unicto.unibot.crash

import android.util.Log
import java.io.File

/**
 * T283: JNI bridge to the NDK signal handler. Loaded lazily on first
 * `install()` so callers that never enable native crash capture don't
 * pay the dlopen cost.
 *
 * The native side (cpp/crash_handler.cpp) registers sigaction handlers
 * for SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGILL/SIGSYS. On a fatal signal it
 * writes a one-shot report to `<logsDir>/native-crash-<stamp>.log`
 * (same `.log` extension AppLogger.listLogFiles already filters for, so
 * reports show up in LogManagementScreen) and re-raises the signal so
 * the system tombstone is still produced.
 *
 * [T-android-v121-crash-self-diagnosing] The in-handler report can never
 * carry the abort reason: the "Abort message:" line and the scudo/libc
 * diagnostics live in logd's buffers, which a signal handler cannot safely
 * read (no fork/exec, no socket I/O under async-signal-safety rules). So
 * [install] also schedules [finalizePendingReports] on a background thread:
 * on the NEXT launch it finds every crash report not yet enriched, dumps
 * the relevant logcat lines (tags scudo / libc / DEBUG / abort / fatal,
 * plus this process's PID) and the tail of the app's own log into the
 * same file, and marks it finalized. The next crash report a user sends is
 * therefore self-diagnosing instead of telling them to go find logcat.
 */
object NativeCrashHandler {

    private const val TAG = "NativeCrashHandler"
    private const val FINALIZED_MARKER = "[finalized — logcat context appended]"
    @Volatile private var installed = false

    fun install(logsDir: File) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                System.loadLibrary("unibot_crash_handler")
            } catch (t: Throwable) {
                Log.w(TAG, "loadLibrary failed: ${t.message}")
                return
            }
            try {
                logsDir.mkdirs()
                nativeInstall(logsDir.absolutePath)
                installed = true
                Log.i(TAG, "installed; dir=${logsDir.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "nativeInstall failed: ${t.message}")
            }
        }
        // Enrich any crash reports left by a previous process death. Off the
        // calling thread — logcat -d can take a second on a cold device.
        Thread({ finalizePendingReports(logsDir) }, "crash-finalizer").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * [T-android-v122-ort-pinning] Runtime ORT version guard. The APK pins
     * VAD's ORT 1.20.0 at build time, but this is the last line of defense:
     * returns "OK <version>" when the loaded libonnxruntime.so serves
     * OrtApi v20, else "FAIL <reason>". Voice init paths call this BEFORE
     * touching the VAD/sherpa native libs so a version skew degrades to
     * "voice unavailable" instead of a SIGABRT. Never throws.
     */
    fun checkOnnxRuntime(): String {
        return try {
            System.loadLibrary("unibot_crash_handler")
            nativeCheckOnnxRuntime()
        } catch (t: Throwable) {
            "FAIL err=${t.message}"
        }
    }

    /**
     * Appends logcat context + app-log tail to every un-finalized
     * native-crash report in [logsDir]. Best-effort: logd's buffers may
     * already have rotated, in which case the file keeps its original
     * content plus a note saying so.
     */
    fun finalizePendingReports(logsDir: File) {
        val reports = try {
            logsDir.listFiles { f ->
                f.isFile && f.name.startsWith("native-crash-") && f.name.endsWith(".log")
            }?.toList() ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "finalize: list failed: ${t.message}")
            return
        }
        for (report in reports) {
            try {
                val existing = report.readText()
                if (FINALIZED_MARKER in existing) continue
                val sb = StringBuilder()
                sb.append("\n\n--- logcat context (captured at next launch) ---\n")
                val logcatLines = captureLogcatContext(report)
                if (logcatLines.isEmpty()) {
                    sb.append("(logd buffers already rotated — no matching lines left)\n")
                } else {
                    logcatLines.forEach { sb.append(it).append('\n') }
                }
                sb.append("\n--- app log tail ---\n")
                val appTail = captureAppLogTail(logsDir)
                sb.append(appTail.ifEmpty { "(no app log found)\n" })
                sb.append("\n").append(FINALIZED_MARKER).append("\n")
                report.appendText(sb.toString())
                Log.i(TAG, "finalized ${report.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "finalize ${report.name} failed: ${t.message}")
            }
        }
    }

    /**
     * [T-android-v122-crash-capture] Last matching logcat lines for the tags
     * that diagnose native aborts. Two lessons from the v1.2.1 field report:
     * (1) the old `*:W` filter dropped debuggerd's "Abort message:" line
     * (info-level), so the capture held stack frames but never the abort
     * reason — now `*:I`; (2) the window was unbounded-but-tiny (takeLast
     * 150 of tag matches), so the reason scrolled away — now every line
     * within ±30 s of the crash timestamp (parsed from the report filename)
     * is kept, PLUS every line matching the abort patterns regardless of
     * time, capped at 400.
     */
    private fun captureLogcatContext(report: File): List<String> {
        val crashSec = parseCrashTimestampSec(report.name)
        val abortPatterns = listOf(
            "abort message", "fatal signal", "fatal", "scudo", "sigabrt",
            "f libc", "f debug", "tombstone", "backtrace", "abort(",
        )
        return try {
            val proc = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "*:I",
            ).redirectErrorStream(true).start()
            // Bound the read: a chatty device can hold megabytes in the
            // ring buffers; 60k lines is plenty for a ±30 s window.
            val lines = proc.inputStream.bufferedReader().readLines().takeLast(60_000)
            proc.waitFor()
            val picked = ArrayList<String>(400)
            for (line in lines) {
                val l = line.lowercase()
                val inWindow = crashSec != null && logcatLineInWindow(line, crashSec)
                if (inWindow || abortPatterns.any { it in l }) picked.add(line)
            }
            picked.takeLast(400)
        } catch (t: Throwable) {
            Log.w(TAG, "logcat capture failed: ${t.message}")
            emptyList()
        }
    }

    /** "native-crash-2026-10-04_11-41-13.log" → seconds since month start. */
    private fun parseCrashTimestampSec(name: String): Long? {
        return try {
            val m = Regex(
                """native-crash-\d{4}-\d{2}-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.log"""
            ).find(name) ?: return null
            val (day, h, min, s) = m.destructured
            ((day.toLong() * 24 + h.toLong()) * 60 + min.toLong()) * 60 + s.toLong()
        } catch (_: Throwable) {
            null
        }
    }

    /** threadtime head "10-04 11:41:13.096" → true when within ±30 s. */
    private fun logcatLineInWindow(line: String, crashSec: Long): Boolean {
        return try {
            val m = Regex("""^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(line)
                ?: return false
            // groupValues = [whole, month, day, h, min, s]; List destructuring
            // only supports component1..5, so index explicitly.
            val g = m.groupValues
            val sec = ((g[2].toLong() * 24 + g[3].toLong()) * 60 + g[4].toLong()) * 60 + g[5].toLong()
            kotlin.math.abs(sec - crashSec) <= 30
        } catch (_: Throwable) {
            false
        }
    }

    /** Tail of today's app log (unibot-yyyy-MM-dd.log lives beside the crash reports). */
    private fun captureAppLogTail(logsDir: File): String {
        return try {
            val appLogs = logsDir.listFiles { f ->
                f.isFile && f.name.startsWith("unibot-") && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
            val latest = appLogs.firstOrNull() ?: return ""
            latest.readLines().takeLast(60).joinToString("\n") + "\n"
        } catch (t: Throwable) {
            Log.w(TAG, "app log tail failed: ${t.message}")
            ""
        }
    }

    private external fun nativeInstall(logDir: String)
    private external fun nativeCheckOnnxRuntime(): String
}
