# VoicePing Android Offline Transcribe

Android app for on-device transcription with optional on-device translation.
All ASR inference runs locally after model download.

## Current Scope (Code-Accurate)

- Live transcription with confirmed text plus rolling hypothesis.
- Audio source switching:
- `Voice` (microphone)
- `System` (playback capture via MediaProjection)
- In-app model download/load/switch.
- Runtime stats while recording (`CPU`, `RAM`, `tok/s`, elapsed audio).
- Settings toggles for `Voice Activity Detection`, timestamps, and translation options.
- Translation backend in this repo: Google ML Kit (`MlKitTranslator`).
- Android 14+ MediaProjection path uses a foreground service (`MediaProjectionService`).

## Supported Models

Defined in `VoicePingAndroidOfflineTranscribe/app/src/main/kotlin/com/jima/offlinetranscription/model/ModelInfo.kt`.

| Model ID | Display Name | Engine | Languages |
|---|---|---|---|
| `sensevoice-small` | SenseVoice Small | sherpa-onnx offline | `zh/en/ja/ko/yue` |
| `whisper-tiny` | Whisper Tiny | whisper.cpp (JNI) | `99 languages` |
| `whisper-base` | Whisper Base | whisper.cpp (JNI) | `99 languages` |
| `whisper-base-en` | Whisper Base (.en) | whisper.cpp (JNI) | `English` |
| `whisper-small` | Whisper Small | whisper.cpp (JNI) | `99 languages` |
| `whisper-large-v3-turbo` | Whisper Large V3 Turbo | whisper.cpp (JNI, q8_0 file) | `99 languages` |
| `whisper-large-v3-turbo-compressed` | Whisper Large V3 Turbo (Compressed) | whisper.cpp (JNI, q8_0 file) | `99 languages` |
| `moonshine-tiny` | Moonshine Tiny | sherpa-onnx offline | `English` |
| `moonshine-base` | Moonshine Base | sherpa-onnx offline | `English` |
| `omnilingual-300m` | Omnilingual 300M | sherpa-onnx offline | `1,600+ languages` |
| `zipformer-20m` | Zipformer Streaming | sherpa-onnx streaming | `English` |

## Architecture

- Orchestrator: `.../service/WhisperEngine.kt`
- Engines:
- `WhisperCppEngine`
- `SherpaOnnxEngine`
- `SherpaOnnxStreamingEngine`
- Audio capture:
- `AudioRecorder` (microphone + playback capture)
- `MediaProjectionService` (foreground service for capture flow)
- Translation: `MlKitTranslator`
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

## Tests and Automation

```bash
cd VoicePingAndroidOfflineTranscribe
./gradlew testDebugUnitTest

cd ..
scripts/ci-android-unit-test.sh
scripts/android-e2e-test.sh
scripts/android-userflow-test.sh
```

## Privacy

- Audio and transcription are processed locally on device.
- Network is used for model and language-pack downloads only.

## License

Apache License 2.0. See `LICENSE`.
