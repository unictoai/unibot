package ai.unicto.unibot.scheduled

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import ai.unicto.unibot.logging.AppLogger
import java.util.Calendar

/**
 * Item 92 — quiet hours + battery awareness for scheduled tasks.
 *
 * [decide] is the pure policy: given a task, the current time, the user's
 * quiet-hours window and a battery snapshot, it returns [FireDecision.Run]
 * or [FireDecision.DeferUntil]. The receiver arms a one-shot alarm for the
 * deferred instant instead of running — the task is never silently dropped,
 * it just waits for a better moment.
 *
 * Deferral rules, in order:
 *  1. Critical battery (< 15% and not charging) → defer 30 min. Hard safety
 *     rule, not user-configurable: a background agent loop must not be the
 *     thing that kills the phone.
 *  2. Quiet hours (per-task [ScheduledTask.respectQuietHours]) → defer to
 *     the end of the window.
 *  3. Per-task [ScheduledTask.requireCharging] while not charging → defer
 *     30 min and re-check.
 *  4. Global "pause during battery saver" (default ON) while the OS power
 *     saver is active → defer 30 min.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0,
)

data class BatterySnapshot(
    val isPowerSave: Boolean = false,
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
)

sealed class FireDecision {
    data object Run : FireDecision()
    data class DeferUntil(val atMs: Long) : FireDecision()
}

object SchedulePolicy {

    const val CRITICAL_BATTERY_PCT = 15
    const val DEFER_RECHECK_MINUTES = 30L

    fun decide(
        task: ScheduledTask,
        now: Long = System.currentTimeMillis(),
        quietHours: QuietHours = QuietHours(),
        battery: BatterySnapshot = BatterySnapshot(),
        batterySaverPause: Boolean = true,
    ): FireDecision {
        if (!battery.isCharging && battery.batteryPct in 0 until CRITICAL_BATTERY_PCT) {
            return FireDecision.DeferUntil(now + DEFER_RECHECK_MINUTES * 60_000L)
        }
        if (task.respectQuietHours && inQuietHours(now, quietHours)) {
            return FireDecision.DeferUntil(quietHoursEndMs(now, quietHours))
        }
        if (task.requireCharging && !battery.isCharging) {
            return FireDecision.DeferUntil(now + DEFER_RECHECK_MINUTES * 60_000L)
        }
        if (batterySaverPause && battery.isPowerSave) {
            return FireDecision.DeferUntil(now + DEFER_RECHECK_MINUTES * 60_000L)
        }
        return FireDecision.Run
    }

    /** True when [now] falls inside the quiet window (overnight-aware). */
    fun inQuietHours(now: Long, qh: QuietHours): Boolean {
        if (!qh.enabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = qh.startHour.coerceIn(0, 23) * 60 + qh.startMinute.coerceIn(0, 59)
        val end = qh.endHour.coerceIn(0, 23) * 60 + qh.endMinute.coerceIn(0, 59)
        if (start == end) return false // degenerate window = disabled
        return if (start < end) mins in start until end else mins >= start || mins < end
    }

    /** Wall-clock ms of the next quiet-window end at/after [now]. */
    fun quietHoursEndMs(now: Long, qh: QuietHours): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, qh.endHour.coerceIn(0, 23))
            set(Calendar.MINUTE, qh.endMinute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}

/**
 * Reads the live battery state. Kept separate from [SchedulePolicy] so the
 * policy stays pure and unit-testable.
 */
object BatteryStateReader {
    fun snapshot(context: Context): BatterySnapshot {
        var pct = 100
        var charging = false
        runCatching {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val status: Intent? = context.registerReceiver(null, filter)
            val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) pct = (level * 100 / scale).coerceIn(0, 100)
            val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val chargeStatus = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = plugged != 0 ||
                chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                chargeStatus == BatteryManager.BATTERY_STATUS_FULL
        }
        var powerSave = false
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerSave = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                pm.isPowerSaveMode
            } else false
        }
        return BatterySnapshot(isPowerSave = powerSave, batteryPct = pct, isCharging = charging)
    }
}

/**
 * Persists the global knobs: the quiet-hours window and the battery-saver
 * pause default. Per-task [ScheduledTask.respectQuietHours] /
 * [ScheduledTask.requireCharging] live on the task rows themselves.
 */
class SchedulePolicyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun quietHours(): QuietHours = QuietHours(
        enabled = prefs.getBoolean(KEY_QH_ENABLED, false),
        startHour = prefs.getInt(KEY_QH_START_H, 22),
        startMinute = prefs.getInt(KEY_QH_START_M, 0),
        endHour = prefs.getInt(KEY_QH_END_H, 7),
        endMinute = prefs.getInt(KEY_QH_END_M, 0),
    )

    fun setQuietHours(qh: QuietHours) {
        prefs.edit()
            .putBoolean(KEY_QH_ENABLED, qh.enabled)
            .putInt(KEY_QH_START_H, qh.startHour.coerceIn(0, 23))
            .putInt(KEY_QH_START_M, qh.startMinute.coerceIn(0, 59))
            .putInt(KEY_QH_END_H, qh.endHour.coerceIn(0, 23))
            .putInt(KEY_QH_END_M, qh.endMinute.coerceIn(0, 59))
            .apply()
    }

    fun batterySaverPause(): Boolean = prefs.getBoolean(KEY_BATTERY_SAVER_PAUSE, true)

    fun setBatterySaverPause(pause: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_SAVER_PAUSE, pause).apply()
    }

    companion object {
        private const val PREFS_NAME = "unibot_schedule_policy_prefs"
        private const val KEY_QH_ENABLED = "quiet_hours_enabled"
        private const val KEY_QH_START_H = "quiet_hours_start_h"
        private const val KEY_QH_START_M = "quiet_hours_start_m"
        private const val KEY_QH_END_H = "quiet_hours_end_h"
        private const val KEY_QH_END_M = "quiet_hours_end_m"
        private const val KEY_BATTERY_SAVER_PAUSE = "battery_saver_pause"
    }
}
