package ai.unicto.unibot.ui.pairing

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.muse.pressableRow
import ai.unicto.unibot.ui.theme.ChatColors
import java.text.DateFormat
import java.util.Date

/**
 * P4 "Pair a computer" screen (v0.2.0).
 *
 * Shows the pairing QR (token payload per the documented v1 protocol),
 * token actions, and the remote-sessions list with revoke. Muse design
 * language; the QR itself is always black-on-white so it scans in any
 * theme. Entry: Settings → Pair a computer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P4PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tokenVersion by remember { mutableStateOf(0) }
    // Payload + QR, computed off the main thread (the 640² pixel loop is
    // not composition work). Rebuilt when the token rotates.
    val qrState by produceState<Pair<String?, androidx.compose.ui.graphics.ImageBitmap?>>(
        initialValue = null to null,
        tokenVersion,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val p = p4PairingPayload(context)
            p to P4Qr.render(p)
        }
    }
    val (payload, qr) = qrState
    var remotes by remember { mutableStateOf(P4PairingStore.remoteSessions(context)) }
    fun refreshRemotes() {
        remotes = P4PairingStore.remoteSessions(context)
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Pair a computer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MuseSectionLabel("Scan with the desktop client")
            MuseCard(inset = 0.dp) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Always black-on-white: QR scanners need the contrast in
                    // both themes.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (qr != null) {
                            Image(
                                bitmap = qr,
                                contentDescription = "Pairing QR code",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    MuseCaption(
                        "The desktop client scans this, connects to your relay " +
                            "and pairs with this phone. The code holds a secret " +
                            "token — don't share screenshots of it.",
                    )
                }
            }
            MuseGap()

            MuseSectionLabel("Pairing token")
            MuseCard(inset = 0.dp) {
                MuseRow(
                    title = "Copy payload",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        payload?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                )
                MuseRowDivider()
                MuseRow(
                    title = "Regenerate token",
                    icon = Icons.Outlined.Refresh,
                    onClick = {
                        P4PairingStore.regenerateToken(context)
                        tokenVersion++
                    },
                )
            }
            MuseCaption(
                "Regenerating signs out every paired desktop — they scan again.",
                modifier = Modifier.padding(top = 6.dp),
            )
            MuseGap()

            MuseSectionLabel("Remote sessions")
            MuseCard(inset = 0.dp) {
                if (remotes.isEmpty()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Computer,
                                contentDescription = null,
                                tint = ChatColors.secondaryText,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "No desktops paired yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ChatColors.secondaryText,
                            )
                        }
                    }
                } else {
                    remotes.forEachIndexed { i, remote ->
                        if (i > 0) MuseRowDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Computer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = remote.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Paired " + DateFormat.getDateInstance(DateFormat.MEDIUM)
                                        .format(Date(remote.pairedAt)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ChatColors.secondaryText,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .pressableRow {
                                        P4PairingStore.revokeRemoteSession(context, remote.id)
                                        refreshRemotes()
                                    }
                                    .padding(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Revoke",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            MuseGap()

            MuseSectionLabel("How it works")
            MuseCard(inset = 0.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    HowRow("1", "Scan", "The desktop client scans the QR on this screen.")
                    HowRow("2", "Pair", "It connects to your relay and proves the token.")
                    HowRow("3", "Drive", "This phone can then drive the desktop agent.")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HowRow(num: String, title: String, body: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = num,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
        }
    }
}
