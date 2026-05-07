package com.example.smartglassesagents.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpeechController(context: Context) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    var muted: Boolean = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            textToSpeech?.language = Locale.getDefault()
            textToSpeech?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    fun speak(text: String) {
        if (!ready || muted || text.isBlank()) return
        textToSpeech?.stop()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "latest-result")
    }

    fun stop() {
        textToSpeech?.stop()
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
    }
}
