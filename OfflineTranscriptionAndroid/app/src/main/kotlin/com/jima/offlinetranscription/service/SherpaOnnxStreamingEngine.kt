package com.voiceping.offlinetranscription.service

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ASR engine backed by sherpa-onnx OnlineRecognizer for real-time streaming
 * transcription (Zipformer transducer).
 *
 * Audio is fed incrementally via [feedAudio]. A single-thread executor
 * serialises decode work so that the recognizer is never accessed concurrently.
 */
class SherpaOnnxStreamingEngine : AsrEngine {
    companion object {
        private const val TAG = "SherpaOnnxStreaming"
        private const val MAX_DECODE_STEPS_PER_PASS = 1024
        private const val BATCH_STREAM_CHUNK_SAMPLES = 1600 // 100 ms @ 16 kHz
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private val lock = ReentrantLock()
    private var decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var latestText: String = ""

    override val isLoaded: Boolean get() = lock.withLock { recognizer != null }
    override val isStreaming: Boolean get() = true

    override suspend fun loadModel(modelPath: String): Boolean {
        release()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                decodeExecutor = Executors.newSingleThreadExecutor()
                val providers = preferredProviders()
                val threads = computeStreamingThreads()
                var lastError: Throwable? = null
                try {
                    val tokensPath = File(modelPath, "tokens.txt").absolutePath
                    val encoderPath = findFile(modelPath, "encoder")
                    val decoderPath = findFile(modelPath, "decoder")
                    val joinerPath = findFile(modelPath, "joiner")
                    Log.i(
                        TAG,
                        "Model files selected: encoder=${File(encoderPath).name}, decoder=${File(decoderPath).name}, joiner=${File(joinerPath).name}"
                    )

                    val transducerConfig = OnlineTransducerModelConfig(
                        encoder = encoderPath,
                        decoder = decoderPath,
                        joiner = joinerPath,
                    )

                    for (provider in providers) {
                        try {
                            val modelConfig = OnlineModelConfig(
                                transducer = transducerConfig,
                                tokens = tokensPath,
                                numThreads = threads,
                                debug = false,
                                provider = provider,
                            )

                            val config = OnlineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                                modelConfig = modelConfig,
                                endpointConfig = EndpointConfig(
                                    rule1 = EndpointRule(
                                        mustContainNonSilence = false,
                                        minTrailingSilence = 1.8f,
                                        minUtteranceLength = 0.0f,
                                    ),
                                    rule2 = EndpointRule(
                                        mustContainNonSilence = true,
                                        minTrailingSilence = 0.8f,
                                        minUtteranceLength = 0.0f,
                                    ),
                                    rule3 = EndpointRule(
                                        mustContainNonSilence = false,
                                        minTrailingSilence = 0.0f,
                                        minUtteranceLength = 20.0f,
                                    ),
                                ),
                                enableEndpoint = true,
                                // Beam search is slower than greedy but improves small-model quality.
                                decodingMethod = "modified_beam_search",
                            )

                            val rec = OnlineRecognizer(config = config)
                            recognizer = rec
                            stream = rec.createStream()
                            Log.i(TAG, "Loaded streaming model with provider=$provider threads=$threads")
                            return@withContext true
                        } catch (e: Throwable) {
                            lastError = e
                            Log.w(TAG, "Failed to initialize provider=$provider, trying fallback", e)
                        }
                    }
                } catch (outer: Throwable) {
                    lastError = outer
                }
                Log.e(TAG, "Failed to load streaming model from $modelPath", lastError)
                recognizer = null
                stream = null
                false
            }
        }
    }

    override suspend fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int,
        language: String
    ): List<TranscriptionSegment> {
        // File-transcription path for streaming model:
        // feed fixed chunks (closer to realtime path) to improve stability and quality.
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val rec = recognizer ?: return@withContext emptyList()
                val s = rec.createStream()
                try {
                    var offset = 0
                    while (offset < audioSamples.size) {
                        val end = (offset + BATCH_STREAM_CHUNK_SAMPLES).coerceAtMost(audioSamples.size)
                        val chunk = audioSamples.copyOfRange(offset, end)
                        s.acceptWaveform(chunk, sampleRate = 16000)
                        decodeUntilNotReady(rec, s, stage = "batch-chunk")
                        offset = end
                    }
                    s.inputFinished()
                    decodeUntilNotReady(rec, s, stage = "batch")
                    val rawText = rec.getResult(s).text.trim()
                    val text = normalizeStreamingText(rawText)
                    Log.d(TAG, "Batch result text='$text'")
                    if (text.isBlank()) emptyList()
                    else listOf(TranscriptionSegment(text = text, startMs = 0, endMs = 0))
                } catch (e: Throwable) {
                    Log.e(TAG, "Batch transcribe failed", e)
                    emptyList()
                } finally {
                    s.release()
                }
            }
        }
    }

    // -- Streaming methods --

    override fun feedAudio(samples: FloatArray) {
        decodeExecutor.execute {
            lock.withLock {
                val rec = recognizer ?: return@execute
                val s = stream ?: return@execute
                try {
                    // sherpa-onnx Kotlin API expects float samples in [-1, 1] range
                    s.acceptWaveform(samples, sampleRate = 16000)
                    decodeUntilNotReady(rec, s, stage = "stream")
                    latestText = normalizeStreamingText(rec.getResult(s).text)
                } catch (e: Throwable) {
                    Log.e(TAG, "Streaming decode error", e)
                }
            }
        }
    }

    override fun getStreamingResult(): TranscriptionSegment? {
        val text = latestText.trim()
        if (text.isEmpty()) return null
        return TranscriptionSegment(text = text, startMs = 0, endMs = 0)
    }

    override fun isEndpointDetected(): Boolean {
        return lock.withLock {
            val rec = recognizer ?: return false
            val s = stream ?: return false
            try {
                rec.isEndpoint(s)
            } catch (e: Throwable) {
                false
            }
        }
    }

    override fun resetStreamingState() {
        lock.withLock {
            val rec = recognizer ?: return
            val s = stream ?: return
            try {
                rec.reset(s)
            } catch (e: Throwable) {
                Log.e(TAG, "Reset streaming state failed", e)
            }
            latestText = ""
        }
    }

    override fun drainFinalAudio(): TranscriptionSegment? {
        // Wait for any pending decode work to complete
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            decodeExecutor.execute { latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        return lock.withLock {
            val rec = recognizer ?: return null
            val s = stream ?: return null
            try {
                s.inputFinished()
                decodeUntilNotReady(rec, s, stage = "drain")
                val text = normalizeStreamingText(rec.getResult(s).text)
                Log.d(TAG, "drainFinalAudio: text='$text'")
                // Reset stream for potential reuse
                rec.reset(s)
                latestText = ""
                if (text.isBlank()) null
                else TranscriptionSegment(text = text, startMs = 0, endMs = 0)
            } catch (e: Throwable) {
                Log.e(TAG, "drainFinalAudio failed", e)
                null
            }
        }
    }

    override fun release() {
        decodeExecutor.shutdown()
        try {
            if (!decodeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                decodeExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            decodeExecutor.shutdownNow()
        }
        lock.withLock {
            stream?.release()
            stream = null
            recognizer?.release()
            recognizer = null
            latestText = ""
        }
    }

    /**
     * Resolve model files with Zipformer-friendly precision:
     * - encoder/joiner: prefer int8 for speed
     * - decoder: prefer non-int8 for accuracy
     */
    private fun findFile(dir: String, baseName: String): String {
        val files = File(dir).listFiles().orEmpty().filter { it.name.endsWith(".onnx") }
        if (files.isEmpty()) {
            return File(dir, "$baseName.onnx").absolutePath
        }

        fun containsBase(file: File): Boolean = file.name.contains(baseName)

        if (baseName.contains("decoder", ignoreCase = true)) {
            val nonInt8 = files.firstOrNull { containsBase(it) && !it.name.contains("int8") }
            if (nonInt8 != null) return nonInt8.absolutePath
            val int8 = files.firstOrNull { containsBase(it) && it.name.contains("int8") }
            if (int8 != null) return int8.absolutePath
        } else {
            val int8 = files.firstOrNull { containsBase(it) && it.name.contains("int8") }
            if (int8 != null) return int8.absolutePath
            val nonInt8 = files.firstOrNull { containsBase(it) && !it.name.contains("int8") }
            if (nonInt8 != null) return nonInt8.absolutePath
        }

        // Last resort: construct expected path
        return File(dir, "$baseName.onnx").absolutePath
    }

    private fun computeStreamingThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return if (cores <= 4) 1 else 2
    }

    private fun preferredProviders(): List<String> {
        // Keep streaming recognizer on CPU by default for stable real-time behavior.
        return listOf("cpu")
    }

    private fun decodeUntilNotReady(recognizer: OnlineRecognizer, stream: OnlineStream, stage: String) {
        var steps = 0
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
            steps += 1
            if (steps >= MAX_DECODE_STEPS_PER_PASS) {
                Log.w(TAG, "Decode loop guard hit during $stage (steps=$steps)")
                return
            }
        }
    }

    private fun normalizeStreamingText(text: String): String {
        return text
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
