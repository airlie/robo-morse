package com.robomorse.core

import kotlin.math.roundToInt
import kotlin.random.Random

/** Callsigns in one ladder run. */
const val LADDER_CALLS = 25
const val LADDER_STEP = 2
const val LADDER_MIN_WPM = 5
const val LADDER_MAX_WPM = 60

data class LadderResult(val target: String, val typed: String, val correct: Boolean, val wpm: Int, val points: Int)

/**
 * Callsign speed ladder: a fixed run of callsigns, each sent with no extra spacing.
 * A callsign copied right makes the next one faster; a miss makes it slower. Points are
 * the callsign's length times the speed it was copied at, halved if it was replayed.
 */
class CallsignLadder(
    val startWpm: Int,
    val calls: Int = LADDER_CALLS,
    private val random: Random = Random.Default,
) {
    var wpm: Int = startWpm.coerceIn(LADDER_MIN_WPM, LADDER_MAX_WPM)
        private set
    var score: Int = 0
        private set
    /** The fastest speed at which a callsign was copied right, or 0. */
    var peakWpm: Int = 0
        private set
    val results = mutableListOf<LadderResult>()
    private var last = ""

    val done: Int get() = results.size
    val finished: Boolean get() = done >= calls

    fun next(): String {
        var call: String
        do { call = EchoContent.callsign(random) } while (call == last)
        last = call
        return call
    }

    fun record(target: String, typed: String, replayed: Boolean): LadderResult {
        val correct = typed == target
        val points = if (correct) (target.length * wpm * if (replayed) 0.5 else 1.0).roundToInt() else 0
        val r = LadderResult(target, typed, correct, wpm, points)
        results += r
        score += points
        if (correct) peakWpm = maxOf(peakWpm, wpm)
        wpm = (if (correct) wpm + LADDER_STEP else wpm - LADDER_STEP).coerceIn(LADDER_MIN_WPM, LADDER_MAX_WPM)
        return r
    }
}
