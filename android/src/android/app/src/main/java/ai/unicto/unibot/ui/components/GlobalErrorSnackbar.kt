package ai.unicto.unibot.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ai.unicto.unibot.util.AppErrorBus

/**
 * v1.4.0 item 78 — app-wide crash safety net, the rendering half.
 *
 * Collects [AppErrorBus] at the root of the composition and surfaces each
 * entry on [hostState]. Mounted once in `MainActivity.setContent`, above
 * the navigation tree, so a coroutine that dies anywhere — chat, swarm,
 * deferred startup — degrades to a visible snackbar instead of a crash.
 *
 * The host's [androidx.compose.material3.SnackbarHost] lives next to the
 * call site; this composable only pumps the bus into it. Long errors show
 * [SnackbarDuration.Long]; each emission queues behind the previous one
 * via SnackbarHostState's own queueing.
 */
@Composable
fun GlobalErrorSnackbar(hostState: SnackbarHostState) {
    LaunchedEffect(hostState) {
        AppErrorBus.errors.collect { error ->
            hostState.showSnackbar(
                message = error.message,
                duration = SnackbarDuration.Long,
            )
        }
    }
}
