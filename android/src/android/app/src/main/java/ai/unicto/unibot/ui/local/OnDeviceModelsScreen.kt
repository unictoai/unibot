package ai.unicto.unibot.ui.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val ROUTE_ON_DEVICE_MODELS = "unibot/ondevice"

/**
 * Settings → On-device models. The offline LLM chat models (llama.cpp):
 * opt-in downloads with explicit size warnings, and the route selector that
 * flips chat into local mode. Same section the chat model picker hosts —
 * everything is self-contained in [OnDeviceModelsSection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceModelsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = ai.unicto.unibot.ui.home.MuseTones.canvas,
        topBar = {
            ai.unicto.unibot.ui.muse.MuseTopAppBar(
                title = { Text("On-device models") },
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
                .padding(vertical = 8.dp),
        ) {
            OnDeviceModelsSection()
        }
    }
}
