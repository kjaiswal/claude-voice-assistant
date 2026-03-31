#!/usr/bin/env python3
"""
Voice Assistant Backend — ve1claude
Accepts audio from Android app, transcribes with Parakeet MLX,
runs Claude Code, returns text + TTS audio.

Listens on 0.0.0.0:8888 (accessible via Tailscale 100.99.18.69:8888)
"""

import http.server
import json
import subprocess
import tempfile
import os
import uuid
import threading
import time
import re
import hashlib
from pathlib import Path
from urllib.parse import urlparse, parse_qs

PORT = 8888
CLAUDE_BIN = "/opt/homebrew/bin/claude"
AUDIO_DIR = Path("/tmp/voice_assistant_audio")
AUDIO_DIR.mkdir(exist_ok=True)

# Parakeet model — loaded once at startup
_model = None
_model_lock = threading.Lock()

def get_model():
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                from parakeet_mlx import from_pretrained
                print("Loading Parakeet TDT 0.6b v2...")
                t0 = time.time()
                _model = from_pretrained("mlx-community/parakeet-tdt-0.6b-v2")
                print(f"Parakeet loaded in {time.time()-t0:.1f}s")
    return _model


def transcribe_audio(audio_path: str) -> str:
    """Transcribe audio file using Parakeet MLX."""
    model = get_model()
    result = model.transcribe(audio_path)
    return result.text.strip()


def run_claude(text: str, session_id: str = None) -> str:
    """Run Claude Code with the given text prompt."""
    cmd = [CLAUDE_BIN, "--print", "-p", text, "--output-format", "json"]
    if session_id:
        cmd.extend(["--resume", session_id])

    env = os.environ.copy()
    env.pop("CLAUDECODE", None)  # prevent nested session error

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=600,
            cwd="/Users/ve1claude",
            env=env,
        )
        if result.returncode != 0:
            return f"Error: {result.stderr[:500]}"

        # Parse JSON output — extract the response text
        try:
            data = json.loads(result.stdout)
            # Claude --print --output-format json returns {result: "...", ...}
            if isinstance(data, dict):
                return data.get("result", result.stdout[:2000])
            return str(data)[:2000]
        except json.JSONDecodeError:
            return result.stdout[:2000]
    except subprocess.TimeoutExpired:
        return "Request timed out after 10 minutes."
    except Exception as e:
        return f"Error running Claude: {e}"


def generate_tts(text: str) -> str:
    """Generate TTS audio using macOS say. Returns path to WAV file."""
    # Clean text for TTS — strip markdown, code blocks, long outputs
    clean = text
    # Remove code blocks
    clean = re.sub(r"```[\s\S]*?```", "Code block omitted.", clean)
    # Remove inline code
    clean = re.sub(r"`[^`]+`", "", clean)
    # Remove markdown links, keep text
    clean = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", clean)
    # Remove markdown formatting
    clean = re.sub(r"[*_#>]+", "", clean)
    # Truncate for TTS (don't read novels)
    if len(clean) > 1500:
        clean = clean[:1500] + "... Response truncated."
    clean = clean.strip()
    if not clean:
        clean = "Done."

    file_id = uuid.uuid4().hex[:12]
    aiff_path = AUDIO_DIR / f"{file_id}.aiff"
    wav_path = AUDIO_DIR / f"{file_id}.wav"

    subprocess.run(
        ["say", "-v", "Samantha", "-o", str(aiff_path), clean],
        check=True,
        timeout=30,
    )
    subprocess.run(
        ["afconvert", "-f", "WAVE", "-d", "LEI16@22050", str(aiff_path), str(wav_path)],
        check=True,
        timeout=10,
    )
    aiff_path.unlink(missing_ok=True)

    # Clean old audio files (>1 hour)
    cutoff = time.time() - 3600
    for f in AUDIO_DIR.glob("*.wav"):
        try:
            if f.stat().st_mtime < cutoff:
                f.unlink()
        except OSError:
            pass

    return str(wav_path)


# Session management — per-device sessions
_sessions = {}  # device_id -> session_id
_sessions_file = Path("/Users/ve1claude/.claude/voice_sessions.json")

def load_sessions():
    global _sessions
    if _sessions_file.exists():
        try:
            _sessions = json.loads(_sessions_file.read_text())
        except (json.JSONDecodeError, OSError):
            _sessions = {}

def save_sessions():
    try:
        _sessions_file.write_text(json.dumps(_sessions))
    except OSError:
        pass


class VoiceHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[{time.strftime('%H:%M:%S')}] {args[0] if args else format}")

    def _send_json(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path, content_type="audio/wav"):
        try:
            data = Path(path).read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", len(data))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
        except FileNotFoundError:
            self._send_json({"error": "File not found"}, 404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)

        if parsed.path == "/health":
            self._send_json({"status": "ok", "model": "parakeet-tdt-0.6b-v2"})

        elif parsed.path.startswith("/audio/"):
            filename = parsed.path.split("/audio/")[-1]
            # Sanitize
            if "/" in filename or ".." in filename:
                self._send_json({"error": "Invalid path"}, 400)
                return
            self._send_file(AUDIO_DIR / filename)

        else:
            self._send_json({"error": "Not found"}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        content_length = int(self.headers.get("Content-Length", 0))

        if parsed.path == "/voice":
            # Main voice endpoint — accepts multipart audio or JSON text
            content_type = self.headers.get("Content-Type", "")

            if "multipart/form-data" in content_type:
                # Audio upload
                self._handle_voice_audio(content_type, content_length)
            elif "application/json" in content_type:
                # Text input (for testing or text-only mode)
                self._handle_voice_text(content_length)
            else:
                # Assume raw audio bytes
                self._handle_voice_raw(content_length)

        elif parsed.path == "/transcribe":
            # Transcribe-only endpoint
            content_type = self.headers.get("Content-Type", "")
            if "application/json" in content_type:
                body = json.loads(self.rfile.read(content_length))
                audio_path = body.get("path")
                if audio_path and os.path.exists(audio_path):
                    text = transcribe_audio(audio_path)
                    self._send_json({"text": text})
                else:
                    self._send_json({"error": "Invalid audio path"}, 400)
            else:
                # Raw audio bytes
                self._handle_transcribe_raw(content_length)

        elif parsed.path == "/tts":
            body = json.loads(self.rfile.read(content_length))
            text = body.get("text", "")
            if text:
                wav_path = generate_tts(text)
                filename = Path(wav_path).name
                self._send_json({"audio_url": f"/audio/{filename}"})
            else:
                self._send_json({"error": "No text"}, 400)

        elif parsed.path == "/reset":
            body = json.loads(self.rfile.read(content_length))
            device_id = body.get("device_id", "default")
            _sessions.pop(device_id, None)
            save_sessions()
            self._send_json({"status": "session reset"})

        else:
            self._send_json({"error": "Not found"}, 404)

    def _handle_voice_raw(self, content_length):
        """Handle raw audio bytes POST."""
        audio_data = self.rfile.read(content_length)

        # Save to temp file
        tmp = tempfile.NamedTemporaryFile(suffix=".wav", dir=str(AUDIO_DIR), delete=False)
        tmp.write(audio_data)
        tmp.close()

        try:
            self._process_voice(tmp.name, "default")
        finally:
            try:
                os.unlink(tmp.name)
            except OSError:
                pass

    def _handle_voice_text(self, content_length):
        """Handle JSON text input."""
        body = json.loads(self.rfile.read(content_length))
        text = body.get("text", "")
        device_id = body.get("device_id", "default")

        if not text:
            self._send_json({"error": "No text"}, 400)
            return

        print(f"[voice-text] {text[:100]}")

        # Run Claude
        response = run_claude(text)

        # Generate TTS
        wav_path = generate_tts(response)
        filename = Path(wav_path).name

        self._send_json({
            "transcript": text,
            "response": response,
            "audio_url": f"/audio/{filename}",
        })

    def _handle_transcribe_raw(self, content_length):
        """Transcribe raw audio bytes."""
        audio_data = self.rfile.read(content_length)
        tmp = tempfile.NamedTemporaryFile(suffix=".wav", dir=str(AUDIO_DIR), delete=False)
        tmp.write(audio_data)
        tmp.close()

        try:
            text = transcribe_audio(tmp.name)
            self._send_json({"text": text})
        except Exception as e:
            self._send_json({"error": str(e)}, 500)
        finally:
            try:
                os.unlink(tmp.name)
            except OSError:
                pass

    def _handle_voice_audio(self, content_type, content_length):
        """Handle multipart form audio upload."""
        # Parse boundary
        boundary = None
        for part in content_type.split(";"):
            part = part.strip()
            if part.startswith("boundary="):
                boundary = part.split("=", 1)[1].strip('"')
                break

        if not boundary:
            self._send_json({"error": "No boundary in multipart"}, 400)
            return

        raw = self.rfile.read(content_length)

        # Extract audio data and device_id from multipart
        parts = raw.split(f"--{boundary}".encode())
        audio_data = None
        device_id = "default"

        for part in parts:
            if b"Content-Disposition" not in part:
                continue
            header_end = part.find(b"\r\n\r\n")
            if header_end == -1:
                continue
            header = part[:header_end].decode("utf-8", errors="replace")
            body = part[header_end + 4:]
            # Strip trailing \r\n
            if body.endswith(b"\r\n"):
                body = body[:-2]

            if 'name="audio"' in header or 'name="file"' in header:
                audio_data = body
            elif 'name="device_id"' in header:
                device_id = body.decode().strip()

        if not audio_data:
            self._send_json({"error": "No audio in upload"}, 400)
            return

        # Detect format and convert if needed
        tmp_ext = ".wav"
        if audio_data[:4] == b"OggS":
            tmp_ext = ".ogg"
        elif audio_data[:3] == b"\x1a\x45\xdf":  # WebM/Matroska
            tmp_ext = ".webm"
        elif audio_data[:4] == b"\x1a\x45\xdf\xa3":
            tmp_ext = ".webm"

        tmp = tempfile.NamedTemporaryFile(suffix=tmp_ext, dir=str(AUDIO_DIR), delete=False)
        tmp.write(audio_data)
        tmp.close()

        # Convert to WAV 16kHz mono if not already WAV
        wav_path = tmp.name
        if tmp_ext != ".wav":
            wav_path = tmp.name.replace(tmp_ext, ".wav")
            try:
                subprocess.run(
                    ["/opt/homebrew/bin/ffmpeg", "-y", "-i", tmp.name,
                     "-ar", "16000", "-ac", "1", "-f", "wav", wav_path],
                    check=True, capture_output=True, timeout=30,
                )
            except (subprocess.CalledProcessError, FileNotFoundError):
                # Try afconvert as fallback
                try:
                    subprocess.run(
                        ["afconvert", "-f", "WAVE", "-d", "LEI16@16000",
                         tmp.name, wav_path],
                        check=True, capture_output=True, timeout=30,
                    )
                except subprocess.CalledProcessError:
                    self._send_json({"error": "Audio conversion failed"}, 500)
                    return
            finally:
                if tmp.name != wav_path:
                    try:
                        os.unlink(tmp.name)
                    except OSError:
                        pass

        try:
            self._process_voice(wav_path, device_id)
        finally:
            try:
                os.unlink(wav_path)
            except OSError:
                pass

    def _process_voice(self, audio_path: str, device_id: str):
        """Core pipeline: transcribe → Claude → TTS → respond."""
        t0 = time.time()

        # Step 1: Transcribe
        try:
            transcript = transcribe_audio(audio_path)
        except Exception as e:
            self._send_json({"error": f"Transcription failed: {e}"}, 500)
            return

        t_stt = time.time()
        print(f"[STT {t_stt-t0:.2f}s] {transcript}")

        if not transcript or len(transcript.strip()) < 2:
            self._send_json({"error": "No speech detected"}, 400)
            return

        # Step 2: Run Claude Code
        response = run_claude(transcript)
        t_llm = time.time()
        print(f"[LLM {t_llm-t_stt:.1f}s] {response[:100]}...")

        # Step 3: Generate TTS
        wav_path = generate_tts(response)
        t_tts = time.time()
        filename = Path(wav_path).name
        print(f"[TTS {t_tts-t_llm:.1f}s] {filename}")
        print(f"[TOTAL {t_tts-t0:.1f}s]")

        self._send_json({
            "transcript": transcript,
            "response": response,
            "audio_url": f"/audio/{filename}",
            "timing": {
                "stt_ms": int((t_stt - t0) * 1000),
                "llm_ms": int((t_llm - t_stt) * 1000),
                "tts_ms": int((t_tts - t_llm) * 1000),
                "total_ms": int((t_tts - t0) * 1000),
            },
        })


class ThreadedHTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    load_sessions()

    # Pre-load model in background
    threading.Thread(target=get_model, daemon=True).start()

    server = ThreadedHTTPServer(("0.0.0.0", PORT), VoiceHandler)
    print(f"Voice Assistant server on http://0.0.0.0:{PORT}")
    print(f"  Tailscale: http://100.99.18.69:{PORT}")
    print(f"  Endpoints:")
    print(f"    POST /voice         — audio or text → transcribe → Claude → TTS")
    print(f"    POST /transcribe    — audio → text only")
    print(f"    POST /tts           — text → audio")
    print(f"    GET  /audio/<file>  — serve TTS audio")
    print(f"    GET  /health        — health check")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
