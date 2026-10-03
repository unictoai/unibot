package ai.unicto.unibot.connectors.healthconnect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeRangeFilter
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.os.Build
import android.os.OutcomeReceiver
import androidx.core.content.ContextCompat
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Health Connect connector — on-device health data, read-only.
 *
 * Uses the Android 14+ framework API (`android.health.connect.*`) directly
 * instead of the Jetpack client (which isn't in the dependency set).
 * Everything is guarded by [isSupported]: on older Android the connector
 * simply reports itself unavailable — no crash, no dead end.
 *
 * Privacy: steps, heart rate, sleep and workouts are read only when the
 * user asks; nothing is ever uploaded or logged. The user grants access
 * through Android's own Health Connect permission screen after seeing an
 * in-app rationale dialog.
 */
object HealthConnectConnector {

    private const val TAG = "HealthConnectConnector"

    private fun neededPermissions(): List<String> {
        // Resolved inside the function (not in a field initializer) so merely
        // touching this object on pre-34 devices never loads the
        // android.health.connect classes.
        if (Build.VERSION.SDK_INT < 34) return emptyList()
        return listOf(
            HealthPermissions.READ_STEPS,
            HealthPermissions.READ_HEART_RATE,
            HealthPermissions.READ_SLEEP,
            HealthPermissions.READ_EXERCISE,
        )
    }

    data class HealthSummary(
        val steps7d: Long,
        val heartRateAvg: Long?,
        val heartRateMin: Long?,
        val heartRateMax: Long?,
        val sleepLastNightMs: Long,
        val workouts7d: List<Workout>,
    )

    data class Workout(val title: String, val startMs: Long, val durationMs: Long)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotSupported(val hint: String) : ApiResult<Nothing>()
        data class NotGranted(val hint: String = "Health permissions aren't granted yet. Open Health Connect settings to allow access.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    /** Android 14+ with a Health Connect service present. */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return try {
            context.getSystemService(HealthConnectManager::class.java) != null
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[support] ${t.message}")
            false
        }
    }

    /** "Connected" = supported AND all read permissions granted. */
    fun isConnected(context: Context): Boolean =
        isSupported(context) && hasAllPermissions(context)

    fun hasAllPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return neededPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun missingPermissions(context: Context): List<String> {
        // String literals here: referencing HealthPermissions.<X> would load
        // android.health.connect classes on devices that don't have them.
        val literals = listOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_EXERCISE",
        )
        if (Build.VERSION.SDK_INT < 34) return literals
        return neededPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Open Android's Health Connect permission screen for this app.
     * Call only AFTER showing the in-app rationale.
     */
    fun openPermissionScreen(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val intent = Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[perms] ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    private val executor: Executor by lazy { Executors.newSingleThreadExecutor() }

    private suspend fun <T> aggregate(
        manager: HealthConnectManager,
        request: AggregateRecordsRequest<T>,
    ): AggregateRecordsResponse<T> = withTimeout(30_000) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            manager.aggregate(
                request,
                executor,
                object : OutcomeReceiver<AggregateRecordsResponse<T>, HealthConnectException> {
                    override fun onResult(result: AggregateRecordsResponse<T>) {
                        if (cont.isActive) cont.resume(result, null)
                    }
                    override fun onError(error: HealthConnectException) {
                        if (cont.isActive) cont.resumeWith(
                            Result.failure(Exception(error.message ?: "Health Connect error")),
                        )
                    }
                },
            )
        }
    }

    private suspend fun <T : android.health.connect.datatypes.Record> readRecords(
        manager: HealthConnectManager,
        type: Class<T>,
        start: Instant,
        end: Instant,
    ): List<T> = withTimeout(30_000) {
        val request = ReadRecordsRequestUsingFilters.Builder(type)
            .setTimeRangeFilter(TimeRangeFilter.between(start, end))
            .build()
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            manager.readRecords(
                request,
                executor,
                object : OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> {
                    override fun onResult(result: ReadRecordsResponse<T>) {
                        if (cont.isActive) cont.resume(result.records, null)
                    }
                    override fun onError(error: HealthConnectException) {
                        if (cont.isActive) cont.resumeWith(
                            Result.failure(Exception(error.message ?: "Health Connect error")),
                        )
                    }
                },
            )
        }
    }

    private fun manager(context: Context): HealthConnectManager? {
        if (Build.VERSION.SDK_INT < 34) return null
        return runCatching { context.getSystemService(HealthConnectManager::class.java) }.getOrNull()
    }

    /** One-shot summary: steps (7d), heart rate (24h), last night's sleep, workouts (7d). */
    suspend fun summary(context: Context): ApiResult<HealthSummary> =
        withContext(Dispatchers.IO) {
            if (!isSupported(context)) {
                return@withContext ApiResult.NotSupported(
                    "Health Connect needs Android 14 or newer.",
                )
            }
            if (!hasAllPermissions(context)) return@withContext ApiResult.NotGranted()
            val mgr = manager(context)
                ?: return@withContext ApiResult.Error("Health Connect isn't available on this phone.")
            val now = Instant.now()
            try {
                val stepsReq = AggregateRecordsRequest.Builder(StepsRecord.STEPS_COUNT_TOTAL)
                    .setTimeRangeFilter(TimeRangeFilter.between(now.minusSeconds(7 * 86400), now))
                    .build()
                val steps = aggregate(mgr, stepsReq).get(StepsRecord.STEPS_COUNT_TOTAL) ?: 0L

                val hrReq = AggregateRecordsRequest.Builder(HeartRateRecord.BPM_AVG)
                    .addAggregationType(HeartRateRecord.BPM_MIN)
                    .addAggregationType(HeartRateRecord.BPM_MAX)
                    .setTimeRangeFilter(TimeRangeFilter.between(now.minusSeconds(86400), now))
                    .build()
                val hr = aggregate(mgr, hrReq)

                val sleepReq = AggregateRecordsRequest.Builder(SleepSessionRecord.SLEEP_DURATION_TOTAL)
                    .setTimeRangeFilter(TimeRangeFilter.between(now.minusSeconds(86400), now))
                    .build()
                val sleepMs = aggregate(mgr, sleepReq).get(SleepSessionRecord.SLEEP_DURATION_TOTAL) ?: 0L

                val sessions = readRecords(
                    mgr, ExerciseSessionRecord::class.java,
                    now.minusSeconds(7 * 86400), now,
                ).map { r ->
                    Workout(
                        title = r.title?.toString()?.takeIf { it.isNotBlank() } ?: "Workout",
                        startMs = r.startTime.toEpochMilli(),
                        durationMs = r.endTime.toEpochMilli() - r.startTime.toEpochMilli(),
                    )
                }.sortedByDescending { it.startMs }

                ApiResult.Ok(
                    HealthSummary(
                        steps7d = steps,
                        heartRateAvg = hr.get(HeartRateRecord.BPM_AVG),
                        heartRateMin = hr.get(HeartRateRecord.BPM_MIN),
                        heartRateMax = hr.get(HeartRateRecord.BPM_MAX),
                        sleepLastNightMs = sleepMs,
                        workouts7d = sessions,
                    ),
                )
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[summary] ${t.message}")
                ApiResult.Error(t.message ?: "Couldn't read health data.")
            }
        }

    /** Steps in the last 24 hours. */
    suspend fun stepsToday(context: Context): ApiResult<Long> =
        withContext(Dispatchers.IO) {
            if (!isSupported(context)) return@withContext ApiResult.NotSupported("Health Connect needs Android 14 or newer.")
            if (!hasAllPermissions(context)) return@withContext ApiResult.NotGranted()
            val mgr = manager(context) ?: return@withContext ApiResult.Error("Health Connect isn't available.")
            val now = Instant.now()
            try {
                val req = AggregateRecordsRequest.Builder(StepsRecord.STEPS_COUNT_TOTAL)
                    .setTimeRangeFilter(TimeRangeFilter.between(now.minusSeconds(86400), now))
                    .build()
                ApiResult.Ok(aggregate(mgr, req).get(StepsRecord.STEPS_COUNT_TOTAL) ?: 0L)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[steps] ${t.message}")
                ApiResult.Error(t.message ?: "Couldn't read steps.")
            }
        }

    /** Recent sleep sessions (newest first). */
    suspend fun sleepSessions(
        context: Context,
        days: Int = 7,
    ): ApiResult<List<SleepSessionRecord>> = withContext(Dispatchers.IO) {
        if (!isSupported(context)) return@withContext ApiResult.NotSupported("Health Connect needs Android 14 or newer.")
        if (!hasAllPermissions(context)) return@withContext ApiResult.NotGranted()
        val mgr = manager(context) ?: return@withContext ApiResult.Error("Health Connect isn't available.")
        val now = Instant.now()
        try {
            val records = readRecords(
                mgr, SleepSessionRecord::class.java,
                now.minusSeconds(days.coerceIn(1, 30) * 86400L), now,
            ).sortedByDescending { it.startTime }
            ApiResult.Ok(records)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[sleep] ${t.message}")
            ApiResult.Error(t.message ?: "Couldn't read sleep data.")
        }
    }
}
