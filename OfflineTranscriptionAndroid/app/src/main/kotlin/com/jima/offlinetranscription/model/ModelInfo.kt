package com.voiceping.offlinetranscription.model

enum class EngineType { WHISPER_CPP, SHERPA_ONNX, SHERPA_ONNX_STREAMING }
enum class SherpaModelType { MOONSHINE, SENSE_VOICE, ZIPFORMER_TRANSDUCER, OMNILINGUAL_CTC }

data class ModelFile(val url: String, val localName: String)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val engineType: EngineType,
    val sherpaModelType: SherpaModelType? = null,
    val parameterCount: String,
    val sizeOnDisk: String,
    val description: String,
    val languages: String = "99 languages",
    val files: List<ModelFile>
) {
    val inferenceMethod: String
        get() = when (engineType) {
            EngineType.WHISPER_CPP -> "whisper.cpp (C++/JNI)"
            EngineType.SHERPA_ONNX -> "sherpa-onnx offline (ONNX Runtime)"
            EngineType.SHERPA_ONNX_STREAMING -> "sherpa-onnx streaming (ONNX Runtime)"
        }

    companion object {
        private const val WHISPER_BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        private const val MOONSHINE_TINY_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/"

        private const val MOONSHINE_BASE_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-base-en-int8/resolve/main/"

        private const val SENSEVOICE_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"

        private const val ZIPFORMER_EN_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/"

        private const val OMNILINGUAL_300M_BASE_URL =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/resolve/main/"

        private fun moonshineFiles(baseUrl: String) = listOf(
            ModelFile("${baseUrl}preprocess.onnx", "preprocess.onnx"),
            ModelFile("${baseUrl}encode.int8.onnx", "encode.int8.onnx"),
            ModelFile("${baseUrl}uncached_decode.int8.onnx", "uncached_decode.int8.onnx"),
            ModelFile("${baseUrl}cached_decode.int8.onnx", "cached_decode.int8.onnx"),
            ModelFile("${baseUrl}tokens.txt", "tokens.txt"),
        )

        val availableModels = listOf(
            // -- Whisper (whisper.cpp) --
            ModelInfo(
                id = "whisper-tiny",
                displayName = "Whisper Tiny",
                engineType = EngineType.WHISPER_CPP,
                parameterCount = "39M",
                sizeOnDisk = "~80 MB",
                description = "Fastest, lower accuracy. Good for quick notes.",
                files = listOf(ModelFile("${WHISPER_BASE_URL}ggml-tiny.bin", "ggml-tiny.bin"))
            ),
            ModelInfo(
                id = "whisper-base",
                displayName = "Whisper Base",
                engineType = EngineType.WHISPER_CPP,
                parameterCount = "74M",
                sizeOnDisk = "~150 MB",
                description = "Balanced speed and accuracy. Recommended.",
                files = listOf(ModelFile("${WHISPER_BASE_URL}ggml-base.bin", "ggml-base.bin"))
            ),
            ModelInfo(
                id = "whisper-base-en",
                displayName = "Whisper Base (.en)",
                engineType = EngineType.WHISPER_CPP,
                parameterCount = "74M",
                sizeOnDisk = "~150 MB",
                description = "English-only base model with lower decoding overhead.",
                languages = "English",
                files = listOf(ModelFile("${WHISPER_BASE_URL}ggml-base.en.bin", "ggml-base.en.bin"))
            ),
            ModelInfo(
                id = "whisper-small",
                displayName = "Whisper Small",
                engineType = EngineType.WHISPER_CPP,
                parameterCount = "244M",
                sizeOnDisk = "~500 MB",
                description = "Higher accuracy, slower. Best for important recordings.",
                files = listOf(ModelFile("${WHISPER_BASE_URL}ggml-small.bin", "ggml-small.bin"))
            ),
            ModelInfo(
                id = "whisper-large-v3-turbo",
                displayName = "Whisper Large V3 Turbo",
                engineType = EngineType.WHISPER_CPP,
                parameterCount = "809M",
                sizeOnDisk = "~834 MB",
                description = "Best accuracy. Uses q8_0 fallback on Android for emulator stability.",
                files = listOf(
                    ModelFile("${WHISPER_BASE_URL}ggml-large-v3-turbo-q8_0.bin", "ggml-large-v3-turbo-q8_0.bin")
                )
            ),
            ModelInfo(
                id = "whisper-large-v3-turbo-compressed",
                displayName = "Whisper Large V3 Turbo (Compressed)",
                engineType = EngineType.WHISPER_CPP,
                parameterCount = "809M",
                sizeOnDisk = "~834 MB",
                description = "Quantized q8_0 Turbo. Smaller download, near-turbo quality.",
                files = listOf(
                    ModelFile("${WHISPER_BASE_URL}ggml-large-v3-turbo-q8_0.bin", "ggml-large-v3-turbo-q8_0.bin")
                )
            ),
            // -- Moonshine (sherpa-onnx) --
            ModelInfo(
                id = "moonshine-tiny",
                displayName = "Moonshine Tiny",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.MOONSHINE,
                parameterCount = "27M",
                sizeOnDisk = "~125 MB",
                description = "Ultra-fast, English. 5x faster than Whisper Tiny.",
                languages = "English",
                files = moonshineFiles(MOONSHINE_TINY_BASE_URL)
            ),
            ModelInfo(
                id = "moonshine-base",
                displayName = "Moonshine Base",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.MOONSHINE,
                parameterCount = "62M",
                sizeOnDisk = "~290 MB",
                description = "Fast, English. Matches Whisper Base accuracy.",
                languages = "English",
                files = moonshineFiles(MOONSHINE_BASE_BASE_URL)
            ),
            // -- SenseVoice (sherpa-onnx) --
            ModelInfo(
                id = "sensevoice-small",
                displayName = "SenseVoice Small",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.SENSE_VOICE,
                parameterCount = "234M",
                sizeOnDisk = "~240 MB",
                description = "Multilingual (zh/en/ja/ko/yue). 5x faster than Whisper Small.",
                languages = "zh/en/ja/ko/yue",
                files = listOf(
                    ModelFile("${SENSEVOICE_BASE_URL}model.int8.onnx", "model.int8.onnx"),
                    ModelFile("${SENSEVOICE_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
            // -- Omnilingual CTC (sherpa-onnx) --
            ModelInfo(
                id = "omnilingual-300m",
                displayName = "Omnilingual 300M",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.OMNILINGUAL_CTC,
                parameterCount = "300M",
                sizeOnDisk = "~365 MB",
                description = "1,600+ languages. Facebook MMS CTC model, int8 quantized.",
                languages = "1,600+ languages",
                files = listOf(
                    ModelFile("${OMNILINGUAL_300M_BASE_URL}model.int8.onnx", "model.int8.onnx"),
                    ModelFile("${OMNILINGUAL_300M_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
            // -- Zipformer Streaming (sherpa-onnx) --
            ModelInfo(
                id = "zipformer-20m",
                displayName = "Zipformer Streaming",
                engineType = EngineType.SHERPA_ONNX_STREAMING,
                sherpaModelType = SherpaModelType.ZIPFORMER_TRANSDUCER,
                parameterCount = "20M",
                sizeOnDisk = "~73 MB",
                description = "Real-time streaming English model with improved accuracy.",
                languages = "English",
                files = listOf(
                    ModelFile(
                        "${ZIPFORMER_EN_BASE_URL}encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                        "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                    ),
                    ModelFile(
                        "${ZIPFORMER_EN_BASE_URL}decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                        "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
                    ),
                    ModelFile(
                        "${ZIPFORMER_EN_BASE_URL}joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                        "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                    ),
                    ModelFile("${ZIPFORMER_EN_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
        )

        val defaultModel = availableModels.first { it.id == "whisper-base" }

        /** Group models by engine for UI display. */
        val modelsByEngine: Map<EngineType, List<ModelInfo>>
            get() = availableModels.groupBy { it.engineType }
    }
}
