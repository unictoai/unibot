package ai.unicto.unibot.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * v1.4.0 item 78 — app-wide crash safety net, the catching half.
 *
 * A coroutine that throws with no [CoroutineExceptionHandler] in context
 * kills the process via the thread's uncaught-exception handler. These
 * helpers make "catch and show" the path of least resistance:
 *
 * - [launchCatching]: like `launch`, but any [Throwable] (including from
 *   child coroutines, via the installed handler) routes to [onError] —
 *   defaulting to [AppErrorBus], i.e. a visible snackbar — instead of a
 *   crash. [CancellationException] is always rethrown: cancelling a scope
 *   must keep working.
 * - [asyncCatching]: same for [async]; the [Deferred] still completes
 *   exceptionally for callers that `await()`, but the uncaught path can
 *   no longer take the process down.
 */
fun CoroutineScope.launchCatching(
    tag: String = "App",
    onError: (Throwable) -> Unit = { AppErrorBus.post(it, tag) },
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val handler = CoroutineExceptionHandler { _, e -> onError(e) }
    return launch(handler) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError(t)
        }
    }
}

fun <T> CoroutineScope.asyncCatching(
    tag: String = "App",
    onError: (Throwable) -> Unit = { AppErrorBus.post(it, tag) },
    block: suspend CoroutineScope.() -> T,
): Deferred<T> {
    val handler = CoroutineExceptionHandler { _, e -> onError(e) }
    return async(handler) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError(t)
            throw t
        }
    }
}
