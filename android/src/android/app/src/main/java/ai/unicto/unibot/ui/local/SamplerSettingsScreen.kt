package ai.unicto.unibot.ui.local

// [v1.2 Batch F] Sampler settings screen (Routes.SAMPLER_SETTINGS).
//
// Sliders for temperature, top-p, top-k, repeat penalty and max tokens —
// persisted per on-device model via SamplerSettingsStore and read by
// LocalLlamaBackend.generate on every turn, so the sliders genuinely steer
// generation. The three preset cards (Creative / Balanced / Precise) write
// the same prefs in one tap.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.local.LlamaModel
import ai.unicto.unibot.local.LlamaModelManager
import ai.unicto.unibot.local.SamplerPresets
import ai.unicto.unibot.local.SamplerSettings
import ai.unicto.unibot.local.SamplerSettingsStore
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.ChatColors
import ai.unicto.unibot.ui.theme.animationsEnabled
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import java.util.Locale
import kotlin.math.roundToInt

private fun presetTitleRes(id: String): Int = when (id) {
    "creative" -> R.string.v12_preset_creative
    "balanced" -> R.string.v12_preset_balanced
    else -> R.string.v12_preset_precise
}

private fun presetDescRes(id: String): Int = when (id) {
    "creative" -> R.string.v12_preset_creative_desc
    "balanced" -> R.string.v12_preset_balanced_desc
    else -> R.string.v12_preset_precise_desc
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamplerSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val models = remember { LlamaModelManager.models }
    var selectedModel by remember { mutableStateOf(LlamaModelManager.selectedModel.value) }
    var settings by remember(selectedModel) {
        mutableStateOf(SamplerSettingsStore.load(ctx, selectedModel))
    }
    val haptics = rememberHaptic()
    val animated = animationsEnabled()
    val accent = MaterialTheme.colorScheme.primary

    fun persist(next: SamplerSettings) {
        settings = next
        SamplerSettingsStore.save(ctx, selectedModel, next)
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.v12_sampler_screen_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Offline badge ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.v12_sampler_saved_note),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = accent,
                )
            }

            // ── Model picker ──
            Text(
                text = stringResource(R.string.v12_sampler_for_model, selectedModel.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEachIndexed { index, model ->
                    FilterChip(
                        selected = model.id == selectedModel.id,
                        onClick = {
                            haptics.tap()
                            selectedModel = model
                        },
                        label = { Text(model.title) },
                        modifier = if (animated) Modifier.staggeredEntrance(index) else Modifier,
                    )
                }
            }

            // ── Presets ──
            Text(
                text = stringResource(R.string.v12_sampler_presets_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SamplerPresets.all.forEachIndexed { index, preset ->
                val active = settings == preset.settings
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (animated) Modifier.staggeredEntrance(index) else Modifier)
                        .background(
                            if (active) accent.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(14.dp),
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.tap()
                            persist(preset.settings)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(presetTitleRes(preset.id)),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(presetDescRes(preset.id)),
                        style = TextStyle(fontSize = 12.sp),
                        color = ChatColors.secondaryText,
                    )
                }
            }

            // ── Sliders ──
            SamplerSlider(
                title = stringResource(R.string.v12_sampler_temperature),
                hint = stringResource(R.string.v12_sampler_temperature_hint),
                valueLabel = String.format(Locale.US, "%.2f", settings.temperature),
                value = settings.temperature,
                range = 0f..2f,
                steps = 39,
                onChange = { settings = settings.copy(temperature = it) },
                onDone = { persist(settings) },
            )
            SamplerSlider(
                title = stringResource(R.string.v12_sampler_topp),
                hint = stringResource(R.string.v12_sampler_topp_hint),
                valueLabel = String.format(Locale.US, "%.2f", settings.topP),
                value = settings.topP,
                range = 0.1f..1f,
                steps = 17,
                onChange = { settings = settings.copy(topP = it) },
                onDone = { persist(settings) },
            )
            SamplerSlider(
                title = stringResource(R.string.v12_sampler_topk),
                hint = stringResource(R.string.v12_sampler_topk_hint),
                valueLabel = "${settings.topK}",
                value = settings.topK.toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { settings = settings.copy(topK = it.roundToInt()) },
                onDone = { persist(settings) },
            )
            SamplerSlider(
                title = stringResource(R.string.v12_sampler_repeat),
                hint = stringResource(R.string.v12_sampler_repeat_hint),
                valueLabel = String.format(Locale.US, "%.2f", settings.repeatPenalty),
                value = settings.repeatPenalty,
                range = 1f..2f,
                steps = 19,
                onChange = { settings = settings.copy(repeatPenalty = it) },
                onDone = { persist(settings) },
            )
            SamplerSlider(
                title = stringResource(R.string.v12_sampler_maxtokens),
                hint = stringResource(R.string.v12_sampler_maxtokens_hint),
                valueLabel = "${settings.maxTokens}",
                value = settings.maxTokens.toFloat(),
                range = 128f..2048f,
                steps = 29,
                onChange = {
                    // Snap to 64-token steps so the label never shows odd values.
                    val snapped = ((it / 64f).roundToInt() * 64).coerceIn(128, 2048)
                    settings = settings.copy(maxTokens = snapped)
                },
                onDone = { persist(settings) },
            )

            // ── Reset ──
            TextButton(
                onClick = {
                    haptics.tap()
                    SamplerSettingsStore.reset(ctx, selectedModel)
                    settings = SamplerSettingsStore.load(ctx, selectedModel)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.v12_sampler_reset))
            }
        }
    }
}

@Composable
private fun SamplerSlider(
    title: String,
    hint: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
        )
        Text(
            text = hint,
            style = TextStyle(fontSize = 11.sp),
            color = ChatColors.secondaryText,
        )
    }
}
