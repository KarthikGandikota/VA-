package com.example.va.speech

import android.content.Context
import java.io.File

data class WhisperPaths(
    val encoder: String,
    val decoder: String,
    val tokens: String,
    val label: String,
)

data class PiperPaths(
    val model: String,
    val tokens: String,
    val dataDir: String,
    val label: String,
)

object SpeechModels {

    fun findWhisper(context: Context): WhisperPaths? {
        return speechRoots(context).firstNotNullOfOrNull { root ->
            root.walkTopDown().firstOrNull { file ->
                file.isFile && file.name.endsWith("-encoder.int8.onnx")
            }?.let { encoder ->
                val dir = encoder.parentFile ?: return@let null
                val decoder = File(dir, encoder.name.replace("-encoder.int8.onnx", "-decoder.int8.onnx"))
                val tokens = File(dir, encoder.name.replace("-encoder.int8.onnx", "-tokens.txt"))
                if (decoder.isFile && tokens.isFile) {
                    WhisperPaths(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        tokens = tokens.absolutePath,
                        label = dir.name,
                    )
                } else {
                    null
                }
            }
        }
    }

    fun findPiper(context: Context): PiperPaths? {
        return speechRoots(context).firstNotNullOfOrNull { root ->
            root.walkTopDown().firstOrNull { file ->
                file.isFile && file.name.endsWith(".onnx") && !file.name.contains("encoder") &&
                    !file.name.contains("decoder")
            }?.let { model ->
                val dir = model.parentFile ?: return@let null
                val tokens = File(dir, "tokens.txt")
                val dataDir = File(dir, "espeak-ng-data")
                if (tokens.isFile && dataDir.isDirectory) {
                    PiperPaths(
                        model = model.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = dataDir.absolutePath,
                        label = dir.name,
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun speechRoots(context: Context): List<File> {
        return listOfNotNull(
            File(context.filesDir, "speech"),
            context.getExternalFilesDir("speech"),
        )
    }
}
