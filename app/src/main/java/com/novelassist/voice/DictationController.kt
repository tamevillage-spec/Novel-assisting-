package com.novelassist.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class MicState { OFF, STARTING, LISTENING, HEARING, PAUSED }

/**
 * Continuous dictation on top of Android's speech recogniser. Android stops
 * listening after each pause, so in hands-free mode this restarts it
 * automatically and quietly recovers from the usual "didn't catch that" errors.
 */
class DictationController(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onState: (MicState) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var paused = false
    private var continuous = true
    private var preferOffline = false
    private var autoPunctuation = false
    private var consecutiveErrors = 0

    val isActive: Boolean get() = wanted

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(continuous: Boolean, preferOffline: Boolean, autoPunctuation: Boolean) {
        this.continuous = continuous
        this.preferOffline = preferOffline
        this.autoPunctuation = autoPunctuation
        if (!isAvailable()) {
            onError("Speech recognition isn't available. Install or update the Google app or “Speech Recognition and Synthesis” from the Play Store.")
            return
        }
        wanted = true
        paused = false
        consecutiveErrors = 0
        listenNow()
    }

    fun stop() {
        wanted = false
        paused = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onPartial("")
        onState(MicState.OFF)
    }

    /** Stops listening while the phone is speaking, so it doesn't hear itself. */
    fun pause() {
        if (!wanted || paused) return
        paused = true
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onPartial("")
        onState(MicState.PAUSED)
    }

    fun resume() {
        if (!wanted || !paused) return
        paused = false
        main.postDelayed({ listenNow() }, 250)
    }

    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        return SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
    }

    private fun listenNow() {
        if (!wanted || paused) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Give the author time to think between phrases.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (autoPunctuation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        onState(MicState.STARTING)
        try {
            ensureRecognizer().startListening(intent)
        } catch (e: Exception) {
            recognizer?.destroy()
            recognizer = null
            scheduleRestart(800)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!wanted || paused) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listenNow() }, delayMs)
    }

    private fun finishUtterance() {
        if (continuous && wanted && !paused) {
            scheduleRestart(120)
        } else if (!continuous) {
            wanted = false
            onState(MicState.OFF)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onState(MicState.LISTENING)
        }

        override fun onBeginningOfSpeech() {
            onState(MicState.HEARING)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            onPartial("")
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onResult(text)
            finishUtterance()
        }

        override fun onError(error: Int) {
            onPartial("")
            if (!wanted || paused) return
            when (error) {
                // Silence or nothing understood: just listen again.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (continuous) scheduleRestart(150) else finishUtterance()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy()
                    recognizer = null
                    scheduleRestart(500)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stop()
                    onError("Microphone permission is needed. Allow it in the phone's Settings → Apps → Novel Assist → Permissions.")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 4) {
                        stop()
                        onError("Speech recognition needs the internet right now. Check the connection, or turn on “offline speech” in Settings.")
                    } else scheduleRestart(1000L * consecutiveErrors)
                }
                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 5) {
                        stop()
                        onError("The microphone stopped working (error $error). Tap the mic to try again.")
                    } else scheduleRestart(600L * consecutiveErrors)
                }
            }
        }
    }
}
