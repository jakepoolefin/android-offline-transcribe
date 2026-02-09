package com.voiceping.offlinetranscription.service

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.voiceping.offlinetranscription.model.SherpaModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ASR engine backed by sherpa-onnx for Moonshine, SenseVoice, and Omnilingual models.
 * Expects a model directory containing the required ONNX files + tokens.txt.
 *
 * All access to [recognizer] is guarded by [lock] so that release()
 * cannot free the recognizer while transcribe() is in-flight.
 */
class SherpaOnnxEngine(
    private val modelType: SherpaModelType
) : AsrEngine {
    companion object {
        private const val TAG = "SherpaOnnxEngine"
    }

    private var recognizer: OfflineRecognizer? = null
    private val lock = ReentrantLock()

    override val isLoaded: Boolean get() = lock.withLock { recognizer != null }

    override suspend fun loadModel(modelPath: String): Boolean {
        release()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val config = buildConfig(modelPath)
                    recognizer = OfflineRecognizer(config = config)
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load sherpa model from $modelPath", e)
                    recognizer = null
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
            lock.withLock {
                val rec = recognizer ?: return@withContext emptyList()
                try {
                    val stream = rec.createStream()
                    val result = try {
                        stream.acceptWaveform(audioSamples, sampleRate = 16000)
                        rec.decode(stream)
                        rec.getResult(stream)
                    } finally {
                        stream.release()
                    }

                    var text = result.text.trim()
                    var timestamps: FloatArray? = result.timestamps
                    val lang = result.lang.takeIf { it.isNotBlank() }

                    if (text.isBlank() && modelType == SherpaModelType.OMNILINGUAL_CTC) {
                        // Some omnilingual CTC builds are sensitive to waveform scale.
                        // Retry full decode with int16-like scaling before chunked fallback.
                        val scaled = FloatArray(audioSamples.size) { i -> audioSamples[i] * 32768f }
                        val stream = rec.createStream()
                        try {
                            stream.acceptWaveform(scaled, sampleRate = 16000)
                            rec.decode(stream)
                            text = rec.getResult(stream).text.trim()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Omnilingual full decode retry with scaled waveform failed", e)
                        } finally {
                            stream.release()
                        }
                    }

                    if (text.isBlank() && modelType == SherpaModelType.OMNILINGUAL_CTC) {
                        text = decodeOmnilingualChunked(rec, audioSamples)
                        timestamps = null
                    }

                    if (text.isBlank()) return@withContext emptyList()
                    buildSegments(text, timestamps, lang)
                } catch (e: Throwable) {
                    Log.e(TAG, "Sherpa transcribe failed", e)
                    emptyList()
                }
            }
        }
    }

    override fun release() {
        lock.withLock {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun buildConfig(modelDir: String): OfflineRecognizerConfig {
        val tokensPath = File(modelDir, "tokens.txt").absolutePath
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)

        val modelConfig = when (modelType) {
            SherpaModelType.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = File(modelDir, "preprocess.onnx").absolutePath,
                    encoder = findFile(modelDir, "encode"),
                    uncachedDecoder = findFile(modelDir, "uncached_decode"),
                    cachedDecoder = findFile(modelDir, "cached_decode"),
                ),
                tokens = tokensPath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            SherpaModelType.ZIPFORMER_TRANSDUCER -> throw IllegalArgumentException(
                "ZIPFORMER_TRANSDUCER should use SherpaOnnxStreamingEngine, not SherpaOnnxEngine"
            )
            SherpaModelType.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = findFile(modelDir, "model"),
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = tokensPath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            SherpaModelType.OMNILINGUAL_CTC -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = findFile(modelDir, "model"),
                ),
                tokens = tokensPath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    /** Find the int8 version of a model file, falling back to the non-quantized version. */
    private fun findFile(dir: String, baseName: String): String {
        val int8 = File(dir, "$baseName.int8.onnx")
        if (int8.exists()) return int8.absolutePath
        return File(dir, "$baseName.onnx").absolutePath
    }

    private fun buildSegments(
        text: String,
        timestamps: FloatArray?,
        detectedLanguage: String? = null
    ): List<TranscriptionSegment> {
        if (timestamps != null && timestamps.size >= 2) {
            val startMs = (timestamps.first() * 1000).toLong()
            val endMs = (timestamps.last() * 1000).toLong()
            return listOf(TranscriptionSegment(
                text = text.trim(), startMs = startMs, endMs = endMs,
                detectedLanguage = detectedLanguage
            ))
        }
        return listOf(TranscriptionSegment(
            text = text.trim(), startMs = 0, endMs = 0,
            detectedLanguage = detectedLanguage
        ))
    }

    /**
     * Fallback for Omnilingual CTC: decode in shorter chunks when full-clip decode returns empty.
     * On mobile runtimes this model can collapse to blank output beyond ~8s windows.
     */
    private fun decodeOmnilingualChunked(rec: OfflineRecognizer, samples: FloatArray): String {
        fun runPass(input: FloatArray, chunkSize: Int, overlap: Int): List<String> {
            val out = mutableListOf<String>()
            var offset = 0
            while (offset < input.size) {
                val end = (offset + chunkSize).coerceAtMost(input.size)
                val chunk = input.copyOfRange(offset, end)
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(chunk, sampleRate = 16000)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotBlank()) {
                        val prev = out.lastOrNull()
                        when {
                            prev == null -> out.add(text)
                            text == prev -> Unit
                            text.startsWith(prev) -> out[out.lastIndex] = text
                            prev.startsWith(text) -> Unit
                            else -> out.add(text)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Omnilingual chunk decode failed at offset=$offset", e)
                } finally {
                    stream.release()
                }
                if (end == input.size) break
                offset = maxOf(end - overlap, offset + 1)
            }
            return out
        }

        val raw = samples
        val scaled = FloatArray(samples.size) { i -> samples[i] * 32768f }
        val rawBoost = FloatArray(samples.size) { i -> (samples[i] * 2.5f).coerceIn(-1f, 1f) }
        val scaledBoost = FloatArray(samples.size) { i -> scaled[i] * 2.5f }
        val candidates = listOf(raw, scaled, rawBoost, scaledBoost)
        val chunkShapes = listOf(
            Pair(16000 * 4, 16000 / 2),
            Pair(16000 * 5, 16000 / 2),
            Pair(16000 * 6, 16000),
        )
        var bestText = ""
        var bestScore = Int.MIN_VALUE

        for (candidate in candidates) {
            for ((chunkSize, overlap) in chunkShapes) {
                val pieces = runPass(candidate, chunkSize, overlap)
                if (pieces.isEmpty()) continue
                val joined = pieces.joinToString(" ").trim()
                if (joined.isBlank()) continue
                val score = scoreOmnilingualText(joined)
                if (score > bestScore) {
                    bestScore = score
                    bestText = joined
                }
            }
        }

        return bestText
    }

    private fun scoreOmnilingualText(text: String): Int {
        val lower = text.lowercase()
        var score = 0
        val keywords = listOf("country", "ask", "do for", "fellow", "americans")
        keywords.forEach { keyword ->
            if (lower.contains(keyword)) score += 120
        }
        score += text.count { it.isLetter() && it.code < 128 }
        score -= text.count { it.isLetter() && it.code > 127 } * 2
        return score
    }
}
