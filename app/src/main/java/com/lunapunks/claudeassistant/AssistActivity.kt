package com.lunapunks.claudeassistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sin

class AssistActivity : AppCompatActivity() {

    companion object {
        const val SERVER_URL = "http://100.99.18.69:8888"
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val PERMISSION_REQUEST = 100
        const val SILENCE_THRESHOLD = 500
        const val SILENCE_DURATION_MS = 1500
        const val MAX_RECORD_MS = 30000
        const val TONE_SAMPLE_RATE = 44100
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isProcessing = false
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var thinkingTrack: AudioTrack? = null
    @Volatile private var isThinking = false

    // UI
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollView: ScrollView
    private lateinit var timingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assist)

        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        responseText = findViewById(R.id.responseText)
        micButton = findViewById(R.id.micButton)
        progressBar = findViewById(R.id.progressBar)
        scrollView = findViewById(R.id.scrollView)
        timingText = findViewById(R.id.timingText)

        micButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else if (mediaPlayer?.isPlaying == true) {
                // Stop speaking — user understood
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                statusText.text = "Tap mic to ask again"
            } else if (!isProcessing) {
                startRecording()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST
            )
        }
    }

    // Called when activity already exists and receives a new ASSIST intent
    // (e.g. long-press power again, steering wheel button while driving)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Stop any current playback
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopThinkingTone()

        // If not currently processing, start recording again
        if (!isProcessing && !isRecording) {
            startRecording()
        } else if (isRecording) {
            // Already listening — do nothing, that's fine
        }
        // If processing, ignore — response is on its way
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            statusText.text = "Microphone permission required"
        }
    }

    /**
     * Generate and play a gentle looping "thinking" tone.
     * Soft ascending chime that repeats every ~3 seconds.
     */
    private fun startThinkingTone() {
        if (isThinking) return
        isThinking = true

        thread {
            try {
                // Generate a 3-second soft chime pattern
                val durationSec = 3.0
                val numSamples = (TONE_SAMPLE_RATE * durationSec).toInt()
                val samples = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / TONE_SAMPLE_RATE
                    val progress = t / durationSec

                    // Three gentle ascending notes: C5, E5, G5
                    val note = when {
                        t < 0.3 -> 523.25   // C5
                        t < 0.6 -> 659.25   // E5
                        t < 0.9 -> 783.99   // G5
                        else -> 0.0         // silence gap
                    }

                    if (note > 0) {
                        // Soft envelope: fade in/out each note
                        val noteT = (t % 0.3) / 0.3
                        val envelope = sin(noteT * Math.PI) * 0.12  // very soft volume
                        val sample = sin(2.0 * Math.PI * note * t) * envelope
                        samples[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                    } else {
                        samples[i] = 0
                    }
                }

                val bufSize = numSamples * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(TONE_SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, numSamples)
                track.setLoopPoints(0, numSamples, -1)  // loop forever
                thinkingTrack = track
                track.play()

                // Keep thread alive while thinking
                while (isThinking) {
                    Thread.sleep(100)
                }

                track.stop()
                track.release()
                thinkingTrack = null
            } catch (e: Exception) {
                // Tone is non-critical — silently ignore errors
                isThinking = false
            }
        }
    }

    private fun stopThinkingTone() {
        isThinking = false
        try {
            thinkingTrack?.stop()
        } catch (_: Exception) {}
    }

    /**
     * Play a short "ready to listen" chime — two quick rising tones.
     * Blocks until done (~300ms). Safe to call from any thread.
     */
    private fun playListeningChime() {
        try {
            val durSamples = (TONE_SAMPLE_RATE * 0.3).toInt()  // 300ms total
            val samples = ShortArray(durSamples)

            for (i in 0 until durSamples) {
                val t = i.toDouble() / TONE_SAMPLE_RATE
                // Two quick notes: G5 then C6
                val freq = if (t < 0.14) 783.99 else if (t < 0.28) 1046.50 else 0.0
                if (freq > 0) {
                    val noteT = if (t < 0.14) t / 0.14 else (t - 0.14) / 0.14
                    val envelope = sin(noteT * Math.PI) * 0.25
                    samples[i] = (sin(2.0 * Math.PI * freq * t) * envelope * Short.MAX_VALUE).toInt().toShort()
                }
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(TONE_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(durSamples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, durSamples)
            track.play()
            Thread.sleep(320)
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }

    private fun startRecording() {
        if (isRecording || isProcessing) return

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING,
            bufferSize * 2
        )

        isRecording = true
        statusText.text = "Listening..."
        micButton.setImageResource(android.R.drawable.ic_media_pause)
        transcriptText.text = ""
        responseText.text = ""
        timingText.text = ""

        thread {
            // Play "I'm listening" chime before starting mic capture
            playListeningChime()
            val audioData = ByteArrayOutputStream()
            val buffer = ShortArray(bufferSize / 2)
            var silenceStart = 0L
            val recordStart = System.currentTimeMillis()

            audioRecord?.startRecording()

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) continue

                val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) byteBuffer.putShort(buffer[i])
                audioData.write(byteBuffer.array())

                var sum = 0L
                for (i in 0 until read) sum += buffer[i].toLong() * buffer[i].toLong()
                val rms = Math.sqrt(sum.toDouble() / read).toInt()

                val now = System.currentTimeMillis()

                if (rms < SILENCE_THRESHOLD) {
                    if (silenceStart == 0L) silenceStart = now
                    if (audioData.size() > SAMPLE_RATE * 2 &&
                        now - silenceStart > SILENCE_DURATION_MS) {
                        handler.post { statusText.text = "Thinking..." }
                        break
                    }
                } else {
                    silenceStart = 0L
                }

                if (now - recordStart > MAX_RECORD_MS) {
                    handler.post { statusText.text = "Thinking..." }
                    break
                }
            }

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            isRecording = false

            handler.post {
                micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            }

            if (audioData.size() > SAMPLE_RATE) {
                processAudio(audioData.toByteArray())
            } else {
                handler.post {
                    statusText.text = "No speech detected. Tap mic to try again."
                    isProcessing = false
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
    }

    private fun processAudio(pcmData: ByteArray) {
        isProcessing = true
        handler.post {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Thinking..."
        }

        // Start the thinking chime
        startThinkingTone()

        thread {
            try {
                val wavData = buildWav(pcmData, SAMPLE_RATE, 1, 16)

                val url = URL("$SERVER_URL/voice")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "audio/wav")
                conn.connectTimeout = 10000
                conn.readTimeout = 300000
                conn.doOutput = true

                conn.outputStream.use { it.write(wavData) }

                val responseCode = conn.responseCode

                // Stop thinking tone before playing response
                stopThinkingTone()

                if (responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)

                    val transcript = json.optString("transcript", "")
                    val response = json.optString("response", "")
                    val audioUrl = json.optString("audio_url", "")
                    val timing = json.optJSONObject("timing")

                    handler.post {
                        transcriptText.text = transcript
                        responseText.text = response
                        statusText.text = "Done"
                        progressBar.visibility = View.GONE

                        if (timing != null) {
                            val stt = timing.optInt("stt_ms")
                            val llm = timing.optInt("llm_ms")
                            val tts = timing.optInt("tts_ms")
                            val total = timing.optInt("total_ms")
                            timingText.text = "STT: ${stt}ms | LLM: ${llm/1000.0}s | TTS: ${tts}ms | Total: ${total/1000.0}s"
                        }

                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                    }

                    if (audioUrl.isNotEmpty()) {
                        playAudio("$SERVER_URL$audioUrl")
                    }
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    handler.post {
                        statusText.text = "Error: $responseCode"
                        responseText.text = errBody
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                stopThinkingTone()
                handler.post {
                    statusText.text = "Connection error"
                    responseText.text = e.message ?: "Unknown error"
                    progressBar.visibility = View.GONE
                }
            } finally {
                isProcessing = false
            }
        }
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    handler.post { statusText.text = "Tap mic to ask again" }
                    release()
                }
                setOnErrorListener { _, _, _ ->
                    handler.post { statusText.text = "Audio playback failed" }
                    true
                }
                prepareAsync()
            }
            handler.post { statusText.text = "Speaking..." }
        } catch (e: Exception) {
            handler.post { statusText.text = "Audio error: ${e.message}" }
        }
    }

    private fun buildWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize

        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        buf.put(pcm)

        return buf.array()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThinkingTone()
        audioRecord?.release()
        mediaPlayer?.release()
    }
}
