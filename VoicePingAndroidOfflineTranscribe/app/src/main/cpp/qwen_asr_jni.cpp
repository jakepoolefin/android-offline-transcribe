#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <string>

extern "C" {
#include "qwen_asr.h"
#include "qwen_asr_kernels.h"
}

#define TAG "QwenASRJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voiceping_offlinetranscription_service_QwenASRLib_initContext(
        JNIEnv *env, jobject /* this */, jstring model_dir, jint num_threads) {
    if (model_dir == nullptr) {
        LOGE("model_dir is null");
        return 0;
    }
    const char *dir = env->GetStringUTFChars(model_dir, nullptr);
    if (dir == nullptr) {
        LOGE("GetStringUTFChars(model_dir) returned null");
        return 0;
    }
    LOGI("Loading Qwen ASR model from: %s with %d threads", dir, num_threads);

    qwen_verbose = 0; // Suppress stderr logging on mobile
    qwen_set_threads(num_threads);
    qwen_ctx_t *ctx = qwen_load(dir);
    env->ReleaseStringUTFChars(model_dir, dir);

    if (!ctx) {
        LOGE("Failed to load Qwen ASR model");
        return 0;
    }

    // Configure for segmented offline mode (bounded memory)
    ctx->segment_sec = 20.0f;
    ctx->search_sec = 3.0f;

    LOGI("Qwen ASR model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_voiceping_offlinetranscription_service_QwenASRLib_transcribe(
        JNIEnv *env, jobject /* this */, jlong context_ptr, jfloatArray audio_data) {
    auto *ctx = reinterpret_cast<qwen_ctx_t *>(context_ptr);
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
    char *result = qwen_transcribe_audio(ctx, audio, audio_len);
    env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);

    if (!result) {
        LOGE("qwen_transcribe_audio returned null");
        return env->NewStringUTF("");
    }

    LOGI("Transcription: inference=%.0fms tokens=%d", ctx->perf_total_ms, ctx->perf_text_tokens);
    jstring jresult = env->NewStringUTF(result);
    free(result);
    return jresult;
}

JNIEXPORT jint JNICALL
Java_com_voiceping_offlinetranscription_service_QwenASRLib_setLanguage(
        JNIEnv *env, jobject /* this */, jlong context_ptr, jstring language) {
    auto *ctx = reinterpret_cast<qwen_ctx_t *>(context_ptr);
    if (!ctx) return -1;

    if (!language) {
        return qwen_set_force_language(ctx, nullptr);
    }
    const char *lang = env->GetStringUTFChars(language, nullptr);
    if (lang == nullptr) {
        LOGE("GetStringUTFChars(language) returned null");
        return -1;
    }
    int result = qwen_set_force_language(ctx, lang);
    env->ReleaseStringUTFChars(language, lang);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_com_voiceping_offlinetranscription_service_QwenASRLib_getLastInferenceMs(
        JNIEnv * /* env */, jobject /* this */, jlong context_ptr) {
    auto *ctx = reinterpret_cast<qwen_ctx_t *>(context_ptr);
    return ctx ? ctx->perf_total_ms : 0.0;
}

JNIEXPORT void JNICALL
Java_com_voiceping_offlinetranscription_service_QwenASRLib_freeContext(
        JNIEnv * /* env */, jobject /* this */, jlong context_ptr) {
    auto *ctx = reinterpret_cast<qwen_ctx_t *>(context_ptr);
    if (ctx) {
        qwen_free(ctx);
        LOGI("Qwen ASR context freed");
    }
}

} // extern "C"
