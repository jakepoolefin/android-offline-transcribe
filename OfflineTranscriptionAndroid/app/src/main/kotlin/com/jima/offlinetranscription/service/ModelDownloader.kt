package com.voiceping.offlinetranscription.service

import com.voiceping.offlinetranscription.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val modelsDir: File) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Directory for a specific model's files. */
    fun modelDir(model: ModelInfo): File = File(modelsDir, model.id)

    /** For whisper.cpp models: path to the single model file. */
    fun modelFilePath(model: ModelInfo): String {
        val dir = modelDir(model)
        return File(dir, model.files.first().localName).absolutePath
    }

    /** Check that all files for a model are downloaded. */
    fun isModelDownloaded(model: ModelInfo): Boolean {
        val dir = modelDir(model)
        return model.files.all { File(dir, it.localName).exists() }
    }

    /** Downloads all files for a model, emitting overall progress (0.0 to 1.0). */
    fun download(model: ModelInfo): Flow<Float> = flow {
        val dir = modelDir(model)
        dir.mkdirs()
        pruneStaleModelFiles(dir, model)

        val totalFiles = model.files.size
        for ((fileIndex, modelFile) in model.files.withIndex()) {
            val targetFile = File(dir, modelFile.localName)

            // Skip already downloaded files
            if (targetFile.exists()) {
                val overallProgress = (fileIndex + 1).toFloat() / totalFiles
                emit(overallProgress)
                continue
            }

            val tempFile = File(dir, "${modelFile.localName}.tmp")
            val requestBuilder = Request.Builder().url(modelFile.url)

            // Support resume if temp file exists
            if (tempFile.exists()) {
                requestBuilder.addHeader("Range", "bytes=${tempFile.length()}-")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code} for ${modelFile.localName}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength()
            val existingBytes = if (response.code == 206) tempFile.length() else 0L
            val totalBytes = contentLength + existingBytes

            val outputStream = FileOutputStream(tempFile, response.code == 206)
            val buffer = ByteArray(8192)
            var bytesRead: Long = existingBytes

            body.byteStream().use { input ->
                outputStream.use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            // Progress within this file, scaled to overall progress
                            val fileProgress = bytesRead.toFloat() / totalBytes.toFloat()
                            val overallProgress = (fileIndex + fileProgress) / totalFiles
                            emit(overallProgress)
                        }
                    }
                }
            }

            // Verify download size matches Content-Length
            if (totalBytes > 0 && bytesRead != totalBytes) {
                tempFile.delete()
                throw Exception(
                    "Download incomplete for ${modelFile.localName}: " +
                    "expected $totalBytes bytes, got $bytesRead bytes"
                )
            }

            // Rename temp to final
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        }
        emit(1.0f)
    }.flowOn(Dispatchers.IO)

    /**
     * Remove stale artifacts from previous model revisions in the same model ID directory.
     * This prevents mixing incompatible ONNX file sets (e.g. old and new Zipformer variants).
     */
    private fun pruneStaleModelFiles(dir: File, model: ModelInfo) {
        val expected = model.files.map { it.localName }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach

            val name = file.name
            val baseName = if (name.endsWith(".tmp")) name.removeSuffix(".tmp") else name
            val isExpected = expected.contains(baseName)
            if (isExpected) return@forEach

            val removable =
                name.endsWith(".onnx") ||
                    name.endsWith(".txt") ||
                    name.endsWith(".model") ||
                    name.endsWith(".bin") ||
                    name.endsWith(".tmp")
            if (removable) {
                file.delete()
            }
        }
    }
}
