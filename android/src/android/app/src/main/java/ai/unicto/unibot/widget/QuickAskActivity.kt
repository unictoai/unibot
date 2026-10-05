package ai.unicto.unibot.widget
import ai.unicto.unibot.ui.theme.UbColors

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.logging.AppLogger

/**
 * v0.2.0 P3 — the text-entry sheet behind the "Quick ask" widget.
 *
 * Home-screen widgets cannot host a real EditText, so the widget's
 * input-look row opens this translucent activity instead. Sending fires
 * `unibot://ask?text=...`, which prefills the main chat's composer —
 * never auto-sends. Tapping outside the card (or Cancel) dismisses.
 *
 * Manifest: translucent, noHistory, excluded from recents — it must feel
 * like a sheet over the launcher, not a new app screen.
 */
class QuickAskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var text by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            val tapOutside = remember { MutableInteractionSource() }
            val tapCard = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000))
                    .clickable(
                        interactionSource = tapOutside,
                        indication = null,
                        onClick = { finish() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(24.dp),
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
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.ub_widget_quick_ask_hint),
                                    color = UbColors.systemGray,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2C2C2E),
                                unfocusedContainerColor = Color(0xFF2C2C2E),
                                disabledContainerColor = Color(0xFF2C2C2E),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFA78BFA),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            shape = RoundedCornerShape(16.dp),
                            maxLines = 4,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { finish() }) {
                                Text(
                                    stringResource(android.R.string.cancel),
                                    color = UbColors.systemGray,
                                )
                            }
                            Button(
                                onClick = { sendAsk(text) },
                                enabled = text.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6D28D9),
                                    disabledContainerColor = Color(0xFF3A3A3C),
                                    contentColor = Color.White,
                                    disabledContentColor = UbColors.systemGray,
                                ),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(stringResource(R.string.ub_widget_quick_ask_send))
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }

    private fun sendAsk(text: String) {
        if (text.isBlank()) {
            finish()
            return
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("unibot://ask?text=" + Uri.encode(text)),
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
