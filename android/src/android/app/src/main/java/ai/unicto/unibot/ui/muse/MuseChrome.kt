package ai.unicto.unibot.ui.muse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import ai.unicto.unibot.ui.home.MuseTones

/**
 * Muse's page bar: the back glyph on a white disc at the start, the title centred, actions at
 * the end, no elevation or tint. A drop-in for Material's `TopAppBar` — same parameter names,
 * so an upstream screen switches bars by changing one call — which is why `colors`,
 * `scrollBehavior`, `windowInsets` and `expandedHeight` are accepted and not all used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun MuseTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** Put the navigation glyph on Muse's white disc; off for a text action such as "Cancel". */
    disc: Boolean = true,
    expandedHeight: Dp = 56.dp,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .height(expandedHeight),
    ) {
        // The title first and centred on the whole bar, as Muse centres it on the screen, not
        // between the buttons.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 64.dp),
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides TextStyle(
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) { title() }
            }
        }
        if (navigationIcon != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = if (disc) 12.dp else 0.dp),
            ) {
                if (disc) MuseDisc { navigationIcon() } else navigationIcon()
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * The filled disc Muse puts under a bar glyph (48dp, matching the settings screen).
 * The slot is a Material `IconButton` on upstream screens (48dp touch target);
 * the disc clips its ripple and stays tappable.
 */
@Composable
fun MuseDisc(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .size(48.dp)
            .shadow(2.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(MuseTones.surface),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/** A dark/light card of rows on the page canvas. Radius ~24dp per the Muse settings look. */
@Composable
fun MuseCard(modifier: Modifier = Modifier, inset: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MuseTones.surface,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset),
    ) {
        Column(content = content)
    }
}

/** Muse's settings row: an outlined glyph (~26dp), the label, a dim chevron. ~64dp tall. */
@Composable
fun MuseRow(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    value: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressableRow(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text = title,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            )
        }
        if (trailing != null) trailing()
        if (chevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The hairline between two [MuseRow]s, inset past the glyph. */
@Composable
fun MuseRowDivider(inset: Dp = 56.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 0.6.dp,
        color = MuseTones.hairline,
    )
}

/** The small grey caption Muse sets under a card ("永久删除你的 Muse 数据…"). */
@Composable
fun MuseCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

/** The small grey label Muse sets above a card ("聊天主题", "模式"). */
@Composable
fun MuseSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Vertical rhythm between cards on a Muse page. */
@Composable
fun MuseGap(height: Dp = 16.dp) = Spacer(Modifier.height(height))
