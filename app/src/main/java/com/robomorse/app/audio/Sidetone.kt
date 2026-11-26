package com.robomorse.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
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
        // Keep only two bursts queued: the smallest buffer that plays without gaps on most devices.
        runCatching { track.setBufferSizeInFrames(burst * 2) }
        bufferMs = track.bufferSizeInFrames * 1000 / rate

        val chunk = ShortArray(burst.coerceIn(32, 512))
        val rampStep = 1.0 / (rate * 0.005)
        var env = 0.0
        var phase = 0.0
        track.play()
        try {
            while (running) {
                val target = if (keyed) 1.0 else 0.0
                val step = 2 * PI * pitchHz / rate
                val amp = Short.MAX_VALUE * volume.coerceIn(0f, 1f) * 0.8
                for (i in chunk.indices) {
                    env = when {
                        env < target -> (env + rampStep).coerceAtMost(target)
                        env > target -> (env - rampStep).coerceAtLeast(target)
                        else -> env
                    }
                    chunk[i] = (amp * env * sin(phase)).toInt().toShort()
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(chunk, 0, chunk.size) // blocks, which paces the loop
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
