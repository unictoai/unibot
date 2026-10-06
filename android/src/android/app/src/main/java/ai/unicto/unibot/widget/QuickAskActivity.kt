package ai.unicto.unibot.widget

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.ui.theme.UnibotTheme

/**
 * v0.2.0 P3 — the text-entry sheet behind the "Quick ask" widget.
 *
 * Home-screen widgets cannot host a real EditText, so the widget's
 * input-look row opens this translucent activity instead. Sending fires
 * `unibot://ask?text=...&new=1`, which opens a FRESH draft chat with the
 * text prefilled — never auto-sends, and never pollutes the main chat.
 * Tapping outside the card (or Cancel) dismisses.
 *
 * v1.4.0 item 65: all styling now comes from the theme (UnibotTheme +
 * MaterialTheme roles) — no hardcoded colors or font sizes.
 *
 * Manifest: translucent, noHistory, excluded from recents — it must feel
 * like a sheet over the launcher, not a new app screen.
 */
class QuickAskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UnibotTheme(darkTheme = isSystemInDarkTheme()) {
                QuickAskSheet(onSend = ::sendAsk, onDismiss = ::finish)
            }
        }
    }

    private fun sendAsk(text: String) {
        if (text.isBlank()) {
            finish()
            return
        }
        // v1.4.0 item 73: &new=1 — the question lands in a fresh draft chat
        // with the text prefilled (DeepLinkAction.Ask.newChat).
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("unibot://ask?text=" + Uri.encode(text) + "&new=1"),
        ).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            AppLogger.info(TAG, "no handler for unibot://ask")
        }
        finish()
    }

    companion object {
        private const val TAG = "QuickAsk"
    }
}

/**
 * The sheet UI. Theme-token only: surface roles for the card/field, the
 * primary role for the send button, typography roles for text.
 */
@androidx.compose.runtime.Composable
private fun QuickAskSheet(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val tapOutside = remember { MutableInteractionSource() }
    val tapCard = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = tapOutside,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                // Swallow taps so they don't fall through to the
                // dismiss-on-scrim-tap above.
                .clickable(
                    interactionSource = tapCard,
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.ub_widget_quick_ask_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.ub_widget_quick_ask_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    shape = MaterialTheme.shapes.large,
                    maxLines = 4,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(android.R.string.cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { onSend(text) },
                        enabled = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.ub_widget_quick_ask_send))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
