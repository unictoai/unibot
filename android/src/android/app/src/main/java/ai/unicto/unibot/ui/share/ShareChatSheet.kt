package ai.unicto.unibot.ui.share

import ai.unicto.unibot.R
import ai.unicto.unibot.data.db.ChatSessionEntity
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.share.ChatHtmlExporter
import ai.unicto.unibot.ui.chat.StandardChatSheet
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.theme.ChatColors
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Per-share consent for sharing one chat (P8).
 *
 * The cloud relay has no public-blob endpoint, so sharing produces a
 * self-contained HTML file ([ChatHtmlExporter]) handed to the Android share
 * sheet — the person picks the destination themselves. Nothing is uploaded
 * by unibot, and nothing here is automatic: this sheet is the explicit
 * opt-in, shown every time, saying exactly what leaves the phone.
 */
@Composable
fun ShareChatSheet(
    session: ChatSessionEntity,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messageCount by remember { mutableStateOf(-1) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session.id) {
        messageCount = runCatching { chatRepository.messageCount(session.id) }.getOrDefault(0)
    }

    fun share() {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                val uri = ChatHtmlExporter.exportToHtml(context, session, chatRepository)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, context.getString(R.string.ub_share_title))
                    .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(chooser)
                onDismiss()
            } catch (t: Throwable) {
                error = context.getString(R.string.ub_share_failed, t.message ?: t.javaClass.simpleName)
            }
            working = false
        }
    }

    StandardChatSheet(
        title = stringResource(R.string.ub_share_title),
        onDismiss = onDismiss,
        heightFraction = 0.7f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                session.title ?: stringResource(R.string.ub_share_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (messageCount >= 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ub_share_messages, messageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.thinking,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.ub_share_what),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ShareBullet(stringResource(R.string.ub_share_bullet_file))
            ShareBullet(stringResource(R.string.ub_share_bullet_text))
            ShareBullet(stringResource(R.string.ub_share_bullet_local))
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            UnibotButton(
                onClick = ::share,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (working) stringResource(R.string.ub_share_exporting) else stringResource(R.string.ub_share_action))
            }
            Spacer(Modifier.height(8.dp))
            UnibotTextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ub_share_cancel))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ShareBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
