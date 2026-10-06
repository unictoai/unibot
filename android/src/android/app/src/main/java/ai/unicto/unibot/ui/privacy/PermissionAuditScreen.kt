package ai.unicto.unibot.ui.privacy
import ai.unicto.unibot.ui.theme.UbColors

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val ROUTE_PERMISSION_AUDIT = "unibot/permission_audit"

/**
 * Wave 5 (v1.0) — privacy core. Reads the permissions this app declares in
 * its manifest and shows which are granted vs denied right now, from the
 * package manager — not from a hardcoded list, so it can never drift from
 * reality.
 *
 * No new permissions are declared by this wave; the screen is read-only,
 * with a button that opens the system's app-details page for changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAuditScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptic()

    val permissions = remember(context) { readDeclaredPermissions(context) }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Permission audit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                MuseCaption(
                    text = "Every permission this app declares, read live from the " +
                        "package manager. Green = granted, grey = not granted. " +
                        "Nothing here is requested by the privacy features themselves.",
                )
            }
            itemsIndexed(permissions) { index, (name, granted) ->
                PermissionRow(name = name, granted = granted, index = index)
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        haptics.tap()
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open system settings")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean, index: Int) {
    val dot = if (granted) UbColors.success else UbColors.systemGray
    Surface(
        color = MuseTones.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortPermissionName(name),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                // Privacy item 63 — why the app wants this permission, in
                // plain language. Unknown permissions get the honest
                // fallback instead of a made-up reason.
                Text(
                    text = permissionRationale(name),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                Text(
                    text = name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            Text(
                text = if (granted) "Granted" else "Not granted",
                fontSize = 12.sp,
                color = if (granted) UbColors.success
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "android.permission.CAMERA" → "CAMERA"; keeps the full name as subtitle. */
private fun shortPermissionName(name: String): String =
    name.substringAfterLast('.').replace('_', ' ').lowercase()
        .replaceFirstChar { it.uppercase() }

/**
 * Privacy item 63 — plain-language reason per permission, matched against
 * the manifest's declared permissions. Anything not listed gets the honest
 * fallback: the screen reads the permission live from the package manager,
 * so a new permission can appear here before anyone writes its rationale.
 */
private fun permissionRationale(name: String): String {
    val short = name.substringAfterLast('.')
    return when (short) {
        "INTERNET" ->
            "Required — every AI provider call, web search, and connector sync uses the network."
        "ACCESS_NETWORK_STATE" ->
            "Detects connectivity changes so retries and the offline state stay honest."
        "RECORD_AUDIO" ->
            "Voice input and voice chat — only while you hold the mic."
        "CAMERA" ->
            "Camera scan-to-text and photo attachments — only when you use them."
        "POST_NOTIFICATIONS" ->
            "Mission-complete pings and background-task updates you asked for."
        "READ_MEDIA_IMAGES", "READ_MEDIA_VISUAL_USER_SELECTED" ->
            "Lets you attach photos to chats from the gallery."
        "ACCESS_MEDIA_LOCATION" ->
            "Reads location metadata from photos you choose to attach."
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE" ->
            "File access on older Android versions (replaced by scoped storage on new ones)."
        "MANAGE_EXTERNAL_STORAGE" ->
            "Browsing mounted folders you explicitly grant access to."
        "READ_CALENDAR", "WRITE_CALENDAR" ->
            "The calendar connector — reading and creating events you ask for."
        "READ_CONTACTS", "WRITE_CONTACTS" ->
            "Sharing and contact lookup features — only with your contacts' data on this phone."
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION" ->
            "Location-aware answers (weather, nearby places) — only when you ask."
        "USE_BIOMETRIC" ->
            "App lock — confirming it's you with fingerprint or face."
        "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "FOREGROUND_SERVICE_REMOTE_MESSAGING" ->
            "Keeps long voice playback and hub tasks alive in the background."
        "WAKE_LOCK" ->
            "Keeps long downloads or uploads from being killed mid-transfer."
        "SCHEDULE_EXACT_ALARM", "SET_ALARM", "com.android.alarm.permission.SET_ALARM" ->
            "Reminders and scheduled tasks firing at the time you set."
        "RECEIVE_BOOT_COMPLETED" ->
            "Re-schedules your alarms and tasks after a reboot."
        "REQUEST_INSTALL_PACKAGES" ->
            "Installs app updates you download from inside unibot."
        "SYSTEM_ALERT_WINDOW" ->
            "Floating overlay features — only when you enable them."
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" ->
            "Keeps scheduled background work reliable when the phone sleeps."
        "POST_PROMOTED_NOTIFICATIONS" ->
            "Lets the system surface unibot's notifications appropriately."
        "API_V23" ->
            "Shizuku integration for the elevated operations you opt into."
        else ->
            if (name.endsWith(".permission.ALARM")) {
                "The app's own alarm channel for reminders and scheduled tasks."
            } else {
                "Declared by the app — no feature description recorded for it yet."
            }
    }
}

/**
 * Declared permissions + their current grant state, straight from the
 * package manager. Empty (not null) when the manifest declares none.
 */
private fun readDeclaredPermissions(context: android.content.Context): List<Pair<String, Boolean>> {
    return try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val names = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(names.size)
        names.mapIndexed { i, name ->
            val granted = i < flags.size &&
                (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            name to granted
        }
    } catch (t: Throwable) {
        android.util.Log.w("PermissionAudit", "could not read permissions: ${t.message}")
        emptyList()
    }
}
