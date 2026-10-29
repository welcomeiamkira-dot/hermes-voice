package com.example.hermesvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Flow: offline wake word ("hermes") with Vosk -> say "haan bolo" -> Android
 * SpeechRecognizer hears the request -> POST to the Termux bridge -> speak the
 * answer -> back to wake word.
 */
class VoiceService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var model: Model? = null
    private var tts: HermesTts? = null
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile private var listening = false
    @Volatile private var active = false

    override fun onCreate() {
        super.onCreate()
        tts = HermesTts(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (microphone permission?)", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            isRunning = true
            startWakeWord()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        tts?.shutdown()
        tts = null
        executor.execute {
            model?.close()
            model = null
        }
        executor.shutdown()
    }

    // ---------- wake word ----------

    private fun startWakeWord() {
        if (active) return
        active = true
        executor.execute { runWakeLoop() }
    }

    private fun loadModel(): Model {
        model?.let { return it }
        val dir = File(filesDir, "model")
        if (!File(dir, "am/final.mdl").exists()) {
            dir.deleteRecursively()
            if (assets.list("model").isNullOrEmpty()) {
                throw IOException("Vosk model missing in the APK (assets/model)")
            }
            copyAssetDir("model", dir)
        }
        val loaded = Model(dir.absolutePath)
        model = loaded
        return loaded
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (children == null || children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAssetDir("$assetPath/$child", File(dest, child))
            }
        }
    }

    private fun isWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.lowercase().trim()
        if (normalized.contains("her mes") || normalized.contains("her mis")) return true
        return normalized.split(" ").any { it in WAKE_WORDS }
    }

    private fun runWakeLoop() {
        var audio: AudioRecord? = null
        var recognizer: Recognizer? = null
        var detected = false
        try {
            val m = loadModel()
            recognizer = Recognizer(m, SAMPLE_RATE.toFloat())

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, 4096)
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audio.startRecording()
            listening = true

            val buffer = ByteArray(bufferSize)
            while (listening) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                val isFinal = recognizer.acceptWaveForm(buffer, read)
                val json = JSONObject(if (isFinal) recognizer.result else recognizer.partialResult)
                val text = json.optString("text", json.optString("partial", ""))
                if (isWakeWord(text)) {
                    detected = true
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake word loop failed", e)
            active = false
            main.post { tts?.speak("Wake word shuru nahi hua. Model check karo.") }
        } finally {
            listening = false
            try {
                audio?.stop()
            } catch (e: Exception) {
                // ignore
            }
            audio?.release()
            recognizer?.close()
        }

        if (detected) {
            main.post { onWakeWord() }
        }
    }

    // ---------- listen to the request ----------

    private fun onWakeWord() {
        tts?.speak("haan bolo") { startSpeechRecognition() }
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tts?.speak("Speech recognition available nahi hai") { resumeWake() }
            return
        }
        speechRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) resumeWake() else askHermes(text)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "SpeechRecognizer error: $error")
                resumeWake()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    // ---------- talk to Hermes (Termux bridge) ----------

    private fun askHermes(message: String) {
        executor.execute {
            var answer = ""
            try {
                val body = JSONObject().put("message", message).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(BRIDGE_URL)
                    .addHeader("X-Token", BuildConfig.HERMES_TOKEN)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    answer = when {
                        response.code == 403 -> "Token match nahi hua"
                        !response.isSuccessful -> "Server error " + response.code
                        else -> JSONObject(response.body?.string() ?: "{}").optString("answer", "")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bridge call failed", e)
                answer = "Bridge se connect nahi hua. Termux me bridge chalu karo."
            }
            if (answer.isBlank()) answer = "Mujhe jawab nahi mila."
            main.post { tts?.speak(answer) { resumeWake() } }
        }
    }

    private fun resumeWake() {
        main.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            active = false
            if (isRunning) startWakeWord()
        }
    }

    // ---------- stop ----------

    private fun stopListening() {
        isRunning = false
        listening = false
        active = false
        main.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    // ---------- notification ----------

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_channel_desc)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.example.hermesvoice.ACTION_START"
        const val ACTION_STOP = "com.example.hermesvoice.ACTION_STOP"
        const val CHANNEL_ID = "hermes-voice-channel"
        const val NOTIF_ID = 1
        const val TAG = "HermesVoice"
        const val SAMPLE_RATE = 16000
        const val BRIDGE_URL = "http://127.0.0.1:8765/"

        // The small model may spell the name in different ways
        private val WAKE_WORDS = setOf(
            "hermes", "hermis", "harmes", "hermez", "hermas",
            "harmesh", "hermesh", "hermies", "hermess"
        )

        @Volatile
        var isRunning = false
            private set
    }
}
