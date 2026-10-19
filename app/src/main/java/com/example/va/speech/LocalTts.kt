package com.example.va.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlin.math.roundToInt

class LocalTts {

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var track: AudioTrack? = null

    val isLoaded: Boolean
        get() = tts != null

    @Synchronized
    fun load(paths: PiperPaths) {
        closeLocked()
        tts = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = paths.model,
                        tokens = paths.tokens,
                        dataDir = paths.dataDir,
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
    }

    fun speak(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        val engine = synchronized(this) { tts } ?: error("Text-to-speech model is not loaded.")
        stopPlayback()
        val audio = engine.generate(text = spoken)
        play(audio.samples, audio.sampleRate)
    }

    fun stopPlayback() {
        val current = track
        track = null
        current?.apply {
            runCatching { pause() }
            runCatching { stop() }
            runCatching { release() }
        }
    }

    @Synchronized
    fun close() {
        stopPlayback()
        closeLocked()
    }

    private fun closeLocked() {
        tts?.release()
        tts = null
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player
        player.play()
        var offset = 0
        while (offset < pcm.size && track === player) {
            val written = player.write(pcm, offset, minOf(pcm.size - offset, 2048))
            if (written <= 0) break
            offset += written
        }
    }
}
