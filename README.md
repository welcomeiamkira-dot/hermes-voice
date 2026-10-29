# Hermes Voice

Android foreground service that listens for the wake word **"hermes"** offline using Vosk, then talks to your local Hermes backend.

## Features
- Offline wake-word detection (`vosk-model-small-en-in`) via Vosk
- On detection, says **"haan bolo"** and starts `SpeechRecognizer`
- POSTs transcript to `http://127.0.0.1:8765/` with header `X-Token` and JSON body `{"message":"..."}`
- Speaks the `"answer"` field back via Android `TextToSpeech`
- Auto-starts after boot

## Setup
1. Add a GitHub Actions secret named `HERMES_TOKEN` (Settings > Secrets and variables > Actions). It must match `TOKEN` in the Termux bridge (`~/hermes_bridge.py`).
2. Push to `master` (or run the workflow manually). The workflow downloads `vosk-model-small-en-in` from https://alphacephei.com/vosk/models into `app/src/main/assets/model`, builds a debug APK and publishes it to the `latest-build` release.
3. Install `app-debug.apk` from the release, grant the microphone permission and tap Start Listening.
4. Keep the bridge running in Termux: `python3 ~/hermes_bridge.py &`

## Local build
Place the extracted model folder at `app/src/main/assets/model`, then:
```bash
HERMES_TOKEN=your-token gradle assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
`.github/workflows/build.yml` builds a debug-signed APK on every push and attaches it to a GitHub Release tagged `latest-build`.

## Permissions
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`
- `RECEIVE_BOOT_COMPLETED`
- `INTERNET`

## TODO
- Better Vosk wake-word model integration with streaming
- Wake-word confirmation + error handling
- Configurable backend URL / token in UI
