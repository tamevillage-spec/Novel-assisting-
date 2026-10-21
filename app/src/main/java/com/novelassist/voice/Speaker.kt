package com.novelassist.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.novelassist.learning.TextTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Reads text aloud with the phone's text-to-speech voice. */
class Speaker(context: Context, private val onSpeakingChanged: (Boolean) -> Unit) {
    private var ready = false
    private var pending: Pair<String, Float>? = null
    private val counter = AtomicInteger()
    private var lastUtteranceId: String? = null
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            runCatching { tts.language = Locale.getDefault() }
            pending?.let { (text, rate) -> speak(text, rate) }
            pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = setSpeaking(true)
            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastUtteranceId) setSpeaking(false)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = setSpeaking(false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = setSpeaking(false)
        })
    }

    private fun setSpeaking(value: Boolean) {
        if (_speaking.value != value) {
            _speaking.value = value
            onSpeakingChanged(value)
        }
    }

    fun speak(text: String, rate: Float = 1f) {
        val clean = text.replace("“", "\"").replace("”", "\"").trim()
        if (clean.isEmpty()) return
        if (!ready) {
            pending = clean to rate
            return
        }
        tts.setSpeechRate(rate)
        tts.stop()
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val chunks = chunk(clean, limit)
        chunks.forEachIndexed { i, part ->
            val id = "u${counter.incrementAndGet()}"
            if (i == chunks.lastIndex) lastUtteranceId = id
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        if (ready) tts.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private fun chunk(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        TextTools.sentences(text).forEach { s ->
            if (current.length + s.length + 1 > limit && current.isNotEmpty()) {
                out += current.toString(); current.setLength(0)
            }
            current.append(s.take(limit)).append(' ')
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }
}
