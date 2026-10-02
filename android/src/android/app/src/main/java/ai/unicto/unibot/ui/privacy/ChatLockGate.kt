package ai.unicto.unibot.ui.privacy

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ai.unicto.unibot.guard.DeviceCredential
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wave 5 (v1.0) — privacy core. Per-process unlock ledger for locked chats.
 *
 * A chat the user locked in the "…" menu stays gated until they confirm
 * with the phone's own screen lock (fingerprint / face / PIN / pattern).
 * Confirmation is remembered for the life of the process only — a fresh
 * process (app restart) re-gates, but backgrounding the app does not.
 */
object ChatLockAuth {
    private val authed = mutableSetOf<String>()

    @Synchronized
    fun isAuthed(sessionId: String): Boolean = sessionId in authed

    @Synchronized
    fun markAuthed(sessionId: String) {
        authed.add(sessionId)
    }

    @Synchronized
    fun clear(sessionId: String) {
        authed.remove(sessionId)
    }
}

/**
 * Full-screen gate shown instead of a locked chat until [onUnlocked] fires.
 *
 * Uses the system credential confirmation
 * ([KeyguardManager.createConfirmDeviceCredentialIntent]) via
 * [DeviceCredential] — no biometric dependency, no custom PIN to forget.
 * When the phone has no screen lock there is nothing to confirm with, so
 * the button is hidden and the gate explains that instead.
 */
@Composable
fun LockedChatGate(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptic()
    val deviceSecure = DeviceCredential.available(context)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            haptics.success()
            onUnlocked()
        }
    }

    Surface(
        color = MuseTones.canvas,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .staggeredEntrance(0),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "This chat is locked",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (deviceSecure) {
                    "Confirm with your screen lock to open it."
                } else {
                    "Locking needs a screen lock (PIN, pattern, fingerprint). " +
                        "Set one in system settings to use locked chats."
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (deviceSecure) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    haptics.tap()
                    val intent = DeviceCredential.keyguardIntent(
                        context,
                        "Unlock chat",
                        "Confirm it's you to open this locked chat",
                    )
                    if (intent != null) launcher.launch(intent)
                    else onUnlocked() // nothing to confirm with
                }) {
                    Text("Unlock")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Unlocked chats stay unlocked until the app restarts.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Hour buckets for the chat menu's auto-delete cycler. */
internal val CHAT_AUTO_DELETE_BUCKETS = longArrayOf(0L, 1L, 24L, 168L)

/** "0h" → Never, "1h" → 1 hour, "24h" → 1 day, "168h" → 1 week. */
fun autoDeleteLabel(hours: Long): String = when (hours) {
    0L -> "Never"
    1L -> "1 hour"
    24L -> "1 day"
    168L -> "1 week"
    else -> "$hours hours"
}

/** Next bucket after [hours], wrapping around. */
fun nextAutoDeleteBucket(hours: Long): Long {
    val i = CHAT_AUTO_DELETE_BUCKETS.indexOf(hours)
    return CHAT_AUTO_DELETE_BUCKETS[(i + 1).takeIf { i >= 0 } ?: 0]
}
