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

Benchmarked on Samsung Galaxy S10 (Android 12, API 31) with 30s JFK test audio (2026-02-15).

Model links point to the runtime distribution used by the app (mostly Hugging Face repos: `csukuangfj/*` sherpa-onnx, `ggerganov/whisper.cpp` GGML, `Qwen/Qwen3-ASR-0.6B` + `jima/*` Qwen ONNX).

| Model | Engine | Size | Languages | Inference | tok/s | RTF | Result |
|---|---|---|---|---|---|---|---|
| [Moonshine Tiny](https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8) | sherpa-onnx | ~125 MB | English | 1,363 ms | 42.55 | 0.05 | PASS |
| [SenseVoice Small](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17) | sherpa-onnx | ~240 MB | zh/en/ja/ko/yue | 1,725 ms | 33.62 | 0.06 | PASS |
| [Whisper Tiny](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny) | sherpa-onnx | ~100 MB | 99 languages | 2,068 ms | 27.08 | 0.07 | PASS |
| [Moonshine Base](https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-base-en-int8) | sherpa-onnx | ~290 MB | English | 2,251 ms | 25.77 | 0.08 | PASS |
| Android Speech (Offline) | SpeechRecognizer | Built-in | 50+ languages | 3,615 ms | 1.38 | 0.12 | PASS** |
| Android Speech (Online) | SpeechRecognizer | Built-in | 100+ languages | 3,591 ms | 1.39 | 0.12 | PASS** |
| [Zipformer Streaming](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) | sherpa-onnx streaming | ~73 MB | English | 3,568 ms | 16.26 | 0.12 | PASS |
| [Whisper Base (.en)](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en) | sherpa-onnx | ~160 MB | English | 3,917 ms | 14.81 | 0.13 | PASS |
| [Whisper Base](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base) | sherpa-onnx | ~160 MB | 99 languages | 4,038 ms | 14.36 | 0.13 | PASS |
| [Whisper Small](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small) | sherpa-onnx | ~490 MB | 99 languages | 12,329 ms | 4.70 | 0.41 | PASS |
| [Qwen3 ASR 0.6B (ONNX)](https://huggingface.co/jima/qwen3-asr-0.6b-onnx-int8) | ONNX Runtime INT8 | ~1.9 GB | 30 languages | 15,881 ms | 3.65 | 0.53 | PASS |
| [Whisper Turbo](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo) | sherpa-onnx | ~1.0 GB | 99 languages | 17,930 ms | 3.23 | 0.60 | PASS |
| [Whisper Tiny (whisper.cpp)](https://huggingface.co/ggerganov/whisper.cpp) | whisper.cpp GGML | ~31 MB | 99 languages | 105,596 ms | 0.55 | 3.52 | PASS |
| [Qwen3 ASR 0.6B (CPU)](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) | Pure C/NEON | ~1.8 GB | 30 languages | 338,261 ms | 0.17 | 11.28 | PASS*** |
| [Omnilingual 300M](https://huggingface.co/csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12) | sherpa-onnx | ~365 MB | 1,600+ languages | 44,035 ms | 0.05 | 1.47 | FAIL* |

RTF = Real Time Factor (lower is faster; <1 = faster than real-time). tok/s = output tokens per second.

\* Omnilingual MMS CTC 300M outputs wrong language for English — known model limitation on both iOS and Android. CTC model does not support language conditioning ([sherpa-onnx #2812](https://github.com/k2-fsa/sherpa-onnx/issues/2812)).
\*\* Android Speech uses acoustic loopback on API <33 (play WAV through speaker while SpeechRecognizer listens). Partial transcript — environment-dependent. API 33+ supports direct file input via `EXTRA_AUDIO_SOURCE`.
\*\*\* Qwen3 ASR 0.6B CPU runs on this device but is extremely slow (RTF ~11). Prefer the ONNX INT8 variant on older phones.

**14/15 PASS, 1 FAIL (known limitation), 0 OOM**

## Architecture

- Orchestrator: `service/WhisperEngine.kt`
- Engines (6 backends):
  - `SherpaOnnxEngine` — Moonshine, SenseVoice, Whisper, Omnilingual via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ONNX Runtime
  - `SherpaOnnxStreamingEngine` — Zipformer via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ONNX Runtime (100 ms chunks)
  - `CactusEngine` — Whisper via [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (GGML, via JNI)
  - `QwenASREngine` — Qwen3 ASR via [antirez/qwen-asr](https://github.com/antirez/qwen-asr) (Pure C, ARM NEON)
  - `QwenOnnxEngine` — Qwen3 ASR INT8 via ONNX Runtime (uses ORT from sherpa-onnx)
  - `AndroidSpeechEngine` — Android [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) (online/offline)
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

<!-- BENCHMARK_RESULTS_START -->
### Inference Token Speed Benchmarks

Measured from E2E `result.json` files using a longer English fixture.

Fixture: `artifacts/benchmarks/long_en_eval.wav` (30.00s, 16kHz mono WAV)

#### Evaluation Method

- Per-model E2E runs with the same English fixture on each platform.
- `duration_sec = duration_ms / 1000` from each model `result.json`.
- `Words` is computed from transcript words: `[A-Za-z0-9']+`.
- `tok/s` uses `tokens_per_second` from `result.json` when present; otherwise `Words / duration_sec`.
- `RTF = duration_sec / audio_duration_sec`.

#### Android Graph

![Android tokens/sec](artifacts/benchmarks/android_tokens_per_second.svg)

#### Android Results

| Model | Engine | Words | Inference (ms) | Tok/s | RTF | Result |
|---|---|---:|---:|---:|---:|---|
| `moonshine-tiny` | sherpa-onnx offline (ONNX Runtime) | 58 | 1363 | 42.55 | 0.05 | PASS |
| `sensevoice-small` | sherpa-onnx offline (ONNX Runtime) | 58 | 1725 | 33.62 | 0.06 | PASS |
| `whisper-tiny` | sherpa-onnx offline (ONNX Runtime) | 56 | 2068 | 27.08 | 0.07 | PASS |
| `moonshine-base` | sherpa-onnx offline (ONNX Runtime) | 58 | 2251 | 25.77 | 0.08 | PASS |
| `zipformer-20m` | sherpa-onnx streaming (ONNX Runtime) | 58 | 3568 | 16.26 | 0.12 | PASS |
| `whisper-base-en` | sherpa-onnx offline (ONNX Runtime) | 58 | 3917 | 14.81 | 0.13 | PASS |
| `whisper-base` | sherpa-onnx offline (ONNX Runtime) | 58 | 4038 | 14.36 | 0.13 | PASS |
| `whisper-small` | sherpa-onnx offline (ONNX Runtime) | 58 | 12329 | 4.70 | 0.41 | PASS |
| `qwen3-asr-0.6b-onnx` | QwenASR (ONNX Runtime) | 58 | 15881 | 3.65 | 0.53 | PASS |
| `whisper-large-v3-turbo` | sherpa-onnx offline (ONNX Runtime) | 58 | 17930 | 3.23 | 0.60 | PASS |
| `android-speech-online` | Android SpeechRecognizer | 5 | 3591 | 1.39 | 0.12 | PASS |
| `android-speech-offline` | Android SpeechRecognizer | 5 | 3615 | 1.38 | 0.12 | PASS |
| `cactus-whisper-tiny` | whisper.cpp (GGML) | 58 | 105596 | 0.55 | 3.52 | PASS |
| `qwen3-asr-0.6b` | qwen-asr (Pure C/NEON) | 58 | 338261 | 0.17 | 11.28 | PASS |
| `omnilingual-300m` | sherpa-onnx offline (ONNX Runtime) | 0 | 44035 | 0.05 | 1.47 | FAIL |

#### Reproduce

1. `rm -rf artifacts/e2e/android/*`
2. `TARGET_SECONDS=30 scripts/prepare-long-eval-audio.sh`
3. `INSTRUMENT_TIMEOUT_SEC=300 EVAL_WAV_PATH=artifacts/benchmarks/long_en_eval.wav scripts/android-e2e-test.sh`
4. `python3 scripts/generate-inference-report.py --audio artifacts/benchmarks/long_en_eval.wav --update-readme`

One-command runner: `TARGET_SECONDS=30 scripts/run-inference-benchmarks.sh`

<!-- BENCHMARK_RESULTS_END -->
