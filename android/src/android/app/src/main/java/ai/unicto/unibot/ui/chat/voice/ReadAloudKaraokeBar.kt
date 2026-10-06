package ai.unicto.unibot.ui.chat.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.speech.SpokenWord

/**
 * Voice theme item 33: karaoke-style follow-along for long replies.
 *
 * A slim strip above the composer showing the sentence currently being read
 * aloud, with the spoken word highlighted. The word range comes from the
 * engine's onRangeStart callback (system-engine path); provider-audio
 * utterances carry no range, so the sentence shows without a highlight.
 * The X stops read-aloud — every control does something.
 */
@Composable
fun ReadAloudKaraokeBar(
    spokenWord: SpokenWord,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
    ) {
        val annotated = buildAnnotatedString {
            val range = spokenWord.wordRange
            val text = spokenWord.text
            if (range != null && text.isNotEmpty() && range.first < text.length) {
                val s = range.first.coerceIn(0, text.length - 1)
                val e = range.last.coerceIn(s, text.length - 1)
                append(text.substring(0, s))
                withStyle(
                    SpanStyle(
                        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    ),
                ) {
                    append(text.substring(s, e + 1))
                }
                append(text.substring(e + 1))
            } else {
                append(text)
            }
        }
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
