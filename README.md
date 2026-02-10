# VoicePing Android Offline Transcribe

[![Android Build](https://github.com/voiceping-ai/voiceping-android-offline-transcribe/actions/workflows/android-build.yml/badge.svg)](https://github.com/voiceping-ai/voiceping-android-offline-transcribe/actions/workflows/android-build.yml)

Android app for **fully offline speech-to-text transcription and translation** — all inference runs on-device with no cloud dependency.

Record speech from the microphone and transcribe it locally using multiple ASR engines (Whisper, Moonshine, SenseVoice, Zipformer). Translate transcribed text offline via Google ML Kit. Models are downloaded once from HuggingFace, then everything works completely offline.

## Features

- Real-time microphone recording with live transcript rendering
- 3 ASR engine backends with in-app model switching
- On-device model download with progress tracking
- Streaming transcription (Zipformer transducer, endpoint-based)
- Offline translation via Google ML Kit (~30 MB per language pair, 50+ languages)
- Voice Activity Detection (VAD) toggle
- Optional timestamp display
- Session audio saving as WAV (PCM 16kHz mono 16-bit)
- Live audio energy visualization
- CPU / memory / tokens-per-second telemetry display
- Storage guard before large model downloads

## Supported Models (11)

| Model | Engine | Size | Params | Languages |
|-------|--------|------|--------|-----------|
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

### Benchmarks (Samsung Galaxy S10, Exynos 9820, 30s English audio)

| Model | Status | tok/s | Time (30s audio) | RTF | Notes |
|-------|--------|-------|------------------|-----|-------|
| Moonshine Tiny | PASS | 39.6 | 1.5s | 0.05 | Fastest. English only. |
| SenseVoice Small | PASS | 30.9 | 1.9s | 0.06 | Best punctuation + multilingual (zh/en/ja/ko/yue). |
| Moonshine Base | PASS | 23.8 | 2.4s | 0.08 | Excellent accuracy. English only. |
| Zipformer Streaming | PASS | 16.2 | 3.6s | 0.12 | Real-time streaming with endpoint detection. |
| Whisper Tiny | PASS | 1.2 | 48s | 1.6 | Full transcript, no punctuation. |
| Whisper Base | PASS | 0.5 | 111s | 3.7 | Multilingual, good accuracy. |
| Whisper Base (.en) | PASS | 0.5 | 109s | 3.6 | English-only, adds punctuation. |
| Whisper Small | PASS | 0.07 | 13 min | 26.4 | Best Whisper quality with full punctuation. Slow on older devices. |
| Whisper Large V3 Turbo | TIMEOUT | — | >30 min | >60 | 809M params too heavy for mid-range phones. Works on flagship devices. |
| Whisper Large V3 Turbo (q8_0) | TIMEOUT | — | >30 min | >60 | Same model file as above (q8_0 quantized). |
| Omnilingual 300M | FAIL | 0.04 | 47s | 1.6 | Outputs non-English text for English audio. Known model limitation (1,600-language CTC model has low per-language accuracy). Works better with non-English audio. |

**RTF** = Real-Time Factor (processing time / audio duration). RTF < 1.0 means faster than real-time.

**Recommended models:**
- **Best speed:** Moonshine Tiny (39.6 tok/s, 1.5s for 30s audio)
- **Best multilingual:** SenseVoice Small (30.9 tok/s, supports zh/en/ja/ko/yue with punctuation)
- **Best streaming:** Zipformer (16.2 tok/s, real-time endpoint detection)
- **Best accuracy:** Whisper Small (full punctuation, 99 languages, but 13 min on mid-range phones)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      MainActivity                            │
│                (Jetpack Compose + Navigation)                │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────┐              ┌──────────────────────┐  │
│  │ TranscriptionScreen│            │ ModelSetupScreen      │  │
│  └────────┬───────────┘            └──────────┬───────────┘  │
│           │                                   │              │
│  ┌────────▼───────────┐            ┌──────────▼───────────┐  │
│  │ TranscriptionVM    │            │ ModelSetupVM         │  │
│  └────────┬───────────┘            └─────────────────────-┘  │
│           │                                                  │
├───────────▼──────────────────────────────────────────────────┤
│                      WhisperEngine                            │
│             (Orchestrator — coordinates all services)         │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ AsrEngine Interface                                     │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐  │  │
│  │  │ WhisperCpp   │ │ SherpaOnnx   │ │ SherpaOnnx     │  │  │
│  │  │ Engine       │ │ Engine       │ │ StreamingEngine│  │  │
│  │  │ (JNI)       │ │ (Moonshine,  │ │ (Zipformer)   │  │  │
│  │  │              │ │  SenseVoice, │ │                │  │  │
│  │  │              │ │  Omnilingual)│ │                │  │  │
│  │  └──────────────┘ └──────────────┘ └────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────┐ ┌──────────────────┐ ┌───────────────┐  │
│  │ AudioRecorder  │ │ MlKitTranslator  │ │ SystemMetrics │  │
│  │ (AudioRecord)  │ │ (Google ML Kit)  │ │ (CPU/Memory)  │  │
│  └────────────────┘ └──────────────────┘ └───────────────┘  │
│                                                              │
│  ┌────────────────┐ ┌──────────────────┐                     │
│  │ ModelDownloader│ │ StreamingChunk   │                     │
│  │ (OkHttp)      │ │ Manager          │                     │
│  └────────────────┘ └──────────────────┘                     │
│                                                              │
├──────────────────────────────┬───────────────────────────────┤
│ DataStore (Preferences)      │ whisper.cpp (Native / JNI)    │
│ AppPreferences               │ CMake → libwhisper.so         │
│ WavWriter (session audio)    │ WhisperLib.kt (JNI bridge)    │
└──────────────────────────────┴───────────────────────────────┘
```

### ASR Engines

- **WhisperCppEngine** — Loads GGML model files and runs Whisper inference via JNI. Built with CMake from the `whisper.cpp` submodule into `libwhisper.so`.
- **SherpaOnnxEngine** — Runs Moonshine, SenseVoice, and Omnilingual models via sherpa-onnx `OfflineRecognizer` API.
- **SherpaOnnxStreamingEngine** — Runs Zipformer transducer via `OnlineRecognizer` + `OnlineStream` with 100ms polling and endpoint detection.

### Translation

Google ML Kit Translation provides fully offline text translation. Each language pair requires a ~30 MB model download on first use. 50+ languages supported. Works on all Android API levels (minSdk 26+).

## Setup

**Requirements:** Android Studio (or SDK/NDK), JDK 17, Android SDK 35, CMake 3.22.1

```bash
# Clone with submodules
git clone --recurse-submodules <repo-url>
cd voiceping-android-offline-transcribe

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

## Flow Validation (Screenshots + Logs)

Use this when you need reproducible E2E evidence for model selection, regressions, or release checks.

### 1) Run model E2E with explicit evidence output

```bash
ANDROID_SERIAL=<device-serial> \
EVIDENCE_DIR=artifacts/e2e/android/verify-$(date +%Y%m%d-%H%M%S) \
scripts/android-e2e-test.sh moonshine-tiny sensevoice-small whisper-small
```

### 2) Validate screen capture artifacts

Each model directory should include:

- `01_model_selected.png`
- `02_model_loaded.png`
- `03_inference_result.png`

Quick check:

```bash
find artifacts/e2e/android/verify-* -maxdepth 2 -name '*.png' | wc -l
```

### 3) Validate machine-readable result output

```bash
find artifacts/e2e/android/verify-* -maxdepth 2 -name result.json -print
```

Inspect pass/fail + transcript preview:

```bash
python3 - <<'PY'
import json,glob
for p in sorted(glob.glob("artifacts/e2e/android/verify-*/**/result.json", recursive=True)):
    r=json.load(open(p))
    print(p, "PASS" if r.get("pass") else "FAIL", str(r.get("duration_ms",0))+"ms", (r.get("transcript","")[:80]))
PY
```

### 4) Validate logs (instrumentation + device runtime)

Per model, the E2E script now stores:

- `instrumentation.log`: `am instrument` output
- `logcat.txt`: scoped runtime logs captured after each model test

Extract key lines:

```bash
rg -n "OK \\(1 test\\)|FAILURES|TIMEOUT|E2E_RESULT" artifacts/e2e/android/verify-*/**/instrumentation.log
rg -n "WhisperEngine|SherpaOnnxEngine|transcribeFile|E2E|ERROR" artifacts/e2e/android/verify-*/**/logcat.txt
```

### 5) Optional: user-flow UI validation (10 UiAutomator flows)

```bash
EVIDENCE_DIR=artifacts/e2e/android/userflow/verify-$(date +%Y%m%d-%H%M%S) \
scripts/android-userflow-test.sh
```

## Tech Stack

- Kotlin + Jetpack Compose + Material3
- whisper.cpp (C++ via JNI, git submodule)
- sherpa-onnx AAR v1.12.23 (ONNX Runtime)
- Google ML Kit Translation
- Jetpack DataStore (preferences)
- OkHttp (model downloads)
- kotlinx.coroutines
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
# voiceping-android-offline-transcribe
