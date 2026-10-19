package com.example.va.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.va.ChatUiState
import com.example.va.ChatViewModel
import com.example.va.llm.ChatMessage
import com.example.va.llm.Role
import java.io.File

@Composable
fun ChatRoute(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let(viewModel::importModel)
    }
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.toggleMic()
    }

    ChatScreen(
        state = state,
        onChooseModel = { picker.launch(arrayOf("*/*")) },
        onLoadDiscovered = viewModel::loadDiscovered,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onToggleSpeak = viewModel::toggleSpeakReplies,
        onMic = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.toggleMic()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onChooseModel: () -> Unit,
    onLoadDiscovered: (File) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onToggleSpeak: () -> Unit,
    onMic: () -> Unit,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VA", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.ttsReady && state.isModelLoaded) {
                        TextButton(onClick = onToggleSpeak) {
                            Text(if (state.speakReplies) "Voice on" else "Voice off")
                        }
                    }
                    TextButton(onClick = onChooseModel, enabled = !state.isImporting) {
                        Text(if (state.isModelLoaded) "Change" else "Choose")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            if (state.isImporting || state.isTranscribing || state.isSpeaking) {
                val progress = state.importProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when {
                !state.isModelLoaded -> {
                    SetupPane(
                        state = state,
                        onChooseModel = onChooseModel,
                        onLoadDiscovered = onLoadDiscovered,
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> {
                    MessageList(
                        messages = state.messages,
                        isGenerating = state.isGenerating,
                        modifier = Modifier.weight(1f),
                    )
                    Composer(
                        enabled = !state.isImporting && !state.isTranscribing,
                        isGenerating = state.isGenerating,
                        isRecording = state.isRecording,
                        micEnabled = state.sttReady,
                        onSend = onSend,
                        onStop = onStop,
                        onMic = onMic,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPane(
    state: ChatUiState,
    onChooseModel: () -> Unit,
    onLoadDiscovered: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Offline assistant", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Chat, listen, and speak fully on this phone. Load a GGUF for answers. Speech models are picked up from app storage automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    speechStatus(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onChooseModel,
                    enabled = !state.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (state.isImporting) "Loading…" else "Choose GGUF model")
                }

                if (state.discoveredModels.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Found on this device", fontWeight = FontWeight.SemiBold)
                    state.discoveredModels.forEach { file ->
                        OutlinedButton(
                            onClick = { onLoadDiscovered(file) },
                            enabled = !state.isImporting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(file.name)
                        }
                    }
                }
            }
        }
    }
}

private fun speechStatus(state: ChatUiState): String {
    val stt = if (state.sttReady) "Whisper tiny.en ready" else "STT model not found yet"
    val tts = if (state.ttsReady) "Piper Amy ready" else "TTS model not found yet"
    return "$stt. $tts."
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (messages.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Ask by typing or tap Mic. Everything stays on this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages) { message ->
            MessageBubble(message = message, showCursor = isGenerating && message === messages.last())
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showCursor: Boolean) {
    val isUser = message.role == Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val bubbleColor = if (isUser) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        val textColor = if (isUser) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val body = message.content.ifBlank { if (showCursor) "…" else "" }
            Text(text = body, color = textColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    isGenerating: Boolean,
    isRecording: Boolean,
    micEnabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = if (isRecording) "Listening…" else draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            enabled = enabled && !isGenerating && !isRecording,
            placeholder = { Text("Ask something") },
            maxLines = 4,
        )
        OutlinedButton(
            onClick = onMic,
            enabled = micEnabled && enabled && !isGenerating,
        ) {
            Text(if (isRecording) "Stop" else "Mic")
        }
        if (isGenerating) {
            OutlinedButton(onClick = onStop) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    val text = draft
                    draft = ""
                    onSend(text)
                },
                enabled = enabled && draft.isNotBlank() && !isRecording,
            ) {
                Text("Send")
            }
        }
    }
}
