package ai.unicto.unibot.ui.settings

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.theme.animationsEnabled
import ai.unicto.unibot.ui.theme.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [v12-G] Storage breakdown: what on this phone is unibot's, by category,
 * with a stacked bar and per-category actions where safe.
 *
 * - On-device AI models (filesDir/voice + filesDir/llm) — clearable with
 *   confirmation (re-downloadable, no data loss).
 * - TTS voices (filesDir/tts-voices) — clearable with confirmation.
 * - Chats database — never clearable here; "Details" opens the existing
 *   per-session storage screen.
 * - Media — same: details, per-session.
 * - Cache — clearable outright (temporary files by definition).
 * - App — the APK itself; display only.
 */
@Composable
fun StorageBreakdownScreen(
    onBack: () -> Unit,
    onStorageDetails: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<BreakdownCategory>>(emptyList()) }
    var confirmTarget by remember { mutableStateOf<BreakdownCategory?>(null) }

    suspend fun reload() {
        val fresh = withContext(Dispatchers.IO) { measureCategories(context) }
        categories = fresh
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun clearCategory(cat: BreakdownCategory) {
        scope.launch {
            val freed = withContext(Dispatchers.IO) {
                var bytes = 0L
                cat.dirs.forEach { dir ->
                    if (!dir.exists()) return@forEach
                    if (cat.kind == BreakdownKind.CACHE) {
                        // Clear the CONTENTS, not the dir itself — the system
                        // owns cacheDir and recreating it is its job.
                        dir.listFiles()?.forEach { child ->
                            bytes += directorySize(child)
                            runCatching {
                                if (child.isDirectory) child.deleteRecursively() else child.delete()
                            }
                        }
                    } else {
                        bytes += directorySize(dir)
                        runCatching {
                            if (dir.isDirectory) dir.deleteRecursively() else dir.delete()
                        }
                    }
                }
                bytes
            }
            Toast.makeText(
                context,
                context.getString(R.string.v12g_storage_cleared, Formatter.formatFileSize(context, freed)),
                Toast.LENGTH_SHORT,
            ).show()
            loading = true
            reload()
        }
    }

    SettingsScaffold(title = stringResource(R.string.v12g_storage_breakdown_title), onBack = onBack) {
        if (loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@SettingsScaffold
        }

        val total = categories.sumOf { it.bytes }.coerceAtLeast(1L)
        SettingsSection(modifier = Modifier.staggeredEntrance(0)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StackedBar(categories = categories, total = total)
                Text(
                    text = Formatter.formatFileSize(context, total),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        SettingsSection(modifier = Modifier.staggeredEntrance(1)) {
            categories.forEachIndexed { index, cat ->
                BreakdownRow(
                    category = cat,
                    context = context,
                    showDivider = index < categories.lastIndex,
                    onClear = { confirmTarget = cat },
                    onDetails = onStorageDetails,
                )
            }
        }
    }

    confirmTarget?.let { cat ->
        val messageRes = when (cat.kind) {
            BreakdownKind.MODELS -> R.string.v12g_storage_confirm_models
            BreakdownKind.TTS -> R.string.v12g_storage_confirm_tts
            else -> R.string.v12g_storage_confirm_cache
        }
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = {
                Text(
                    context.getString(
                        R.string.v12g_storage_confirm_title,
                        context.getString(cat.titleRes),
                    ),
                )
            },
            text = {
                Text(
                    context.getString(
                        messageRes,
                        Formatter.formatFileSize(context, cat.bytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTarget = null
                        clearCategory(cat)
                    },
                ) { Text(stringResource(R.string.v12g_storage_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text(stringResource(R.string.v12g_storage_cancel))
                }
            },
        )
    }
}

private enum class BreakdownKind { MODELS, TTS, DB, MEDIA, CACHE, APP }

private data class BreakdownCategory(
    val kind: BreakdownKind,
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
    val color: Color,
    val bytes: Long,
    /** Directories that make up this category (for clearing). */
    val dirs: List<File> = emptyList(),
    /** Clear action available (with confirmation dialog). */
    val clearable: Boolean = false,
    /** "Details" navigates to the existing per-session storage screen. */
    val hasDetails: Boolean = false,
)

@Composable
private fun StackedBar(categories: List<BreakdownCategory>, total: Long) {
    val motion = animationsEnabled()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        categories.forEach { cat ->
            val target = if (total == 0L) 0f else cat.bytes.toFloat() / total.toFloat()
            val frac by animateFloatAsState(
                targetValue = target,
                animationSpec = if (motion) tween(durationMillis = 700) else snap(),
                label = "storage_bar_${cat.kind}",
            )
            // Skip hairline slivers so tiny categories don't draw 1px noise.
            if (frac > 0.004f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(frac)
                        .background(cat.color),
                )
            }
        }
    }
    // Legend
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        categories.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                pair.forEach { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(cat.color),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(cat.titleRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    category: BreakdownCategory,
    context: Context,
    showDivider: Boolean,
    onClear: () -> Unit,
    onDetails: () -> Unit,
) {
    SettingsRow(
        title = stringResource(category.titleRes),
        subtitle = "${stringResource(category.subtitleRes)}\n${Formatter.formatFileSize(context, category.bytes)}",
        icon = category.icon,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            when {
                category.clearable -> TextButton(onClick = onClear) {
                    Text(stringResource(R.string.v12g_storage_clear))
                }
                category.hasDetails -> TextButton(onClick = onDetails) {
                    Text(stringResource(R.string.v12g_storage_details))
                }
            }
        },
    )
}

private fun measureCategories(context: Context): List<BreakdownCategory> {
    val filesDir = context.filesDir
    val models = listOf(File(filesDir, "voice"), File(filesDir, "llm"))
    val tts = listOf(File(filesDir, "tts-voices"))
    val dbFile = context.getDatabasePath("minis.db")
    val dbFiles = listOf(
        dbFile,
        File(dbFile.path + "-wal"),
        File(dbFile.path + "-shm"),
    )
    val media = listOf(File(filesDir, "media"))
    val appFile = runCatching { File(context.applicationInfo.sourceDir) }.getOrNull()
    return listOf(
        BreakdownCategory(
            kind = BreakdownKind.MODELS,
            titleRes = R.string.v12g_storage_cat_models,
            subtitleRes = R.string.v12g_storage_cat_models_sub,
            icon = Icons.Outlined.Psychology,
            color = Color(0xFF5856D6),
            bytes = models.sumOf { directorySize(it) },
            dirs = models,
            clearable = true,
        ),
        BreakdownCategory(
            kind = BreakdownKind.TTS,
            titleRes = R.string.v12g_storage_cat_tts,
            subtitleRes = R.string.v12g_storage_cat_tts_sub,
            icon = Icons.Outlined.GraphicEq,
            color = Color(0xFF32ADE6),
            bytes = tts.sumOf { directorySize(it) },
            dirs = tts,
            clearable = true,
        ),
        BreakdownCategory(
            kind = BreakdownKind.DB,
            titleRes = R.string.v12g_storage_cat_db,
            subtitleRes = R.string.v12g_storage_cat_db_sub,
            icon = Icons.Outlined.Forum,
            color = Color(0xFFFF9F0A),
            bytes = dbFiles.filter { it.exists() }.sumOf { it.length() },
            hasDetails = true,
        ),
        BreakdownCategory(
            kind = BreakdownKind.MEDIA,
            titleRes = R.string.v12g_storage_cat_media,
            subtitleRes = R.string.v12g_storage_cat_media_sub,
            icon = Icons.Outlined.PermMedia,
            color = Color(0xFF30D158),
            bytes = media.sumOf { directorySize(it) },
            hasDetails = true,
        ),
        BreakdownCategory(
            kind = BreakdownKind.CACHE,
            titleRes = R.string.v12g_storage_cat_cache,
            subtitleRes = R.string.v12g_storage_cat_cache_sub,
            icon = Icons.Outlined.Cached,
            color = Color(0xFF8E8E93),
            bytes = directorySize(context.cacheDir),
            dirs = listOf(context.cacheDir),
            clearable = true,
        ),
        BreakdownCategory(
            kind = BreakdownKind.APP,
            titleRes = R.string.v12g_storage_cat_app,
            subtitleRes = R.string.v12g_storage_cat_app_sub,
            icon = Icons.Outlined.Apps,
            color = Color(0xFFFF453A),
            bytes = appFile?.takeIf { it.exists() }?.length() ?: 0L,
        ),
    )
}

private fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    // Never follow the app-private tree into oblivion: plain walk, files only.
    dir.walkTopDown().forEach { file ->
        if (file.isFile) total += file.length()
    }
    return total
}
