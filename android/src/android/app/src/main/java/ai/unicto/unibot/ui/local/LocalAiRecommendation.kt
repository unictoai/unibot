package ai.unicto.unibot.ui.local

// [v1.2 Batch F] Cards embedded in the on-device model screen (see the
// // [v12-F] call sites in OnDeviceModelsSection):
// - RecommendedModelCard: "Your phone: X GB RAM → recommended: <model>" with
//   a working Download/Use action.
// - LastTurnContextRow: tokens-used / context-max for the most recent
//   on-device chat turn.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.local.LlamaModel
import ai.unicto.unibot.local.LlamaModelManager
import ai.unicto.unibot.local.LocalChatService
import ai.unicto.unibot.ui.theme.ChatColors
import ai.unicto.unibot.ui.theme.animationsEnabled
import ai.unicto.unibot.ui.theme.staggeredEntrance

/**
 * "Recommended for your phone" card. The recommendation comes from
 * [LlamaModelManager.ramRecommendation] (device total RAM → strongest model
 * that fits). The action button is always live: Download when missing, Use
 * when already on the phone.
 */
@Composable
fun RecommendedModelCard(
    onDownload: (LlamaModel) -> Unit,
    onUse: (LlamaModel) -> Unit,
) {
    val ctx = LocalContext.current
    val rec = remember { LlamaModelManager.ramRecommendation(ctx) }
    // Observe download states so the button flips to "Use" the moment the
    // download finishes.
    val states by LlamaModelManager.downloadStates.collectAsState()
    val downloaded = states[rec.model.id] is LlamaModelManager.DownloadState.Done ||
        LlamaModelManager.isDownloaded(ctx, rec.model)
    val accent = MaterialTheme.colorScheme.primary
    val animated = animationsEnabled()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (animated) Modifier.staggeredEntrance(0) else Modifier)
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.v12_recommend_title),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = stringResource(
                R.string.v12_recommend_body,
                rec.ramLabel,
                rec.model.title,
            ),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${rec.model.hint} · ${rec.model.sizeLabel}",
            style = TextStyle(fontSize = 11.sp),
            color = ChatColors.secondaryText,
        )
        if (downloaded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.v12_recommend_ready),
                    style = TextStyle(fontSize = 12.sp),
                    color = ChatColors.secondaryText,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onUse(rec.model) }) {
                    Text(stringResource(R.string.v12_recommend_use))
                }
            }
        } else {
            Button(
                onClick = { onDownload(rec.model) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.v12_recommend_download))
            }
        }
    }
}

/**
 * Context-size indicator for on-device chats: tokens used (prompt +
 * generated) of the model's context window, from the most recent on-device
 * turn. Hidden until the first on-device reply happens.
 */
@Composable
fun LastTurnContextRow() {
    val stats by LocalChatService.lastTurnStats.collectAsState()
    val s = stats ?: return
    val used = s.promptTokens + s.generatedTokens
    val fraction = if (s.contextMax > 0) {
        (used.toFloat() / s.contextMax).coerceIn(0f, 1f)
    } else {
        0f
    }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.v12_context_title),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.v12_context_used, used, s.contextMax),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = accent,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = accent,
            trackColor = ChatColors.inputIconBg,
        )
        Text(
            text = s.modelTitle,
            style = TextStyle(fontSize = 11.sp),
            color = ChatColors.secondaryText,
        )
    }
}
