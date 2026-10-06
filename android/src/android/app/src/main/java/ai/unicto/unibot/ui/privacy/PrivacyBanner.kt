package ai.unicto.unibot.ui.privacy

import ai.unicto.unibot.privacy.PrivacyPrefs
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wave 5 (v1.0) — privacy core. A small persistent banner shown while any
 * network gate is active: local-only mode, the kill switch (item 58), or
 * on-device-only mode (item 62). The kill switch and on-device-only states
 * take precedence in the copy because they sever ALL network.
 *
 * Tapping it opens the privacy dashboard — it deliberately does NOT offer
 * a one-tap disable, so a gate can't be switched off by an accidental tap;
 * the user turns it off explicitly in Settings → Privacy.
 */
@Composable
fun PrivacyBanner(onOpenDashboard: () -> Unit) {
    val localOnly by PrivacyPrefs.localOnly.collectAsState()
    val killSwitch by PrivacyPrefs.killSwitch.collectAsState()
    val onDeviceOnly by PrivacyPrefs.onDeviceOnly.collectAsState()
    if (!localOnly && !killSwitch && !onDeviceOnly) return
    val haptics = rememberHaptic()
    val message = when {
        killSwitch -> "Kill switch is on — all network is severed"
        onDeviceOnly -> "On-device-only mode — only on-device models and voice run"
        else -> "Local-only mode is on — only your AI provider can connect"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = {
                haptics.tap()
                onOpenDashboard()
            })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
