package ai.unicto.unibot.ui.knowledge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R

/**
 * UI state for the camera scan-to-text flow (item 50).
 */
sealed interface ScanOcrUiState {
    /** The photo was captured; recognition is running. */
    data object Scanning : ScanOcrUiState
    /** Recognition finished — text is editable before inserting. */
    data class Done(val text: String) : ScanOcrUiState
    /** No OCR backend on this device; the photo can be kept as an attachment. */
    data class Unavailable(val reason: String) : ScanOcrUiState
}

/**
 * Result dialog for the scan flow: progress while recognizing, an editable
 * text field on success (chat-editable text per the backlog), and a graceful
 * fallback when on-device OCR isn't available.
 */
@Composable
fun ScanTextDialog(
    state: ScanOcrUiState,
    onInsert: (String) -> Unit,
    onAttachPhotoInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    var edited by remember(state) {
        mutableStateOf((state as? ScanOcrUiState.Done)?.text.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ub_scan_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is ScanOcrUiState.Scanning -> {
                        Text(
                            text = stringResource(R.string.ub_scan_reading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is ScanOcrUiState.Done -> {
                        if (state.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.ub_scan_no_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedTextField(
                                value = edited,
                                onValueChange = { edited = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                                textStyle = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    is ScanOcrUiState.Unavailable -> {
                        Text(
                            text = state.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                when (state) {
                    is ScanOcrUiState.Done -> {
                        if (state.text.isNotEmpty()) {
                            TextButton(onClick = { onInsert(edited) }) {
                                Text(stringResource(R.string.ub_scan_insert))
                            }
                        } else {
                            TextButton(onClick = onAttachPhotoInstead) {
                                Text(stringResource(R.string.ub_scan_attach_instead))
                            }
                        }
                    }
                    is ScanOcrUiState.Unavailable -> {
                        TextButton(onClick = onAttachPhotoInstead) {
                            Text(stringResource(R.string.ub_scan_attach_instead))
                        }
                    }
                    is ScanOcrUiState.Scanning -> {}
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ub_cancel))
                }
            }
        },
    )
}
