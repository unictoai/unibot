package ai.unicto.unibot.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v1.4.0 item 16 — per-message token sheet. Tap the token count under an
 * assistant message to see what that turn used, plus the chat totals framed
 * as "$0 spent": unibot never bills — the user's own API key pays the
 * provider directly.
 *
 * Numbers shown are MEASURED (recorded per turn in the DB). The only
 * limit-derived value — the model's effective output cap — is routed
 * through the v1.2.9 corruption-proof clamp
 * ([ChatViewModel.effectiveCurrentMaxOutputTokens]), never the raw stored
 * metadata.
 */
@Composable
fun MessageTokenSheet(
    viewModel: ChatViewModel,
    messageId: String,
    onDismiss: () -> Unit,
) {
    var stats by remember { mutableStateOf<ChatViewModel.MessageTokenStats?>(null) }
    var session by remember { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val effectiveMaxOutput = remember { viewModel.effectiveCurrentMaxOutputTokens() }

    LaunchedEffect(messageId) {
        stats = viewModel.messageTokenStats(messageId)
        session = viewModel.loadSessionTokenStats()
        loaded = true
    }

    StandardChatSheet(
        title = "Tokens",
        onDismiss = onDismiss,
        heightFraction = 0.55f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!loaded) {
                Text(
                    text = "Loading…",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val s = stats
            if (s == null) {
                Text(
                    text = "No token data recorded for this message yet.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TokenSheetSection(title = "This message") {
                    TokenSheetRow("Input", formatTokenCount(s.input))
                    TokenSheetRow("Output", formatTokenCount(s.output))
                    TokenSheetRow("Total", formatTokenCount(s.total))
                    s.modelName?.let { TokenSheetRow("Model", it) }
                }
            }

            TokenSheetSection(title = "Chat totals") {
                val t = session
                TokenSheetRow("Input", formatTokenCount(t?.input ?: 0L))
                TokenSheetRow("Output", formatTokenCount(t?.output ?: 0L))
                TokenSheetRow(
                    "Total",
                    formatTokenCount((t?.input ?: 0L) + (t?.output ?: 0L)),
                )
            }

            effectiveMaxOutput?.let {
                TokenSheetSection(title = "Model") {
                    TokenSheetRow("Effective max output", formatTokenCount(it.toLong()))
                }
            }

            TokenSheetSection(title = "Cost") {
                TokenSheetRow("Spent via unibot", "$0.00")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "unibot is free — your own API key pays the provider " +
                        "directly. Nothing here ever bills you.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TokenSheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun TokenSheetRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

// iOS formatting: "%.1fM" >= 1M, "%.1fK" >= 1k, raw otherwise.
private fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}
