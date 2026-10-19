package com.example.va.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import net.ladenthin.llama.value.Pair as LlamaPair

/**
 * On-device llama.cpp engine. All inference stays on the phone; nothing is sent
 * over the network.
 */
class LocalLlm {

    @Volatile
    private var model: LlamaModel? = null

    val isLoaded: Boolean
        get() = model != null

    @Synchronized
    fun load(modelPath: String) {
        closeLocked()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        model = LlamaModel(
            ModelParameters()
                .setModel(modelPath)
                .setCtxSize(CONTEXT_TOKENS)
                .setThreads(threads)
                .setGpuLayers(0)
                .setOffline(true)
                .enableJinja(),
        )
    }

    fun generate(history: List<ChatMessage>): Flow<String> = flow {
        val loaded = synchronized(this@LocalLlm) {
            model ?: error("Load a GGUF model before chatting.")
        }
        val messages = history.map { message ->
            LlamaPair(message.role.wireName, message.content)
        }
        val params = InferenceParameters.empty()
            .withMessages(SYSTEM_PROMPT, messages)
            .withNPredict(MAX_NEW_TOKENS)
            .withTemperature(0.7f)
            .withTopK(40)
            .withTopP(0.9f)
            .withCachePrompt(true)

        loaded.generateChat(params).use { stream ->
            for (output in stream) {
                currentCoroutineContext().ensureActive()
                if (output.text.isNotEmpty()) {
                    emit(output.text)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun close() {
        closeLocked()
    }

    private fun closeLocked() {
        model?.close()
        model = null
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You are VA, a helpful assistant running fully offline on this phone. " +
                "Answer briefly and clearly. If you do not know, say so."

        private const val CONTEXT_TOKENS = 2048
        private const val MAX_NEW_TOKENS = 256
    }
}

private val Role.wireName: String
    get() = when (this) {
        Role.User -> "user"
        Role.Assistant -> "assistant"
        Role.System -> "system"
    }
