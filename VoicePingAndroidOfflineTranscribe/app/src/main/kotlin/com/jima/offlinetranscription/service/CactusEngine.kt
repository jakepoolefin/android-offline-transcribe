package com.voiceping.offlinetranscription.service

import android.os.Build
import android.util.Log
import com.cactus.CactusInitParams
import com.cactus.CactusSTT
import com.cactus.CactusTranscriptionParams
import com.cactus.TranscriptionMode
import com.voiceping.offlinetranscription.model.CactusModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ASR engine backed by Cactus (ARM SIMD).
 * Supports Whisper and Moonshine models via the CactusSTT API.
 *
 * Cactus manages its own model downloads and storage internally,
 * so the standard [ModelDownloader] flow is bypassed for this engine.
 * Audio is converted from Float32 [-1,1] to 16-bit PCM ByteArray before
 * passing to the Cactus transcribe API.
 */
class CactusEngine(
    private val cactusModelType: CactusModelType
) : AsrEngine {
    companion object {
        private const val TAG = "CactusEngine"
        private const val MIN_PCM16_BYTES = 32000

        internal fun isRuntimeSupported(): Boolean {
            return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        }

        /**
         * Convert Float32 audio samples [-1,1] to 16-bit PCM ByteArray (little-endian).
         * Cactus expects 16-bit PCM, 16kHz, mono, minimum 32,000 bytes (~1 second).
         */
        internal fun float32ToPcm16(samples: FloatArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val clamped = samples[i].coerceIn(-1f, 1f)
                val s = (clamped * 32767f).toInt().toShort()
                bytes[i * 2] = (s.toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
            }
            return bytes
        }
    }

    private var stt: CactusSTT? = null
    private val mutex = Mutex()
    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean get() = loaded

    override suspend fun loadModel(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isRuntimeSupported()) {
                    Log.e(TAG, "Cactus is unsupported on this ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                    return@withContext false
                }
                try {
                    stt?.reset()
                    stt = null
                    loaded = false

                    val instance = CactusSTT()
                    val modelName = cactusModelName()

                    // Download if not already cached by Cactus
                    if (!instance.isModelDownloaded(modelName)) {
                        Log.i(TAG, "Downloading Cactus model: $modelName")
                        instance.downloadModel(modelName)
                    }

                    // Initialize the model
                    Log.i(TAG, "Initializing Cactus model: $modelName")
                    instance.initializeModel(CactusInitParams(model = modelName))

                    if (!instance.isReady()) {
                        Log.e(TAG, "Cactus model not ready after initialization: $modelName")
                        return@withContext false
                    }

                    stt = instance
                    loaded = true
                    Log.i(TAG, "Loaded Cactus model: $modelName")
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load Cactus model", e)
                    stt = null
                    loaded = false
                    false
                }
            }
        }
    }

    override suspend fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int,
        language: String
    ): List<TranscriptionSegment> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val instance = stt ?: return@withContext emptyList()
                try {
                    // Convert Float32 [-1,1] to 16-bit PCM ByteArray (little-endian, 16kHz mono)
                    val pcmBytes = float32ToPcm16(audioSamples)
                    if (pcmBytes.size < MIN_PCM16_BYTES) {
                        return@withContext emptyList()
                    }

                    val result = instance.transcribe(
                        filePath = "",
                        prompt = "",
                        params = CactusTranscriptionParams(),
                        onToken = null,
                        mode = TranscriptionMode.LOCAL,
                        apiKey = null,
                        audioBuffer = pcmBytes
                    )

                    if (result?.success == true) {
                        val text = result.text?.trim() ?: ""
                        if (text.isBlank()) return@withContext emptyList()
                        val durationMs = (audioSamples.size.toLong() * 1000) / 16000
                        listOf(
                            TranscriptionSegment(
                                text = text,
                                startMs = 0,
                                endMs = durationMs
                            )
                        )
                    } else {
                        Log.w(TAG, "Cactus transcription failed: ${result?.errorMessage}")
                        emptyList()
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Cactus transcribe failed", e)
                    emptyList()
                }
            }
        }
    }

    override fun release() {
        runBlocking {
            mutex.withLock {
                loaded = false
                try {
                    stt?.reset()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to reset Cactus instance during release", e)
                }
                stt = null
            }
        }
    }

    /** Map CactusModelType enum to the Cactus model slug string. */
    internal fun cactusModelName(): String = when (cactusModelType) {
        CactusModelType.WHISPER -> "whisper-tiny"
        CactusModelType.MOONSHINE -> "moonshine-base"
    }
}
