package ai.unicto.unibot.ui.muse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.ui.theme.ChatColors

/**
 * The one drag handle every bottom sheet uses: a slim 32×4dp pill with 6dp top
 * / 4dp bottom padding, so the title sits close to the indicator instead of
 * the Material default's ~44dp whitespace gap. (The sheet container itself
 * keeps M3's default 28dp top corners.)
 */
@Composable
fun MuseSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                    color = ChatColors.secondaryText.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}
