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
                val logcatLines = captureLogcatContext()
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

    /** Last matching logcat lines for the tags that diagnose native aborts. */
    private fun captureLogcatContext(): List<String> {
        val wanted = listOf("scudo", "libc", "DEBUG", "abort", "fatal", "tombstone")
        return try {
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "*:W")
                .redirectErrorStream(true)
                .start()
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            lines.filter { line ->
                val l = line.lowercase()
                wanted.any { it.lowercase() in l }
            }.takeLast(150)
        } catch (t: Throwable) {
            Log.w(TAG, "logcat capture failed: ${t.message}")
            emptyList()
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
}
