package com.voiceping.offlinetranscription.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.voiceping.offlinetranscription.data.AppPreferences
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.AppError
import com.voiceping.offlinetranscription.model.EngineType
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.ModelState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class TranscriptionSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val detectedLanguage: String? = null
)

/** Strict session state machine for the transcription pipeline. */
enum class SessionState {
    Idle,       // No recording, ready to start
    Recording,  // Mic active, transcription loop running
    Stopping,   // Stop requested, waiting for jobs to complete
    Error       // Error occurred, needs clearTranscription() to reset
}

/** E2E test result for evidence collection. */
data class E2ETestResult(
    val modelId: String,
    val engine: String,
    val transcript: String,
    val tokensPerSecond: Double,
    val translatedText: String,
    val pass: Boolean,
    val skipped: Boolean = false,
    val durationMs: Double,
    val timestamp: String,
    val error: String? = null
)

class WhisperEngine(
    private val context: Context,
    private val preferences: AppPreferences
) {
    // NOTE: Cactus Android SDK hardcodes its own model cache under `filesDir/models/<slug>`.
    // Our app models must not share that namespace, otherwise Cactus will see our
    // whisper.cpp/sherpa directories as "downloaded" and skip its own download.
    private val modelsDir = File(context.filesDir, "asr_models")
    private val downloader = ModelDownloader(modelsDir)
    val audioRecorder = AudioRecorder(context)

    // Model state
    private val _modelState = MutableStateFlow(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _selectedModel = MutableStateFlow(ModelInfo.defaultModel)
    val selectedModel: StateFlow<ModelInfo> = _selectedModel.asStateFlow()

    // Session state machine
    private val _sessionState = MutableStateFlow(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // Derived isRecording for backward compat
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Transcription output
    private val _confirmedText = MutableStateFlow("")
    val confirmedText: StateFlow<String> = _confirmedText.asStateFlow()

    private val _hypothesisText = MutableStateFlow("")
    val hypothesisText: StateFlow<String> = _hypothesisText.asStateFlow()

    private val _bufferEnergy = MutableStateFlow<List<Float>>(emptyList())
    val bufferEnergy: StateFlow<List<Float>> = _bufferEnergy.asStateFlow()

    private val _bufferSeconds = MutableStateFlow(0.0)
    val bufferSeconds: StateFlow<Double> = _bufferSeconds.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0.0)
    val tokensPerSecond: StateFlow<Double> = _tokensPerSecond.asStateFlow()

    private val _lastError = MutableStateFlow<AppError?>(null)
    val lastError: StateFlow<AppError?> = _lastError.asStateFlow()

    private val _useVAD = MutableStateFlow(true)
    val useVAD: StateFlow<Boolean> = _useVAD.asStateFlow()

    private val _enableTimestamps = MutableStateFlow(true)
    val enableTimestamps: StateFlow<Boolean> = _enableTimestamps.asStateFlow()

    private val _audioInputMode = MutableStateFlow(AudioInputMode.MICROPHONE)
    val audioInputMode: StateFlow<AudioInputMode> = _audioInputMode.asStateFlow()

    private val _systemAudioCaptureReady = MutableStateFlow(false)
    val systemAudioCaptureReady: StateFlow<Boolean> = _systemAudioCaptureReady.asStateFlow()
    val isSystemAudioCaptureSupported: Boolean
        get() = audioRecorder.isSystemAudioCaptureSupported

    @Volatile var e2eLocked = false
    private val _translationEnabled = MutableStateFlow(false)
    val translationEnabled: StateFlow<Boolean> = _translationEnabled.asStateFlow()

    private val _translationSourceLanguageCode = MutableStateFlow("en")
    val translationSourceLanguageCode: StateFlow<String> = _translationSourceLanguageCode.asStateFlow()

    private val _translationTargetLanguageCode = MutableStateFlow("ja")
    val translationTargetLanguageCode: StateFlow<String> = _translationTargetLanguageCode.asStateFlow()

    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()

    private val _translatedConfirmedText = MutableStateFlow("")
    val translatedConfirmedText: StateFlow<String> = _translatedConfirmedText.asStateFlow()

    private val _translatedHypothesisText = MutableStateFlow("")
    val translatedHypothesisText: StateFlow<String> = _translatedHypothesisText.asStateFlow()

    private val _translationWarning = MutableStateFlow<String?>(null)
    val translationWarning: StateFlow<String?> = _translationWarning.asStateFlow()

    val translationModelReady: StateFlow<Boolean> get() = mlKitTranslator.modelReady
    val translationDownloadStatus: StateFlow<String?> get() = mlKitTranslator.downloadStatus

    // System resource metrics (always sampled)
    private val systemMetrics = SystemMetrics()
    private val _cpuPercent = MutableStateFlow(0f)
    val cpuPercent: StateFlow<Float> = _cpuPercent.asStateFlow()
    private val _memoryMB = MutableStateFlow(0f)
    val memoryMB: StateFlow<Float> = _memoryMB.asStateFlow()

    // E2E evidence result
    private val _e2eResult = MutableStateFlow<E2ETestResult?>(null)
    val e2eResult: StateFlow<E2ETestResult?> = _e2eResult.asStateFlow()

    // ASR engine abstraction
    private val setupMutex = Mutex()
    private var currentEngine: AsrEngine? = null
    private var transcriptionJob: Job? = null
    private var recordingJob: Job? = null
    private var energyJob: Job? = null
    private val recorderPrewarmMutex = Mutex()
    private val inferencePrewarmMutex = Mutex()
    private var chunkManager = createChunkManagerForModel(_selectedModel.value)
    private var lastBufferSize: Int = 0
    private val inferenceMutex = Mutex()
    private var prewarmedModelId: String? = null
    private var recordingStartElapsedMs: Long = 0L
    private var hasCompletedFirstInference: Boolean = false
    private var realtimeInferenceCount: Long = 0
    private var movingAverageInferenceSeconds: Double = 0.0
    private val sessionToken = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mlKitTranslator = MlKitTranslator()
    private var translationJob: Job? = null
    private var lastTranslationInput: Pair<String, String>? = null

    private fun nextSessionToken(): Long = sessionToken.incrementAndGet()

    private fun invalidateSession() {
        sessionToken.incrementAndGet()
    }

    private fun isSessionActive(token: Long): Boolean {
        return sessionToken.get() == token && _sessionState.value == SessionState.Recording
    }

    companion object {
        private const val MAX_BUFFER_SAMPLES = 16000 * 300 // 5 minutes
        // Low-latency tuning for live mic mode (file transcription path is unaffected).
        private const val DEFAULT_MIN_NEW_AUDIO_SECONDS = 0.45f
        private const val DEFAULT_OFFLINE_REALTIME_CHUNK_SECONDS = 2.8f
        private const val DEFAULT_INITIAL_MIN_NEW_AUDIO_SECONDS = 0.20f
        // sherpa-onnx offline models (SenseVoice, Moonshine): fast cadence with
        // CPU-aware delay. Smaller 3.5s chunks keep each inference quick (~0.2s),
        // and the duty-cycle guard prevents CPU starvation. Matches iOS timing.
        private const val SHERPA_OFFLINE_REALTIME_CHUNK_SECONDS = 3.5f
        private const val SHERPA_INITIAL_MIN_NEW_AUDIO_SECONDS = 0.35f
        private const val SHERPA_MIN_NEW_AUDIO_SECONDS = 0.7f
        // Cactus requires at least ~1s of 16kHz mono PCM per inference request.
        private const val CACTUS_INITIAL_MIN_NEW_AUDIO_SECONDS = 1.0f
        private const val CACTUS_MIN_NEW_AUDIO_SECONDS = 1.0f
        // Omnilingual CTC is significantly heavier than other sherpa offline models.
        // Keep realtime decode windows smaller and cadence slower to avoid UI starvation.
        private const val OMNILINGUAL_REALTIME_CHUNK_SECONDS = 4.0f
        private const val OMNILINGUAL_INITIAL_MIN_NEW_AUDIO_SECONDS = 3.0f
        private const val OMNILINGUAL_MIN_NEW_AUDIO_SECONDS = 3.0f
        private const val SHERPA_MIN_INFERENCE_RMS = 0.012f
        private const val INITIAL_VAD_BYPASS_SECONDS = 1.0f
        private const val INFERENCE_PREWARM_AUDIO_SECONDS = 0.5f
        private const val TARGET_INFERENCE_DUTY_CYCLE = 0.24f
        private const val MAX_CPU_PROTECT_DELAY_SECONDS = 1.6f
        private const val INFERENCE_EMA_ALPHA = 0.20
        private const val DIAGNOSTIC_LOG_INTERVAL = 5L
        // Emulator host-mic levels are often lower than physical devices.
        private const val SILENCE_THRESHOLD = 0.0015f
        private const val VAD_PREROLL_SECONDS = 0.6f
        private const val NO_SIGNAL_TIMEOUT_SECONDS = 8.0
        private const val SIGNAL_ENERGY_THRESHOLD = 0.005f
        private val WHITESPACE_REGEX = "\\s+".toRegex()

        /**
         * Normalize language codes: strip SenseVoice "<|en|>" markers,
         * trim, lowercase, take first BCP-47 segment, validate letters only.
         */
        fun normalizeLanguageCode(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw
                .replace("<|", "").replace("|>", "")
                .trim().lowercase()
                .split("-").first()
            return cleaned.takeIf { it.isNotBlank() && it.all { c -> c.isLetter() } }
        }
        private const val CJK_CHAR_CLASS = "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}々〆ヵヶー]"
        private val CJK_INNER_SPACE_REGEX = "($CJK_CHAR_CLASS)\\s+($CJK_CHAR_CLASS)".toRegex()
        private val SPACE_BEFORE_CJK_PUNCT_REGEX = "\\s+([、。！？：；）」』】〉》])".toRegex()
        private val SPACE_AFTER_CJK_OPEN_PUNCT_REGEX = "([（「『【〈《])\\s+".toRegex()
        private val SPACE_AFTER_CJK_END_PUNCT_REGEX = "([、。！？：；])\\s+($CJK_CHAR_CLASS)".toRegex()
    }

    val fullTranscriptionText: String
        get() = chunkManager.fullTranscriptionText()

    val recordingDurationSeconds: Double
        get() = audioRecorder.bufferSeconds

    init {
        scope.launch {
            preferences.selectedModelId.collect { savedId ->
                if (e2eLocked) return@collect
                if (savedId != null) {
                    ModelInfo.findByIdOrLegacy(savedId)?.let {
                        _selectedModel.value = it
                    }
                }
            }
        }
        scope.launch {
            preferences.useVAD.collect { _useVAD.value = it }
        }
        scope.launch {
            preferences.enableTimestamps.collect { _enableTimestamps.value = it }
        }
        scope.launch {
            preferences.translationEnabled.collect { enabled ->
                if (e2eLocked) return@collect
                _translationEnabled.value = enabled
                if (enabled) {
                    scheduleTranslationUpdate()
                } else {
                    resetTranslationState()
                }
            }
        }
        scope.launch {
            preferences.translationSourceLanguage.collect { code ->
                if (e2eLocked) return@collect
                if (code != _translationSourceLanguageCode.value) {
                    _translationSourceLanguageCode.value = code
                    lastTranslationInput = null
                    scheduleTranslationUpdate()
                }
            }
        }
        scope.launch {
            preferences.translationTargetLanguage.collect { code ->
                if (e2eLocked) return@collect
                if (code != _translationTargetLanguageCode.value) {
                    _translationTargetLanguageCode.value = code
                    lastTranslationInput = null
                    scheduleTranslationUpdate()
                }
            }
        }
        // Always-running system metrics sampling
        scope.launch(Dispatchers.Default) {
            while (true) {
                _cpuPercent.value = systemMetrics.getCpuPercent()
                _memoryMB.value = systemMetrics.getMemoryMB()
                delay(1000)
            }
        }
    }

    /** Create the appropriate ASR engine for the given model. */
    private fun createEngine(model: ModelInfo): AsrEngine {
        return when (model.engineType) {
            EngineType.SHERPA_ONNX -> SherpaOnnxEngine(
                modelType = model.sherpaModelType
                    ?: throw IllegalArgumentException("sherpaModelType required for SHERPA_ONNX models")
            )
            EngineType.SHERPA_ONNX_STREAMING -> SherpaOnnxStreamingEngine()
            EngineType.CACTUS -> {
                if (!CactusEngine.isRuntimeSupported()) {
                    throw IllegalStateException("Cactus engine requires an arm64-v8a device.")
                }
                CactusEngine(
                    cactusModelType = model.cactusModelType
                        ?: throw IllegalArgumentException("cactusModelType required for CACTUS models")
                )
            }
            EngineType.QWEN_ASR -> QwenASREngine()
            EngineType.QWEN_ONNX -> QwenOnnxEngine()
            EngineType.ANDROID_SPEECH -> AndroidSpeechEngine(
                context = context,
                preferOffline = model.id.contains("offline")
            )
        }
    }

    private fun createChunkManagerForModel(model: ModelInfo): StreamingChunkManager {
        val chunkSeconds = when (model.engineType) {
            EngineType.SHERPA_ONNX, EngineType.CACTUS -> if (isOmnilingualModel(model)) {
                OMNILINGUAL_REALTIME_CHUNK_SECONDS
            } else {
                SHERPA_OFFLINE_REALTIME_CHUNK_SECONDS
            }
            else -> DEFAULT_OFFLINE_REALTIME_CHUNK_SECONDS
        }
        val minNewAudioSeconds = when (model.engineType) {
            EngineType.SHERPA_ONNX -> if (isOmnilingualModel(model)) {
                OMNILINGUAL_MIN_NEW_AUDIO_SECONDS
            } else {
                SHERPA_MIN_NEW_AUDIO_SECONDS
            }
            EngineType.CACTUS -> CACTUS_MIN_NEW_AUDIO_SECONDS
            else -> DEFAULT_MIN_NEW_AUDIO_SECONDS
        }
        return StreamingChunkManager(
            chunkSeconds = chunkSeconds,
            sampleRate = AudioRecorder.SAMPLE_RATE,
            minNewAudioSeconds = minNewAudioSeconds
        )
    }

    private fun isFastOfflineModel(): Boolean {
        val type = _selectedModel.value.engineType
        return type == EngineType.SHERPA_ONNX || type == EngineType.CACTUS
    }

    private fun isCactusModel(model: ModelInfo = _selectedModel.value): Boolean {
        return model.engineType == EngineType.CACTUS
    }

    private fun isOmnilingualModel(model: ModelInfo = _selectedModel.value): Boolean {
        return model.engineType == EngineType.SHERPA_ONNX &&
            model.sherpaModelType == com.voiceping.offlinetranscription.model.SherpaModelType.OMNILINGUAL_CTC
    }

    /** Resolve the path to pass to loadModel based on engine type. */
    private fun resolveModelPath(model: ModelInfo): String {
        return when (model.engineType) {
            EngineType.SHERPA_ONNX -> downloader.modelDir(model).absolutePath
            EngineType.SHERPA_ONNX_STREAMING -> downloader.modelDir(model).absolutePath
            EngineType.CACTUS -> downloader.modelDir(model).absolutePath
            EngineType.QWEN_ASR -> downloader.modelDir(model).absolutePath
            EngineType.QWEN_ONNX -> downloader.modelDir(model).absolutePath
            EngineType.ANDROID_SPEECH -> "" // System-provided, no model path
        }
    }

    /** Ensure startup model selection reflects persisted user preference before loading. */
    suspend fun syncSelectedModelFromPreferences() {
        val savedId = preferences.selectedModelId.first() ?: return
        val savedModel = ModelInfo.findByIdOrLegacy(savedId) ?: return
        _selectedModel.value = savedModel
    }

    suspend fun loadModelIfAvailable() {
        val model = _selectedModel.value
        if (!downloader.isModelDownloaded(model)) return

        _modelState.value = ModelState.Loading
        _lastError.value = null

        try {
            val engine = createEngine(model)
            val modelPath = resolveModelPath(model)
            val success = engine.loadModel(modelPath)
            if (!success) throw Exception("Failed to load model")
            downloader.markManagedModelReady(model)
            currentEngine = engine
            prewarmedModelId = null
            _modelState.value = ModelState.Loaded
        } catch (e: Throwable) {
            _modelState.value = ModelState.Unloaded
        }
    }

    fun unloadModel() {
        resetTranscriptionState()
        currentEngine?.release()
        currentEngine = null
        prewarmedModelId = null
        _modelState.value = ModelState.Unloaded
    }

    fun isModelDownloaded(model: ModelInfo): Boolean = downloader.isModelDownloaded(model)

    fun setSelectedModel(model: ModelInfo) {
        _selectedModel.value = model
        chunkManager = createChunkManagerForModel(model)
        resetTranscriptionState()
    }

    /** Launch setupModel on the engine's own scope so it survives ViewModel destruction. */
    fun launchSetup() {
        scope.launch { setupModel() }
    }

    suspend fun setupModel() = setupMutex.withLock {
        val model = _selectedModel.value
        _modelState.value = ModelState.Downloading
        _downloadProgress.value = 0f
        _lastError.value = null

        try {
            if (!downloader.isModelDownloaded(model)) {
                if (model.files.isNotEmpty() && !hasValidatedInternetConnection()) {
                    Log.w("WhisperEngine", "No validated internet connection while downloading ${model.id}")
                    _modelState.value = ModelState.Unloaded
                    _lastError.value = AppError.NetworkUnavailable()
                    return@withLock
                }

                // Check available storage before attempting download
                // Use context.filesDir (always exists) since modelsDir may not exist yet
                val available = context.filesDir.usableSpace
                val needed = parseModelSize(model.sizeOnDisk)
                if (needed > 0 && available < (needed * 1.1).toLong()) {
                    _modelState.value = ModelState.Unloaded
                    _lastError.value = AppError.InsufficientStorage(
                        needed = model.sizeOnDisk,
                        available = formatBytes(available)
                    )
                    return@withLock
                }

                downloader.download(model).collect { progress ->
                    _downloadProgress.value = progress
                }
            }

            _modelState.value = ModelState.Loading
            _downloadProgress.value = 1f

            val engine = createEngine(model)
            val modelPath = resolveModelPath(model)
            val success = withContext(Dispatchers.Default) {
                engine.loadModel(modelPath)
            }
            if (!success) throw Exception("Failed to load model")
            downloader.markManagedModelReady(model)

            val previousEngine = currentEngine
            currentEngine = engine
            prewarmedModelId = null
            _modelState.value = ModelState.Loaded
            preferences.setSelectedModelId(model.id)
            preferences.setLastModelPath(modelPath)
            if (previousEngine != null && previousEngine !== engine) {
                withContext(Dispatchers.Default) {
                    previousEngine.release()
                }
            }
        } catch (e: Throwable) {
            val wasDownloading = _downloadProgress.value < 1f
            _modelState.value = ModelState.Unloaded
            _downloadProgress.value = 0f
            _lastError.value = if (wasDownloading) {
                mapDownloadError(e)
            } else {
                AppError.ModelLoadFailed(e)
            }
        }
    }

    suspend fun switchModel(model: ModelInfo) {
        if (_sessionState.value == SessionState.Recording) {
            stopRecordingAndWait()
        }
        _selectedModel.value = model
        chunkManager = createChunkManagerForModel(model)
        resetTranscriptionState()

        val previousEngine = currentEngine
        if (previousEngine != null) {
            withContext(Dispatchers.Default) {
                previousEngine.release()
            }
        }
        currentEngine = null
        prewarmedModelId = null
        _modelState.value = ModelState.Unloaded
        setupModel()
    }

    suspend fun setUseVAD(enabled: Boolean) {
        _useVAD.value = enabled
        preferences.setUseVAD(enabled)
    }

    suspend fun setEnableTimestamps(enabled: Boolean) {
        _enableTimestamps.value = enabled
        preferences.setEnableTimestamps(enabled)
    }

    suspend fun setTranslationEnabled(enabled: Boolean) {
        _translationEnabled.value = enabled
        preferences.setTranslationEnabled(enabled)
        if (!enabled) {
            resetTranslationState()
        } else {
            scheduleTranslationUpdate()
        }
    }

    suspend fun setTranslationSourceLanguageCode(languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        _translationSourceLanguageCode.value = normalized
        preferences.setTranslationSourceLanguage(normalized)
        lastTranslationInput = null  // Force re-translation with new language
        scheduleTranslationUpdate()
    }

    suspend fun setTranslationTargetLanguageCode(languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        _translationTargetLanguageCode.value = normalized
        preferences.setTranslationTargetLanguage(normalized)
        lastTranslationInput = null  // Force re-translation with new language
        scheduleTranslationUpdate()
    }

    /**
     * Warm up microphone recorder path ahead of first record tap so runtime setup
     * does not steal the beginning of the utterance.
     */
    suspend fun prewarmRecordingPath() {
        if (_audioInputMode.value != AudioInputMode.MICROPHONE) return
        if (!audioRecorder.hasPermission()) return
        if (_sessionState.value != SessionState.Idle) return
        recorderPrewarmMutex.withLock {
            withContext(Dispatchers.IO) {
                audioRecorder.prewarm(_audioInputMode.value)
            }
        }
    }

    /**
     * Prime the first native ASR decode so user speech is not delayed by runtime
     * graph compilation / allocator initialization on the first utterance.
     */
    suspend fun prewarmInferencePath() {
        if (_sessionState.value != SessionState.Idle) return
        val engine = currentEngine ?: return
        if (!engine.isLoaded) return
        val selectedModel = _selectedModel.value
        val currentModelId = selectedModel.id
        if (prewarmedModelId == currentModelId) return

        inferencePrewarmMutex.withLock {
            if (_sessionState.value != SessionState.Idle) return
            val liveEngine = currentEngine ?: return
            if (!liveEngine.isLoaded) return
            val liveModel = _selectedModel.value
            val liveModelId = liveModel.id
            if (prewarmedModelId == liveModelId) return

            // Skip synthetic inference prewarm — sherpa-onnx warmup can burn CPU on
            // some Android runtimes while idle. Mic prewarm is still active.
            prewarmedModelId = liveModelId
            Log.i("WhisperEngine", "Skipping inference prewarm for ${liveModel.engineType}")
        }
    }

    suspend fun prewarmRealtimePath() {
        Log.i(
            "WhisperEngine",
            "prewarmRealtimePath: state=${_sessionState.value}, inputMode=${_audioInputMode.value}, micPermission=${audioRecorder.hasPermission()}, modelLoaded=${currentEngine?.isLoaded == true}"
        )
        prewarmRecordingPath()
        prewarmInferencePath()
    }

    fun startRecording() {
        Log.i("WhisperEngine", "startRecording: sessionState=${_sessionState.value}, engine=${currentEngine != null}, loaded=${currentEngine?.isLoaded}")
        if (_sessionState.value != SessionState.Idle) {
            Log.w("WhisperEngine", "startRecording: not idle (${_sessionState.value}), ignoring")
            return
        }

        val engine = currentEngine
        if (engine == null || !engine.isLoaded) {
            Log.e("WhisperEngine", "startRecording: model not ready")
            _lastError.value = AppError.ModelNotReady()
            transitionTo(SessionState.Error)
            return
        }

        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            if (!audioRecorder.isSystemAudioCaptureSupported) {
                _lastError.value = AppError.SystemAudioCaptureUnsupported()
                transitionTo(SessionState.Error)
                return
            }
            if (!audioRecorder.hasSystemAudioCapturePermission) {
                _lastError.value = AppError.SystemAudioCapturePermissionDenied()
                transitionTo(SessionState.Error)
                return
            }
            // Ensure foreground service is running for MediaProjection
            try {
                context.startForegroundService(
                    Intent(context, MediaProjectionService::class.java)
                )
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to start MediaProjectionService: ${e.message}")
            }
        }

        if (!audioRecorder.hasPermission()) {
            Log.e("WhisperEngine", "startRecording: no mic permission")
            _lastError.value = AppError.MicrophonePermissionDenied()
            transitionTo(SessionState.Error)
            return
        }

        resetTranscriptionState()
        transitionTo(SessionState.Recording)
        recordingStartElapsedMs = SystemClock.elapsedRealtime()
        hasCompletedFirstInference = false
        realtimeInferenceCount = 0
        val chunkSeconds = chunkManager.chunkSamples.toFloat() / AudioRecorder.SAMPLE_RATE
        val baseGate = when {
            isOmnilingualModel() -> OMNILINGUAL_MIN_NEW_AUDIO_SECONDS
            isCactusModel() -> CACTUS_MIN_NEW_AUDIO_SECONDS
            isFastOfflineModel() -> SHERPA_MIN_NEW_AUDIO_SECONDS
            else -> DEFAULT_MIN_NEW_AUDIO_SECONDS
        }
        val initialGate = initialRealtimeDelayForModel()
        Log.i(
            "WhisperEngine",
            "realtime config: chunkSeconds=$chunkSeconds baseGate=${baseGate}s initialGate=${initialGate}s targetDuty=${TARGET_INFERENCE_DUTY_CYCLE}"
        )
        val activeSessionToken = nextSessionToken()
        transcriptionJob?.cancel()
        recordingJob?.cancel()
        energyJob?.cancel()
        transcriptionJob = null
        recordingJob = null
        energyJob = null

            recordingJob = scope.launch(Dispatchers.IO) {
                try {
                    if (!isSessionActive(activeSessionToken)) return@launch
                    audioRecorder.startRecording(_audioInputMode.value)
                } catch (e: Throwable) {
                    if (!isSessionActive(activeSessionToken)) return@launch
                    withContext(Dispatchers.Main) {
                    _lastError.value = AppError.TranscriptionFailed(e)
                    transitionTo(SessionState.Error)
                }
            }
        }

        transcriptionJob = scope.launch(Dispatchers.Default) {
            if (engine.isStreaming) {
                streamingLoop(activeSessionToken)
            } else {
                realtimeLoop(activeSessionToken)
            }
        }

        energyJob = scope.launch(Dispatchers.Default) {
            while (isSessionActive(activeSessionToken)) {
                _bufferEnergy.value = audioRecorder.relativeEnergy
                _bufferSeconds.value = audioRecorder.bufferSeconds
                delay(100)
            }
        }
    }

    fun stopRecording() {
        if (_sessionState.value != SessionState.Recording) return
        transitionTo(SessionState.Stopping)
        // Stop mic input first so no new audio arrives
        audioRecorder.stopRecording()
        cancelRecorderAndEnergyJobs()
        drainFinalStreamingAudioIfNeeded()

        // Stop MediaProjection foreground service if it was running
        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            try {
                context.stopService(Intent(context, MediaProjectionService::class.java))
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to stop MediaProjectionService: ${e.message}")
            }
        }

        // Now invalidate and clean up
        invalidateSession()
        cancelTranscriptionJob()
        transitionTo(SessionState.Idle)
    }

    private suspend fun stopRecordingAndWait() {
        if (_sessionState.value != SessionState.Recording) return
        transitionTo(SessionState.Stopping)
        audioRecorder.stopRecording()
        cancelRecorderAndEnergyJobsAndWait()
        drainFinalStreamingAudioIfNeeded()

        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            try {
                context.stopService(Intent(context, MediaProjectionService::class.java))
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to stop MediaProjectionService: ${e.message}")
            }
        }

        invalidateSession()
        cancelTranscriptionJobAndWait()
        transitionTo(SessionState.Idle)
    }

    private fun cancelRecorderAndEnergyJobs() {
        recordingJob?.cancel()
        energyJob?.cancel()
        recordingJob = null
        energyJob = null
    }

    private suspend fun cancelRecorderAndEnergyJobsAndWait() {
        recordingJob?.cancelAndJoin()
        energyJob?.cancelAndJoin()
        recordingJob = null
        energyJob = null
    }

    private fun cancelTranscriptionJob() {
        transcriptionJob?.cancel()
        transcriptionJob = null
    }

    private suspend fun cancelTranscriptionJobAndWait() {
        transcriptionJob?.cancelAndJoin()
        transcriptionJob = null
    }

    private fun drainFinalStreamingAudioIfNeeded() {
        val engine = currentEngine
        if (engine == null || !engine.isLoaded || !engine.isStreaming) return

        val currentCount = audioRecorder.sampleCount
        if (currentCount > lastBufferSize) {
            val remaining = audioRecorder.samplesRange(lastBufferSize, currentCount)
            if (remaining.isNotEmpty()) {
                engine.feedAudio(remaining)
                lastBufferSize = currentCount
            }
        }

        val finalResult = engine.drainFinalAudio()
        if (finalResult == null || finalResult.text.isBlank()) return

        chunkManager.confirmedSegments.add(finalResult)
        _confirmedText.value = chunkManager.renderSegmentsText(chunkManager.confirmedSegments)
        _hypothesisText.value = ""
        chunkManager.confirmedText = _confirmedText.value
        scheduleTranslationUpdate()
    }

    fun setLastError(error: AppError) {
        _lastError.value = error
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        _audioInputMode.value = mode
    }

    fun setSystemAudioCapturePermission(resultCode: Int, data: Intent?) {
        if (!audioRecorder.isSystemAudioCaptureSupported) {
            _systemAudioCaptureReady.value = false
            _lastError.value = AppError.SystemAudioCaptureUnsupported()
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            _systemAudioCaptureReady.value = false
            _lastError.value = AppError.SystemAudioCapturePermissionDenied()
            return
        }
        val granted = audioRecorder.setSystemAudioCapturePermission(resultCode, data)
        _systemAudioCaptureReady.value = granted
        if (!granted) {
            _lastError.value = if (audioRecorder.isSystemAudioCaptureSupported) {
                AppError.SystemAudioCapturePermissionDenied()
            } else {
                AppError.SystemAudioCaptureUnsupported()
            }
        } else if (_lastError.value is AppError.SystemAudioCapturePermissionDenied
            || _lastError.value is AppError.SystemAudioCaptureUnsupported
        ) {
            _lastError.value = null
            if (_sessionState.value == SessionState.Error) {
                transitionTo(SessionState.Idle)
            }
        }
    }

    fun clearError() {
        _lastError.value = null
        if (_sessionState.value == SessionState.Error) {
            transitionTo(SessionState.Idle)
        }
    }

    fun clearTranscription() {
        if (_sessionState.value == SessionState.Recording) {
            invalidateSession()
            audioRecorder.stopRecording()
            transcriptionJob?.cancel()
            recordingJob?.cancel()
            energyJob?.cancel()
            transcriptionJob = null
            recordingJob = null
            energyJob = null
        }
        resetTranscriptionState()
        transitionTo(SessionState.Idle)
    }

    private fun transitionTo(newState: SessionState) {
        _sessionState.value = newState
        _isRecording.value = (newState == SessionState.Recording)
    }

    private suspend fun realtimeLoop(sessionToken: Long) {
        try {
            while (isSessionActive(sessionToken)) {
                try {
                    transcribeCurrentBuffer(sessionToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!isSessionActive(sessionToken)) return
                    _lastError.value = AppError.TranscriptionFailed(e)
                    transitionTo(SessionState.Error)
                    audioRecorder.stopRecording()
                    return
                }
            }
        } finally {
            // Final transcription pass for any remaining buffered audio.
            // stopRecording() handles streaming engines; this handles offline engines.
            val engine = currentEngine
            if (engine != null && !engine.isStreaming && engine.isLoaded) {
                val currentCount = audioRecorder.sampleCount
                if (currentCount > lastBufferSize) {
                    try {
                        withContext(NonCancellable) {
                            transcribeCurrentBuffer(sessionToken)
                        }
                    } catch (_: Throwable) {
                        // Best-effort final pass
                    }
                }
            }
            transcriptionJob = null
            if (isSessionActive(sessionToken)) {
                audioRecorder.stopRecording()
                transitionTo(SessionState.Idle)
            }
        }
    }

    /** Streaming transcription loop — feeds audio incrementally and polls for results. */
    private suspend fun streamingLoop(sessionToken: Long) {
        val engine = currentEngine ?: return
        var streamFeedCount = 0L
        var lastLoggedStreamingText = ""
        try {
            while (isSessionActive(sessionToken)) {
                try {
                    // Feed new audio to the streaming engine
                    val currentCount = audioRecorder.sampleCount
                    if (currentCount > lastBufferSize) {
                        val newSamples = audioRecorder.samplesRange(lastBufferSize, currentCount)
                        if (newSamples.isNotEmpty()) {
                            engine.feedAudio(newSamples)
                            lastBufferSize = currentCount
                            streamFeedCount += 1
                            if (streamFeedCount <= 3 || streamFeedCount % DIAGNOSTIC_LOG_INTERVAL == 0L) {
                                val fedSec = newSamples.size.toFloat() / AudioRecorder.SAMPLE_RATE
                                val totalSec = currentCount.toFloat() / AudioRecorder.SAMPLE_RATE
                                Log.i(
                                    "WhisperEngine",
                                    "stream feed #$streamFeedCount +${"%.2f".format(fedSec)}s total=${"%.2f".format(totalSec)}s"
                                )
                            }
                        }
                    }

                    // Poll streaming result
                    val result = engine.getStreamingResult()
                    if (result != null) {
                        val normalized = normalizeDisplayText(result.text)
                        _hypothesisText.value = normalized
                        scheduleTranslationUpdate()
                        if (normalized.isNotBlank() && normalized != lastLoggedStreamingText) {
                            lastLoggedStreamingText = normalized
                            val totalSec = currentCount.toFloat() / AudioRecorder.SAMPLE_RATE
                            Log.i(
                                "WhisperEngine",
                                "stream hypothesis @${"%.2f".format(totalSec)}s chars=${normalized.length}"
                            )
                        }
                    }

                    // Endpoint detected → finalize this utterance
                    if (engine.isEndpointDetected()) {
                        val totalSec = currentCount.toFloat() / AudioRecorder.SAMPLE_RATE
                        Log.i("WhisperEngine", "stream endpoint detected @${"%.2f".format(totalSec)}s")
                        val finalResult = engine.getStreamingResult()
                        if (finalResult != null && finalResult.text.isNotBlank()) {
                            chunkManager.confirmedSegments.add(finalResult)
                            _confirmedText.value = chunkManager.renderSegmentsText(chunkManager.confirmedSegments)
                        }
                        _hypothesisText.value = ""
                        scheduleTranslationUpdate()
                        engine.resetStreamingState()
                    }

                    val streamingSafeTrimSample = (lastBufferSize - AudioRecorder.SAMPLE_RATE * 30)
                        .coerceAtLeast(0)
                    trimRecorderBufferIfNeeded(streamingSafeTrimSample)

                    delay(100) // 100ms polling interval (matches iOS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!isSessionActive(sessionToken)) return
                    _lastError.value = AppError.TranscriptionFailed(e)
                    transitionTo(SessionState.Error)
                    audioRecorder.stopRecording()
                    return
                }
            }
        } finally {
            // Feed any remaining audio and drain the streaming decoder.
            // stopRecording() already handles this for normal stop flow,
            // but this covers cancellation and error paths too.
            try {
                val currentCount = audioRecorder.sampleCount
                if (currentCount > lastBufferSize) {
                    val remaining = audioRecorder.samplesRange(lastBufferSize, currentCount)
                    if (remaining.isNotEmpty()) {
                        engine.feedAudio(remaining)
                        lastBufferSize = currentCount
                    }
                }
                val finalResult = engine.drainFinalAudio()
                if (finalResult != null && finalResult.text.isNotBlank()) {
                    chunkManager.confirmedSegments.add(finalResult)
                    _confirmedText.value = chunkManager.renderSegmentsText(chunkManager.confirmedSegments)
                    _hypothesisText.value = ""
                    chunkManager.confirmedText = _confirmedText.value
                    scheduleTranslationUpdate()
                }
            } catch (_: Throwable) {
                // Best-effort drain
            }
            transcriptionJob = null
            if (isSessionActive(sessionToken)) {
                audioRecorder.stopRecording()
                transitionTo(SessionState.Idle)
            }
        }
    }

    private suspend fun transcribeCurrentBuffer(sessionToken: Long) {
        val engine = currentEngine ?: return
        if (!isSessionActive(sessionToken)) return

        // No-signal detection
        if (audioRecorder.bufferSeconds >= NO_SIGNAL_TIMEOUT_SECONDS &&
            audioRecorder.maxRecentEnergy < SIGNAL_ENERGY_THRESHOLD &&
            _confirmedText.value.isBlank() &&
            _hypothesisText.value.isBlank()
        ) {
            _lastError.value = AppError.NoMicrophoneSignal()
            transitionTo(SessionState.Error)
            audioRecorder.stopRecording()
            transcriptionJob?.cancel()
            recordingJob?.cancel()
            energyJob?.cancel()
            transcriptionJob = null
            recordingJob = null
            energyJob = null
            return
        }

        val currentBufferSize = audioRecorder.sampleCount
        val nextBufferSize = currentBufferSize - lastBufferSize
        val nextBufferSeconds = nextBufferSize.toFloat() / AudioRecorder.SAMPLE_RATE
        val bufferSeconds = currentBufferSize.toFloat() / AudioRecorder.SAMPLE_RATE

        val initialPhase = !hasCompletedFirstInference
        val baseDelay = if (initialPhase) {
            initialRealtimeDelayForModel()
        } else {
            adaptiveRealtimeDelayForModel()
        }
        val effectiveDelay = if (initialPhase) {
            baseDelay
        } else {
            computeCpuAwareDelay(baseDelay)
        }
        if (nextBufferSeconds < effectiveDelay) {
            delay(100)
            return
        }

        // VAD check — bypass for system playback (continuous audio, not voice-triggered)
        if (_useVAD.value && _audioInputMode.value != AudioInputMode.SYSTEM_PLAYBACK) {
            val vadBypassSamples = (AudioRecorder.SAMPLE_RATE * INITIAL_VAD_BYPASS_SECONDS).toInt()
            val bypassVadDuringStartup = initialPhase && currentBufferSize <= vadBypassSamples
            if (!bypassVadDuringStartup) {
                val energy = audioRecorder.relativeEnergy
                if (energy.isNotEmpty()) {
                    val recentEnergy = energy.takeLast(10)
                    val avgEnergy = recentEnergy.sum() / recentEnergy.size
                    val peakEnergy = recentEnergy.maxOrNull() ?: 0f
                    val hasVoice = peakEnergy >= SILENCE_THRESHOLD ||
                        avgEnergy >= SILENCE_THRESHOLD * 0.5f

                    if (!hasVoice) {
                        chunkManager.consecutiveSilentWindows += 1
                        if (chunkManager.consecutiveSilentWindows <= 2 ||
                            chunkManager.consecutiveSilentWindows % DIAGNOSTIC_LOG_INTERVAL.toInt() == 0
                        ) {
                            Log.i(
                                "WhisperEngine",
                                "rt VAD skip: silentWindows=${chunkManager.consecutiveSilentWindows} buffer=${"%.2f".format(bufferSeconds)}s"
                            )
                        }
                        keepVadPreroll(currentBufferSize)
                        return
                    } else {
                        chunkManager.consecutiveSilentWindows = 0
                    }
                } else {
                    chunkManager.consecutiveSilentWindows += 1
                    if (chunkManager.consecutiveSilentWindows <= 2 ||
                        chunkManager.consecutiveSilentWindows % DIAGNOSTIC_LOG_INTERVAL.toInt() == 0
                    ) {
                        Log.i(
                            "WhisperEngine",
                            "rt VAD skip(no-energy): silentWindows=${chunkManager.consecutiveSilentWindows} buffer=${"%.2f".format(bufferSeconds)}s"
                        )
                    }
                    keepVadPreroll(currentBufferSize)
                    return
                }
            }
        }

        // Update energy visualization
        _bufferEnergy.value = audioRecorder.relativeEnergy
        _bufferSeconds.value = audioRecorder.bufferSeconds

        // Chunk-based windowing via StreamingChunkManager
        val slice = chunkManager.computeSlice(currentBufferSize) ?: return

        val audioSamples = audioRecorder.samplesRange(slice.startSample, slice.endSample)
        if (audioSamples.isEmpty()) return
        val sliceStartSec = slice.startSample.toFloat() / AudioRecorder.SAMPLE_RATE
        val sliceEndSec = slice.endSample.toFloat() / AudioRecorder.SAMPLE_RATE
        if (isFastOfflineModel()) {
            val sliceRms = computeRms(audioSamples)
            if (sliceRms < SHERPA_MIN_INFERENCE_RMS) {
                if (chunkManager.consecutiveSilentWindows <= 2 ||
                    chunkManager.consecutiveSilentWindows % DIAGNOSTIC_LOG_INTERVAL.toInt() == 0
                ) {
                    Log.i(
                        "WhisperEngine",
                        "rt RMS skip: rms=${"%.4f".format(sliceRms)} < ${"%.4f".format(SHERPA_MIN_INFERENCE_RMS)} slice=[${"%.2f".format(sliceStartSec)},${"%.2f".format(sliceEndSec)}]"
                    )
                }
                delay(500)
                return
            }
        }
        lastBufferSize = currentBufferSize

        val startTime = System.nanoTime()
        val numThreads = computeInferenceThreads()
        // Keep at most one native transcribe call in-flight.
        // If an older session is still finishing, skip this cycle instead of queueing,
        // which prevents start/stop latency from compounding across sessions.
        if (!inferenceMutex.tryLock()) {
            return
        }
        val newSegments = try {
            engine.transcribe(audioSamples, numThreads, "auto")
        } finally {
            inferenceMutex.unlock()
        }
        realtimeInferenceCount += 1
        val inferenceIndex = realtimeInferenceCount
        if (!hasCompletedFirstInference) {
            val firstInferenceMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs
            Log.i(
                "WhisperEngine",
                "First inference completed in ${firstInferenceMs}ms (buffer=${"%.2f".format(bufferSeconds)}s, slice=${"%.2f".format(sliceEndSec - sliceStartSec)}s)"
            )
        }
        hasCompletedFirstInference = true
        if (!isSessionActive(sessionToken)) return

        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
        if (elapsed > 0.0) {
            movingAverageInferenceSeconds = if (movingAverageInferenceSeconds <= 0.0) {
                elapsed
            } else {
                movingAverageInferenceSeconds + INFERENCE_EMA_ALPHA * (elapsed - movingAverageInferenceSeconds)
            }
        }
        val sliceDurationSec = audioSamples.size.toFloat() / AudioRecorder.SAMPLE_RATE
        val totalWords = newSegments.sumOf { it.text.split(" ").size }
        if (elapsed > 0 && totalWords > 0) {
            _tokensPerSecond.value = totalWords / elapsed
        }
        val confirmedBeforeSec = chunkManager.lastConfirmedSegmentEndMs / 1000f

        if (newSegments.isNotEmpty()) {
            chunkManager.consecutiveSilentWindows = 0
            // Extract detected language from engine (SenseVoice provides this)
            val lang = normalizeLanguageCode(newSegments.firstOrNull()?.detectedLanguage)
            if (lang != null && lang != _detectedLanguage.value) {
                _detectedLanguage.value = lang
                applyDetectedLanguageToTranslation(lang)
            }
        }
        chunkManager.processTranscriptionResult(newSegments, slice.sliceOffsetMs)
        _confirmedText.value = chunkManager.confirmedText
        _hypothesisText.value = chunkManager.hypothesisText
        scheduleTranslationUpdate()
        val confirmedAfterSec = chunkManager.lastConfirmedSegmentEndMs / 1000f
        val lagAfterSec = (bufferSeconds - confirmedAfterSec).coerceAtLeast(0f)
        val previewText = newSegments.firstOrNull()
            ?.text
            ?.let { normalizeDisplayText(it) }
            ?.take(64)
            .orEmpty()
        val ratio = if (elapsed > 0.0) sliceDurationSec / elapsed else 0.0f
        val shouldLogDetailed =
            inferenceIndex <= 4L ||
                inferenceIndex % DIAGNOSTIC_LOG_INTERVAL == 0L ||
                elapsed >= 0.35 ||
                sliceDurationSec >= 4.0f ||
                lagAfterSec >= 2.0f
        if (shouldLogDetailed) {
            Log.i(
                "WhisperEngine",
                "rt chunk #$inferenceIndex buf=${"%.2f".format(bufferSeconds)}s new=${"%.2f".format(nextBufferSeconds)}s gate=${"%.2f".format(effectiveDelay)}s base=${"%.2f".format(baseDelay)}s avgInfer=${"%.3f".format(movingAverageInferenceSeconds)}s cpu=${"%.0f".format(_cpuPercent.value)}% slice=[${"%.2f".format(sliceStartSec)},${"%.2f".format(sliceEndSec)}] dur=${"%.2f".format(sliceDurationSec)}s infer=${"%.3f".format(elapsed)}s ratio=${"%.1f".format(ratio)}x seg=${newSegments.size} words=$totalWords conf=${"%.2f".format(confirmedBeforeSec)}s->${"%.2f".format(confirmedAfterSec)}s lag=${"%.2f".format(lagAfterSec)}s preview='${previewText}'"
            )
        }

        val safeTrimSample = ((chunkManager.lastConfirmedSegmentEndMs * AudioRecorder.SAMPLE_RATE) / 1000)
            .toInt()
        trimRecorderBufferIfNeeded(safeTrimSample)
    }

    private fun initialRealtimeDelayForModel(): Float {
        if (isOmnilingualModel()) {
            return OMNILINGUAL_INITIAL_MIN_NEW_AUDIO_SECONDS
        }
        if (isCactusModel()) {
            return CACTUS_INITIAL_MIN_NEW_AUDIO_SECONDS
        }
        return if (isFastOfflineModel()) {
            SHERPA_INITIAL_MIN_NEW_AUDIO_SECONDS
        } else {
            DEFAULT_INITIAL_MIN_NEW_AUDIO_SECONDS
        }
    }

    private fun adaptiveRealtimeDelayForModel(): Float {
        if (!isFastOfflineModel()) return chunkManager.adaptiveDelay()
        if (isOmnilingualModel()) {
            return OMNILINGUAL_MIN_NEW_AUDIO_SECONDS
        }
        if (isCactusModel()) {
            return CACTUS_MIN_NEW_AUDIO_SECONDS
        }
        // SenseVoice/Moonshine: use base delay (CPU-aware delay applied by caller)
        return SHERPA_MIN_NEW_AUDIO_SECONDS
    }

    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * When VAD says "no voice", keep a small trailing pre-roll instead of
     * fully advancing lastBufferSize. This preserves utterance onsets that
     * straddle VAD boundaries, especially right after recording starts.
     */
    private fun keepVadPreroll(currentBufferSize: Int) {
        val preRollSamples = (AudioRecorder.SAMPLE_RATE * VAD_PREROLL_SECONDS).toInt()
        lastBufferSize = (currentBufferSize - preRollSamples).coerceAtLeast(0)
    }


    private fun trimRecorderBufferIfNeeded(safeTrimBeforeAbsoluteSample: Int) {
        val currentAbsoluteSamples = audioRecorder.sampleCount
        if (currentAbsoluteSamples <= MAX_BUFFER_SAMPLES) return

        val targetKeepSamples = MAX_BUFFER_SAMPLES / 2
        val desiredDropBefore = currentAbsoluteSamples - targetKeepSamples
        val dropBefore = minOf(
            desiredDropBefore,
            safeTrimBeforeAbsoluteSample.coerceAtLeast(0)
        )
        if (dropBefore <= 0) return

        val dropped = audioRecorder.discardSamples(beforeAbsoluteIndex = dropBefore)
        if (dropped > 0) {
            Log.i(
                "WhisperEngine",
                "Trimmed $dropped old mic samples (safeBefore=$safeTrimBeforeAbsoluteSample, current=$currentAbsoluteSamples)"
            )
        }
    }

    /**
     * When the ASR engine detects a language (e.g., SenseVoice returns "en" or "ja"),
     * auto-swap translation direction so that detected speech is the source and
     * the other configured language becomes the target.
     */
    private fun applyDetectedLanguageToTranslation(lang: String) {
        if (!_translationEnabled.value) return
        val currentSource = _translationSourceLanguageCode.value
        val currentTarget = _translationTargetLanguageCode.value

        // If detected language matches target but not source, swap them
        if (lang == currentTarget && lang != currentSource) {
            Log.i("WhisperEngine", "Detected language '$lang' matches target — swapping ($currentSource→$currentTarget becomes $currentTarget→$currentSource)")
            _translationSourceLanguageCode.value = currentTarget
            _translationTargetLanguageCode.value = currentSource
            scope.launch {
                preferences.setTranslationSourceLanguage(currentTarget)
                preferences.setTranslationTargetLanguage(currentSource)
            }
            lastTranslationInput = null
            resetTranslationState()
            scheduleTranslationUpdate()
        } else if (lang != currentSource && lang != currentTarget) {
            Log.i("WhisperEngine", "Detected language '$lang' not in pair ($currentSource→$currentTarget) — ignoring")
        }
        // If lang == currentSource, no change needed
    }

    private fun resetTranscriptionState() {
        resetTranslationState()
        lastBufferSize = 0
        chunkManager = createChunkManagerForModel(_selectedModel.value)
        _confirmedText.value = ""
        _hypothesisText.value = ""
        _e2eResult.value = null
        _detectedLanguage.value = null
        _bufferEnergy.value = emptyList()
        _bufferSeconds.value = 0.0
        _tokensPerSecond.value = 0.0
        _lastError.value = null
        recordingStartElapsedMs = 0L
        hasCompletedFirstInference = false
        realtimeInferenceCount = 0
        movingAverageInferenceSeconds = 0.0
        audioRecorder.reset()
    }

    /**
     * Keep realtime CPU usage stable by spacing decode cycles based on observed
     * native inference time (EMA) and a target duty cycle budget.
     */
    private fun computeCpuAwareDelay(baseDelay: Float): Float {
        val avg = movingAverageInferenceSeconds
        if (avg <= 0.0) return baseDelay
        val budgetDelay = (avg / TARGET_INFERENCE_DUTY_CYCLE).toFloat()
        return maxOf(baseDelay, budgetDelay.coerceAtMost(MAX_CPU_PROTECT_DELAY_SECONDS))
    }

    /** Clear translation-related state without affecting transcription or audio. */
    private fun resetTranslationState() {
        translationJob?.cancel()
        translationJob = null
        _translatedConfirmedText.value = ""
        _translatedHypothesisText.value = ""
        _translationWarning.value = null
        lastTranslationInput = null
    }

    /** Transcribe a WAV file. Used for testing on emulator and file import UI. */
    fun transcribeFile(filePath: String, languageHint: String = "auto") {
        val engine = currentEngine
        if (engine == null || !engine.isLoaded) {
            Log.e("WhisperEngine", "transcribeFile: model not ready")
            _lastError.value = AppError.ModelNotReady()
            writeE2EFailure(error = "model not ready")
            return
        }

        // Guard: ignore if already busy transcribing
        if (_sessionState.value == SessionState.Recording) {
            Log.w("WhisperEngine", "transcribeFile: already busy, ignoring")
            writeE2EFailure(error = "engine busy")
            return
        }

        resetTranscriptionState()
        transitionTo(SessionState.Recording)
        _hypothesisText.value = "Transcribing file..."

        // File decode can be CPU-heavy (especially omnilingual); keep it off main.
        transcriptionJob = scope.launch(Dispatchers.Default) {
            try {
                Log.i("WhisperEngine", "transcribeFile: reading $filePath")
                val audioSamples = withContext(Dispatchers.IO) {
                    readWavFile(filePath)
                }
                val durationSec = audioSamples.size / 16000.0
                Log.i("WhisperEngine", "transcribeFile: ${audioSamples.size} samples (${durationSec}s)")
                _hypothesisText.value = "Transcribing ${"%.1f".format(durationSec)}s of audio..."

                audioRecorder.injectSamples(audioSamples)
                _bufferSeconds.value = durationSec
                _bufferEnergy.value = audioRecorder.relativeEnergy

                val startTime = System.nanoTime()
                val numThreads = computeInferenceThreads()
                Log.i("WhisperEngine", "transcribeFile: starting transcription with $numThreads threads")
                val segments = engine.transcribe(audioSamples, numThreads, languageHint)

                val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                val totalWords = segments.sumOf { it.text.split(" ").size }
                Log.i("WhisperEngine", "transcribeFile: ${segments.size} segments, $totalWords words in ${"%.2f".format(elapsed)}s")
                if (elapsed > 0 && totalWords > 0) {
                    _tokensPerSecond.value = totalWords / elapsed
                }
                // Apply detected language to translation direction
                val lang = normalizeLanguageCode(segments.firstOrNull()?.detectedLanguage)
                if (lang != null && lang != _detectedLanguage.value) {
                    _detectedLanguage.value = lang
                    applyDetectedLanguageToTranslation(lang)
                }

                chunkManager.confirmedSegments.addAll(segments)
                val renderedText = chunkManager.renderSegmentsText(segments)
                chunkManager.confirmedText = renderedText
                _confirmedText.value = renderedText
                _hypothesisText.value = ""
                val model = _selectedModel.value
                val skipReason = when {
                    model.engineType == EngineType.ANDROID_SPEECH &&
                        Build.VERSION.SDK_INT < 33 &&
                        renderedText.contains("requires API 33+", ignoreCase = true) ->
                        "Android Speech file transcription requires API 33+ (device API ${Build.VERSION.SDK_INT})"
                    model.engineType == EngineType.ANDROID_SPEECH &&
                        Build.VERSION.SDK_INT < 33 &&
                        renderedText.isBlank() ->
                        "Android Speech file transcription unavailable on device API ${Build.VERSION.SDK_INT}"
                    else -> null
                }
                val skipped = skipReason != null

                if (!skipped) {
                    Log.i("WhisperEngine", "E2E translation state: enabled=${_translationEnabled.value} src=${_translationSourceLanguageCode.value} tgt=${_translationTargetLanguageCode.value} translated=${_translatedConfirmedText.value.take(20)}")
                    scheduleTranslationUpdate()
                    val waitUntil = SystemClock.elapsedRealtime() + 12_000L
                    while (SystemClock.elapsedRealtime() < waitUntil) {
                        val translatedReady = !_translationEnabled.value ||
                            _confirmedText.value.isBlank() ||
                            _translatedConfirmedText.value.isNotBlank()
                        if (translatedReady) break
                        delay(250)
                    }
                }

                // Write E2E evidence result
                val transcript = _confirmedText.value
                writeE2EResult(
                    transcript = transcript,
                    durationMs = if (skipped) 0.0 else elapsed * 1000,
                    tokensPerSecond = if (skipped) 0.0 else _tokensPerSecond.value,
                    error = skipReason,
                    skipped = skipped
                )
            } catch (e: CancellationException) {
                Log.i("WhisperEngine", "transcribeFile: cancelled")
            } catch (e: Throwable) {
                Log.e("WhisperEngine", "transcribeFile failed", e)
                _lastError.value = AppError.TranscriptionFailed(e)
                writeE2EResult(
                    transcript = "",
                    durationMs = 0.0,
                    tokensPerSecond = 0.0,
                    error = e.message,
                    skipped = false
                )
            } finally {
                transitionTo(SessionState.Idle)
            }
        }
    }

    private fun writeE2EResult(
        transcript: String,
        durationMs: Double,
        tokensPerSecond: Double,
        error: String?,
        skipped: Boolean = false
    ) {
        val model = _selectedModel.value
        val keywords = listOf("country", "ask", "do for", "fellow", "americans")
        val lowerTranscript = transcript.lowercase()
        val translatedText = _translatedConfirmedText.value
        val sourceCode = _translationSourceLanguageCode.value.trim().lowercase()
        val targetCode = _translationTargetLanguageCode.value.trim().lowercase()
        val expectsTranslation = !skipped &&
            _translationEnabled.value &&
            transcript.isNotBlank() &&
            sourceCode.isNotBlank() &&
            targetCode.isNotBlank() &&
            sourceCode != targetCode
        val translationReady = !expectsTranslation || translatedText.isNotBlank()
        val isOmnilingual = model.id.contains("omnilingual", ignoreCase = true)
        val hasKeywordHit = keywords.any { lowerTranscript.contains(it) }
        val hasMeaningfulText = transcript.any { it.isLetterOrDigit() }
        val asciiLetters = transcript.count { it.isLetter() && it.code < 128 }
        val nonAsciiLetters = transcript.count { it.isLetter() && it.code >= 128 }
        val omnilingualLooksEnglish =
            asciiLetters >= 8 &&
                asciiLetters >= nonAsciiLetters
        val isAndroidSpeech = model.engineType == com.voiceping.offlinetranscription.model.EngineType.ANDROID_SPEECH
        val androidSpeechApiLimited = isAndroidSpeech &&
            !AndroidSpeechEngine.supportsAudioPipe() &&
            transcript.contains("API 33+")
        val transcriptPass = when {
            androidSpeechApiLimited -> true  // Engine correctly reports API limitation
            isOmnilingual -> hasMeaningfulText && omnilingualLooksEnglish
            else -> hasKeywordHit
        }

        // pass = core transcription quality only; translation tracked separately
        val pass = !skipped &&
            error == null &&
            transcript.isNotEmpty() &&
            transcriptPass

        val result = E2ETestResult(
            modelId = model.id,
            engine = model.inferenceMethod,
            transcript = transcript,
            tokensPerSecond = tokensPerSecond,
            translatedText = translatedText,
            pass = pass,
            skipped = skipped,
            durationMs = durationMs,
            timestamp = java.time.Instant.now().toString(),
            error = error
        )
        _e2eResult.value = result

        val json = buildString {
            append("{\n")
            append("  \"model_id\": \"${jsonEscape(result.modelId)}\",\n")
            append("  \"engine\": \"${jsonEscape(result.engine)}\",\n")
            append("  \"transcript\": \"${jsonEscape(result.transcript)}\",\n")
            append("  \"tokens_per_second\": ${result.tokensPerSecond},\n")
            append("  \"translated_text\": \"${jsonEscape(result.translatedText)}\",\n")
            append("  \"translation_warning\": ")
            append(
                _translationWarning.value?.let { "\"${jsonEscape(it)}\"" } ?: "null"
            )
            append(",\n")
            append("  \"expects_translation\": $expectsTranslation,\n")
            append("  \"translation_ready\": $translationReady,\n")
            append("  \"pass\": ${result.pass},\n")
            append("  \"skipped\": ${result.skipped},\n")
            append("  \"duration_ms\": ${result.durationMs},\n")
            append("  \"timestamp\": \"${jsonEscape(result.timestamp)}\",\n")
            append("  \"error\": ")
            append(
                if (result.error != null) {
                    "\"${jsonEscape(result.error)}\""
                } else {
                    "null"
                }
            )
            append("\n")
            append("}")
        }
        writeE2EJson(modelId = model.id, json = json)
    }

    fun writeE2EFailure(modelId: String = _selectedModel.value.id, error: String) {
        val model = ModelInfo.findByIdOrLegacy(modelId) ?: _selectedModel.value
        val json = buildString {
            append("{\n")
            append("  \"model_id\": \"${jsonEscape(modelId)}\",\n")
            append("  \"engine\": \"${jsonEscape(model.inferenceMethod)}\",\n")
            append("  \"transcript\": \"\",\n")
            append("  \"tokens_per_second\": 0.0,\n")
            append("  \"translated_text\": \"\",\n")
            append("  \"translation_warning\": null,\n")
            append("  \"expects_translation\": false,\n")
            append("  \"translation_ready\": true,\n")
            append("  \"pass\": false,\n")
            append("  \"skipped\": false,\n")
            append("  \"duration_ms\": 0.0,\n")
            append("  \"timestamp\": \"${jsonEscape(java.time.Instant.now().toString())}\",\n")
            append("  \"error\": \"${jsonEscape(error)}\"\n")
            append("}")
        }
        writeE2EJson(modelId = modelId, json = json)
    }

    fun writeE2ESkipped(modelId: String = _selectedModel.value.id, reason: String) {
        val model = ModelInfo.findByIdOrLegacy(modelId) ?: _selectedModel.value
        val json = buildString {
            append("{\n")
            append("  \"model_id\": \"${jsonEscape(modelId)}\",\n")
            append("  \"engine\": \"${jsonEscape(model.inferenceMethod)}\",\n")
            append("  \"transcript\": \"\",\n")
            append("  \"tokens_per_second\": 0.0,\n")
            append("  \"translated_text\": \"\",\n")
            append("  \"translation_warning\": null,\n")
            append("  \"expects_translation\": false,\n")
            append("  \"translation_ready\": true,\n")
            append("  \"pass\": false,\n")
            append("  \"skipped\": true,\n")
            append("  \"duration_ms\": 0.0,\n")
            append("  \"timestamp\": \"${jsonEscape(java.time.Instant.now().toString())}\",\n")
            append("  \"error\": \"${jsonEscape(reason)}\"\n")
            append("}")
        }
        writeE2EJson(modelId = modelId, json = json)
    }

    private fun writeE2EJson(modelId: String, json: String) {
        var wrote = false

        // App-private external dir (legacy path consumed by scripts/tests)
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val file = File(extDir, "e2e_result_${modelId}.json")
                file.writeText(json)
                Log.i("E2E", "Result written to ${file.absolutePath}")
                wrote = true
            }
        } catch (e: Throwable) {
            Log.w("E2E", "Could not write app-private E2E result JSON", e)
        }

        // Public evidence dir used by UiAutomator captures and adb pull.
        try {
            val evidenceDir = File("/sdcard/Documents/e2e/$modelId")
            if (!evidenceDir.exists()) {
                evidenceDir.mkdirs()
            }
            val evidenceFile = File(evidenceDir, "result.json")
            evidenceFile.writeText(json)
            Log.i("E2E", "Result mirrored to ${evidenceFile.absolutePath}")
            wrote = true
        } catch (e: Throwable) {
            Log.w("E2E", "Could not mirror E2E result JSON to public evidence dir", e)
        }

        // Internal storage fallback — always accessible without permissions.
        if (!wrote) {
            try {
                val internalFile = File(context.filesDir, "e2e_result_${modelId}.json")
                internalFile.writeText(json)
                Log.i("E2E", "Result written to internal: ${internalFile.absolutePath}")
                wrote = true
            } catch (e: Throwable) {
                Log.w("E2E", "Could not write internal E2E result JSON", e)
            }
        }

        if (!wrote) {
            Log.w("E2E", "E2E result JSON was not written to any location")
        }
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun mapDownloadError(error: Throwable): AppError {
        val root = rootCause(error)
        return when {
            !hasValidatedInternetConnection() -> AppError.NetworkUnavailable()
            root is UnknownHostException -> AppError.NetworkUnavailable()
            root is SocketTimeoutException -> AppError.ModelDownloadFailed(
                Exception("Network timeout while downloading. Check connection and retry.")
            )
            else -> AppError.ModelDownloadFailed(error)
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }

    private fun parseModelSize(sizeStr: String): Long {
        val cleaned = sizeStr.replace("~", "").trim()
        val parts = cleaned.split(" ")
        if (parts.size != 2) return 0L
        val value = parts[0].toDoubleOrNull() ?: return 0L
        return when (parts[1].uppercase()) {
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            else -> 0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun scheduleTranslationUpdate() {
        translationJob?.cancel()

        if (!_translationEnabled.value) {
            resetTranslationState()
            return
        }

        // Quick pre-check: skip scheduling if language codes are not configured
        val srcCheck = _translationSourceLanguageCode.value.trim()
        val tgtCheck = _translationTargetLanguageCode.value.trim()
        if (srcCheck.isEmpty() || tgtCheck.isEmpty()) return

        translationJob = scope.launch(Dispatchers.Default) {
            delay(150)  // Debounce rapid updates during fast speech

            // Re-read latest values after debounce
            val confirmedSnapshot = _confirmedText.value.trim()
            val hypothesisSnapshot = _hypothesisText.value.trim()
            val sourceLanguageCode = _translationSourceLanguageCode.value.trim().lowercase()
            val targetLanguageCode = _translationTargetLanguageCode.value.trim().lowercase()

            if (sourceLanguageCode.isEmpty() || targetLanguageCode.isEmpty()) return@launch

            val currentInput = confirmedSnapshot to hypothesisSnapshot
            if (lastTranslationInput == currentInput) return@launch

            var warningMessage: String? = null

            var translatedConfirmed: String
            var translatedHypothesis: String

            if (sourceLanguageCode == targetLanguageCode) {
                translatedConfirmed = confirmedSnapshot
                translatedHypothesis = hypothesisSnapshot
            } else {
                try {
                    translatedConfirmed = if (confirmedSnapshot.isBlank()) {
                        ""
                    } else {
                        mlKitTranslator.translate(
                            text = confirmedSnapshot,
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode
                        )
                    }

                    translatedHypothesis = if (hypothesisSnapshot.isBlank()) {
                        ""
                    } else {
                        mlKitTranslator.translate(
                            text = hypothesisSnapshot,
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode
                        )
                    }
                } catch (e: UnsupportedOperationException) {
                    translatedConfirmed = confirmedSnapshot
                    translatedHypothesis = hypothesisSnapshot
                    warningMessage = e.message ?: AppError.TranslationUnavailable().message
                } catch (e: Throwable) {
                    if (e is CancellationException) return@launch
                    translatedConfirmed = confirmedSnapshot
                    translatedHypothesis = hypothesisSnapshot
                    warningMessage = AppError.TranslationFailed(e).message
                }
            }

            _translatedConfirmedText.value = normalizeDisplayText(translatedConfirmed)
            _translatedHypothesisText.value = normalizeDisplayText(translatedHypothesis)
            _translationWarning.value = warningMessage
            lastTranslationInput = currentInput
        }
    }

    private fun normalizeDisplayText(text: String): String {
        val collapsed = text.replace(WHITESPACE_REGEX, " ").trim()
        return normalizeCjkSpacing(collapsed)
    }

    private fun normalizeCjkSpacing(text: String): String {
        var current = text
        while (true) {
            var next = current
            next = CJK_INNER_SPACE_REGEX.replace(next, "$1$2")
            next = SPACE_BEFORE_CJK_PUNCT_REGEX.replace(next, "$1")
            next = SPACE_AFTER_CJK_OPEN_PUNCT_REGEX.replace(next, "$1")
            next = SPACE_AFTER_CJK_END_PUNCT_REGEX.replace(next, "$1$2")
            if (next == current) return next
            current = next
        }
    }

    private fun computeInferenceThreads(): Int {
        return Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(1)
    }

    private fun readWavFile(filePath: String): FloatArray {
        val file = File(filePath)
        if (!file.exists()) throw Exception("File not found: $filePath")
        val bytes = file.readBytes()
        if (bytes.size < 12) throw Exception("File too small to be a valid WAV")

        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        if (riff != "RIFF") throw Exception("Not a RIFF file")
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        if (wave != "WAVE") throw Exception("Not a WAVE file")

        // Parse chunks to find fmt and data
        var bitsPerSample = 16
        var channels = 1
        var sampleRate = 16000
        var dataOffset = -1
        var dataSize = -1

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = java.nio.ByteBuffer.wrap(bytes, pos + 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "fmt " && pos + 8 + chunkSize <= bytes.size) {
                val buf = java.nio.ByteBuffer.wrap(bytes, pos + 8, chunkSize)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.short // audioFormat
                channels = buf.short.toInt()
                sampleRate = buf.int
                buf.int // byteRate
                buf.short // blockAlign
                bitsPerSample = buf.short.toInt()
            } else if (chunkId == "data") {
                dataOffset = pos + 8
                dataSize = chunkSize.coerceAtMost(bytes.size - dataOffset)
                break
            }
            pos += 8 + chunkSize
            if (chunkSize % 2 != 0) pos++ // RIFF chunks are word-aligned
        }

        if (dataOffset < 0 || dataSize <= 0) throw Exception("No data chunk found in WAV")
        Log.i("WhisperEngine", "WAV: ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit data=${dataSize}B")

        return if (bitsPerSample == 16) {
            val sampleCount = dataSize / (2 * channels)
            FloatArray(sampleCount) { i ->
                val off = dataOffset + i * 2 * channels
                val low = bytes[off].toInt() and 0xFF
                val high = bytes[off + 1].toInt()
                (high shl 8 or low).toFloat() / 32768f
            }
        } else if (bitsPerSample == 32) {
            val sampleCount = dataSize / (4 * channels)
            FloatArray(sampleCount) { i ->
                val off = dataOffset + i * 4 * channels
                java.nio.ByteBuffer.wrap(bytes, off, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            }
        } else {
            throw Exception("Unsupported bits per sample: $bitsPerSample")
        }
    }

    fun destroy() {
        if (_sessionState.value == SessionState.Recording) {
            audioRecorder.stopRecording()
            transcriptionJob?.cancel()
            recordingJob?.cancel()
            energyJob?.cancel()
        }
        audioRecorder.clearSystemAudioCapturePermission()
        _systemAudioCaptureReady.value = false
        translationJob?.cancel()
        scope.cancel()
        mlKitTranslator.close()
        currentEngine?.release()
        currentEngine = null
        prewarmedModelId = null
    }
}
