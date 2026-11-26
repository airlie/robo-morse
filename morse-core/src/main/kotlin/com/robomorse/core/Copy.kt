package com.robomorse.core

import kotlin.random.Random

/** Copy practice levels: whole words and callsigns heard, then typed. */
enum class CopyLevel(val label: String, val description: String) {
    SHORT_WORDS("Short words", "Two- and three-letter words"),
    WORDS("Words", "Common words and ham abbreviations"),
    CALLSIGNS("Callsigns", "Realistic amateur callsigns"),
    MIXED("Mixed", "Words and callsigns together"),
}

object CopyContent {
    /** Echo's words plus more of the commonest short English words. */
    val words: List<String> = (
        EchoContent.words + listOf(
            "A", "I", "AM", "AN", "AS", "AT", "BE", "BY", "DO", "GO", "HE", "IF", "IN", "OF", "ON", "OR",
            "TO", "UP", "US", "WE", "ANY", "ASK", "BIG", "BOY", "DAY", "DID", "END", "FEW", "GET", "HAD",
            "HAS", "HER", "HIM", "HIS", "HOW", "ITS", "LET", "MAY", "OLD", "OWN", "PUT", "RUN", "SAY", "SET",
            "SHE", "SIT", "TEN", "TOP", "TRY", "TWO", "USE", "WAY", "WHO", "WHY", "YES", "YET", "ALSO",
            "BACK", "BEST", "CALL", "COME", "DOWN", "EACH", "EVEN", "FIND", "FROM", "GIVE", "HAVE", "JUST",
            "KEEP", "KNOW", "LAST", "LIKE", "LONG", "MAKE", "MANY", "MOST", "MUCH", "MUST", "NAME", "NEXT",
            "ONLY", "OVER", "PART", "SAME", "SOME", "SUCH", "TAKE", "THAN", "THAT", "THEM", "THEN", "THEY",
            "THIS", "VERY", "WANT", "WERE", "WHAT", "WHEN", "WILL", "WITH", "YEAR", "YOUR", "ABOUT", "AFTER",
            "AGAIN", "BAND", "BEAM", "CLEAR", "COPY", "DIPOLE", "FIRST", "GREAT", "KEY", "LOUD", "MORSE",
            "NIGHT", "OTHER", "RADIO", "RIGHT", "SIGNAL", "SPEED", "STILL", "THERE", "THESE", "THINK",
            "WATT", "WATTS", "WHERE", "WHICH", "WORLD", "WOULD",
            // more contact shorthand
            "ES", "GL", "GUD", "HPE", "NR", "OP", "R", "RPT", "SIG", "TKS", "VY", "WKD", "XYL", "QRP",
            "QRT", "QRX", "QSB", "QSO", "QSY", "SOTA", "POTA", "DX",
        )
        ).distinct()

    /** What the shorthand in [words] means, shown after you copy it. */
    val meanings: Map<String, String> = mapOf(
        "CQ" to "calling any station", "DE" to "from (this is)", "K" to "over, go ahead", "TU" to "thank you",
        "UR" to "your / you're", "RST" to "signal report: readability, strength, tone", "QTH" to "location",
        "WX" to "weather", "FB" to "fine business (great)", "ES" to "and", "TNX" to "thanks", "TKS" to "thanks",
        "PSE" to "please", "AGN" to "again", "HR" to "here", "ABT" to "about", "RIG" to "radio",
        "ANT" to "antenna", "PWR" to "power", "GM" to "good morning", "GA" to "good afternoon / go ahead",
        "GE" to "good evening", "OM" to "old man (any male operator)", "YL" to "young lady (any female operator)",
        "XYL" to "wife", "QSL" to "I confirm receipt", "QRZ" to "who is calling me?", "QRS" to "send slower",
        "QRM" to "interference from other stations", "QRP" to "low power", "QRT" to "stopping transmitting",
        "QRX" to "wait", "QSB" to "fading", "QSO" to "a contact", "QSY" to "change frequency",
        "73" to "best regards", "5NN" to "599, the usual contest report", "599" to "perfect signal report",
        "BK" to "back to you", "CUL" to "see you later", "HW" to "how (did you copy)?", "CPY" to "copy",
        "SRI" to "sorry", "FER" to "for", "WID" to "with", "GL" to "good luck", "GUD" to "good",
        "HPE" to "hope", "NR" to "number", "OP" to "operator", "R" to "received, roger", "RPT" to "repeat / report",
        "SIG" to "signal", "VY" to "very", "WKD" to "worked", "SOTA" to "Summits on the Air",
        "POTA" to "Parks on the Air", "DX" to "distant station",
    )

    private val letters = ('A'..'Z').toSet()
    private val digits = ('0'..'9').toSet()

    fun wordsFor(level: CopyLevel, chars: Set<Char>): List<String> {
        val all = words.filter { w -> w.all { it in chars } }
        return if (level == CopyLevel.SHORT_WORDS) all.filter { it.length in 2..3 } else all
    }

    private fun callsignsAvailable(chars: Set<Char>) = chars.containsAll(letters) && chars.containsAll(digits)

    /** Whether [level] has enough material using only [chars]. */
    fun available(level: CopyLevel, chars: Set<Char>): Boolean = when (level) {
        CopyLevel.SHORT_WORDS -> wordsFor(level, chars).size >= 5
        CopyLevel.WORDS -> wordsFor(level, chars).size >= 10
        CopyLevel.CALLSIGNS -> callsignsAvailable(chars)
        CopyLevel.MIXED -> callsignsAvailable(chars) && wordsFor(CopyLevel.WORDS, chars).size >= 10
    }

    fun next(level: CopyLevel, chars: Set<Char>, random: Random = Random.Default): String = when (level) {
        CopyLevel.SHORT_WORDS, CopyLevel.WORDS -> wordsFor(level, chars).let { it[random.nextInt(it.size)] }
        CopyLevel.CALLSIGNS -> EchoContent.callsign(random)
        CopyLevel.MIXED ->
            if (random.nextBoolean()) EchoContent.callsign(random)
            else wordsFor(CopyLevel.WORDS, chars).let { it[random.nextInt(it.size)] }
    }
}

/** One copied item, scored position by position. */
data class CopyResult(val target: String, val typed: String, val correct: Boolean) {
    /** Whether each character of [target] was typed correctly. */
    val perChar: List<Boolean> get() = target.indices.map { typed.getOrNull(it) == target[it] }
}

/**
 * Runs a copy drill: picks words or callsigns, brings misses back a few items later,
 * and moves between levels (up after 8 of the last 10 right, down after fewer than
 * 4 of the last 8).
 */
class CopyDrill(level: CopyLevel, private val chars: Set<Char>, private val random: Random = Random.Default) {
    var level: CopyLevel = level
        private set
    private val recent = ArrayDeque<Boolean>()
    private val lastItems = ArrayDeque<String>()
    /** Missed items and how many more items to wait before they come back. */
    private val retries = mutableListOf<Pair<String, Int>>()

    val recentCorrect: Int get() = recent.count { it }
    val recentCount: Int get() = recent.size

    enum class Change { UP, DOWN }

    fun setLevel(l: CopyLevel) {
        level = l
        recent.clear()
        retries.clear()
    }

    fun next(): String {
        val due = retries.indexOfFirst { it.second <= 0 }
        val item = if (due >= 0) {
            retries.removeAt(due).first
        } else {
            var pick: String
            var tries = 0
            do {
                pick = CopyContent.next(level, chars, random)
                tries++
            } while (tries < 10 && pick in lastItems)
            pick
        }
        for (i in retries.indices) retries[i] = retries[i].first to retries[i].second - 1
        lastItems.addLast(item)
        while (lastItems.size > 6) lastItems.removeFirst()
        return item
    }

    /** Scores an answer; a miss comes back three items later. Returns a level change if one happened. */
    fun record(target: String, typed: String, auto: Boolean = true): Pair<CopyResult, Change?> {
        val result = CopyResult(target, typed, typed == target)
        if (!result.correct && retries.none { it.first == target }) retries += target to 3
        recent.addLast(result.correct)
        while (recent.size > 10) recent.removeFirst()
        if (!auto) return result to null
        if (recent.size >= 10 && recentCorrect >= 8) {
            val up = CopyLevel.entries.drop(level.ordinal + 1).firstOrNull { CopyContent.available(it, chars) }
            if (up != null) { setLevel(up); return result to Change.UP }
        }
        val last8 = recent.takeLast(8)
        if (last8.size == 8 && last8.count { it } < 4) {
            val down = CopyLevel.entries.take(level.ordinal).lastOrNull { CopyContent.available(it, chars) }
            if (down != null) { setLevel(down); return result to Change.DOWN }
        }
        return result to null
    }
}
