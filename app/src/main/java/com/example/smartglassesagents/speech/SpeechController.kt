package com.example.smartglassesagents.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class SpeechController(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val pendingUtterances = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val utteranceSeq = AtomicLong(0L)
    private val speechMutex = Mutex()
    private val _status = MutableStateFlow("Initializing Android speech engine…")
    val status: StateFlow<String> = _status
    var muted: Boolean = false
    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            textToSpeech?.setSpeechRate(field)
        }
    private var textToSpeech: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val languageStatus = textToSpeech?.setLanguage(Locale.getDefault())
            _status.value = when (languageStatus) {
                TextToSpeech.LANG_MISSING_DATA -> "Voice data is missing. Install it in Android Text-to-speech settings."
                TextToSpeech.LANG_NOT_SUPPORTED -> "This device's language is not supported by the speech engine."
                else -> "Speech engine ready."
            }
            textToSpeech?.setSpeechRate(speechRate)
            textToSpeech?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            textToSpeech?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _status.value = "Speaking…"
                    }

                    override fun onDone(utteranceId: String?) {
                        _status.value = "Speech finished."
                        finishPendingSpeech(utteranceId, completed = true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _status.value = "Speech engine could not play the readout."
                        finishPendingSpeech(utteranceId, completed = false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _status.value = "Speech failed (Android TTS error $errorCode)."
                        finishPendingSpeech(utteranceId, completed = false)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        _status.value = if (interrupted) "Speech stopped." else "Speech finished."
                        finishPendingSpeech(utteranceId, completed = !interrupted)
                    }
                }
            )
        } else {
            _status.value = "Android speech engine failed to initialize. Check Text-to-speech settings."
        }
    }

    suspend fun speak(text: String): Boolean {
        if (muted || text.isBlank()) return false
        if (!ready) {
            _status.value = "Android speech engine is not ready. Check Text-to-speech settings."
            return false
        }
        return speechMutex.withLock {
            // Android TTS can truncate long strings; speak in small chunks and await each one.
            stop()
            val chunks = chunkText(text.trim(), MAX_UTTERANCE_CHARS)
            var first = true
            for (chunk in chunks) {
                val ok = speakChunk(chunk, flush = first)
                if (!ok) return@withLock false
                first = false
            }
            true
        }
    }

    fun stop() {
        textToSpeech?.stop()
        _status.value = "Speech stopped."
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
                _status.value = "Android speech engine rejected the readout."
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
        const val MAX_UTTERANCE_CHARS = 1000
    }
}
