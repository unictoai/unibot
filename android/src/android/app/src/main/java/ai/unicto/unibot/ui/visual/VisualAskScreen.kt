package ai.unicto.unibot.ui.visual

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.core.net.toUri
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.share.PendingShare
import ai.unicto.unibot.share.ShareCoordinator
import ai.unicto.unibot.share.SharedShareStore
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Ask about camera" — visual context for the chat, privacy-first.
 *
 * The camera is opened only on this screen, only when the user opens it, and
 * the photo is sent only when the user taps Ask — straight into the chat
 * composer's existing attachment pipeline (the same path as a shared image),
 * addressed to their own BYOK provider. Nothing leaves the phone otherwise.
 *
 * "Ask about screen": the share-sheet IN path already exists — a screenshot
 * shared to unibot from the gallery lands in the composer as an attachment
 * (manifest ACTION_SEND → ShareReceiverActivity). The gallery button below is
 * the in-app equivalent: pick a screenshot/photo and attach it with a question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualAskScreen(
    navController: NavController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickRequest by remember { mutableStateOf(false) }
    // The file the system camera writes into (filesDir/camera-photos/, kept
    // out of cacheDir so MIUI can't evict it mid-capture).
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    // System camera capture (ACTION_IMAGE_CAPTURE) — the same path as the
    // chat composer's "Take Photo" entry (see createCameraOutputUri).
    val cameraResult = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (result.resultCode == Activity.RESULT_OK && file != null && file.exists() && file.length() > 0) {
            scope.launch(Dispatchers.IO) {
                val ok = stagePhotoIntoComposer(context, file.toUri(), question)
                file.delete()
                withContext(Dispatchers.Main) {
                    busy = false
                    if (ok) navController.popBackStack() else error = "Couldn't attach that photo."
                }
            }
        } else {
            file?.delete()
            busy = false
            if (result.resultCode != Activity.RESULT_CANCELED) error = "Capture failed."
        }
    }

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.IO) {
            val ok = stagePhotoIntoComposer(context, uri, question)
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) navController.popBackStack() else error = "Couldn't read that image."
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(pickRequest) {
        if (pickRequest) {
            pickRequest = false
            pickImage.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
    }

    val requestCamera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    // Capture goes through the system camera app (ACTION_IMAGE_CAPTURE) —
    // no in-app preview; the viewfinder lives in the camera app itself.

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Ask about camera") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MuseTones.fill),
                ) {
                    // No live in-app preview: tapping capture hands off to the
                    // system camera app, whose viewfinder is the preview.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Tap capture to take a photo with your camera app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 21.sp,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (busy) return@IconButton
                            busy = true
                            error = null
                            val (uri, file) = ai.unicto.unibot.ui.chat.createCameraOutputUri(context)
                            pendingCaptureFile = file
                            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { cameraResult.launch(intent) }
                                .onFailure {
                                    AppLogger.warning("VisualAsk", "camera launch failed: ${it.message}")
                                    pendingCaptureFile = null
                                    file.delete()
                                    busy = false
                                    error = "Couldn't open the camera app."
                                }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            .size(72.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            Icon(
                                Icons.Outlined.CameraAlt,
                                contentDescription = "Capture and ask",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MuseTones.fill)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Camera permission is needed to ask about what you see.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow camera")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("What do you want to ask?") },
                placeholder = { Text("What's wrong with this error message?") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    IconButton(
                        onClick = { pickRequest = true },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = "Pick a photo or screenshot")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            MuseCaption(
                "The photo is attached to your chat and sent only when you press send — to your own provider, never anywhere else.",
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Copy a photo into the share staging dir and buffer it as a pending share —
 * the exact path an image shared from the gallery takes — so ChatScreen
 * injects it into the composer as an attachment with the question as text.
 */
private fun stagePhotoIntoComposer(
    context: android.content.Context,
    uri: android.net.Uri,
    question: String,
): Boolean = runCatching {
    val dir = SharedShareStore.sharedFileDirectory(context)
    val name = "visual_ask_${System.currentTimeMillis()}.jpg"
    context.contentResolver.openInputStream(uri)?.use { input ->
        File(dir, name).outputStream().use { output -> input.copyTo(output) }
    } ?: return false
    val items = mutableListOf(
        PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name),
    )
    if (question.isNotBlank()) {
        items += PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, question.trim())
    }
    val share = PendingShare(items, System.currentTimeMillis())
    SharedShareStore.savePendingShare(context, share)
    ShareCoordinator.processPendingShare(context)
    true
}.onFailure { AppLogger.warning("VisualAsk", "stage failed: ${it.message}") }
    .getOrDefault(false)
