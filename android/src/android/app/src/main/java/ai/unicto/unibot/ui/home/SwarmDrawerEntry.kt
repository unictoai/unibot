package ai.unicto.unibot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.swarm.SharedPrefsSwarmCheckpointStore
import ai.unicto.unibot.swarm.SwarmDrawerHint
import ai.unicto.unibot.swarm.SwarmStatus
import ai.unicto.unibot.ui.navigation.Routes

/**
 * [v1.3.5] The destination the drawer's Swarm entry opens.
 *
 * Pinned to [Routes.SWARM] — the same dedicated swarm space the chat top-bar
 * pill opens — so the two entries can never drift to different destinations.
 * Covered by SwarmDrawerEntryTest.
 */
internal fun swarmEntryRoute(): String = Routes.SWARM

/**
 * [v1.3.5] The nav drawer's Swarm entry: directly below Devices, opening the
 * same swarm space as the top-bar pill.
 *
 * It carries the pill's violet identity without copying its shape (a pill in
 * the drawer would read as a button, not a row): the AutoAwesome glyph — the
 * same icon the pill uses — tinted with the theme's violet primary,
 * everything else monochrome like the neighbouring rows. The status line
 * mirrors the Devices row: the live agent count while a run is
 * planning/running/paused, "Idle" otherwise. No badges, no animation —
 * professional restraint.
 */
@Composable
internal fun SwarmDrawerRow(onSwarm: () -> Unit) {
    val context = LocalContext.current
    // Replay a checkpointed (interrupted) run into the status mirror so the
    // hint is honest from the first frame, before the swarm screen's engine
    // exists in this process.
    LaunchedEffect(Unit) {
        SwarmStatus.ensureCheckpointMirror(SharedPrefsSwarmCheckpointStore(context))
    }
    val hint by SwarmStatus.hint.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSwarm)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.ub_drawer_swarm),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (val h = hint) {
                is SwarmDrawerHint.Active ->
                    pluralStringResource(R.plurals.ub_swarm_agents, h.agentCount, h.agentCount)
                SwarmDrawerHint.Idle -> stringResource(R.string.ub_swarm_idle)
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
