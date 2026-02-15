package com.voiceping.offlinetranscription.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * ASR engine backed by Android's built-in SpeechRecognizer.
 * Supports both online (cloud) and offline (on-device) modes.
 *
 * File-based transcription (feeding pre-recorded audio) requires API 33+
 * via EXTRA_AUDIO_SOURCE with ParcelFileDescriptor pipe.
 * On API < 33, transcribe() returns an error (no file-audio input). For E2E benchmarks,
 * transcribeViaAcousticLoopback() can be used as a best-effort workaround.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val preferOffline: Boolean = false
) : AsrEngine {
    companion object {
        private const val TAG = "AndroidSpeechEngine"
        // SpeechRecognizer can legitimately take ~audioDuration to return results when using mic input.
        // Keep a base timeout but extend it based on audio length in each request.
        private const val BASE_TIMEOUT_MS = 30_000L

        fun isSupported(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }

        fun isOfflineAvailable(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } else {
                false
            }
        }

        fun supportsAudioPipe(): Boolean = Build.VERSION.SDK_INT >= 33

        private fun errorCodeToString(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_UNKNOWN($error)"
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var loaded = false
    private fun shouldPreferOffline(): Boolean = preferOffline && isOfflineAvailable(context)

    override val isLoaded: Boolean get() = loaded

    override suspend fun loadModel(modelPath: String): Boolean {
        if (!isSupported(context)) {
            Log.e(TAG, "SpeechRecognizer not available on this device")
            return false
        }

        if (preferOffline && !shouldPreferOffline()) {
            Log.w(TAG, "On-device recognition not available; falling back to standard SpeechRecognizer (may use network)")
        }

        return suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    recognizer?.destroy()
                    recognizer = if (shouldPreferOffline()) {
                        Log.i(TAG, "Creating on-device SpeechRecognizer (API 31+)")
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        Log.i(TAG, "Creating standard SpeechRecognizer (preferOffline=$preferOffline)")
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                    loaded = true
                    Log.i(TAG, "SpeechRecognizer created successfully")
                    cont.resume(true)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to create SpeechRecognizer", e)
                    loaded = false
                    cont.resume(false)
                }
            }
        }
    }

    override suspend fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int,
        language: String
    ): List<TranscriptionSegment> {
        if (!loaded || recognizer == null) {
            Log.e(TAG, "transcribe called but recognizer not loaded")
            return emptyList()
        }

        val audioDurationMs = (audioSamples.size.toLong() * 1000) / 16000
        Log.i(TAG, "Transcribing ${audioSamples.size} samples (${audioDurationMs}ms) preferOffline=$preferOffline")

        return withContext(Dispatchers.IO) {
            try {
                if (!supportsAudioPipe()) {
                    Log.e(TAG, "EXTRA_AUDIO_SOURCE requires API 33+, current=${Build.VERSION.SDK_INT}")
                    return@withContext listOf(
                        TranscriptionSegment(
                            text = "[Android Speech requires API 33+ for file transcription]",
                            startMs = 0, endMs = 0
                        )
                    )
                }

                val result = recognizeFromBuffer(audioSamples, language)
                if (result.isNullOrBlank()) {
                    Log.w(TAG, "SpeechRecognizer returned empty result")
                    return@withContext emptyList()
                }
                Log.i(TAG, "Transcription result (${result.length} chars): ${result.take(120)}")
                listOf(
                    TranscriptionSegment(
                        text = result.trim(),
                        startMs = 0,
                        endMs = audioDurationMs
                    )
                )
            } catch (e: Throwable) {
                Log.e(TAG, "SpeechRecognizer transcription failed", e)
                emptyList()
            }
        }
    }

    /**
     * Best-effort file transcription on API < 33 by "acoustic loopback":
     * play the provided PCM samples through the device speaker while SpeechRecognizer listens to the mic.
     *
     * This is intended for E2E benchmarking on older devices. It is not deterministic:
     * echo cancellation, room acoustics, and volume can cause empty/incorrect results.
     */
    suspend fun transcribeViaAcousticLoopback(
        audioSamples: FloatArray,
        language: String
    ): List<TranscriptionSegment> {
        if (!loaded || recognizer == null) {
            Log.e(TAG, "transcribeViaAcousticLoopback called but recognizer not loaded")
            return emptyList()
        }

        val audioDurationMs = (audioSamples.size.toLong() * 1000) / 16000
        Log.i(TAG, "Loopback transcription: ${audioSamples.size} samples (${audioDurationMs}ms) preferOffline=$preferOffline")

        return withContext(Dispatchers.IO) {
            try {
                val result = recognizeFromSpeakerLoopback(audioSamples, language)
                if (result.isNullOrBlank()) {
                    Log.w(TAG, "Loopback SpeechRecognizer returned empty result")
                    return@withContext emptyList()
                }
                listOf(
                    TranscriptionSegment(
                        text = result.trim(),
                        startMs = 0,
                        endMs = audioDurationMs
                    )
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Loopback SpeechRecognizer transcription failed", e)
                emptyList()
            }
        }
    }

    override fun release() {
        loaded = false
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to destroy SpeechRecognizer", e)
            }
            recognizer = null
        }
    }

    /**
     * Feed audio samples to SpeechRecognizer via EXTRA_AUDIO_SOURCE pipe.
     * Converts Float32 [-1,1] to Int16 PCM, writes to pipe, awaits result.
     */
    private suspend fun recognizeFromBuffer(
        audioSamples: FloatArray,
        language: String
    ): String? {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        return suspendCancellableCoroutine { cont ->
            var hasResumed = false
            val resumeOnce: (String?) -> Unit = { result ->
                synchronized(this) {
                    if (!hasResumed) {
                        hasResumed = true
                        try { readFd.close() } catch (_: Throwable) {}
                        try { writeFd.close() } catch (_: Throwable) {}
                        cont.resume(result)
                    }
                }
            }

            val timeoutMs = computeTimeoutMs(audioSamples)
            // Timeout handler
            val timeoutRunnable = Runnable {
                Log.w(TAG, "Recognition timed out after ${timeoutMs}ms")
                mainHandler.post {
                    try { recognizer?.cancel() } catch (_: Throwable) {}
                }
                resumeOnce(null)
            }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.post {
                    try { recognizer?.cancel() } catch (_: Throwable) {}
                }
                readFd.close()
                writeFd.close()
            }

            // Set up recognition listener
            val listener = object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    Log.i(TAG, "onResults: ${text?.take(100) ?: "<null>"}")
                    resumeOnce(text)
                }

                override fun onError(error: Int) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e(TAG, "onError: ${errorCodeToString(error)}")
                    resumeOnce(null)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "onPartialResults: ${matches?.firstOrNull()?.take(60)}")
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "onReadyForSpeech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "onBeginningOfSpeech")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "onEndOfSpeech")
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

            // Build recognition intent
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // NOTE: Officially documented in API 33+, but we guard usage at the call site.
                putExtra("android.speech.extra.AUDIO_SOURCE", readFd)
                putExtra(
                    "android.speech.extra.AUDIO_SOURCE_ENCODING",
                    AudioFormat.ENCODING_PCM_16BIT
                )
                putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", 16000)
                putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1)
                if (shouldPreferOffline()) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                // Set language if specified and non-empty
                val lang = language.trim().lowercase()
                if (lang.isNotEmpty() && lang != "auto") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                }
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Extend silence timeouts for long audio
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 30_000L)
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5_000L)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 3_000L)
            }

            // Start listening on main thread
            mainHandler.post {
                try {
                    recognizer?.setRecognitionListener(listener)
                    recognizer?.startListening(intent)
                    Log.i(TAG, "startListening called")
                } catch (e: Throwable) {
                    Log.e(TAG, "startListening failed", e)
                    mainHandler.removeCallbacks(timeoutRunnable)
                    resumeOnce(null)
                    return@post
                }
            }

            // Write PCM data to pipe on IO thread
            Thread {
                try {
                    val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
                    outputStream.write(floatToPcm16LeBytes(audioSamples))
                    outputStream.close()
                    Log.i(TAG, "Wrote ${audioSamples.size * 2} PCM bytes to pipe")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to write PCM to pipe", e)
                    try { writeFd.close() } catch (_: Throwable) {}
                }
            }.start()
        }
    }

    private fun computeTimeoutMs(audioSamples: FloatArray): Long {
        val audioDurationMs = (audioSamples.size.toLong() * 1000) / 16000
        return maxOf(BASE_TIMEOUT_MS, audioDurationMs + 20_000L)
    }

    private fun floatToPcm16LeBytes(audioSamples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(audioSamples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audioSamples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val int16 = (clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(int16)
        }
        return buffer.array()
    }

    /**
     * Mic-based recognition with the audio played through the speaker.
     * Used as a fallback to produce benchmark numbers on devices where audio-pipe input isn't available.
     */
    private suspend fun recognizeFromSpeakerLoopback(
        audioSamples: FloatArray,
        language: String
    ): String? {
        val pcmBytes = floatToPcm16LeBytes(audioSamples)
        val audioFrames = audioSamples.size

        return suspendCancellableCoroutine { cont ->
            var hasResumed = false
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

            var audioTrack: AudioTrack? = null
            var playbackThread: Thread? = null

            val stopPlayback: () -> Unit = {
                cancelled.set(true)
                try {
                    audioTrack?.pause()
                } catch (_: Throwable) {}
                try {
                    audioTrack?.flush()
                } catch (_: Throwable) {}
                try {
                    audioTrack?.stop()
                } catch (_: Throwable) {}
                try {
                    audioTrack?.release()
                } catch (_: Throwable) {}
                audioTrack = null
            }

            val resumeOnce: (String?) -> Unit = { result ->
                synchronized(this) {
                    if (!hasResumed) {
                        hasResumed = true
                        stopPlayback()
                        cont.resume(result)
                    }
                }
            }

            val timeoutMs = computeTimeoutMs(audioSamples)
            val timeoutRunnable = Runnable {
                Log.w(TAG, "Loopback recognition timed out after ${timeoutMs}ms")
                mainHandler.post {
                    try { recognizer?.cancel() } catch (_: Throwable) {}
                }
                resumeOnce(null)
            }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            cont.invokeOnCancellation {
                cancelled.set(true)
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.post {
                    try { recognizer?.cancel() } catch (_: Throwable) {}
                }
                stopPlayback()
            }

            val startedPlaybackLock = Any()
            var startedPlayback = false
            fun startPlaybackOnce() {
                synchronized(startedPlaybackLock) {
                    if (startedPlayback || cancelled.get()) return
                    startedPlayback = true
                }

                playbackThread = Thread {
                    try {
                        val sampleRate = 16000
                        val channelOut = AudioFormat.CHANNEL_OUT_MONO
                        val encoding = AudioFormat.ENCODING_PCM_16BIT
                        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
                        val bufferSize = maxOf(minBuffer, 8 * 1024)

                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelOut)
                                    .setEncoding(encoding)
                                    .build()
                            )
                            .setBufferSizeInBytes(bufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                            .build()

                        audioTrack = track
                        try { track.setVolume(1.0f) } catch (_: Throwable) {}

                        track.play()
                        var offset = 0
                        while (!cancelled.get() && offset < pcmBytes.size) {
                            val toWrite = minOf(16 * 1024, pcmBytes.size - offset)
                            val written = track.write(pcmBytes, offset, toWrite)
                            if (written <= 0) {
                                Log.w(TAG, "AudioTrack.write returned $written, stopping playback")
                                break
                            }
                            offset += written
                        }

                        // Wait for playback to actually drain before stopping recognition.
                        val deadline = SystemClock.elapsedRealtime() + computeTimeoutMs(audioSamples)
                        while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                            val playedFrames = try { track.playbackHeadPosition } catch (_: Throwable) { 0 }
                            if (playedFrames >= audioFrames) break
                            Thread.sleep(50)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Loopback playback failed", e)
                    } finally {
                        mainHandler.post {
                            try { recognizer?.stopListening() } catch (_: Throwable) {}
                        }
                        stopPlayback()
                    }
                }.apply { name = "AndroidSpeechLoopbackPlayback" }
                playbackThread?.start()
            }

            val listener = object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    Log.i(TAG, "loopback onResults: ${text?.take(100) ?: "<null>"}")
                    resumeOnce(text)
                }

                override fun onError(error: Int) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e(TAG, "loopback onError: ${errorCodeToString(error)}")
                    resumeOnce(null)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "loopback onPartialResults: ${matches?.firstOrNull()?.take(60)}")
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "loopback onReadyForSpeech")
                    startPlaybackOnce()
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "loopback onBeginningOfSpeech")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "loopback onEndOfSpeech")
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (shouldPreferOffline()) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                val lang = language.trim().lowercase()
                if (lang.isNotEmpty() && lang != "auto") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                }
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 30_000L)
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5_000L)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 3_000L)
            }

            mainHandler.post {
                try {
                    recognizer?.setRecognitionListener(listener)
                    recognizer?.startListening(intent)
                    Log.i(TAG, "loopback startListening called")
                } catch (e: Throwable) {
                    Log.e(TAG, "loopback startListening failed", e)
                    mainHandler.removeCallbacks(timeoutRunnable)
                    resumeOnce(null)
                }
            }
        }
    }
}
