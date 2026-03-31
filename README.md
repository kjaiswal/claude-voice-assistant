# Claude Voice Assistant

Turn your Android phone into a voice-powered Claude Code assistant. Long-press your power button, speak naturally, and get answers from Claude Code with full access to your filesystem, SSH, memory, and tools — all by voice.

## How it works

```
Android Phone (long-press power button)
  → Records voice (auto-stops on 1.5s silence)
  → Sends audio over Tailscale/local network
  → Backend: NVIDIA Parakeet STT (168ms) → Claude Code → macOS TTS
  → Plays spoken response on phone
```

**Typical latency:** ~12 seconds end-to-end for complex queries with filesystem access.

## Features

- **Replaces default Android assistant** — triggers via long-press power button or corner swipe gesture
- **NVIDIA Parakeet TDT 0.6b v2** — #1 on Open ASR Leaderboard, runs locally on Apple Silicon via MLX
- **Full Claude Code access** — filesystem, SSH, memory files, tools, everything `claude --print` can do
- **Hands-free driving mode:**
  - Audible chime when listening starts (no need to look at screen)
  - Gentle thinking tone while processing
  - Tap mic or long-press power again to interrupt Claude speaking
  - Long-press power for follow-up questions without touching the screen
- **Dark UI** matching a terminal aesthetic (#111111 bg, #F9A060 accent)

## Architecture

| Component | Location | Tech |
|---|---|---|
| Android app | Phone | Kotlin, AudioRecord, ROLE_ASSISTANT |
| Backend server | Mac/Linux | Python, parakeet-mlx, Claude Code CLI |
| STT | Backend (MLX) | NVIDIA Parakeet TDT 0.6b v2 |
| LLM | Backend | Claude Code (`claude --print`) |
| TTS | Backend (macOS) | `say -v Samantha` → WAV |
| Network | Tailscale | HTTP over WireGuard tunnel |

## Requirements

### Backend (macOS Apple Silicon)
- macOS 13+ with Apple Silicon (M1/M2/M3/M4)
- Python 3.10+
- [Claude Code](https://claude.ai/code) installed and authenticated
- [Tailscale](https://tailscale.com) (or any network path from phone to server)

### Android
- Android 9+ (API 28+)
- Tailscale app connected to same tailnet as backend

## Setup

### 1. Backend

```bash
# Create virtual environment
python3 -m venv voice-env
source voice-env/bin/activate

# Install dependencies
pip install parakeet-mlx

# Run server (downloads model on first run, ~1 min)
python3 voice_assistant.py
# Server starts on http://0.0.0.0:8888
```

The server pre-loads the Parakeet model at startup. First run downloads ~600MB from HuggingFace.

**Run as a service (macOS):**

```bash
# Copy the LaunchAgent plist (edit paths for your system)
cp com.voice.assistant.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.voice.assistant.plist
```

### 2. Android App

**Option A: Build from source**

```bash
# Requires JDK 17-21 and Android SDK
export JAVA_HOME=/path/to/jdk
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Option B: Download pre-built APK** from [Releases](../../releases).

### 3. Configure

1. Edit `SERVER_URL` in `AssistActivity.kt` to point to your backend's Tailscale IP
2. Open the Claude app on your phone
3. Tap **"Set as Default Assistant"**
4. Long-press power button to test

**Note:** After each APK reinstall, you may need to re-set the assistant settings:

```bash
adb shell settings put secure assistant com.claudecode.assistant/.AssistActivity
adb shell settings put secure voice_interaction_service com.claudecode.assistant/.ClaudeVoiceInteractionService
```

Or use the included `deploy.sh` script which handles this automatically.

## API Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/voice` | Audio/text → STT → Claude → TTS → response |
| `POST` | `/transcribe` | Audio → text only |
| `POST` | `/tts` | Text → audio |
| `GET` | `/audio/<file>` | Serve generated TTS audio |
| `GET` | `/health` | Health check |

### Example: Text query

```bash
curl -X POST http://localhost:8888/voice \
  -H 'Content-Type: application/json' \
  -d '{"text": "What files are in my home directory?"}'
```

### Example: Audio query

```bash
curl -X POST http://localhost:8888/voice \
  --data-binary @recording.wav \
  -H 'Content-Type: audio/wav'
```

## Customization

- **TTS Voice:** Change `say -v Samantha` in `voice_assistant.py` to any macOS voice (`say -v ?` to list)
- **STT Model:** Swap `parakeet-tdt-0.6b-v2` for `parakeet-tdt-0.6b-v3` (multilingual) or `parakeet-tdt-1.1b` (higher accuracy)
- **Server URL:** Edit `SERVER_URL` in `AssistActivity.kt`
- **Silence detection:** Adjust `SILENCE_THRESHOLD` and `SILENCE_DURATION_MS` in `AssistActivity.kt`

## Known Issues

- Android clears assistant settings on APK reinstall — use `deploy.sh` to auto-restore
- The official Claude Android app has the same `ROLE_ASSISTANT` registration issue ([related discussion](https://github.com/anthropics/claude-code/issues/41696))
- macOS `say` TTS is functional but not neural-quality — consider Kokoro or Piper for better voices

## License

MIT
