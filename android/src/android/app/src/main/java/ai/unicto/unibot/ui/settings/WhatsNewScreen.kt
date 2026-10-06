package ai.unicto.unibot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.unicto.unibot.BuildConfig
import ai.unicto.unibot.ui.components.UnibotAlertDialog
import ai.unicto.unibot.ui.navigation.Routes
import ai.unicto.unibot.ui.navigation.safeNavigate

/**
 * v1.4.0 item 72 — the "What's new" changelog screen, rendered from the
 * bundled [WHATS_NEW] data. Reached from About and from the one-shot
 * version-bump dialog ([WhatsNewHost]).
 */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    SettingsScaffold(title = "What's new", onBack = onBack) {
        // The scaffold already scrolls (scrollable = true); a plain Column
        // of entries is all this short changelog needs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            WHATS_NEW.forEach { entry ->
                WhatsNewEntryBlock(entry = entry)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "unibot ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WhatsNewEntryBlock(entry: WhatsNewEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.NewReleases,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.version,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.date,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.highlights.forEach { highlight ->
            Row {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = highlight,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * v1.4.0 item 72 — one-shot "What's new" dialog, shown once per version
 * bump. Hosted at the MainActivity root (below the splash overlay, above
 * the nav host) so it appears on top of wherever the user lands.
 */
@Composable
fun WhatsNewHost(navController: NavHostController) {
    val context = LocalContext.current
    val currentVersion = remember { BuildConfig.VERSION_NAME }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = WhatsNewStore.shouldShow(currentVersion, WhatsNewStore.lastSeen(context))
        WhatsNewStore.markShown(context, currentVersion)
    }
    val entry = WHATS_NEW.firstOrNull { it.version == currentVersion } ?: WHATS_NEW.firstOrNull()
    if (visible && entry != null) {
        UnibotAlertDialog(
            onDismissRequest = { visible = false },
            title = "What's new in ${entry.version}",
            text = entry.highlights.joinToString("\n") { "• $it" },
            confirmText = "See all",
            onConfirm = {
                visible = false
                navController.safeNavigate(Routes.WHATS_NEW)
            },
            dismissText = "Close",
        )
    }
}
