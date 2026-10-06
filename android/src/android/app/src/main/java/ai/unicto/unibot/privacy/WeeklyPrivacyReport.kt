package ai.unicto.unibot.privacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import ai.unicto.unibot.util.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Privacy backlog item 56 — weekly privacy report, generated on-device.
 *
 * Fed by [PrivacyNetworkGate]'s logging interceptor (one [record] call per
 * outbound request — metadata only: host, byte counts, category; never
 * paths, queries, headers, or bodies). Buckets are keyed by week
 * (Monday 00:00, device-local), accumulated in memory, and persisted to the
 * encrypted prefs file at most every 30 seconds so a hot request loop never
 * turns into a disk-write loop. Only the last 8 weeks are kept.
 *
 * The "0 bytes to trackers" figure is honest by construction: [isKnownTracker]
 * matches a small list of well-known analytics/ad hosts, and unibot bundles
 * no analytics or ad SDKs — so the number should always be zero, and if it
 * ever isn't, the report shows it instead of hiding it.
 *
 * Everything here is generated and stored on this phone. Nothing is
 * uploaded, ever.
 */
object WeeklyPrivacyReport {

    private const val TAG = "WeeklyPrivacyReport"
    private const val FILE = "privacy_weekly"
    private const val KEY_BUCKETS = "week_buckets_json"
    private const val PERSIST_THROTTLE_MS = 30_000L
    private const val KEEP_WEEKS = 8

    /** Mutable counters for one week. Guarded by [lock]. */
    data class Bucket(
        var requests: Long = 0,
        var bytesUp: Long = 0,
        var bytesDown: Long = 0,
        var providerRequests: Long = 0,
        var connectorRequests: Long = 0,
        var updateSearchRequests: Long = 0,
        var otherRequests: Long = 0,
        var trackerBytes: Long = 0,
    )

    /** Immutable snapshot for the UI card. */
    data class Report(
        val weekStartMs: Long,
        val requests: Long,
        val bytesUp: Long,
        val bytesDown: Long,
        val providerRequests: Long,
        val connectorRequests: Long,
        val updateSearchRequests: Long,
        val otherRequests: Long,
        val trackerBytes: Long,
    ) {
        val isEmpty: Boolean get() = requests == 0L
    }

    private val lock = Any()
    private val buckets = mutableMapOf<Long, Bucket>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var lastPersistMs = 0L

    /** Host substrings of well-known analytics / advertising / telemetry SDKs. */
    private val TRACKER_SUBSTRINGS = listOf(
        "google-analytics", "googletagmanager", "doubleclick",
        "facebook.net", "fbcdn", "fbsbx",
        "hotjar", "mixpanel", "segment.io", "amplitude",
        "fullstory", "crazyegg", "matomo", "branch.io",
        "appsflyer", "adjust.com", "telemetry", "crashlytics",
        "sentry.io", "bugsnag",
    )

    /** True when [host] looks like a known analytics/ad/telemetry endpoint. */
    fun isKnownTracker(host: String): Boolean {
        val h = host.lowercase()
        return TRACKER_SUBSTRINGS.any { h.contains(it) }
    }

    /**
     * Start of the week containing [nowMs]: Monday 00:00 in the device's
     * locale-independent week definition (Monday-first, always).
     */
    fun weekStartMs(nowMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.timeInMillis = nowMs
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Open the encrypted store and load persisted buckets. Idempotent; call
     * once at app start (PrivacyPrefs.init time). Until then, [record] still
     * accumulates in memory — nothing is lost, persistence just starts late.
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            val sp = EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
            prefs = sp
            loadJson(sp.getString(KEY_BUCKETS, null).orEmpty())
            prune(System.currentTimeMillis())
        }
    }

    /**
     * Record one outbound request. Never throws; called on network threads,
     * so it must stay cheap — one map lookup and a few increments.
     */
    fun record(host: String, bytesUp: Long, bytesDown: Long) {
        try {
            val now = System.currentTimeMillis()
            val week = weekStartMs(now)
            val tracker = isKnownTracker(host)
            val category = PrivacyNetworkGate.classify(host)
            var dirty = false
            synchronized(lock) {
                val bucket = buckets.getOrPut(week) { Bucket() }
                bucket.requests += 1
                bucket.bytesUp += bytesUp.coerceAtLeast(0)
                bucket.bytesDown += bytesDown.coerceAtLeast(0)
                when {
                    tracker -> bucket.trackerBytes += bytesUp.coerceAtLeast(0) + bytesDown.coerceAtLeast(0)
                    category == PrivacyNetworkGate.Category.LLM -> bucket.providerRequests += 1
                    category == PrivacyNetworkGate.Category.CONNECTOR ||
                        category == PrivacyNetworkGate.Category.OAUTH -> bucket.connectorRequests += 1
                    category == PrivacyNetworkGate.Category.UPDATE ||
                        category == PrivacyNetworkGate.Category.WEB_SEARCH -> bucket.updateSearchRequests += 1
                    else -> bucket.otherRequests += 1
                }
                dirty = true
            }
            if (dirty) maybePersist(now)
        } catch (t: Throwable) {
            Log.w(TAG, "record failed: ${t.message}")
        }
    }

    /** This week's report (empty counters when nothing was recorded yet). */
    fun currentReport(nowMs: Long = System.currentTimeMillis()): Report {
        val week = weekStartMs(nowMs)
        val bucket = synchronized(lock) { buckets[week]?.copy() } ?: Bucket()
        return Report(
            weekStartMs = week,
            requests = bucket.requests,
            bytesUp = bucket.bytesUp,
            bytesDown = bucket.bytesDown,
            providerRequests = bucket.providerRequests,
            connectorRequests = bucket.connectorRequests,
            updateSearchRequests = bucket.updateSearchRequests,
            otherRequests = bucket.otherRequests,
            trackerBytes = bucket.trackerBytes,
        )
    }

    /** Write buckets to disk now. Called on the 30s throttle and by tests. */
    fun flush() {
        val sp = prefs ?: return
        val json = synchronized(lock) { toJson() }
        try {
            sp.edit().putString(KEY_BUCKETS, json).apply()
            lastPersistMs = System.currentTimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "flush failed: ${t.message}")
        }
    }

    // -- persistence ----------------------------------------------------------

    private fun maybePersist(nowMs: Long) {
        val sp = prefs ?: return
        if (nowMs - lastPersistMs < PERSIST_THROTTLE_MS) return
        lastPersistMs = nowMs
        val json = synchronized(lock) { toJson() }
        try {
            sp.edit().putString(KEY_BUCKETS, json).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "persist failed: ${t.message}")
        }
    }

    private fun prune(nowMs: Long) {
        val cutoff = weekStartMs(nowMs) - (KEEP_WEEKS - 1) * 7 * 24 * 3_600_000L
        buckets.keys.filter { it < cutoff }.forEach { buckets.remove(it) }
    }

    // -- JSON (org.json ships on Android and in unit tests) --------------------

    /** Serialize all buckets. Pure — safe to call from unit tests. */
    fun toJson(): String {
        val array = JSONArray()
        val snapshot = synchronized(lock) { buckets.toMap() }
        for ((week, b) in snapshot.entries.sortedBy { it.key }) {
            array.put(JSONObject().apply {
                put("week", week)
                put("requests", b.requests)
                put("bytesUp", b.bytesUp)
                put("bytesDown", b.bytesDown)
                put("providerRequests", b.providerRequests)
                put("connectorRequests", b.connectorRequests)
                put("updateSearchRequests", b.updateSearchRequests)
                put("otherRequests", b.otherRequests)
                put("trackerBytes", b.trackerBytes)
            })
        }
        return array.toString()
    }

    /** Replace in-memory buckets from [toJson] output. Pure — unit-testable. */
    fun loadJson(json: String) {
        if (json.isBlank()) return
        try {
            val array = JSONArray(json)
            synchronized(lock) {
                buckets.clear()
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    buckets[o.getLong("week")] = Bucket(
                        requests = o.optLong("requests"),
                        bytesUp = o.optLong("bytesUp"),
                        bytesDown = o.optLong("bytesDown"),
                        providerRequests = o.optLong("providerRequests"),
                        connectorRequests = o.optLong("connectorRequests"),
                        updateSearchRequests = o.optLong("updateSearchRequests"),
                        otherRequests = o.optLong("otherRequests"),
                        trackerBytes = o.optLong("trackerBytes"),
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadJson failed: ${t.message}")
        }
    }

    // -- test seams -------------------------------------------------------------

    /** Deep copy of the in-memory buckets, for unit tests. */
    fun snapshotForTest(): Map<Long, Bucket> =
        synchronized(lock) { buckets.mapValues { it.value.copy() } }

    /** Replace in-memory buckets wholesale, for unit tests. */
    fun restoreForTest(snapshot: Map<Long, Bucket>) {
        synchronized(lock) {
            buckets.clear()
            snapshot.forEach { (k, v) -> buckets[k] = v.copy() }
        }
    }

    /** Drop all in-memory buckets, for unit tests. */
    fun clearForTest() {
        synchronized(lock) { buckets.clear() }
    }
}
