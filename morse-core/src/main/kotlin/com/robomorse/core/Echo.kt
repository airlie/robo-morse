package com.robomorse.core

import kotlin.random.Random

/** Echo practice levels, from single characters up to callsigns. */
enum class EchoLevel(val label: String, val description: String) {
    LETTERS("Letters", "One character at a time"),
    PAIRS("Pairs", "Two characters"),
    GROUPS("Groups", "Three or four characters"),
    WORDS("Words", "Common words and ham abbreviations"),
    CALLSIGNS("Callsigns", "Realistic amateur callsigns"),
}

object EchoContent {
    /** Everyday English plus the abbreviations heard in nearly every CW contact. */
    val words: List<String> = listOf(
        // ham
        "CQ", "DE", "K", "TU", "UR", "RST", "NAME", "QTH", "WX", "FB", "ES", "TNX", "PSE", "AGN",
        "HR", "ABT", "RIG", "ANT", "PWR", "GM", "GA", "GE", "OM", "YL", "QSL", "QRZ", "QRS", "QRM",
        "73", "5NN", "599", "BK", "CUL", "HW", "CPY", "SRI", "FER", "WID",
        // English
        "THE", "AND", "FOR", "YOU", "ARE", "NOT", "BUT", "ALL", "CAN", "WAS", "ONE", "OUR", "OUT",
        "NOW", "NEW", "GOOD", "HERE", "NICE", "TEST", "SUN", "RAIN", "COLD", "WARM", "HOME", "WORK",
        "TIME", "MAN", "SEE", "MORE", "WELL", "TOO", "SO", "IT", "IS", "ME", "MY", "NO", "OK",
    )

    private val usPrefixes = listOf("K", "W", "N", "AA", "AB", "AC", "KA", "KB", "KC", "KD", "KE", "KF", "KG", "KI", "KJ", "KK", "KN", "NA", "NB", "WA", "WB", "WD")
    private val dxPrefixes = listOf("G", "M", "DL", "F", "EA", "I", "VE", "VA", "JA", "VK", "ZL", "PY", "LU", "ON", "PA", "OH", "SM", "LA", "OK", "SP", "HA", "YO", "9A", "S5", "EI")

    private val letters = ('A'..'Z').toSet()
    private val digits = ('0'..'9').toSet()

    /** Words that use only characters from [chars]. */
    fun wordsFor(chars: Set<Char>): List<String> = words.filter { w -> w.all { it in chars } }

    /** Whether [level] has anything to send with [chars]. */
    fun available(level: EchoLevel, chars: Set<Char>): Boolean = when (level) {
        EchoLevel.LETTERS -> chars.isNotEmpty()
        EchoLevel.PAIRS, EchoLevel.GROUPS -> chars.size >= 2
        EchoLevel.WORDS -> wordsFor(chars).size >= 3
        EchoLevel.CALLSIGNS -> chars.containsAll(letters) && chars.containsAll(digits)
    }

    fun next(level: EchoLevel, chars: Set<Char>, random: Random = Random.Default): String {
        val pool = chars.toList()
        fun pick(n: Int) = (1..n).map { pool[random.nextInt(pool.size)] }.joinToString("")
        return when (level) {
            EchoLevel.LETTERS -> pick(1)
            EchoLevel.PAIRS -> pick(2)
            EchoLevel.GROUPS -> pick(random.nextInt(3, 5))
            EchoLevel.WORDS -> wordsFor(chars).let { it[random.nextInt(it.size)] }
            EchoLevel.CALLSIGNS -> callsign(random)
        }
    }

    /** A plausible callsign: mostly US formats (1x2 to 2x3), some common DX prefixes. */
    fun callsign(random: Random = Random.Default): String {
        val prefixes = if (random.nextInt(4) == 0) dxPrefixes else usPrefixes
        val prefix = prefixes[random.nextInt(prefixes.size)]
        val digit = random.nextInt(10)
        val suffixLen = if (prefix.length == 1) random.nextInt(2, 4) else random.nextInt(1, 4)
        val suffix = (1..suffixLen).map { 'A' + random.nextInt(26) }.joinToString("")
        return "$prefix$digit$suffix"
    }
}

/**
 * Moves the learner through Echo levels: up after 8 of the last 10 correct with a
 * decent rhythm, down after fewer than 4 of the last 8.
 */
class EchoProgress(level: EchoLevel, private val chars: Set<Char>) {
    var level: EchoLevel = level
        private set
    private val recent = ArrayDeque<Pair<Boolean, Int?>>()

    val recentCorrect: Int get() = recent.count { it.first }
    val recentCount: Int get() = recent.size

    enum class Change { UP, DOWN }

    fun setLevel(l: EchoLevel) {
        level = l
        recent.clear()
    }

    /** Records an attempt; returns a level change if one happened. */
    fun record(correct: Boolean, rhythm: Int?, auto: Boolean): Change? {
        recent.addLast(correct to rhythm)
        while (recent.size > 10) recent.removeFirst()
        if (!auto) return null
        val rhythms = recent.mapNotNull { it.second }
        val rhythmOk = rhythms.isEmpty() || rhythms.average() >= 60
        if (recent.size >= 10 && recentCorrect >= 8 && rhythmOk) {
            val up = EchoLevel.entries.drop(level.ordinal + 1).firstOrNull { EchoContent.available(it, chars) }
            if (up != null) { setLevel(up); return Change.UP }
        }
        val last8 = recent.takeLast(8)
        if (last8.size == 8 && last8.count { it.first } < 4) {
            val down = EchoLevel.entries.take(level.ordinal).lastOrNull { EchoContent.available(it, chars) }
            if (down != null) { setLevel(down); return Change.DOWN }
        }
        return null
    }
}
