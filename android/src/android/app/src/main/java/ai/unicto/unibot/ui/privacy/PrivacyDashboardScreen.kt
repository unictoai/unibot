package ai.unicto.unibot.ui.privacy

import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.local.FactMemoryStore
import ai.unicto.unibot.privacy.PrivacyPrefs
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseGap
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

const val ROUTE_PRIVACY_DASHBOARD = "unibot/privacy_dashboard"

/**
 * Wave 5 (v1.0) — privacy core. Settings → Privacy dashboard.
 *
 * The honest-privacy page: hero shield with a pulsing violet glow and a
 * drawn-in checkmark, a "0 trackers" counter (honest caption: no analytics
 * or ad SDKs are bundled — the counter reflects the code, not blocking),
 * then what lives on this phone and the network controls.
 *
 * [chatRepository] is optional — when null (or the count can't be read)
 * the rows fall back to "On device" instead of a number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(
    onBack: () -> Unit,
    onTrafficLogClick: () -> Unit = {},
    chatRepository: ChatRepository? = null,
) {
    val context = LocalContext.current
    val haptics = rememberHaptic()
    val localOnly by PrivacyPrefs.localOnly.collectAsState()

    var chatCount by remember { mutableIntStateOf(-1) }
    var memoryCount by remember { mutableIntStateOf(-1) }

    LaunchedEffect(chatRepository) {
        chatCount = runCatching {
            chatRepository?.observeSessions()?.first()?.size ?: -1
        }.getOrDefault(-1)
        memoryCount = runCatching {
            FactMemoryStore(context).getAll().size
        }.getOrDefault(-1)
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Privacy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
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
                .padding(horizontal = 16.dp),
        ) {
            MuseGap()
            PrivacyHero()
            MuseGap()

            // -- On this device -------------------------------------------
            MuseCard(modifier = Modifier.staggeredEntrance(1)) {
                MuseRow(
                    title = "Chats",
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = {},
                    chevron = false,
                    value = if (chatCount >= 0) "$chatCount" else "On device",
                )
                MuseRowDivider()
                MuseRow(
                    title = "Saved memories",
                    icon = Icons.Outlined.Memory,
                    onClick = {},
                    chevron = false,
                    value = if (memoryCount >= 0) "$memoryCount" else "On device",
                )
                MuseRowDivider()
                MuseRow(
                    title = "API keys & tokens",
                    icon = Icons.Outlined.Lock,
                    onClick = {},
                    chevron = false,
                    value = "On device",
                )
            }
            MuseCaption(
                text = "Everything above is stored encrypted on this phone. " +
                    "unibot has no account system and no server that could receive it.",
            )
            MuseGap()

            // -- Network ----------------------------------------------------
            MuseCard(modifier = Modifier.staggeredEntrance(2)) {
                MuseRow(
                    title = "Network traffic log",
                    icon = Icons.Outlined.SwapHoriz,
                    onClick = onTrafficLogClick,
                    value = "Memory only",
                )
                MuseRowDivider()
                MuseRow(
                    title = "Local-only mode",
                    icon = Icons.Outlined.WifiOff,
                    onClick = {
                        haptics.toggle()
                        PrivacyPrefs.setLocalOnly(!localOnly)
                    },
                    chevron = false,
                    value = if (localOnly) "On" else "Off",
                    trailing = {
                        Switch(
                            checked = localOnly,
                            onCheckedChange = {
                                haptics.toggle()
                                PrivacyPrefs.setLocalOnly(it)
                            },
                        )
                    },
                )
            }
            MuseCaption(
                text = "Local-only mode lets only your AI provider's servers connect — " +
                    "web search and update checks stop while it is on. " +
                    "Connector traffic is not gated in this version.",
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Hero: shield with a slow violet breathing glow, an animated checkmark
 * draw-in, and the count-up "0 trackers" figure with its honesty caption.
 */
@Composable
private fun PrivacyHero() {
    val violet = MaterialTheme.colorScheme.primary

    // Breathing glow behind the shield.
    val glow = rememberInfiniteTransition(label = "privacy_glow")
    val glowAlpha by glow.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Slow, easing = Motion.Linear),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_alpha",
    )

    // Checkmark draw-in, played once on entrance.
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { checked = true }
    val checkProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(Motion.Emphasis, easing = Motion.FastOutSlowIn),
        label = "check_draw",
    )

    // The tracker counter: counts down from a "scan" to the honest 0.
    val counter = remember { androidx.compose.animation.core.Animatable(100f) }
    LaunchedEffect(Unit) {
        counter.animateTo(0f, tween(1200, easing = Motion.Linear))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(0),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(violet.copy(alpha = glowAlpha * 0.35f)),
            )
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = violet,
                modifier = Modifier.size(72.dp),
            )
            DrawnCheckmark(
                color = Color(0xFF34C759),
                progress = checkProgress,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        CircleShape,
                    )
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${counter.value.toInt()} trackers",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "No analytics or ad SDKs are bundled — this counter " +
                "reflects the code, not blocking.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** A checkmark drawn in two segments, revealed by [progress] 0→1. */
@Composable
private fun DrawnCheckmark(
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (progress <= 0f) return@Canvas
        val stroke = Stroke(width = size.minDimension * 0.16f)
        val p1 = Offset(size.width * 0.22f, size.height * 0.54f)
        val p2 = Offset(size.width * 0.44f, size.height * 0.74f)
        val p3 = Offset(size.width * 0.80f, size.height * 0.28f)
        // First segment draws over progress 0..0.45, second over 0.35..1.
        val s1 = (progress / 0.45f).coerceIn(0f, 1f)
        drawLine(
            color = color,
            start = p1,
            end = p1 + (p2 - p1) * s1,
            strokeWidth = stroke.width,
        )
        val s2 = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (s2 > 0f) {
            drawLine(
                color = color,
                start = p2,
                end = p2 + (p3 - p2) * s2,
                strokeWidth = stroke.width,
            )
        }
    }
}
