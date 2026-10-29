package com.example.hermesvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Thin TextToSpeech wrapper. speak() takes an optional onDone callback that is
 * always invoked on the main thread once the utterance has finished (or failed),
 * so callers can continue the voice flow.
 */
class HermesTts(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private var counter = 0

    private var pendingText: String? = null
    private var pendingDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            // TTS unavailable: still run the pending callback so the voice flow continues
            pendingDone?.let { main.post(it) }
            pendingText = null
            pendingDone = null
            return
        }
        engine.setLanguage(Locale.forLanguageTag("en-IN"))
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                finish(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finish(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finish(utteranceId)
            }
        })
        ready = true
        val text = pendingText
        val done = pendingDone
        pendingText = null
        pendingDone = null
        if (text != null) speak(text, done)
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pendingText = text
            pendingDone = onDone
            return
        }
        val id = "hermes-" + (counter++)
        if (onDone != null) {
            synchronized(callbacks) { callbacks[id] = onDone }
        }
        // TextToSpeech rejects very long input
        val result = tts?.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) finish(id)
    }

    private fun finish(utteranceId: String?) {
        if (utteranceId == null) return
        val cb = synchronized(callbacks) { callbacks.remove(utteranceId) } ?: return
        main.post { cb() }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
