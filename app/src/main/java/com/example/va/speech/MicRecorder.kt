package com.example.va.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicRecorder {

    private val recording = AtomicBoolean(false)
    private val chunks = mutableListOf<FloatArray>()
    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null

    val isRecording: Boolean
        get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording.get()) return
        chunks.clear()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Could not open the microphone.")
        }
        audioRecord = recorder
        recording.set(true)
        recorder.startRecording()
        worker = thread(name = "va-mic") {
            val buffer = ShortArray(SAMPLE_RATE / 10)
            while (recording.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val samples = FloatArray(read) { buffer[it] / 32768.0f }
                    synchronized(chunks) { chunks += samples }
                }
            }
        }
    }

    fun stop(): FloatArray {
        recording.set(false)
        worker?.join(1_000)
        worker = null
        audioRecord?.apply {
            runCatching { stop() }
            runCatching { release() }
        }
        audioRecord = null
        val captured = synchronized(chunks) { chunks.toList() }
        chunks.clear()
        val total = captured.sumOf { it.size }
        val merged = FloatArray(total)
        var offset = 0
        for (chunk in captured) {
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        return merged
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
