#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <string>

extern "C" {
#include "qwen_asr_onnx.h"
}

#define TAG "QwenOnnxJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voiceping_offlinetranscription_service_QwenOnnxLib_initContext(
        JNIEnv *env, jobject /* this */, jstring model_dir) {
    if (model_dir == nullptr) {
        LOGE("model_dir is null");
        return 0;
    }
    const char *dir = env->GetStringUTFChars(model_dir, nullptr);
    if (dir == nullptr) {
        LOGE("GetStringUTFChars(model_dir) returned null");
        return 0;
    }
    LOGI("Loading Qwen ONNX model from: %s", dir);

    qwen_onnx_verbose = 1;
    qwen_onnx_ctx_t *ctx = qwen_onnx_load(dir);
    env->ReleaseStringUTFChars(model_dir, dir);

    if (!ctx) {
        LOGE("Failed to load Qwen ONNX model");
        return 0;
    }

    LOGI("Qwen ONNX model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_voiceping_offlinetranscription_service_QwenOnnxLib_transcribe(
        JNIEnv *env, jobject /* this */, jlong context_ptr, jfloatArray audio_data) {
    auto *ctx = reinterpret_cast<qwen_onnx_ctx_t *>(context_ptr);
    if (!ctx) {
        LOGE("Context is null");
        return env->NewStringUTF("");
    }
    if (audio_data == nullptr) {
        LOGE("audio_data is null");
        return env->NewStringUTF("");
    }

    jfloat *audio = env->GetFloatArrayElements(audio_data, nullptr);
    if (audio == nullptr) {
        LOGE("GetFloatArrayElements(audio_data) returned null");
        return env->NewStringUTF("");
    }
    jsize audio_len = env->GetArrayLength(audio_data);

    LOGI("Transcribing %d samples (%.2fs)", audio_len, (float)audio_len / 16000.0f);
    char *result = qwen_onnx_transcribe(ctx, audio, audio_len);
    env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);

    if (!result) {
        LOGE("qwen_onnx_transcribe returned null");
        return env->NewStringUTF("");
    }

    LOGI("Transcription result: %.100s%s", result, strlen(result) > 100 ? "..." : "");
    jstring jresult = env->NewStringUTF(result);
    free(result);
    return jresult;
}

JNIEXPORT void JNICALL
Java_com_voiceping_offlinetranscription_service_QwenOnnxLib_freeContext(
        JNIEnv * /* env */, jobject /* this */, jlong context_ptr) {
    auto *ctx = reinterpret_cast<qwen_onnx_ctx_t *>(context_ptr);
    if (ctx) {
        qwen_onnx_free(ctx);
        LOGI("Qwen ONNX context freed");
    }
}

} // extern "C"
