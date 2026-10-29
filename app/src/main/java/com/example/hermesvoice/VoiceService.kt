package com.example.hermesvoice

import android.app.Service
import android.content.Intent
import android.content.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors

class VoiceService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private var wakeRecognizer: Recognizer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: HermesTts? = null
    private var recording: AudioRecord? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        tts = HermesTts(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        when (intent?.action) {
            ACTION_START -> ensureModelAndListen()
            ACTION_STOP -> stopListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        tts?.shutdown()
    }

    private fun ensureModelAndListen() {
        isRunning = true
        executor.execute {
            try {
                val modelDir = File(filesDir, "model")
                if (!modelDir.exists() || !File(modelDir, "am/final.mdl").exists()) {
                    copyModelFromAssets(modelDir)
                }
                wakeRecognizer = Model(modelDir.absolutePath).let { Recognizer(it, 16000.0f) }
                startWakeWordLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Model init failed", e)
            }
        }
    }

    private fun copyModelFromAssets(destDir: File) {
        // In production, download the model or ship it as an expansion file.
        // This is a placeholder that throws until the model is present.
        throw IOException("Vosk model not found at ${destDir.absolutePath}. Place 'model' folder there.")
    }

    private fun startWakeWordLoop() {
        listening = true
        executor.execute {
            val bufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            recording = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            val buffer = ByteArray(bufferSize)
            recording?.startRecording()

            while (listening && wakeRecognizer != null) {
                val read = recording?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    wakeRecognizer?.acceptWaveForm(buffer, read)
                    val partial = JSONObject(wakeRecognizer?.partialResult ?: "{}")
                        .optString("partial", "").lowercase(Locale.getDefault())
                    if (partial.contains("hermes")) {
                        onWakeWordDetected()
                        break
                    }
                }
            }
        }
    }

    private fun onWakeWordDetected() {
        listening = false
        recording?.stop()
        recording?.release()
        recording = null

        tts?.speak("haan bolo") {
            startSpeechRecognition()
        }
    }

    private fun startSpeechRecognition() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(bundle: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        postMessageAndSpeak(text)
                    } else {
                        // Restart wake word listening if nothing heard
                        ensureModelAndListen()
                    }
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error: $error")
                    ensureModelAndListen()
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        } else {
            tts?.speak("Speech recognition not available")
            ensureModelAndListen()
        }
    }

    private fun postMessageAndSpeak(message: String) {
        executor.execute {
            try {
                val body = JSONObject().put("message", message).toString()
                val request = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:8765/")
                    .addHeader("X-Token", BuildConfig.HERMES_TOKEN)
                    .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body))
                    .build()

                val client = okhttp3.OkHttpClient()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        tts?.speak("Server error")
                        return@execute
                    }
                    val resp = JSONObject(response.body!!.string())
                    val answer = resp.optString("answer", "")
                    if (answer.isNotEmpty()) {
                        tts?.speak(answer)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                tts?.speak("Network error")
            } finally {
                // Resume wake word listening after answering
                ensureModelAndListen()
            }
        }
    }

    private fun stopListening() {
        isRunning = false
        listening = false
        recording?.stop()
        recording?.release()
        recording = null
        wakeRecognizer?.close()
        wakeRecognizer = null
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        executor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
        var isRunning = false
            private set
    }
}
