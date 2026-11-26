package com.robomorse.core

/**
 * Timing for sending Morse at a character speed [charWpm] with optional Farnsworth
 * spacing giving a slower effective speed [effWpm].
 *
 * Speeds use the PARIS standard (50 dit units per word): dit = 1200 / wpm ms.
 * Farnsworth spacing follows the ARRL formula: characters stay at [charWpm], and the
 * extra time needed to reach [effWpm] is spread over the gaps between characters and
 * between words (3/19 and 7/19 of the total delay).
 */
data class MorseTiming(val charWpm: Double, val effWpm: Double = charWpm) {
    init {
        require(charWpm > 0 && effWpm > 0) { "Speeds must be positive" }
    }

    val ditMs: Double get() = 1200.0 / charWpm
    val dahMs: Double get() = 3 * ditMs
    val elementGapMs: Double get() = ditMs

    private val farnsworth: Boolean get() = effWpm < charWpm

    /** Gap between characters. */
    val charGapMs: Double
        get() = if (!farnsworth) 3 * ditMs else 1000.0 * 3 * totalDelaySeconds / 19

    /** Gap between words. */
    val wordGapMs: Double
        get() = if (!farnsworth) 7 * ditMs else 1000.0 * 7 * totalDelaySeconds / 19

    private val totalDelaySeconds: Double
        get() = (60 * charWpm - 37.2 * effWpm) / (charWpm * effWpm)

    /** Duration of a single character's sound, excluding surrounding gaps. */
    fun characterMs(c: Char): Double {
        val p = MorseCode.patternOf(c) ?: return 0.0
        val marks = p.sumOf { if (it == '.') ditMs else dahMs }
        return marks + (p.length - 1) * elementGapMs
    }
}

/** One tone (on=true) or silence (on=false) segment. */
data class Segment(val on: Boolean, val durationMs: Double)

/**
 * The rendered sound of a piece of text: tone/silence segments, plus the time (from
 * the start) at which each sent character's sound ends. Response times are measured
 * from these end times.
 */
data class RenderedText(
    val segments: List<Segment>,
    val chars: List<Char>,
    val charEndMs: List<Double>,
    val totalMs: Double,
)

object MorseRenderer {
    /**
     * Renders [text] (spaces separate words; unsupported characters are skipped).
     * [leadInMs] silence is added at the start so the audio device can settle.
     */
    fun render(text: String, timing: MorseTiming, leadInMs: Double = 100.0): RenderedText {
        val segments = mutableListOf<Segment>()
        val chars = mutableListOf<Char>()
        val ends = mutableListOf<Double>()
        var t = 0.0
        fun add(on: Boolean, ms: Double) {
            if (ms <= 0) return
            segments += Segment(on, ms)
            t += ms
        }
        add(false, leadInMs)
        val words = text.uppercase().split(' ').filter { w -> w.any(MorseCode::isSupported) }
        words.forEachIndexed { wi, word ->
            if (wi > 0) add(false, timing.wordGapMs)
            val wordChars = word.filter(MorseCode::isSupported)
            wordChars.forEachIndexed { ci, c ->
                if (ci > 0) add(false, timing.charGapMs)
                val pattern = MorseCode.patternOf(c)!!
                pattern.forEachIndexed { ei, el ->
                    if (ei > 0) add(false, timing.elementGapMs)
                    add(true, if (el == '.') timing.ditMs else timing.dahMs)
                }
                chars += c
                ends += t
            }
        }
        return RenderedText(segments, chars, ends, t)
    }
}
