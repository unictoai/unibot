package ai.unicto.unibot.ui.muse

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ripple

/**
 * Shared press feedback: the row scales to 0.97 while pressed, with a spring
 * back. Use on [MuseRow], settings rows, drawer rows — anywhere a row is
 * tappable. Cheap: one float animation, no layout.
 *
 * Returns the [MutableInteractionSource] so the caller can share it with its
 * own [clickable]; or use [pressableRow] for the common case.
 */
@Composable
fun rememberPressScale(): Pair<Modifier, MutableInteractionSource> {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    val modifier = Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    return modifier to interactionSource
}

/**
 * The common tappable-row modifier: press-scale 0.97 + ripple, sharing one
 * interaction source so the scale and ripple stay in sync.
 */
@Composable
fun Modifier.pressableRow(onClick: () -> Unit): Modifier {
    val (scaleModifier, interactionSource) = rememberPressScale()
    return this
        .then(scaleModifier)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = onClick,
        )
}

/**
 * Plain scale-only press feedback for rows that already own their clickable
 * (share the returned interaction source with it).
 */
@Composable
fun Modifier.pressScaleOnly(): Modifier {
    val (scaleModifier, _) = rememberPressScale()
    return this.then(scaleModifier)
}

/** Drop-in scale for tab icons etc. (see MuseBottomBar). */
@Composable
fun selectedPopScale(selected: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "selectedPop",
    )
    return Modifier.scale(scale)
}
