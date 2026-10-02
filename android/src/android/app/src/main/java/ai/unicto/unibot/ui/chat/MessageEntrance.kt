package ai.unicto.unibot.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * One-shot entrance for a newly arrived message: fade + 8dp rise over 300ms.
 *
 * Keyed on the item key so it runs exactly once per message — never again on
 * recomposition (streaming tokens) or LazyColumn recycling. The caller decides
 * which items get it (only the newest message); every other item renders with
 * zero added cost, keeping the streaming path clean.
 */
@Composable
fun Modifier.messageEntrance(key: Any, enabled: Boolean): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val risePx = remember(key) { with(density) { 8.dp.toPx() } }
    val alpha = remember(key) { Animatable(0f) }
    val rise = remember(key) { Animatable(risePx) }
    LaunchedEffect(key) {
        launch { alpha.animateTo(1f, tween(300)) }
        launch { rise.animateTo(0f, tween(300)) }
    }
    return this.graphicsLayer {
        this.alpha = alpha.value
        translationY = rise.value
    }
}
