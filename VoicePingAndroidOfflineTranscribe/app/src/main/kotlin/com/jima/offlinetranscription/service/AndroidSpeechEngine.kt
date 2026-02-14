package com.voiceping.offlinetranscription.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
 * On API < 33, transcribe() returns an error — only live mic works.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val preferOffline: Boolean = false
) : AsrEngine {
    companion object {
        private const val TAG = "AndroidSpeechEngine"
        private const val TIMEOUT_MS = 30_000L

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

    override val isLoaded: Boolean get() = loaded

    override suspend fun loadModel(modelPath: String): Boolean {
        if (!isSupported(context)) {
            Log.e(TAG, "SpeechRecognizer not available on this device")
            return false
        }

        if (preferOffline && !isOfflineAvailable(context)) {
            Log.w(TAG, "On-device recognition not available; will use EXTRA_PREFER_OFFLINE fallback")
        }

        return suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    recognizer?.destroy()
                    recognizer = if (preferOffline && Build.VERSION.SDK_INT >= 31 &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    ) {
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

        if (!supportsAudioPipe()) {
            Log.e(TAG, "EXTRA_AUDIO_SOURCE requires API 33+, current=${Build.VERSION.SDK_INT}")
            return listOf(
                TranscriptionSegment(
                    text = "[Android Speech requires API 33+ for file transcription]",
                    startMs = 0, endMs = 0
                )
            )
        }

        val audioDurationMs = (audioSamples.size.toLong() * 1000) / 16000
        Log.i(TAG, "Transcribing ${audioSamples.size} samples (${audioDurationMs}ms) preferOffline=$preferOffline")

        return withContext(Dispatchers.IO) {
            try {
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
                        readFd.close()
                        cont.resume(result)
                    }
                }
            }

            // Timeout handler
            val timeoutRunnable = Runnable {
                Log.w(TAG, "Recognition timed out after ${TIMEOUT_MS}ms")
                mainHandler.post {
                    try { recognizer?.cancel() } catch (_: Throwable) {}
                }
                resumeOnce(null)
            }
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

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
                if (Build.VERSION.SDK_INT >= 33) {
                    putExtra("android.speech.extra.AUDIO_SOURCE", readFd)
                    putExtra(
                        "android.speech.extra.AUDIO_SOURCE_ENCODING",
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", 16000)
                    putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1)
                }
                if (preferOffline) {
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
                    val buffer = ByteBuffer.allocate(audioSamples.size * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    for (sample in audioSamples) {
                        val clamped = sample.coerceIn(-1f, 1f)
                        val int16 = (clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        buffer.putShort(int16)
                    }
                    outputStream.write(buffer.array())
                    outputStream.close()
                    Log.i(TAG, "Wrote ${audioSamples.size * 2} PCM bytes to pipe")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to write PCM to pipe", e)
                    try { writeFd.close() } catch (_: Throwable) {}
                }
            }.start()
        }
    }
}
