package ai.unicto.unibot.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * v1.4.0 item 78 — app-wide crash safety net, the visible half.
 *
 * Uncaught coroutine errors used to ride the thread's uncaught-exception
 * handler straight into ACRA and a process death. [AppErrorBus] is the
 * alternative landing zone: [SafeLaunch.launchCatching] and the
 * application-scoped supervisor post here, and the root UI
 * ([ai.unicto.unibot.ui.components.GlobalErrorSnackbar]) renders each
 * entry as a snackbar. The user sees "something hiccupped" instead of
 * watching the app vanish.
 *
 * Fire-and-forget by design: `tryEmit` on a buffered SharedFlow never
 * suspends and never throws, so posting from an exception handler cannot
 * itself become the next crash. Overflow drops the oldest — a flood of
 * errors degrades to one visible line, not a queue that OOMs.
 */
data class AppError(
    val message: String,
    val tag: String = "App",
    val cause: Throwable? = null,
)

object AppErrorBus {
    private const val TAG = "AppErrorBus"

    private val _errors = MutableSharedFlow<AppError>(
        // No replay: a collector that subscribes late (first composition)
        // must not re-show errors from before it existed.
        replay = 0,
        extraBufferCapacity = 16,
    )
    val errors: SharedFlow<AppError> = _errors.asSharedFlow()

    /** Post a ready-made error. Never throws. */
    fun post(error: AppError) {
        _errors.tryEmit(error)
    }

    /** Post a plain message. Never throws. */
    fun post(tag: String, message: String, cause: Throwable? = null) {
        post(AppError(message = message, tag = tag, cause = cause))
    }

    /**
     * Post a throwable with a human-readable, never-blank message.
     * Exception messages are nullable AND often blank (e.g. a bare
     * `IOException()`), so we fall back to the class name rather than
     * showing "null".
     */
    fun post(throwable: Throwable, tag: String = "App") {
        val msg = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName.ifBlank { "Unknown error" }
        post(AppError(message = msg, tag = tag, cause = throwable))
        try {
            android.util.Log.w(TAG, "[$tag] $msg", throwable)
        } catch (_: Throwable) {
            // Logging must never throw out of an error path.
        }
    }
}
