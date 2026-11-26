package com.robomorse.core

import kotlin.math.abs
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

/** One part of the rhythm score, 0–100, with a short reading for the learner. */
data class FistPart(val name: String, val score: Int, val reading: String)

/** How even a sender's rhythm is, compared with ideal timing at their own speed. */
data class FistReport(
    val estWpm: Double,
    val ditMs: Double,
    val dahDitRatio: Double?,
    val elementGapRatio: Double?,
    val charGapRatio: Double?,
    val wordGapRatio: Double?,
    /** Spread of dit, dah and element-gap lengths after allowing for touch timing noise. */
    val variability: Double,
    val score: Int,
    val marks: Int,
    val parts: List<FistPart>,
    /** The single most useful thing to work on, or praise if everything is good. */
    val tip: String,
)

object FistAnalyzer {
    /**
     * Touchscreens report timing to the nearest few milliseconds; that noise is
     * removed from the spread before judging consistency, so it isn't blamed on the sender.
     */
    const val TOUCH_NOISE_MS = 8.0

    /**
     * Analyzes [intervals]. Marks are split into dits and dahs at the midpoint
     * between the shortest and longest marks; ideal ratios are dah 3, element gap 1,
     * character gap 3, word gap 7 dit-lengths.
     *
     * The score is a weighted average of four parts. Each part has a tolerance band
     * (good operators aren't metronomes), so small deviations cost nothing and larger
     * ones cost progressively more.
     */
    fun analyze(intervals: List<KeyInterval>, noiseMs: Double = TOUCH_NOISE_MS): FistReport? {
        val marks = intervals.filter { it.on }.map { it.ms }
        if (marks.size < 3) return null
        val (dits, dahs) = split(marks)
        val dit = if (dits.isNotEmpty()) dits.average() else dahs.average() / 3
        val dahRatio = if (dits.isNotEmpty() && dahs.isNotEmpty()) dahs.average() / dits.average() else null

        val gapsMs = intervals.filter { !it.on }.map { it.ms }
        val elementGapsMs = gapsMs.filter { it / dit < 2 }
        val gaps = gapsMs.map { it / dit }
        val eg = elementGapsMs.map { it / dit }.averageOrNull()
        val cg = gaps.filter { it in 2.0..5.0 }.averageOrNull()
        val wg = gaps.filter { it > 5 && it < 20 }.averageOrNull() // longer is just a pause

        val cvs = listOf(dits, dahs, elementGapsMs).filter { it.size >= 2 }.map { cv(it, noiseMs) }
        val variability = cvs.averageOrNull() ?: 0.0

        val parts = mutableListOf<Pair<FistPart, Double>>() // part to weight
        dahRatio?.let { r ->
            val s = band(abs(r - 3), tol = 0.3, span = 1.0)
            parts += FistPart("Dah length", s, "%.1f× a dit (ideal 3)".format(r)) to 30.0
        }
        // Most letters have several elements, so most short gaps should be inside letters.
        // If almost none are, the sender is pausing so long that letters fall apart.
        val nonPauseGaps = gaps.count { it < 20 }
        val breakingApart = marks.size >= 5 && nonPauseGaps >= 4 && gaps.count { it < 2 } < nonPauseGaps * 0.25
        if (breakingApart) {
            parts += FistPart("Gaps inside letters", 20, "letters breaking apart") to 25.0
        } else eg?.let { e ->
            val s = band(abs(e - 1), tol = 0.15, span = 0.8)
            parts += FistPart("Gaps inside letters", s, "%.1f dits (ideal 1)".format(e)) to 25.0
        }
        cg?.let { g ->
            // Too short runs letters together; a little long is fine (that's Farnsworth spacing).
            val s = if (g < 3) band(3 - g, tol = 0.3, span = 1.2) else band(g - 3, tol = 1.5, span = 2.0)
            parts += FistPart("Gaps between letters", s, "%.1f dits (ideal 3)".format(g)) to 15.0
        }
        if (cvs.isNotEmpty()) {
            val s = band(variability, tol = 0.08, span = 0.3)
            parts += FistPart("Evenness", s, "%.0f%% variation".format(variability * 100)) to 30.0
        }
        // Blend the weighted average with the weakest part, so one clear problem
        // can't hide behind three good parts.
        val score = if (parts.isEmpty()) 0 else {
            val mean = parts.sumOf { it.first.score * it.second } / parts.sumOf { it.second }
            val weakest = parts.minOf { it.first.score }.toDouble()
            (0.6 * mean + 0.4 * weakest).toInt().coerceIn(0, 100)
        }

        return FistReport(
            estWpm = 1200 / dit, ditMs = dit,
            dahDitRatio = dahRatio, elementGapRatio = eg, charGapRatio = cg, wordGapRatio = wg,
            variability = variability, score = score, marks = marks.size,
            parts = parts.map { it.first },
            tip = if (breakingApart) "Your letters are breaking apart: keep the dits and dahs of each letter close together, then pause between letters."
            else tip(dahRatio, eg, cg, parts.map { it.first }),
        )
    }

    /** 100 inside the tolerance, falling linearly to 0 over [span] beyond it. */
    private fun band(err: Double, tol: Double, span: Double): Int =
        (100 * (1 - ((err - tol).coerceAtLeast(0.0) / span))).toInt().coerceIn(0, 100)

    private fun tip(dah: Double?, eg: Double?, cg: Double?, parts: List<FistPart>): String {
        val worst = parts.minByOrNull { it.score } ?: return "Keep sending: a few more letters are needed for a score."
        if (worst.score >= 85) return "Nice, even rhythm. Try a little faster, or longer words."
        return when (worst.name) {
            "Dah length" -> if ((dah ?: 3.0) < 3) "Hold your dahs longer: they're %.1f× a dit; aim for 3×.".format(dah)
            else "Shorten your dahs a little: they're %.1f× a dit; aim for 3×.".format(dah)
            "Gaps inside letters" -> if ((eg ?: 1.0) > 1) "Close up the gaps inside each letter: lift and press again quickly."
            else "Leave a tiny pause between the dits and dahs of a letter."
            "Gaps between letters" -> if ((cg ?: 3.0) < 3) "Pause a little longer between letters so they don't run together."
            else "Bring letters a bit closer together."
            else -> "Aim for evenness: every dit the same length, and every dah the same length."
        }
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

    /** Coefficient of variation with measurement noise [noiseMs] removed from the spread. */
    private fun cv(xs: List<Double>, noiseMs: Double): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        val variance = xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        val sd = sqrt((variance - noiseMs * noiseMs).coerceAtLeast(0.0))
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
