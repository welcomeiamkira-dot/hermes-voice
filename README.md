# Hermes Voice

Android foreground service that listens for the wake word **"hermes"** offline using Vosk, then talks to your local Hermes backend.

## Features
- Offline wake-word detection (`vosk-model-small-en-in`) via Vosk
- On detection, says **"haan bolo"** and starts `SpeechRecognizer`
- POSTs transcript to `http://127.0.0.1:8765/` with header `X-Token` and JSON body `{"message":"..."}`
- Speaks the `"answer"` field back via Android `TextToSpeech`
- Auto-starts after boot

## Setup
1. Download `vosk-model-small-en-in` from https://alphacephei.com/vosk/models
2. Place the extracted folder as `app/src/main/assets/model` or push to `filesDir/model` at runtime
3. Set your backend token in `local.properties`:
   ```properties
   hermes.token=YOUR_TOKEN_HERE
   ```
4. Build & install

## Build
```bash
./gradlew assembleRelease
```

APK appears at `app/build/outputs/apk/release/app-release.apk`.

## GitHub Actions
This repo includes `.github/workflows/build.yml` that builds a signed debug APK on every push and attaches it to a GitHub Release tagged `latest-build`.

## Permissions
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`
- `RECEIVE_BOOT_COMPLETED`
- `INTERNET`

## TODO
- Better Vosk wake-word model integration with streaming
- Wake-word confirmation + error handling
- Configurable backend URL / token in UI
