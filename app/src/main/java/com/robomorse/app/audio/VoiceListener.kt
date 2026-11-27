package com.robomorse.app.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.robomorse.core.VoiceAnswers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What came of one turn of listening. */
sealed interface Heard {
    /** The recognizer's guesses, best first. */
    data class Words(val guesses: List<String>) : Heard
    /** Nothing was said before the recognizer gave up. */
    data object Silence : Heard
    /** Something was said but not understood. */
    data object NoMatch : Heard
    data class Failed(val code: Int) : Heard
}

/**
 * Listens for one spoken answer with Android's speech recognizer. On Android 12 and up it
 * uses recognition on the phone itself where available; otherwise the system's speech
 * service, asked to work offline if it can.
 */
class VoiceListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available: Boolean
        get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    private fun create(): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    /** Listens until the speaker stops, and returns what was heard. */
    suspend fun listen(onReady: () -> Unit = {}): Heard = withContext(Dispatchers.Main) {
        val r = recognizer ?: create().also { recognizer = it }
        suspendCancellableCoroutine { cont ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onReady()
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (cont.isActive) cont.resume(if (list.isEmpty()) Heard.NoMatch else Heard.Words(list))
                }

                override fun onError(error: Int) {
                    val heard = when (error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Silence
                        SpeechRecognizer.ERROR_NO_MATCH -> Heard.NoMatch
                        else -> Heard.Failed(error)
                    }
                    if (cont.isActive) cont.resume(heard)
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
                if (Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(VoiceAnswers.vocabulary))
                }
            }
            cont.invokeOnCancellation { runCatching { r.cancel() } }
            r.startListening(intent)
        }
    }

    fun stop() {
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
