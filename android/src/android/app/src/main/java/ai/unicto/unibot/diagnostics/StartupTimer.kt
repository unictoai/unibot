package ai.unicto.unibot.diagnostics

import android.os.SystemClock
import android.util.Log

/**
 * v1.4.0 item 86 — cold-start measurement.
 *
 * Records elapsed-realtime timestamps for named startup phases so a
 * before/after comparison is one logcat line away. `UnibotApp.onCreate`
 * calls [mark] after each phase and [report] at the end; the summary
 * line lands in logcat AND in the daily app log (when logging is on),
 * e.g.:
 *
 *   cold-start total=1842ms phases={primes=12ms crash-infra=48ms repos=620ms deferred-kickoff=3ms}
 *
 * Main-thread cost only: marks are taken on the calling thread, so a
 * phase that moved to a background dispatcher shows up as the few ms it
 * took to LAUNCH, not the work itself — which is exactly the number that
 * matters for time-to-first-frame.
 *
 * Zero-allocation on the hot path beyond the list append; never throws.
 */
object StartupTimer {
    private const val TAG = "StartupTimer"

    private data class Mark(val name: String, val elapsedMs: Long)

    private val processStartMs: Long = SystemClock.elapsedRealtime()

    private val marks = ArrayList<Mark>(16)

    @Synchronized
    fun mark(name: String) {
        try {
            marks.add(Mark(name, SystemClock.elapsedRealtime() - processStartMs))
        } catch (_: Throwable) {
        }
    }

    /**
     * Logs the one-line summary. Called once at the end of
     * `UnibotApp.onCreate`. Each phase shows its CUMULATIVE elapsed time
     * since process start (deltas are trivially derived).
     */
    @Synchronized
    fun report() {
        try {
            val total = SystemClock.elapsedRealtime() - processStartMs
            val phases = marks.joinToString(" ") { "${it.name}=${it.elapsedMs}ms" }
            Log.i(TAG, "cold-start total=${total}ms phases={$phases}")
        } catch (_: Throwable) {
        }
    }

    /** Test hook: snapshot of marks taken so far. */
    @Synchronized
    internal fun snapshotForTest(): List<Pair<String, Long>> = marks.map { it.name to it.elapsedMs }
}
