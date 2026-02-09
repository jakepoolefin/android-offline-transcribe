# Offline Transcription (Android)

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
cd repo-android-transcription-only

# Download sherpa-onnx AAR (v1.12.23, ~37 MB)
./OfflineTranscriptionAndroid/setup-deps.sh

# Build
cd OfflineTranscriptionAndroid
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew assembleDebug
```

## Testing

```bash
# Unit tests (170 tests, 8 classes)
cd OfflineTranscriptionAndroid
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest
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
