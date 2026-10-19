package com.example.va.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

class ModelRepository(private val context: Context) {

    data class CopyProgress(
        val copiedBytes: Long,
        val totalBytes: Long,
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) copiedBytes.toFloat() / totalBytes else 0f
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun savedModel(): File? {
        val path = prefs.getString(KEY_MODEL_PATH, null) ?: return null
        val file = File(path)
        return file.takeIf { it.isFile }
    }

    fun savedDisplayName(): String? = prefs.getString(KEY_MODEL_NAME, null)

    fun discoverModels(): List<File> {
        val dirs = listOfNotNull(
            modelsDir,
            context.getExternalFilesDir("models")?.also { it.mkdirs() },
        )
        return dirs.flatMap { dir ->
            dir.listFiles { file -> file.isFile && file.extension.equals("gguf", true) }
                ?.toList()
                .orEmpty()
        }.distinctBy { it.absolutePath }
    }

    fun remember(file: File, displayName: String = file.name) {
        prefs.edit()
            .putString(KEY_MODEL_PATH, file.absolutePath)
            .putString(KEY_MODEL_NAME, displayName)
            .apply()
    }

    fun copyFromUri(
        uri: Uri,
        onProgress: (CopyProgress) -> Unit = {},
    ): File {
        val displayName = queryDisplayName(uri) ?: "current-model.gguf"
        val dest = File(modelsDir, "current-model.gguf")
        val total = querySize(uri)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file.")

        input.use { stream ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(CopyProgress(copied, total))
                }
            }
        }
        remember(dest, displayName)
        return dest
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun querySize(uri: Uri): Long {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
    }

    companion object {
        private const val PREFS_NAME = "va_offline_llm"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_NAME = "model_name"
    }
}
