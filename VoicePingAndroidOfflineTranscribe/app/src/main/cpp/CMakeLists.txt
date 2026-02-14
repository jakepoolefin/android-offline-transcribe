cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp)

# Build whisper.cpp as a library
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

# Build JNI bridge
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    log
)

# ---- Qwen ASR library ----
set(QWEN_ASR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../qwen-asr)

add_library(qwen_asr STATIC
    ${QWEN_ASR_DIR}/qwen_asr.c
    ${QWEN_ASR_DIR}/qwen_asr_kernels.c
    ${QWEN_ASR_DIR}/qwen_asr_kernels_generic.c
    ${QWEN_ASR_DIR}/qwen_asr_kernels_neon.c
    ${QWEN_ASR_DIR}/qwen_asr_kernels_avx.c
    ${QWEN_ASR_DIR}/qwen_asr_audio.c
    ${QWEN_ASR_DIR}/qwen_asr_encoder.c
    ${QWEN_ASR_DIR}/qwen_asr_decoder.c
    ${QWEN_ASR_DIR}/qwen_asr_tokenizer.c
    ${QWEN_ASR_DIR}/qwen_asr_safetensors.c
)

target_include_directories(qwen_asr PUBLIC ${QWEN_ASR_DIR})

target_compile_options(qwen_asr PRIVATE
    -O3 -ffast-math
)

# Build Qwen ASR JNI bridge
add_library(qwen_asr_jni SHARED qwen_asr_jni.cpp)

target_include_directories(qwen_asr_jni PRIVATE ${QWEN_ASR_DIR})

target_link_libraries(qwen_asr_jni
    qwen_asr
    log
)

# ---- Qwen ONNX library (ONNX Runtime inference) ----
add_library(qwen_asr_onnx STATIC
    ${QWEN_ASR_DIR}/qwen_asr_onnx.c
)

target_include_directories(qwen_asr_onnx PRIVATE
    ${QWEN_ASR_DIR}
    ${QWEN_ASR_DIR}/ort
)

target_compile_options(qwen_asr_onnx PRIVATE
    -O3 -ffast-math
)

# Build Qwen ONNX JNI bridge
# Uses dlopen to load libonnxruntime.so at runtime (from sherpa-onnx AAR)
add_library(qwen_onnx_jni SHARED qwen_onnx_jni.cpp)

target_include_directories(qwen_onnx_jni PRIVATE ${QWEN_ASR_DIR})

target_link_libraries(qwen_onnx_jni
    qwen_asr_onnx
    qwen_asr          # for mel spectrogram + tokenizer
    dl                 # for dlopen/dlsym (loads libonnxruntime.so at runtime)
    log
)
