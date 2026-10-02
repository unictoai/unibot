package ai.unicto.unibot.speech

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.theme.ChatColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [P1-dictation-cleanup] Pure-local post-pass over Whisper/system dictation.
 *
 * Strips filler words ("um", "uh", "ah", "you know", interjection "like"),
 * then applies basic written-text conventions: collapsed whitespace, no
 * space before punctuation, sentence capitalization, terminal punctuation.
 *
 * NO network, NO LLM — a small regex/rules pass only. Opt-in via
 * [DictationCleanupPrefs]; default OFF so raw transcripts are untouched
 * unless the user asks for cleanup.
 */
object DictationCleanup {

    private val fillerTokens = Regex(
        "\\b(um+|uh+|uhm+|ah+|er+|erm+|hmm+|mm+)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val fillerPhrases = listOf("you know", "i mean", "sort of", "kind of")

    /**
     * Interjection "like" only — between punctuation or at a boundary, so
     * "I like apples" is never touched but "it's, like, great" is cleaned.
     */
    private val likeBetweenPunct = Regex("([,.!?])\\s*like\\s*(?=[,.!?])", RegexOption.IGNORE_CASE)
    private val likeAtEdge = Regex("(^|[,.!?])\\s*like\\s*(?=$|[.!?])", RegexOption.IGNORE_CASE)

    private val spaceBeforePunct = Regex("\\s+([,.!?;:])")
    // NOTE: ':' only splits before a letter — "10:30" must survive intact.
    private val missingSpaceAfterPunct = Regex("([,!?;])([^\\s,.!?;:])")
    private val missingSpaceAfterColon = Regex("(:)([A-Za-z])")
    private val sentenceStart = Regex("(^|[.!?]\\s+)([a-z])")

    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // 1. Strip fillers.
        s = fillerTokens.replace(s, "")
        for (phrase in fillerPhrases) {
            s = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE).replace(s, "")
        }
        s = likeBetweenPunct.replace(s, "$1")
        s = likeAtEdge.replace(s, "$1")

        // 2. Whitespace / punctuation spacing.
        s = s.replace(Regex("\\s+"), " ")
        s = spaceBeforePunct.replace(s, "$1")
        s = missingSpaceAfterPunct.replace(s, "$1 $2")
        s = missingSpaceAfterColon.replace(s, "$1 $2")
        // A filler removal can strand ", ," or leading punctuation — tidy up.
        s = s.replace(Regex(",\\s*,"), ",").trim()
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^[,.!?:;\\s]+"), "")
        if (s.isEmpty()) return s

        // 3. Capitalization: first letter + after sentence-ending punctuation.
        s = sentenceStart.replace(s) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
        s = s.replaceFirstChar { it.uppercaseChar() }

        // 4. Terminal punctuation when the text ends mid-air on a word char.
        val last = s.last()
        if (last.isLetterOrDigit()) s += "."

        return s
    }
}

/**
 * Opt-in toggle for [DictationCleanup], persisted in SharedPreferences.
 * Default OFF.
 */
object DictationCleanupPrefs {
    private const val PREFS = "dictation_cleanup"
    private const val KEY_ENABLED = "enabled"

    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Current value for non-composable call sites (e.g. the transcribe callback). */
    val isEnabled: Boolean get() = _enabled.value

    /** One-shot load; idempotent, safe to call per composition. */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        _enabled.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, on: Boolean) {
        init(context)
        _enabled.value = on
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }
}

/**
 * [P1-dictation-cleanup] Toggle row for the expanded voice panel, next to
 * the ASR engine chip. Reads/writes [DictationCleanupPrefs] directly so no
 * parameter threading through the panel is needed.
 */
@Composable
fun DictationCleanupRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DictationCleanupPrefs.init(context)
    val enabled by DictationCleanupPrefs.enabled.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { DictationCleanupPrefs.setEnabled(context, !enabled) }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dictation_cleanup_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.dictation_cleanup_subtitle),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = ChatColors.secondaryText,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { DictationCleanupPrefs.setEnabled(context, it) },
        )
    }
}
