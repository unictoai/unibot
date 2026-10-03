package ai.unicto.unibot.ui.local

// [v1.2 Batch F] Settings rows the coordinator wires into SettingsScreen —
// entries into the model benchmark and sampler settings screens.
// Converted to MuseRow for visual consistency with the settings page.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.muse.MuseRow

/**
 * Row → [Routes.MODEL_BENCHMARK]: measure a downloaded model's tokens/sec
 * on this phone.
 */
@Composable
fun LocalAiBenchmarkRow(onClick: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.v12_benchmark_title),
        value = stringResource(R.string.v12_benchmark_sub),
        icon = Icons.Default.Speed,
        onClick = onClick,
    )
}

/**
 * Row → [Routes.SAMPLER_SETTINGS]: temperature, top-p, top-k, repeat
 * penalty, max tokens — per on-device model.
 */
@Composable
fun LocalAiSamplerRow(onClick: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.v12_sampler_title),
        value = stringResource(R.string.v12_sampler_sub),
        icon = Icons.Default.Tune,
        onClick = onClick,
    )
}
