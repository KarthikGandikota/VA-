package com.example.va

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.va.llm.ChatMessage
import com.example.va.llm.LocalLlm
import com.example.va.llm.ModelRepository
import com.example.va.llm.Role
import com.example.va.speech.LocalStt
import com.example.va.speech.LocalTts
import com.example.va.speech.MicRecorder
import com.example.va.speech.SpeechModels
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val modelName: String? = null,
    val isModelLoaded: Boolean = false,
    val isImporting: Boolean = false,
    val importProgress: Float? = null,
    val status: String = "Choose a GGUF model to chat offline.",
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val discoveredModels: List<File> = emptyList(),
    val sttReady: Boolean = false,
    val ttsReady: Boolean = false,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val isSpeaking: Boolean = false,
    val speakReplies: Boolean = true,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val models = ModelRepository(application)
    private val llm = LocalLlm()
    private val stt = LocalStt()
    private val tts = LocalTts()
    private val mic = MicRecorder()
    private var generateJob: Job? = null
    private var speakJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        val discovered = models.discoverModels()
        val saved = models.savedModel()
        _uiState.update {
            it.copy(
                discoveredModels = discovered,
                modelName = models.savedDisplayName() ?: saved?.name,
            )
        }
        val autoLoad = saved ?: discovered.singleOrNull()
        if (autoLoad != null) {
            loadFile(autoLoad, models.savedDisplayName() ?: autoLoad.name)
        }
        loadSpeechModels()
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgress = 0f,
                    error = null,
                    status = "Copying model onto the phone…",
                )
            }
            try {
                val file = withContext(Dispatchers.IO) {
                    models.copyFromUri(uri) { progress ->
                        _uiState.update { state ->
                            state.copy(importProgress = progress.fraction)
                        }
                    }
                }
                loadFile(file, models.savedDisplayName() ?: file.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        error = error.message ?: "Could not import the model.",
                        status = "Choose a GGUF model to chat offline.",
                    )
                }
            }
        }
    }

    fun loadDiscovered(file: File) {
        models.remember(file)
        loadFile(file, file.name)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || !llm.isLoaded || _uiState.value.isGenerating) return

        val userMessage = ChatMessage(Role.User, prompt)
        val history = _uiState.value.messages + userMessage
        _uiState.update {
            it.copy(
                messages = history + ChatMessage(Role.Assistant, ""),
                isGenerating = true,
                error = null,
            )
        }
        tts.stopPlayback()

        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            try {
                llm.generate(history).collect { token ->
                    _uiState.update { state ->
                        val messages = state.messages.toMutableList()
                        if (messages.isEmpty()) return@update state
                        val last = messages.last()
                        messages[messages.lastIndex] = last.copy(content = last.content + token)
                        state.copy(messages = messages)
                    }
                }
                val reply = _uiState.value.messages.lastOrNull()?.content.orEmpty()
                if (_uiState.value.speakReplies) {
                    speak(reply)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = error.message ?: "Generation failed.")
                }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun stop() {
        generateJob?.cancel()
        generateJob = null
        speakJob?.cancel()
        tts.stopPlayback()
        _uiState.update { it.copy(isGenerating = false, isSpeaking = false) }
    }

    fun toggleSpeakReplies() {
        _uiState.update { it.copy(speakReplies = !it.speakReplies) }
        if (!_uiState.value.speakReplies) {
            tts.stopPlayback()
            _uiState.update { it.copy(isSpeaking = false) }
        }
    }

    fun toggleMic() {
        if (_uiState.value.isRecording) {
            finishRecording()
        } else {
            startRecording()
        }
    }

    override fun onCleared() {
        generateJob?.cancel()
        speakJob?.cancel()
        if (mic.isRecording) {
            runCatching { mic.stop() }
        }
        llm.close()
        stt.close()
        tts.close()
        super.onCleared()
    }

    private fun startRecording() {
        if (!_uiState.value.sttReady || _uiState.value.isGenerating || _uiState.value.isTranscribing) {
            _uiState.update { it.copy(error = "Speech-to-text is not ready yet.") }
            return
        }
        tts.stopPlayback()
        try {
            mic.start()
            _uiState.update { it.copy(isRecording = true, error = null) }
        } catch (error: Throwable) {
            _uiState.update { it.copy(error = error.message ?: "Could not start the microphone.") }
        }
    }

    private fun finishRecording() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
            try {
                val samples = withContext(Dispatchers.IO) { mic.stop() }
                val text = withContext(Dispatchers.IO) { stt.transcribe(samples) }
                _uiState.update { it.copy(isTranscribing = false) }
                if (text.isBlank()) {
                    _uiState.update { it.copy(error = "Didn't catch that. Try again a bit louder.") }
                } else {
                    send(text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        error = error.message ?: "Speech recognition failed.",
                    )
                }
            }
        }
    }

    private fun speak(text: String) {
        if (!tts.isLoaded || text.isBlank()) return
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }
            try {
                withContext(Dispatchers.IO) { tts.speak(text) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "Speech playback failed.") }
            } finally {
                _uiState.update { it.copy(isSpeaking = false) }
            }
        }
    }

    private fun loadSpeechModels() {
        viewModelScope.launch {
            val whisper = SpeechModels.findWhisper(getApplication())
            val piper = SpeechModels.findPiper(getApplication())
            try {
                if (whisper != null) {
                    withContext(Dispatchers.IO) { stt.load(whisper) }
                }
                if (piper != null) {
                    withContext(Dispatchers.IO) { tts.load(piper) }
                }
                _uiState.update { state ->
                    val next = state.copy(
                        sttReady = stt.isLoaded,
                        ttsReady = tts.isLoaded,
                    )
                    next.copy(status = statusLine(next))
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = error.message ?: "Could not load speech models.")
                }
            }
        }
    }

    private fun loadFile(file: File, displayName: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    status = "Loading $displayName into memory…",
                    error = null,
                    modelName = displayName,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    llm.load(file.absolutePath)
                }
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        isModelLoaded = true,
                        modelName = displayName,
                        status = statusLine(it.copy(isModelLoaded = true, modelName = displayName)),
                        messages = emptyList(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                llm.close()
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        isModelLoaded = false,
                        error = error.message ?: "Could not load the model.",
                        status = "Choose a GGUF model to chat offline.",
                    )
                }
            }
        }
    }

    private fun statusLine(state: ChatUiState): String {
        val parts = buildList {
            add("Offline")
            state.modelName?.let { add(it) }
            if (state.sttReady) add("STT")
            if (state.ttsReady) add("TTS")
        }
        return parts.joinToString(" • ")
    }
}
