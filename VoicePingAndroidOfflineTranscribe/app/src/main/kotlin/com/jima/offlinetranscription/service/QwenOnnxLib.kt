package com.voiceping.offlinetranscription.service

object QwenOnnxLib {
    init {
        System.loadLibrary("qwen_onnx_jni")
    }

    external fun initContext(modelDir: String): Long
    external fun transcribe(contextPtr: Long, audioData: FloatArray): String
    external fun freeContext(contextPtr: Long)
}
