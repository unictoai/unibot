package ai.unicto.unibot.ui.creator

import ai.unicto.unibot.connectors.youtube.YouTubeConnector
import ai.unicto.unibot.ui.components.SkeletonList
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.theme.Motion
import ai.unicto.unibot.ui.theme.staggeredEntrance
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

const val ROUTE_YOUTUBE_DASHBOARD = "unibot/youtube_dashboard"

/**
 * Creator dashboard — the connected YouTube channel at a glance.
 *
 * Stat cards (subscribers / total views / videos) count up with
 * [Motion.Emphasis]; latest uploads stagger in; pull-to-refresh reloads.
 * Reads through [YouTubeConnector] with the existing YouTube OAuth tokens —
 * no new auth, no new permissions. When YouTube isn't connected the screen
 * explains where to connect it instead of showing an empty shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDashboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<YouTubeConnector.ChannelStats?>(null) }
    var videos by remember { mutableStateOf<List<YouTubeConnector.Video>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(true) }

    suspend fun load() {
        val s = coroutineScope {
            val statsD = async { YouTubeConnector.myChannel(context) }
            val videosD = async { YouTubeConnector.recentVideos(context, 5) }
            statsD.await() to videosD.await()
        }
        val (sr, vr) = s
        when (sr) {
            is YouTubeConnector.ApiResult.Ok -> stats = sr.value
            is YouTubeConnector.ApiResult.NotConnected -> connected = false
            is YouTubeConnector.ApiResult.Error -> error = sr.message
        }
        when (vr) {
            is YouTubeConnector.ApiResult.Ok -> videos = vr.value
            is YouTubeConnector.ApiResult.NotConnected -> connected = false
            is YouTubeConnector.ApiResult.Error -> if (error == null) error = vr.message
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text("YouTube dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load() } },
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when {
                loading -> SkeletonList(modifier = Modifier.padding(16.dp), count = 5)
                !connected -> NotConnectedCard()
                error != null && stats == null -> ErrorCard(error!!) { loading = true; error = null }
                else -> DashboardBody(stats = stats, videos = videos)
            }
        }
    }
}

@Composable
private fun NotConnectedCard() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Connect YouTube first",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Settings → Connectors → YouTube, then come back — your stats will appear here.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun DashboardBody(
    stats: YouTubeConnector.ChannelStats?,
    videos: List<YouTubeConnector.Video>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (stats != null) {
            item {
                Text(
                    stats.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.staggeredEntrance(0),
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        icon = Icons.Filled.Subscriptions,
                        label = "Subscribers",
                        value = stats.subscribers.toLongOrNull() ?: 0L,
                        modifier = Modifier.weight(1f).staggeredEntrance(1),
                    )
                    StatCard(
                        icon = Icons.Filled.Visibility,
                        label = "Total views",
                        value = stats.views.toLongOrNull() ?: 0L,
                        modifier = Modifier.weight(1f).staggeredEntrance(2),
                    )
                }
                Spacer(Modifier.height(12.dp))
                StatCard(
                    icon = Icons.Filled.PlayCircle,
                    label = "Videos",
                    value = stats.videos.toLongOrNull() ?: 0L,
                    modifier = Modifier.staggeredEntrance(3),
                )
            }
        }
        if (videos.isNotEmpty()) {
            item {
                Text(
                    "Latest uploads",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            itemsIndexed(videos, key = { _, v -> v.id }) { i, video ->
                VideoRow(video = video, modifier = Modifier.staggeredEntrance(i))
            }
        }
    }
}

/** Stat card with an animated count-up number. */
@Composable
private fun StatCard(icon: ImageVector, label: String, value: Long, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = Motion.Emphasis, easing = Motion.FastOutSlowIn),
        label = "stat_count_$label",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatCompact(animated.toLong()),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCompact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun VideoRow(video: YouTubeConnector.Video, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Text(
            video.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(
                "${formatCompact(video.views.toLongOrNull() ?: 0L)} views",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(
                "${formatCompact(video.likes.toLongOrNull() ?: 0L)} likes",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(video.publishedAt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
