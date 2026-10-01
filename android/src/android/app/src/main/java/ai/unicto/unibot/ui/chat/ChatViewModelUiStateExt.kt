package ai.unicto.unibot.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import ai.unicto.unibot.agent.Level
import ai.unicto.unibot.agent.ToolLoopDetector
import ai.unicto.unibot.browser.BrowserActionInput
import ai.unicto.unibot.browser.BrowserTabPool
import ai.unicto.unibot.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import ai.unicto.unibot.data.BPETokenizer
import ai.unicto.unibot.data.ContextOffload
import ai.unicto.unibot.data.ContextPolicy
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.data.FileMentionIndex
import ai.unicto.unibot.data.db.CompactMarkerEntity
import ai.unicto.unibot.data.model.AgentContentPart
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.LLMStreamChunk
import ai.unicto.unibot.data.model.LLMUsage
import ai.unicto.unibot.data.model.ModelGroup
import ai.unicto.unibot.data.model.ThinkingLevel
import ai.unicto.unibot.R
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.data.repository.MemoryRepository
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.provider.ImageBudget
import ai.unicto.unibot.provider.LLMProvider
import ai.unicto.unibot.provider.ProviderFactory
import ai.unicto.unibot.sandbox.ExecutionCoordinator
import ai.unicto.unibot.terminal.MinisOpenUrlBroker
import ai.unicto.unibot.terminal.MinisUrlMarker
import ai.unicto.unibot.tools.AgentTools
import ai.unicto.unibot.tools.FileEditTool
import ai.unicto.unibot.tools.FileReadTool
import ai.unicto.unibot.tools.FileWriteTool
import ai.unicto.unibot.tools.MemoryTools
import ai.unicto.unibot.tools.ReadImageTool
import ai.unicto.unibot.tools.ToolExecutionResult
import ai.unicto.unibot.offload.OffloadPermissionManager
import ai.unicto.unibot.service.SessionActivityTracker
import ai.unicto.unibot.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
