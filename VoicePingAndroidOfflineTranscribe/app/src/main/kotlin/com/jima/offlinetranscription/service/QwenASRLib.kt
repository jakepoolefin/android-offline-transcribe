package com.voiceping.offlinetranscription.service

object QwenASRLib {
    init {
        System.loadLibrary("qwen_asr_jni")
    }

    external fun initContext(modelDir: String, numThreads: Int): Long
    external fun transcribe(contextPtr: Long, audioData: FloatArray): String
    external fun setLanguage(contextPtr: Long, language: String?): Int
    external fun getLastInferenceMs(contextPtr: Long): Double
    external fun freeContext(contextPtr: Long)
}
