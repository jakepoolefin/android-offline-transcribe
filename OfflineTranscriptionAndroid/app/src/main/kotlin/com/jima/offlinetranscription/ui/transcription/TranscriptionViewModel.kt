package com.voiceping.offlinetranscription.ui.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.service.WhisperEngine
import kotlinx.coroutines.launch

class TranscriptionViewModel(
    val engine: WhisperEngine
) : ViewModel() {

    init {
        viewModelScope.launch {
            engine.setTranslationEnabled(false)
            engine.setSpeakTranslatedAudio(false)
        }
    }

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
}
