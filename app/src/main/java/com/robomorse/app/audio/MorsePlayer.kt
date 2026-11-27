package com.robomorse.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.robomorse.core.MorseRenderer
import com.robomorse.core.MorseTiming
import com.robomorse.core.RenderedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plays Morse with sample-accurate timing and reports, on the monotonic clock
 * (the same clock as touch events' uptime), when each character's sound ended.
 */
class MorsePlayer {
    data class Playback(val rendered: RenderedText, val charEndUptimeMs: List<Long>)

    private val sampleRate = 48_000
    private val rampMs = 5.0

    @Volatile private var current: AudioTrack? = null

    /** Builds 16-bit mono PCM for [rendered], with raised-cosine edges to avoid clicks. */
    fun synthesize(rendered: RenderedText, pitchHz: Int, volume: Float): ShortArray {
        val totalSamples = (rendered.totalMs * sampleRate / 1000).roundToInt() + sampleRate / 20
        val pcm = ShortArray(totalSamples)
        val rampSamples = (rampMs * sampleRate / 1000).roundToInt().coerceAtLeast(1)
        val amp = (Short.MAX_VALUE * volume.coerceIn(0f, 1f) * 0.8f)
        var t = 0.0
        for (seg in rendered.segments) {
            val start = (t * sampleRate / 1000).roundToInt()
            val end = ((t + seg.durationMs) * sampleRate / 1000).roundToInt().coerceAtMost(totalSamples)
            if (seg.on) {
                val n = end - start
                for (i in 0 until n) {
                    val env = when {
                        i < rampSamples -> 0.5 * (1 - cos(PI * i / rampSamples))
                        i >= n - rampSamples -> 0.5 * (1 - cos(PI * (n - i) / rampSamples))
                        else -> 1.0
                    }
                    val s = sin(2 * PI * pitchHz * (start + i) / sampleRate)
                    pcm[start + i] = (amp * env * s).toInt().toShort()
                }
            }
            t += seg.durationMs
        }
        return pcm
    }

    /**
     * Plays [text] and suspends until it finishes. [onStarted] receives the playback
     * with character end times as soon as they are known (shortly after playback
     * starts), so the UI can accept answers while the sound is still playing.
     */
    suspend fun play(
        text: String,
        timing: MorseTiming,
        pitchHz: Int,
        volume: Float,
        onStarted: (Playback) -> Unit = {},
    ): Playback = withContext(Dispatchers.Default) {
        val rendered = MorseRenderer.render(text, timing)
        val pcm = synthesize(rendered, pitchHz, volume)
        val track = newTrack(pcm.size)
        current?.let { runCatching { it.stop() } }
        current = track
        try {
            track.write(pcm, 0, pcm.size)
            val playCalledNs = System.nanoTime()
            track.play()
            val found = findStartNanos(track)
            val startNs = found ?: playCalledNs
            android.util.Log.d("MorsePlayer", "timestamp=${found != null} startOffsetMs=${(startNs - playCalledNs) / 1_000_000}")
            val ends = rendered.charEndMs.map { ms -> (startNs / 1_000_000L) + ms.toLong() }
            val playback = Playback(rendered, ends)
            withContext(Dispatchers.Main) { onStarted(playback) }
            val remaining = (startNs / 1_000_000L + rendered.totalMs.toLong()) - System.nanoTime() / 1_000_000L
            if (remaining > 0) delay(remaining + 30)
            playback
        } finally {
            runCatching { track.stop() }
            track.release()
            if (current === track) current = null
        }
    }

    /**
     * A short sound for a right answer (two quick rising notes) or a wrong one (two low
     * falling notes), pitched well away from the Morse tone so it isn't mistaken for it.
     */
    suspend fun cue(right: Boolean, volume: Float) = withContext(Dispatchers.Default) {
        val notes = if (right) listOf(988 to 70, 1319 to 110) else listOf(330 to 130, 220 to 200)
        val amp = Short.MAX_VALUE * volume.coerceIn(0f, 1f) * 0.6f
        val ramp = (rampMs * sampleRate / 1000).roundToInt()
        val gap = sampleRate * 25 / 1000
        val pcm = ShortArray(notes.sumOf { (_, ms) -> ms * sampleRate / 1000 + gap } + sampleRate / 20)
        var pos = 0
        for ((hz, ms) in notes) {
            val n = ms * sampleRate / 1000
            for (i in 0 until n) {
                val env = when {
                    i < ramp -> 0.5 * (1 - cos(PI * i / ramp))
                    i >= n - ramp -> 0.5 * (1 - cos(PI * (n - i) / ramp))
                    else -> 1.0
                }
                pcm[pos + i] = (amp * env * sin(2 * PI * hz * i / sampleRate)).toInt().toShort()
            }
            pos += n + gap
        }
        val track = newTrack(pcm.size)
        current?.let { runCatching { it.stop() } }
        current = track
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / sampleRate + 30)
        } finally {
            runCatching { track.stop() }
            track.release()
            if (current === track) current = null
        }
    }

    private fun newTrack(samples: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples * 2)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    fun stop() {
        current?.let { runCatching { it.pause(); it.flush() } }
    }

    /** Uses AudioTimestamp to find when frame 0 was (or will be) presented. */
    private suspend fun findStartNanos(track: AudioTrack): Long? {
        val ts = AudioTimestamp()
        repeat(40) {
            if (track.getTimestamp(ts) && ts.framePosition > 0) {
                return ts.nanoTime - ts.framePosition * 1_000_000_000L / sampleRate
            }
            delay(5)
        }
        return null
    }
}
