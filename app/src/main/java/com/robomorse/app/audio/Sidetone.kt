package com.robomorse.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Continuous low-latency tone that follows a key: set [keyed] and the tone starts or
 * stops within one small audio buffer, with a 5 ms ramp so there are no clicks.
 * Runs its own audio thread between [start] and [stop].
 */
class Sidetone(private val context: Context) {
    @Volatile var keyed: Boolean = false
    @Volatile var pitchHz: Int = 600
    @Volatile var volume: Float = 0.8f
    @Volatile var louder: Boolean = false

    /** Approximate output buffer delay, for display. */
    @Volatile var bufferMs: Int = 0
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "sidetone").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        keyed = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val am = context.getSystemService(AudioManager::class.java)
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBytes)
            .build()
        // Start with two bursts queued, then grow one burst at a time whenever the
        // track underruns (the latency-tuning approach Oboe uses). Each device ends
        // up with the smallest buffer that plays without gaps.
        runCatching { track.setBufferSizeInFrames(burst * 2) }
        bufferMs = track.bufferSizeInFrames * 1000 / rate
        var lastUnderruns = track.underrunCount

        val chunk = ShortArray(burst.coerceIn(32, 512))
        // Same 5 ms raised-cosine edges as the receive tones in MorsePlayer.
        val rampSamples = (rate * 0.005).toInt().coerceAtLeast(1)
        var rampPos = 0 // 0 = silent, rampSamples = full volume
        var phase = 0.0
        track.play()
        try {
            while (running) {
                val on = keyed
                val step = 2 * PI * pitchHz / rate
                val amp = Short.MAX_VALUE * Loudness.gain(volume)
                val louder = louder
                for (i in chunk.indices) {
                    if (on && rampPos < rampSamples) rampPos++ else if (!on && rampPos > 0) rampPos--
                    val env = 0.5 * (1 - cos(PI * rampPos / rampSamples))
                    chunk[i] = (amp * env * Loudness.shape(sin(phase), louder)).toInt().toShort()
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(chunk, 0, chunk.size) // blocks, which paces the loop
                val underruns = track.underrunCount
                if (underruns > lastUnderruns) {
                    val bigger = track.bufferSizeInFrames + burst
                    if (bigger <= track.bufferCapacityInFrames) {
                        runCatching { track.setBufferSizeInFrames(bigger) }
                        bufferMs = track.bufferSizeInFrames * 1000 / rate
                        android.util.Log.d("Sidetone", "underrun: buffer now ${track.bufferSizeInFrames} frames (${bufferMs} ms)")
                    }
                    lastUnderruns = underruns
                }
            }
        } finally {
            android.util.Log.d("Sidetone", "stopped: ${track.underrunCount} underruns, buffer ${track.bufferSizeInFrames} frames")
            runCatching { track.stop() }
            track.release()
        }
    }
}
