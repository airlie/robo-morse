package com.robomorse.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Speaks short words with Android's text-to-speech, for saying an answer aloud when you
 * aren't looking at the screen. If the phone has no usable voice, [say] returns false
 * and callers carry on without it.
 */
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val ids = AtomicInteger()
    private val waiting = mutableMapOf<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS && setUp())
    }

    private fun setUp(): Boolean {
        val lang = tts.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setSpeechRate(1.15f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
        return true
    }

    private fun finish(id: String?, ok: Boolean) {
        val done = synchronized(waiting) { waiting.remove(id) }
        done?.invoke(ok)
    }

    /** Says [text] and suspends until it has been spoken. False if nothing could be said. */
    suspend fun say(text: String): Boolean {
        val ok = withTimeoutOrNull(3000) { ready.await() } ?: false
        if (!ok) return false
        return suspendCancellableCoroutine { cont ->
            val id = "u${ids.incrementAndGet()}"
            synchronized(waiting) { waiting[id] = { spoken -> if (cont.isActive) cont.resume(spoken) } }
            cont.invokeOnCancellation {
                synchronized(waiting) { waiting.remove(id) }
                runCatching { tts.stop() }
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, false)
        }
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
