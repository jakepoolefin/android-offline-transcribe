package com.voiceping.offlinetranscription.ui.transcription

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.service.WhisperEngine
import kotlinx.coroutines.launch
import java.io.File

class TranscriptionViewModel(
    val engine: WhisperEngine
) : ViewModel() {

    val isRecording = engine.isRecording
    val confirmedText = engine.confirmedText
    val hypothesisText = engine.hypothesisText
    val bufferEnergy = engine.bufferEnergy
    val bufferSeconds = engine.bufferSeconds
    val tokensPerSecond = engine.tokensPerSecond
    val lastError = engine.lastError
    val selectedModel = engine.selectedModel
    val modelState = engine.modelState
    val useVAD = engine.useVAD
    val enableTimestamps = engine.enableTimestamps
    val cpuPercent = engine.cpuPercent
    val memoryMB = engine.memoryMB
    val e2eResult = engine.e2eResult

    // Translation state
    val translationEnabled = engine.translationEnabled
    val translationSourceLanguage = engine.translationSourceLanguageCode
    val translationTargetLanguage = engine.translationTargetLanguageCode
    val translatedConfirmedText = engine.translatedConfirmedText
    val translatedHypothesisText = engine.translatedHypothesisText
    val translationWarning = engine.translationWarning
    val translationModelReady = engine.translationModelReady
    val translationDownloadStatus = engine.translationDownloadStatus

    val fullText: String
        get() = engine.fullTranscriptionText

    fun toggleRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        } else {
            engine.startRecording()
        }
    }

    fun clearTranscription() {
        engine.clearTranscription()
    }

    /** Dismiss error without clearing transcription text. */
    fun dismissError() {
        engine.clearError()
    }

    fun transcribeTestFile(filePath: String) {
        engine.transcribeFile(filePath)
    }

    fun transcribeTestAsset(context: Context) {
        val cached = File(context.cacheDir, "test_speech.wav")
        if (!cached.exists()) {
            context.assets.open("test_speech.wav").use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        }
        engine.transcribeFile(cached.absolutePath)
    }

    fun stopIfRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        }
    }

    fun switchModel(model: ModelInfo) {
        viewModelScope.launch {
            engine.switchModel(model)
        }
    }

    fun setUseVAD(enabled: Boolean) {
        viewModelScope.launch {
            engine.setUseVAD(enabled)
        }
    }

    fun setEnableTimestamps(enabled: Boolean) {
        viewModelScope.launch {
            engine.setEnableTimestamps(enabled)
        }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            engine.setTranslationEnabled(enabled)
        }
    }

    fun setTranslationSourceLanguageCode(languageCode: String) {
        viewModelScope.launch {
            engine.setTranslationSourceLanguageCode(languageCode)
        }
    }

    fun setTranslationTargetLanguageCode(languageCode: String) {
        viewModelScope.launch {
            engine.setTranslationTargetLanguageCode(languageCode)
        }
    }
}
