package ai.unicto.unibot.ui.chat

// [P2-compare] Model comparison mode: run the same prompt against two
// selected models side by side, streaming independently into two panes.
// Plain (non-agent) streaming calls — no tools, no history — via
// LLMProvider.streamMessage, mirroring the quick-test provider setup.

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.unicto.unibot.R
import ai.unicto.unibot.UnibotApp
import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMStreamChunk
import ai.unicto.unibot.data.model.ModelEntry
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.provider.ProviderFactory
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-pane run state. */
internal data class ComparePaneState(
    val text: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
)

class CompareViewModel(app: Application) : AndroidViewModel(app) {
    private val _entryA = MutableStateFlow<ModelEntry?>(null)
    val entryA: StateFlow<ModelEntry?> = _entryA.asStateFlow()
    private val _entryB = MutableStateFlow<ModelEntry?>(null)
    val entryB: StateFlow<ModelEntry?> = _entryB.asStateFlow()

    private val _paneA = MutableStateFlow(ComparePaneState())
    internal val paneA: StateFlow<ComparePaneState> = _paneA.asStateFlow()
    private val _paneB = MutableStateFlow(ComparePaneState())
    internal val paneB: StateFlow<ComparePaneState> = _paneB.asStateFlow()

    private var jobA: Job? = null
    private var jobB: Job? = null

    val anyStreaming: Boolean
        get() = _paneA.value.streaming || _paneB.value.streaming

    fun setEntry(left: Boolean, entry: ModelEntry) {
        if (left) _entryA.value = entry else _entryB.value = entry
    }

    /** All text-capable, non-hidden model entries for the pickers. */
    fun comparableEntries(): List<ModelEntry> {
        val repo = (getApplication() as? UnibotApp)?.providerRepositoryOrNull
            ?: return emptyList()
        return repo.config.value.modelEntries
            .filter { !it.isHidden && it.model.isTextOutput }
            .sortedBy { it.model.displayName.lowercase() }
    }

    /** Human label for the provider instance behind [entry] (picker rows). */
    fun instanceLabel(entry: ModelEntry): String {
        val repo = (getApplication() as? UnibotApp)?.providerRepositoryOrNull
            ?: return ""
        return repo.instance(entry.providerInstanceId)?.label
            ?.takeIf { it.isNotBlank() }
            ?: entry.model.provider
    }

    fun run(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || anyStreaming) return
        val a = _entryA.value
        val b = _entryB.value
        if (a == null || b == null) return
        _paneA.value = ComparePaneState(streaming = true)
        _paneB.value = ComparePaneState(streaming = true)
        jobA = viewModelScope.launch(Dispatchers.IO) { streamInto(a, clean, _paneA, "A") }
        jobB = viewModelScope.launch(Dispatchers.IO) { streamInto(b, clean, _paneB, "B") }
    }

    fun stop() {
        jobA?.cancel()
        jobB?.cancel()
        _paneA.value = _paneA.value.copy(streaming = false)
        _paneB.value = _paneB.value.copy(streaming = false)
    }

    private suspend fun streamInto(
        entry: ModelEntry,
        prompt: String,
        sink: MutableStateFlow<ComparePaneState>,
        tag: String,
    ) {
        val app = getApplication<Application>()
        try {
            val repo = (app as? UnibotApp)?.providerRepositoryOrNull
                ?: throw IllegalStateException("Providers not ready")
            val instance = repo.instance(entry.providerInstanceId)
                ?: throw IllegalStateException("Provider instance not found")
            // [T-android-keyless-provider-selection] usableApiKey, not
            // loadApiKey — a keyless local endpoint is a valid config.
            val apiKey = repo.usableApiKey(instance)
                ?: throw IllegalStateException("No API key configured for ${instance.label}")
            val provider = ProviderFactory.create(instance, apiKey, entry.model, app)
            val sb = StringBuilder()
            provider.streamMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                systemPrompt = null,
                maxTokens = 2048,
                temperature = null,
            ).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.Text -> {
                        sb.append(chunk.text)
                        sink.value = sink.value.copy(text = sb.toString())
                    }
                    is LLMStreamChunk.Finished -> {
                        sink.value = sink.value.copy(streaming = false)
                    }
                    is LLMStreamChunk.Usage -> Unit
                    else -> Unit
                }
            }
            // A provider that completes without Finished still ends the spin.
            sink.value = sink.value.copy(streaming = false)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                sink.value = sink.value.copy(streaming = false)
            } else {
                AppLogger.warning("Compare", "pane $tag failed: ${t.message}")
                sink.value = sink.value.copy(
                    streaming = false,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, vm: CompareViewModel = viewModel()) {
    val entryA by vm.entryA.collectAsState()
    val entryB by vm.entryB.collectAsState()
    val paneA by vm.paneA.collectAsState()
    val paneB by vm.paneB.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var pickerSide by remember { mutableStateOf<Boolean?>(null) } // null=closed, true=A, false=B
    val scope = rememberCoroutineScope()

    val canRun = entryA != null && entryB != null && prompt.isNotBlank() &&
        !paneA.streaming && !paneB.streaming

    Column(modifier = Modifier.fillMaxSize()) {
        MuseTopAppBar(
            title = { Text(stringResource(R.string.ub_compare_title)) },
            navigationIcon = {
                IconButton(onClick = {
                    vm.stop()
                    onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            },
        )

        // Model selectors.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelPickButton(
                label = entryA?.model?.displayName
                    ?: stringResource(R.string.ub_compare_choose_left),
                modifier = Modifier.weight(1f),
                onClick = { pickerSide = true },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "vs",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            ModelPickButton(
                label = entryB?.model?.displayName
                    ?: stringResource(R.string.ub_compare_choose_right),
                modifier = Modifier.weight(1f),
                onClick = { pickerSide = false },
            )
        }

        // Two panes.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            ComparePane(
                title = entryA?.model?.displayName ?: "—",
                state = paneA,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            )
            ComparePane(
                title = entryB?.model?.displayName ?: "—",
                state = paneB,
                modifier = Modifier.weight(1f),
            )
        }

        // Prompt row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text(stringResource(R.string.ub_compare_prompt_hint)) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                maxLines = 4,
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (paneA.streaming || paneB.streaming) vm.stop()
                    else vm.run(prompt)
                },
                enabled = canRun || paneA.streaming || paneB.streaming,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (paneA.streaming || paneB.streaming) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Icon(
                    imageVector = if (paneA.streaming || paneB.streaming) Icons.Filled.Stop
                    else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(
                        if (paneA.streaming || paneB.streaming) R.string.ub_compare_stop
                        else R.string.ub_compare_send,
                    ),
                    tint = if (paneA.streaming || paneB.streaming) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    pickerSide?.let { left ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { pickerSide = null },
            sheetState = sheetState,
        ) {
            val entries = remember { vm.comparableEntries() }
            Text(
                text = stringResource(R.string.ub_compare_pick_model),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val selected = (if (left) entryA else entryB)?.id == entry.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.setEntry(left, entry)
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    pickerSide = null
                                }
                            }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.model.displayName,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                vm.instanceLabel(entry),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.ub_prompt_library_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComparePane(title: String, state: ComparePaneState, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.streaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            when {
                state.error != null -> Text(
                    text = state.error,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                state.text.isEmpty() && !state.streaming -> Text(
                    text = "—",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                else -> MarkdownBlock(
                    rawText = state.text,
                    isStreaming = state.streaming,
                )
            }
        }
    }
}
