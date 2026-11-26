package com.robomorse.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** One key-down (mark) or key-up (gap) interval, in milliseconds. */
data class KeyInterval(val on: Boolean, val ms: Double)

enum class GapKind { ELEMENT, CHARACTER, WORD }

/**
 * Decodes hand-sent Morse live, adapting to the sender's speed.
 *
 * Feed it key-down and key-up times (ms, any monotonic clock) and call [tick]
 * periodically so characters and word spaces are finished during silence.
 * Marks longer than 2 dit-lengths are dahs; the dit estimate follows the sender.
 * Gaps: under 2 dits = within a character, 2–5 = between characters, over 5 = between words.
 */
class SendDecoder(initialWpm: Double = 15.0, private val alpha: Double = 0.25) {
    var ditMs: Double = 1200.0 / initialWpm
        private set
    val estimatedWpm: Double get() = 1200.0 / ditMs

    private val sb = StringBuilder()
    private val pattern = StringBuilder()
    private var downAt: Long? = null
    private var upAt: Long? = null
    private var wordSpaceAdded = true

    /** Every mark and gap, for rhythm analysis. */
    val intervals = mutableListOf<KeyInterval>()

    /** Characters decoded so far ('▯' for an unknown pattern). */
    val text: String get() = sb.toString()

    /** Elements of the character being sent right now, e.g. "-.". */
    val currentPattern: String get() = pattern.toString()

    val isKeyDown: Boolean get() = downAt != null

    fun keyDown(t: Long) {
        if (downAt != null) return
        upAt?.let { up ->
            val gap = (t - up).toDouble()
            intervals += KeyInterval(false, gap)
            when (classifyGap(gap)) {
                GapKind.ELEMENT -> {}
                GapKind.CHARACTER -> finishChar()
                GapKind.WORD -> { finishChar(); addWordSpace() }
            }
        }
        downAt = t
    }

    fun keyUp(t: Long) {
        val d = downAt ?: return
        downAt = null
        upAt = t
        val ms = (t - d).toDouble().coerceAtLeast(1.0)
        intervals += KeyInterval(true, ms)
        val dah = ms > 2 * ditMs
        pattern.append(if (dah) '-' else '.')
        val sample = if (dah) ms / 3 else ms
        ditMs += alpha * (sample - ditMs)
        ditMs = ditMs.coerceIn(20.0, 400.0)
    }

    /** Call regularly (e.g. every 20 ms). Finishes characters and words during silence. */
    fun tick(now: Long) {
        val up = upAt ?: return
        if (downAt != null) return
        val gap = (now - up).toDouble()
        if (pattern.isNotEmpty() && gap > 2 * ditMs) finishChar()
        if (!wordSpaceAdded && gap > 5 * ditMs) addWordSpace()
    }

    fun classifyGap(ms: Double): GapKind = when {
        ms < 2 * ditMs -> GapKind.ELEMENT
        ms < 5 * ditMs -> GapKind.CHARACTER
        else -> GapKind.WORD
    }

    private fun finishChar() {
        if (pattern.isEmpty()) return
        sb.append(MorseCode.charOf(pattern.toString()) ?: '▯')
        pattern.clear()
        wordSpaceAdded = false
    }

    private fun addWordSpace() {
        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        wordSpaceAdded = true
    }

    fun clear() {
        sb.clear(); pattern.clear(); intervals.clear()
        downAt = null; upAt = null; wordSpaceAdded = true
    }
}

/** How even a sender's rhythm is, compared with ideal timing at their own speed. */
data class FistReport(
    val estWpm: Double,
    val ditMs: Double,
    val dahDitRatio: Double?,
    val elementGapRatio: Double?,
    val charGapRatio: Double?,
    val wordGapRatio: Double?,
    val variability: Double,
    val score: Int,
    val marks: Int,
)

object FistAnalyzer {
    /**
     * Analyzes [intervals]. Marks are split into dits and dahs at the midpoint
     * between the shortest and longest mark clusters; ideal ratios are dah 3,
     * element gap 1, character gap 3, word gap 7 dit-lengths.
     */
    fun analyze(intervals: List<KeyInterval>): FistReport? {
        val marks = intervals.filter { it.on }.map { it.ms }
        if (marks.size < 3) return null
        val (dits, dahs) = split(marks)
        val dit = if (dits.isNotEmpty()) dits.average() else dahs.average() / 3
        val dahRatio = if (dits.isNotEmpty() && dahs.isNotEmpty()) dahs.average() / dits.average() else null

        val gaps = intervals.filter { !it.on }.map { it.ms / dit }
        val elementGaps = gaps.filter { it < 2 }
        val charGaps = gaps.filter { it in 2.0..5.0 }
        val wordGaps = gaps.filter { it > 5 && it < 20 } // longer is just a pause
        val eg = elementGaps.averageOrNull()
        val cg = charGaps.averageOrNull()
        val wg = wordGaps.averageOrNull()

        val variability = (cv(dits) + cv(dahs) + cv(elementGaps)) / 3
        // Each term is a relative error; the score falls off smoothly as they add up.
        var penalty = 0.0
        dahRatio?.let { penalty += abs(it - 3) / 3 }
        eg?.let { penalty += abs(it - 1) }
        cg?.let { penalty += abs(it - 3) / 3 * 0.7 }
        penalty += variability * 1.5
        val score = (100 * exp(-1.6 * penalty)).toInt().coerceIn(0, 100)

        return FistReport(1200 / dit, dit, dahRatio, eg, cg, wg, variability, score, marks.size)
    }

    private fun split(marks: List<Double>): Pair<List<Double>, List<Double>> {
        val lo = marks.min()
        val hi = marks.max()
        if (hi < lo * 1.8) {
            // All one kind: decide by comparison with a typical 3:1 spread.
            return if (lo > 150) emptyList<Double>() to marks else marks to emptyList()
        }
        val mid = (lo + hi) / 2
        return marks.filter { it < mid } to marks.filter { it >= mid }
    }

    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()

    private fun cv(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        val sd = sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        return if (m == 0.0) 0.0 else sd / m
    }
}

enum class Element(val units: Int) { DIT(1), DAH(3) }

enum class IambicMode { A, B }

/**
 * Iambic paddle logic. The app calls [next] each time an element (and its
 * following one-unit gap) has finished; paddle presses during an element are
 * reported with [paddlePressed] so they are remembered (dit/dah memory).
 * Mode B also sends one extra alternate element after a squeeze is released.
 */
class IambicKeyer(var mode: IambicMode = IambicMode.B) {
    private var last: Element? = null
    private var ditMemory = false
    private var dahMemory = false
    private var squeezedDuringElement = false

    /** Called whenever a paddle goes down, including during an element. */
    fun paddlePressed(dit: Boolean, ditDown: Boolean, dahDown: Boolean) {
        if (dit) ditMemory = true else dahMemory = true
        if (ditDown && dahDown) squeezedDuringElement = true
    }

    /** Decides the next element given the paddles' current state, or null to stop. */
    fun next(ditDown: Boolean, dahDown: Boolean): Element? {
        val modeBExtra = mode == IambicMode.B && squeezedDuringElement && !(ditDown && dahDown)
        val el = when {
            ditDown && dahDown -> alternate()
            last == Element.DIT && dahMemory -> Element.DAH
            last == Element.DAH && ditMemory -> Element.DIT
            modeBExtra -> alternate()
            ditDown || ditMemory -> Element.DIT
            dahDown || dahMemory -> Element.DAH
            else -> null
        }
        ditMemory = false
        dahMemory = false
        squeezedDuringElement = ditDown && dahDown
        last = el
        return el
    }

    private fun alternate(): Element = if (last == Element.DIT) Element.DAH else Element.DIT

    fun reset() {
        last = null; ditMemory = false; dahMemory = false; squeezedDuringElement = false
    }
}
