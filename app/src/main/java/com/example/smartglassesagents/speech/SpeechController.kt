package com.example.smartglassesagents.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class SpeechController(context: Context) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val pendingUtterances = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val utteranceSeq = AtomicLong(0L)
    var muted: Boolean = false
    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            textToSpeech?.setSpeechRate(field)
        }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            textToSpeech?.language = Locale.getDefault()
            textToSpeech?.setSpeechRate(speechRate)
            textToSpeech?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            textToSpeech?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        finishPendingSpeech(utteranceId, completed = true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finishPendingSpeech(utteranceId, completed = false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        finishPendingSpeech(utteranceId, completed = false)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        finishPendingSpeech(utteranceId, completed = !interrupted)
                    }
                }
            )
        }
    }

    suspend fun speak(text: String): Boolean {
        if (!ready || muted || text.isBlank()) return false
        // Android TTS can truncate very long strings; speak in chunks and await completion.
        stop()
        val chunks = chunkText(text.trim(), MAX_UTTERANCE_CHARS)
        var first = true
        for (chunk in chunks) {
            val ok = speakChunk(chunk, flush = first)
            if (!ok) return false
            first = false
        }
        return true
    }

    fun stop() {
        textToSpeech?.stop()
        // Resume any awaiting callers as interrupted.
        val callbacks = pendingUtterances.values.toList()
        pendingUtterances.clear()
        callbacks.forEach { callback -> callback(false) }
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
    }

    private fun finishPendingSpeech(utteranceId: String?, completed: Boolean) {
        if (utteranceId.isNullOrBlank()) return
        val callback = pendingUtterances.remove(utteranceId) ?: return
        callback(completed)
    }

    private suspend fun speakChunk(text: String, flush: Boolean): Boolean {
        val tts = textToSpeech ?: return false
        val utteranceId = "utterance-${utteranceSeq.incrementAndGet()}"
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return suspendCancellableCoroutine { continuation ->
            pendingUtterances[utteranceId] = { completed ->
                if (continuation.isActive) continuation.resume(completed)
            }
            val result = tts.speak(text, queueMode, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pendingUtterances.remove(utteranceId)
                if (continuation.isActive) continuation.resume(false)
            }
            continuation.invokeOnCancellation {
                pendingUtterances.remove(utteranceId)
                tts.stop()
            }
        }
    }

    private fun chunkText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val endExclusive = (start + maxChars).coerceAtMost(text.length)
            var cut = endExclusive
            if (endExclusive < text.length) {
                val lastSpace = text.lastIndexOf(' ', endExclusive - 1)
                if (lastSpace > start + 200) {
                    cut = lastSpace
                }
            }
            chunks.add(text.substring(start, cut).trim())
            start = cut
        }
        return chunks.filter { it.isNotBlank() }
    }

    private companion object {
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 1.5f
        const val MAX_UTTERANCE_CHARS = 3000
    }
}
