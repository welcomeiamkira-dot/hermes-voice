package com.example.hermesvoice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class HermesTts(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            ready = true
            pendingText?.let { speak(it, onDone ?: {}) }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pendingText = text
            this.onDone = onDone
            return
        }
        this.onDone = onDone
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-tts")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
