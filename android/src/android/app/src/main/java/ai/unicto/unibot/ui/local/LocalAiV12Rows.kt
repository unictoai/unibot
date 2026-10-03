package ai.unicto.unibot.ui.local

// [v1.2 Batch F] Settings rows the coordinator wires into SettingsScreen —
// entries into the model benchmark and sampler settings screens.
// Do NOT edit SettingsScreen.kt from this batch.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.settings.SettingsRow

/**
 * Row → [Routes.MODEL_BENCHMARK]: measure a downloaded model's tokens/sec
 * on this phone.
 */
@Composable
fun LocalAiBenchmarkRow(onClick: () -> Unit) {
    SettingsRow(
        title = stringResource(R.string.v12_benchmark_title),
        subtitle = stringResource(R.string.v12_benchmark_sub),
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
    SettingsRow(
        title = stringResource(R.string.v12_sampler_title),
        subtitle = stringResource(R.string.v12_sampler_sub),
        icon = Icons.Default.Tune,
        onClick = onClick,
        showDivider = false,
    )
}
