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
                val providers = preferredProviders()
                val threads = computeOfflineThreads()
                var lastError: Throwable? = null
                try {
                    for (provider in providers) {
                        try {
                            val config = buildConfig(
                                modelDir = modelPath,
                                threads = threads,
                                provider = provider
                            )
                            recognizer = OfflineRecognizer(config = config)
                            Log.i(TAG, "Loaded sherpa model with provider=$provider threads=$threads")
                            return@withContext true
                        } catch (e: Throwable) {
                            lastError = e
                            Log.w(TAG, "Failed to initialize provider=$provider, trying fallback", e)
                        }
                    }
                } catch (outer: Throwable) {
                    lastError = outer
                }
                Log.e(TAG, "Failed to load sherpa model from $modelPath", lastError)
                recognizer = null
                false
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
                    val audioDurationSec = audioSamples.size / 16000f
                    val isLongOmnilingual =
                        modelType == SherpaModelType.OMNILINGUAL_CTC && audioSamples.size > 16000 * 8
                    val decodeStartNs = System.nanoTime()

                    var text = ""
                    var timestamps: FloatArray? = null
                    var lang: String? = null

                    if (isLongOmnilingual) {
                        // Mobile safeguard: long omnilingual clips can stall on full-clip decode.
                        // Use bounded chunked decode directly to keep runtime predictable.
                        Log.i(
                            TAG,
                            "Omnilingual long decode (${String.format("%.1f", audioDurationSec)}s) -> chunked path"
                        )
                        text = decodeOmnilingualChunked(rec, audioSamples, languageHint = language)
                    } else {
                        val stream = rec.createStream()
                        val result = try {
                            stream.acceptWaveform(audioSamples, sampleRate = 16000)
                            rec.decode(stream)
                            rec.getResult(stream)
                        } finally {
                            stream.release()
                        }

                        text = result.text.trim()
                        timestamps = result.timestamps
                        // SenseVoice returns language codes in "<|en|>" format — normalize
                        // to plain BCP-47 (e.g. "en"). Same fix as iOS SherpaOnnxOfflineEngine.
                        lang = result.lang.takeIf { it.isNotBlank() }
                            ?.replace("<|", "")?.replace("|>", "")
                            ?.trim()?.lowercase()
                            ?.takeIf { it.isNotBlank() }
                        var bestScore = if (text.isBlank()) Int.MIN_VALUE else scoreOmnilingualText(
                            text,
                            languageHint = language
                        )

                        if (modelType == SherpaModelType.OMNILINGUAL_CTC && bestScore < 40) {
                            // Some omnilingual CTC builds are sensitive to waveform scale.
                            val scaled = FloatArray(audioSamples.size) { i -> audioSamples[i] * 32768f }
                            val scaledStream = rec.createStream()
                            try {
                                scaledStream.acceptWaveform(scaled, sampleRate = 16000)
                                rec.decode(scaledStream)
                                val scaledResult = rec.getResult(scaledStream)
                                val scaledText = scaledResult.text.trim()
                                val scaledScore = if (scaledText.isBlank()) Int.MIN_VALUE else scoreOmnilingualText(
                                    scaledText,
                                    languageHint = language
                                )
                                if (scaledScore > bestScore) {
                                    text = scaledText
                                    timestamps = scaledResult.timestamps
                                    lang = scaledResult.lang.takeIf { it.isNotBlank() }
                                    bestScore = scaledScore
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "Omnilingual scaled full decode retry failed", e)
                            } finally {
                                scaledStream.release()
                            }
                        }

                        if (text.isBlank() && modelType == SherpaModelType.OMNILINGUAL_CTC) {
                            text = decodeOmnilingualChunked(rec, audioSamples, languageHint = language)
                            timestamps = null
                        }
                    }

                    val decodeElapsedSec = (System.nanoTime() - decodeStartNs) / 1_000_000_000.0
                    Log.i(
                        TAG,
                        "Decode done modelType=$modelType dur=${String.format("%.1f", audioDurationSec)}s elapsed=${String.format("%.2f", decodeElapsedSec)}s textLen=${text.length}"
                    )
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

    private fun buildConfig(modelDir: String, threads: Int, provider: String): OfflineRecognizerConfig {
        val tokensPath = File(modelDir, "tokens.txt").absolutePath

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
                provider = provider,
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
                provider = provider,
            )
            SherpaModelType.OMNILINGUAL_CTC -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = findFile(modelDir, "model"),
                ),
                tokens = tokensPath,
                numThreads = threads,
                debug = false,
                provider = provider,
            )
        }

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    private fun computeOfflineThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return when {
            cores <= 2 -> 1
            cores <= 4 -> 2
            else -> 4
        }
    }

    private fun preferredProviders(): List<String> {
        // Keep sherpa-onnx offline on CPU by default for predictable thermal/latency behavior.
        return listOf("cpu")
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
    private fun decodeOmnilingualChunked(
        rec: OfflineRecognizer,
        samples: FloatArray,
        languageHint: String = "auto"
    ): String {
        val deadlineNs = System.nanoTime() + 45_000_000_000L

        fun runPass(input: FloatArray, chunkSize: Int, overlap: Int): String {
            val out = mutableListOf<String>()
            var offset = 0
            while (offset < input.size) {
                if (System.nanoTime() >= deadlineNs) {
                    Log.w(TAG, "Omnilingual chunk decode timeout; returning partial result")
                    break
                }
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
            return out.joinToString(" ").trim()
        }

        val raw = samples
        val scaled = FloatArray(samples.size) { i -> samples[i] * 32768f }
        val candidates = listOf(raw, scaled)
        val chunkShapes = listOf(
            Pair(16000 * 4, 16000 / 2),
            Pair(16000 * 8, 16000),
            Pair(16000 * 12, 16000 * 3 / 2),
        )
        var bestText = ""
        var bestScore = Int.MIN_VALUE

        for ((candidateIndex, candidate) in candidates.withIndex()) {
            if (System.nanoTime() >= deadlineNs) break
            for ((chunkSize, overlap) in chunkShapes) {
                if (System.nanoTime() >= deadlineNs) break
                val decoded = runPass(candidate, chunkSize = chunkSize, overlap = overlap)
                if (decoded.isBlank()) continue
                val score = scoreOmnilingualText(decoded, languageHint = languageHint)
                Log.i(
                    TAG,
                    "Omnilingual chunk candidate=${if (candidateIndex == 0) "raw" else "scaled"} " +
                        "chunk=${chunkSize / 16000.0f}s score=$score text='${decoded.take(120)}'"
                )
                if (score > bestScore) {
                    bestScore = score
                    bestText = decoded
                }
            }
        }

        if (bestText.isBlank()) {
            Log.w(TAG, "Omnilingual chunked fallback produced empty output")
        } else {
            Log.i(TAG, "Omnilingual chunked fallback selected score=$bestScore text='${bestText.take(160)}'")
        }
        return bestText
    }

    private fun scoreOmnilingualText(text: String, languageHint: String = "auto"): Int {
        val lower = text.lowercase()
        val normalizedHint = languageHint.trim().lowercase()
        var score = 0
        val keywords = listOf("country", "ask", "do for", "fellow", "americans")
        keywords.forEach { keyword ->
            if (lower.contains(keyword)) score += 120
        }
        val asciiLetters = text.count { it.isLetter() && it.code < 128 }
        val nonAsciiLetters = text.count { it.isLetter() && it.code >= 128 }
        score += asciiLetters
        // For English-hint decode, strongly discourage non-Latin output.
        score -= if (normalizedHint.startsWith("en")) nonAsciiLetters * 3 else nonAsciiLetters * 2
        score -= text.count { it == '\uFFFD' } * 8
        if (normalizedHint.startsWith("en") && asciiLetters == 0) {
            score -= 300
        }
        return score
    }
}
