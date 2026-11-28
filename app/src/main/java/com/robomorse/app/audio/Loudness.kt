package com.robomorse.app.audio

import kotlin.math.pow
import kotlin.math.tanh

/** Volume curve and tone shaping shared by every tone the app makes. */
object Loudness {
    /** The volume slider covers this many decibels, so each step sounds like the same change. */
    private const val RANGE_DB = 30.0

    /** Peak level at full volume, just under full scale. */
    private const val PEAK = 0.97

    /** How hard the "louder tone" option rounds the sine towards a square wave. */
    private const val DRIVE = 2.5

    /** Peak amplitude (0..1) for a slider value (0..1), on a decibel scale. */
    fun gain(volume: Float): Double {
        val v = volume.coerceIn(0f, 1f).toDouble()
        return if (v <= 0.0) 0.0 else PEAK * 10.0.pow((v - 1) * RANGE_DB / 20)
    }

    /**
     * With [louder], a sine sample (-1..1) is softly clipped. That raises its average
     * level and adds overtones at three and five times the pitch, which phone speakers
     * play far more loudly than the pitch itself.
     */
    fun shape(sine: Double, louder: Boolean): Double =
        if (louder) tanh(DRIVE * sine) / tanh(DRIVE) else sine
}
