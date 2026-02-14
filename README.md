# VoicePing Android Offline Transcribe

Android app for on-device transcription with optional on-device translation.
All ASR inference runs locally after model download.

## Current Scope

- Live transcription with confirmed text plus rolling hypothesis.
- Audio source switching: `Voice` (microphone), `System` (playback capture via MediaProjection).
- In-app model download/load/switch across 6 engine backends.
- Runtime stats while recording (`CPU`, `RAM`, `tok/s`, elapsed audio).
- Settings toggles for `Voice Activity Detection`, timestamps, and translation options.
- Translation: Google ML Kit (`MlKitTranslator`) with 20 language pairs.
- Android 14+ MediaProjection path uses a foreground service (`MediaProjectionService`).

## Supported Models & Benchmarks

15 models across 6 engine types. Defined in `ModelInfo.kt`.

Benchmarked on Samsung Galaxy S10 (Exynos 9820, API 31) with 30s JFK test audio (2026-02-14).

| Model | Engine | Size | Languages | Inference | tok/s | RTF | Result |
|---|---|---|---|---|---|---|---|
| Moonshine Tiny | sherpa-onnx | ~125 MB | English | 1,341 ms | 43.3 | 0.04 | PASS |
| SenseVoice Small | sherpa-onnx | ~240 MB | zh/en/ja/ko/yue | 1,827 ms | 31.7 | 0.06 | PASS |
| Whisper Tiny | sherpa-onnx | ~100 MB | 99 languages | 2,134 ms | 26.2 | 0.07 | PASS |
| Moonshine Base | sherpa-onnx | ~290 MB | English | 2,534 ms | 22.9 | 0.08 | PASS |
| Zipformer Streaming | sherpa-onnx streaming | ~73 MB | English | 3,596 ms | 16.1 | 0.12 | PASS |
| Whisper Base (.en) | sherpa-onnx | ~160 MB | English | 4,202 ms | 13.8 | 0.14 | PASS |
| Whisper Base | sherpa-onnx | ~160 MB | 99 languages | 4,478 ms | 13.0 | 0.15 | PASS |
| Whisper Small | sherpa-onnx | ~490 MB | 99 languages | 12,447 ms | 4.7 | 0.41 | PASS |
| Qwen3 ASR 0.6B (ONNX) | ONNX Runtime INT8 | ~1.9 GB | 30 languages | 17,090 ms | 3.4 | 0.57 | PASS |
| Whisper Turbo | sherpa-onnx | ~1.0 GB | 99 languages | 19,318 ms | 3.0 | 0.64 | PASS |
| Omnilingual 300M | sherpa-onnx | ~365 MB | 1,600+ languages | 49,877 ms | 0.04 | 1.66 | FAIL* |
| Whisper Tiny (whisper.cpp) | whisper.cpp GGML | ~31 MB | 99 languages | 112,112 ms | 0.5 | 3.74 | PASS |
| Qwen3 ASR 0.6B (CPU) | Pure C/NEON | ~1.8 GB | 30 languages | 387,413 ms | 0.1 | 12.9 | PASS |
| Android Speech (Offline) | SpeechRecognizer | Built-in | 50+ languages | - | - | - | SKIP** |
| Android Speech (Online) | SpeechRecognizer | Built-in | 100+ languages | - | - | - | SKIP** |

RTF = Real Time Factor (lower is faster; <1 = faster than real-time). tok/s = output tokens per second.

\* Omnilingual MMS CTC 300M outputs wrong language for English — known model limitation on both iOS and Android.
\*\* Android `SpeechRecognizer.EXTRA_AUDIO_SOURCE` requires API 33+. Galaxy S10 is API 31. Live mic transcription works on API 31+.

**14/15 PASS, 1 FAIL (known limitation), 0 CRASH**

## Architecture

- Orchestrator: `service/WhisperEngine.kt`
- Engines (6 backends):
  - `SherpaOnnxEngine` — sherpa-onnx offline (ONNX Runtime)
  - `SherpaOnnxStreamingEngine` — sherpa-onnx streaming (ONNX Runtime)
  - `CactusEngine` — whisper.cpp (GGML, via JNI)
  - `QwenASREngine` — Pure C inference with ARM NEON
  - `QwenOnnxEngine` — ONNX Runtime INT8 quantized
  - `AndroidSpeechEngine` — Android SpeechRecognizer (online/offline)
- Audio capture:
  - `AudioRecorder` (microphone + playback capture)
  - `MediaProjectionService` (foreground service for capture flow)
- Translation: `MlKitTranslator` (Google ML Kit, 20 language pairs)
- UI: `ui/transcription/TranscriptionScreen.kt`, `ui/setup/ModelSetupScreen.kt`

## Requirements

- Android Studio / Android SDK
- JDK 17
- Android SDK 35
- CMake 3.22.1
- Android 8.0+ (`minSdk 26`)

## Setup

```bash
git clone --recurse-submodules <repo-url>
cd android-offline-transcribe/VoicePingAndroidOfflineTranscribe
./setup-deps.sh
./gradlew assembleDebug
```

## Tests

```bash
# Unit tests (180 tests)
cd VoicePingAndroidOfflineTranscribe
./gradlew testDebugUnitTest

# E2E benchmark (all 15 models)
bash /tmp/android-benchmark.sh

# CI and automation
scripts/ci-android-unit-test.sh
scripts/android-e2e-test.sh
scripts/android-userflow-test.sh
```

## Privacy

- Audio and transcription are processed locally on device.
- Network is used for model and language-pack downloads only.
- Android Speech (Online) mode uses Google cloud services when selected.

## License

Apache License 2.0. See `LICENSE`.
