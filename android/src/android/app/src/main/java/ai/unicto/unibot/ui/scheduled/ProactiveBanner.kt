package ai.unicto.unibot.ui.scheduled

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.scheduled.ScheduledTask
import ai.unicto.unibot.scheduled.ScheduledTaskManager
import ai.unicto.unibot.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [v1.0-wave6] "While you were away" banner.
 *
 * When the app comes to the foreground (the session list composes), this
 * checks whether any scheduled task fired since the last foreground visit
 * and produced a result with a session to open. If so, one dismissible card
 * slides down above the chat list: task label + result preview, tap opens
 * the session. Only the single most recent notable task is shown — never a
 * stack.
 *
 * State lives in plain SharedPreferences (`last_foreground_ms`,
 * `dismissed_task_ids`); nothing leaves the device. The foreground stamp is
 * written AFTER the notable-task query so the banner can't clear itself
 * before showing.
 */
@Composable
fun ProactiveBannerCard(
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var notable by remember { mutableStateOf<ScheduledTask?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val task = withContext(Dispatchers.IO) {
            ProactiveBannerState.consumeNotable(context.applicationContext)
        }
        if (task != null) {
            notable = task
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = tween(Motion.Standard, easing = Motion.FastOutSlowIn),
            initialOffsetY = { -it },
        ) + fadeIn(animationSpec = tween(Motion.Quick)),
        exit = slideOutVertically(
            animationSpec = tween(Motion.Quick, easing = Motion.FastOutSlowIn),
            targetOffsetY = { -it },
        ) + fadeOut(animationSpec = tween(Motion.Quick)),
    ) {
        val task = notable ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                    visible = false
                    ProactiveBannerState.dismiss(context.applicationContext, task.id)
                    task.lastResultSessionId?.let { onOpenSession(it) }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "While you were away",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = task.label.ifBlank { "Scheduled task" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                task.lastResultPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                    Text(
                        text = preview,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = {
                visible = false
                ProactiveBannerState.dismiss(context.applicationContext, task.id)
            }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** Prefs-backed bookkeeping for the proactive banner. */
internal object ProactiveBannerState {
    private const val PREFS = "unibot_proactive_banner"
    private const val KEY_LAST_FOREGROUND = "last_foreground_ms"
    private const val KEY_DISMISSED = "dismissed_task_ids"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Returns the most recent task that fired since the last foreground
     * visit with an openable result session, then stamps the foreground
     * time. Dismissed tasks are never returned twice. Runs on IO.
     */
    fun consumeNotable(context: Context): ScheduledTask? {
        val p = prefs(context)
        val lastFg = p.getLong(KEY_LAST_FOREGROUND, 0L)
        val dismissed = p.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        // Stamp first so a crash/retry can't double-show; the query already
        // captured everything fired before `now`.
        p.edit().putLong(KEY_LAST_FOREGROUND, now).apply()
        if (lastFg == 0L) return null // first-ever launch: nothing is "new"
        return runCatching {
            ScheduledTaskManager(context).list()
                .filter { t ->
                    val fired = t.lastFiredAt ?: return@filter false
                    fired > lastFg && t.lastResultSessionId != null && t.id !in dismissed
                }
                .maxByOrNull { it.lastFiredAt ?: 0L }
        }.getOrNull()
    }

    fun dismiss(context: Context, taskId: String) {
        runCatching {
            val p = prefs(context)
            val set = (p.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()).toMutableSet()
            set.add(taskId)
            // Cap the set so it can't grow forever.
            val trimmed = if (set.size > 100) set.toList().takeLast(100).toSet() else set
            p.edit().putStringSet(KEY_DISMISSED, trimmed).apply()
        }
    }
}
