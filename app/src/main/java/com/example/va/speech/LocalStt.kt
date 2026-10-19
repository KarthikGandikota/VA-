package com.example.va.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

class LocalStt {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    val isLoaded: Boolean
        get() = recognizer != null

    @Synchronized
    fun load(paths: WhisperPaths) {
        closeLocked()
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = paths.encoder,
                        decoder = paths.decoder,
                        language = "en",
                        task = "transcribe",
                    ),
                    tokens = paths.tokens,
                    modelType = "whisper",
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
    }

    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): String {
        val loaded = recognizer ?: error("Speech-to-text model is not loaded.")
        if (samples.size < sampleRate / 5) {
            return ""
        }
        val stream = loaded.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            loaded.decode(stream)
            loaded.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun close() {
        closeLocked()
    }

    private fun closeLocked() {
        recognizer?.release()
        recognizer = null
    }
}
