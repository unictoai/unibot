package ai.unicto.unibot.ui.settings

import ai.unicto.unibot.data.repository.SkillRepository
import ai.unicto.unibot.ui.components.UnibotTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Item 90 — gallery import review. Shows the PARSED skill fetched from a URL
 * and requires an explicit tap on Install. Nothing is written to disk or the
 * database before that tap; the preview call itself never installs.
 *
 * Install is disabled when the preview carries validation errors, with the
 * reasons listed so the user knows exactly why.
 */
@Composable
fun SkillImportReviewDialog(
    preview: SkillRepository.SkillPreview,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review skill before installing") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Identity
                Text(preview.name, style = MaterialTheme.typography.titleMedium)
                if (preview.description.isNotBlank()) {
                    Text(preview.description, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = {}, label = { Text("v${preview.version}") })
                    if (preview.author.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(preview.author) })
                    }
                }

                // Declared tool surface — the safety-relevant part of the review.
                Text("Declared tools", style = MaterialTheme.typography.labelLarge)
                if (preview.tools.isEmpty()) {
                    ReviewLine(
                        icon = Icons.Outlined.CheckCircle,
                        text = "No extra tools — prompt instructions only.",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        preview.tools.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Text(
                    "Source: ${preview.sourceUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Instructions preview
                if (preview.bodyPreview.isNotBlank()) {
                    Text("Instructions preview", style = MaterialTheme.typography.labelLarge)
                    Text(
                        preview.bodyPreview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 160.dp),
                    )
                    Text(
                        "${preview.bodyLength} characters total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Validation findings
                for (error in preview.errors) {
                    ReviewLine(
                        icon = Icons.Outlined.ErrorOutline,
                        text = error,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                for (warning in preview.warnings) {
                    ReviewLine(
                        icon = Icons.Outlined.WarningAmber,
                        text = warning,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }

                Text(
                    "Skills run as prompt instructions only — they cannot execute code on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            UnibotTextButton(onClick = onInstall, enabled = preview.canInstall) {
                Text("Install skill")
            }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ReviewLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}
