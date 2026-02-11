<p align="center">
  <img src="assets/app-icon.png" width="120" alt="App Icon"/>
</p>

# VoicePing Android Offline Transcribe

[![Android Build](https://github.com/voiceping-ai/android-offline-transcribe/actions/workflows/android-build.yml/badge.svg)](https://github.com/voiceping-ai/android-offline-transcribe/actions/workflows/android-build.yml)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)](#tech-stack)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7f52ff)](#tech-stack)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](#license)

Android app for **fully offline speech-to-text transcription and translation** — all inference runs on-device with no cloud dependency.

Record speech from the microphone and transcribe it locally using multiple ASR engines (Whisper, Moonshine, SenseVoice, Zipformer). Translate transcribed text offline via Google ML Kit. Models are downloaded once from HuggingFace, then everything works completely offline.

> **Related repos:**
> [iOS Transcription](https://github.com/voiceping-ai/ios-offline-transcribe) ·
> [iOS + Android Translation](https://github.com/voiceping-ai/ios-android-offline-speech-translation)

## Features

- Real-time microphone recording with live transcript rendering
- 3 ASR engine backends with in-app model switching
- On-device model download with progress tracking
- Streaming transcription (Zipformer transducer, endpoint-based)
- Offline translation via Google ML Kit (~30 MB per language pair, 50+ languages)
- Voice Activity Detection (VAD) toggle
- Optional timestamp display
- Session audio saving as WAV (PCM 16kHz mono 16-bit)
- Audio playback with waveform scrubber
- ZIP export of session (transcription + audio)
- Transcription history with save, copy, share, delete
- Live audio energy visualization
- CPU / memory / tokens-per-second telemetry display
- Storage guard before large model downloads

## Supported Models (11)

| Model | Engine | Size | Params | Languages |
|---|---|---:|---:|---|
| Whisper Tiny | whisper.cpp | ~80 MB | 39M | 99 languages |
| Whisper Base | whisper.cpp | ~150 MB | 74M | 99 languages |
| Whisper Base (.en) | whisper.cpp | ~150 MB | 74M | English |
| Whisper Small | whisper.cpp | ~500 MB | 244M | 99 languages |
| Whisper Large V3 Turbo | whisper.cpp | ~1.6 GB | 809M | 99 languages |
| Whisper Large V3 Turbo (q8_0) | whisper.cpp | ~834 MB | 809M | 99 languages |
| Moonshine Tiny | sherpa-onnx | ~125 MB | 27M | English |
| Moonshine Base | sherpa-onnx | ~290 MB | 62M | English |
| SenseVoice Small | sherpa-onnx | ~240 MB | 234M | zh/en/ja/ko/yue |
| Omnilingual 300M | sherpa-onnx | ~365 MB | 300M | 1,600+ languages |
| Zipformer Streaming | sherpa-onnx | ~46 MB | 20M | English |

## Benchmarks (Samsung Galaxy S10, Exynos 9820, 30s English audio)

| Model | Status | tok/s | Time | RTF | Notes |
|---|---|---:|---:|---:|---|
| Moonshine Tiny | PASS | 39.6 | 1.5s | 0.05 | Fastest. English only. |
| SenseVoice Small | PASS | 30.9 | 1.9s | 0.06 | Best punctuation + multilingual. |
| Moonshine Base | PASS | 23.8 | 2.4s | 0.08 | Excellent accuracy. English only. |
| Zipformer Streaming | PASS | 16.2 | 3.6s | 0.12 | Real-time streaming with endpoint detection. |
| Whisper Tiny | PASS | 1.2 | 48s | 1.6 | Full transcript, no punctuation. |
| Whisper Base | PASS | 0.5 | 111s | 3.7 | Multilingual, good accuracy. |
| Whisper Base (.en) | PASS | 0.5 | 109s | 3.6 | English-only, adds punctuation. |
| Whisper Small | PASS | 0.07 | 13 min | 26.4 | Best Whisper quality. Slow on older devices. |
| Whisper Large V3 Turbo | TIMEOUT | — | >30 min | >60 | Too heavy for mid-range phones. |
| Whisper Large V3 Turbo (q8_0) | TIMEOUT | — | >30 min | >60 | Same model (q8_0 quantized). |
| Omnilingual 300M | FAIL | 0.04 | 47s | 1.6 | Outputs non-English for English audio. Known model limitation. |

**RTF** = Real-Time Factor (processing time / audio duration). RTF < 1.0 means faster than real-time.

### Recommended Models

- **Best speed:** Moonshine Tiny (39.6 tok/s, 1.5s for 30s audio)
- **Best multilingual:** SenseVoice Small (30.9 tok/s, zh/en/ja/ko/yue with punctuation)
- **Best streaming:** Zipformer (16.2 tok/s, real-time endpoint detection)
- **Best accuracy:** Whisper Small (full punctuation, 99 languages — slow on mid-range phones)

## Architecture

```
WhisperEngine (orchestrator)
 ├── AsrEngine interface
 │    ├── WhisperCppEngine (JNI → libwhisper.so)
 │    ├── SherpaOnnxEngine (Moonshine, SenseVoice, Omnilingual)
 │    └── SherpaOnnxStreamingEngine (Zipformer)
 ├── AudioRecorder (AudioRecord)
 ├── MlKitTranslator (Google ML Kit)
 ├── ModelDownloader (OkHttp)
 ├── StreamingChunkManager
 ├── WavWriter + WaveformGenerator + AudioPlaybackManager
 ├── SessionExporter (ZIP via FileProvider)
 └── SystemMetrics (CPU/memory telemetry)

UI: Jetpack Compose + Material3
    TranscriptionScreen, ModelSetupScreen, HistoryDetailScreen
Persistence: Room DB + DataStore (TranscriptionEntity)
Native: whisper.cpp (CMake → libwhisper.so + WhisperLib.kt JNI bridge)
```

## Setup

**Requirements:** Android Studio (or SDK/NDK), JDK 17, Android SDK 35, CMake 3.22.1

```bash
# Clone with submodules
git clone --recurse-submodules <repo-url>
cd android-offline-transcribe

# Download sherpa-onnx AAR (v1.12.23, ~37 MB)
./VoicePingAndroidOfflineTranscribe/setup-deps.sh

# Build
cd VoicePingAndroidOfflineTranscribe
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew assembleDebug
```

## Testing

```bash
# Unit tests (170 tests, 8 classes)
cd VoicePingAndroidOfflineTranscribe
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest
```

## E2E Validation

### Run E2E with evidence output

```bash
ANDROID_SERIAL=<device-serial> \
EVIDENCE_DIR=artifacts/e2e/android/verify-$(date +%Y%m%d-%H%M%S) \
scripts/android-e2e-test.sh moonshine-tiny sensevoice-small whisper-small
```

### Validate artifacts

Each model directory contains `01_model_selected.png`, `02_model_loaded.png`, `03_inference_result.png`, and `result.json`.

```bash
# Count screenshots
find artifacts/e2e/android/verify-* -maxdepth 2 -name '*.png' | wc -l

# Inspect results
python3 - <<'PY'
import json,glob
for p in sorted(glob.glob("artifacts/e2e/android/verify-*/**/result.json", recursive=True)):
    r=json.load(open(p))
    print(p, "PASS" if r.get("pass") else "FAIL", str(r.get("duration_ms",0))+"ms", (r.get("transcript","")[:80]))
PY
```

### UI flow validation

```bash
EVIDENCE_DIR=artifacts/e2e/android/userflow/verify-$(date +%Y%m%d-%H%M%S) \
scripts/android-userflow-test.sh
```

## Tech Stack

- Kotlin 2.1, Jetpack Compose, Material3
- whisper.cpp (C++ via JNI, git submodule)
- sherpa-onnx AAR v1.12.23 (ONNX Runtime)
- Google ML Kit Translation
- Jetpack DataStore + Room
- OkHttp (model downloads), kotlinx.coroutines
- minSdk 26 / targetSdk 35 / compileSdk 35

## Privacy

- All audio and transcripts are processed and stored locally on device
- Network access is only required for initial model and translation language pack downloads
- No cloud transcription or analytics services are used

## License

Apache License 2.0. See `LICENSE`.

Model weights are downloaded at runtime and have their own licenses — see `NOTICE`.

## Creator

Created by **Akinori Nakajima** ([atyenoria](https://github.com/atyenoria)).
