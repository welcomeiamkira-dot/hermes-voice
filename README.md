# Hermes Voice

Android foreground service that listens for the wake word **"hermes"** offline using Vosk, then talks to your local Hermes backend.

## Features
- Offline wake-word detection (`vosk-model-small-en-in`) via Vosk
- On detection, says **"haan bolo"** and starts `SpeechRecognizer`
- POSTs transcript to `http://127.0.0.1:8765/` with header `X-Token` and JSON body `{"message":"..."}`
- Speaks the `"answer"` field back via Android `TextToSpeech`
- Auto-starts after boot

## Setup
1. In Termux, enable the Hermes API server: add `API_SERVER_ENABLED=true` and `API_SERVER_KEY=<key>` to `~/.hermes/.env`, install `aiohttp` into the Hermes venv, then restart the gateway: `hermes -p default gateway restart`. The app calls `http://127.0.0.1:8642/v1/chat/completions` with `Authorization: Bearer <key>` and `X-Hermes-Session-Id: voice`.
2. Add a GitHub Actions secret named `HERMES_TOKEN` with the same value as `API_SERVER_KEY` (Settings > Secrets and variables > Actions).
3. Push to `master` (or run the workflow manually). It downloads `vosk-model-small-en-in`, builds a debug APK and publishes it to the `latest-build` release.
4. Install `app-debug.apk`, grant the microphone permission and tap Start Listening. Say "hermes", "jarvis" or "computer" to wake it, and "stop" to turn it off.

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
