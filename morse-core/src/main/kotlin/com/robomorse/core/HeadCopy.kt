package com.robomorse.core

import kotlin.random.Random

/**
 * Head copy: hear a whole word or phrase without writing anything down, then pick what
 * you heard. Choices are the closest-looking alternatives, so you have to really hear it.
 */
enum class HeadCopyLevel(val label: String, val description: String) {
    SHORT("Short words", "Two- and three-letter words"),
    WORDS("Words", "Four- and five-letter words"),
    LONG("Longer words", "Six letters and up"),
    PHRASES("Phrases", "Short phrases from a contact"),
}

data class HeadCopyItem(val target: String, val choices: List<String>)

object HeadCopyContent {
    private val longer = listOf(
        "ANTENNA", "CONTEST", "STATION", "WEATHER", "CALLING", "LISTEN", "NUMBER", "REPORT", "SUMMIT",
        "PADDLE", "AMATEUR", "BATTERY", "CHANNEL", "MESSAGE", "PLEASE", "THANKS", "AROUND", "BEFORE",
        "BETTER", "PEOPLE", "SHOULD", "LITTLE", "SIMPLE", "SPRING", "SUMMER", "WINTER", "AUTUMN",
        "SUNDAY", "FRIDAY", "MONDAY", "RECEIVER", "SIGNALS", "STRONG", "WEAKER", "MOBILE", "PORTABLE",
        "VERTICAL", "COUNTRY", "SECOND", "MINUTE", "FRIEND", "FAMILY", "GARDEN", "WINDOW", "CASTLE",
    )

    val phrases: List<String> = listOf(
        "CQ CQ DE", "TNX FER CALL", "UR RST 599", "UR RST 579", "UR RST 559", "NAME HR IS BOB",
        "NAME IS ANN", "NAME IS TOM", "QTH IS OHIO", "QTH IS IOWA", "QTH NR DENVER", "RIG IS QRP",
        "PWR 5 WATTS", "PWR 100W", "ANT IS DIPOLE", "ANT IS YAGI", "WX IS SUNNY", "WX IS RAIN", "GM OM",
        "GE OM", "TNX FER QSO", "73 ES GL", "CUL 73", "HPE CUL AGN", "PSE QRS", "PSE RPT", "FB OM",
        "VY FB", "GUD DX", "SRI QRM", "QSB HR", "TU 73", "R R TNX", "HW CPY", "ES GUD DX",
    )

    private val allWords = (CopyContent.words + longer).distinct()

    fun itemsFor(level: HeadCopyLevel, chars: Set<Char>): List<String> {
        val pool = if (level == HeadCopyLevel.PHRASES) phrases else allWords
        val usable = pool.filter { item -> item.all { it == ' ' || it in chars } }
        return when (level) {
            HeadCopyLevel.SHORT -> usable.filter { it.length in 2..3 }
            HeadCopyLevel.WORDS -> usable.filter { it.length in 4..5 }
            HeadCopyLevel.LONG -> usable.filter { it.length >= 6 }
            HeadCopyLevel.PHRASES -> usable
        }
    }

    /** Enough items for four choices and some variety. */
    fun available(level: HeadCopyLevel, chars: Set<Char>) = itemsFor(level, chars).size >= 8

    /** The three items closest to [target] (by edit distance, ties broken at random). */
    fun distractors(target: String, pool: List<String>, random: Random): List<String> =
        pool.filter { it != target }
            .shuffled(random)
            .sortedBy { editDistance(it, target) }
            .take(3)

    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/**
 * Runs head copy: misses come back three items later, and the level moves up after 8 of
 * the last 10 right and down after fewer than 4 of the last 8.
 */
class HeadCopyDrill(level: HeadCopyLevel, private val chars: Set<Char>, private val random: Random = Random.Default) {
    var level: HeadCopyLevel = level
        private set
    private val recent = ArrayDeque<Boolean>()
    private val lastItems = ArrayDeque<String>()
    private val retries = mutableListOf<Pair<String, Int>>()

    val recentCorrect: Int get() = recent.count { it }

    enum class Change { UP, DOWN }

    fun setLevel(l: HeadCopyLevel) {
        level = l
        recent.clear()
        retries.clear()
    }

    fun next(): HeadCopyItem {
        val pool = HeadCopyContent.itemsFor(level, chars)
        val due = retries.indexOfFirst { it.second <= 0 }
        val target = if (due >= 0) {
            retries.removeAt(due).first
        } else {
            val fresh = pool.filter { it !in lastItems }.ifEmpty { pool }
            fresh[random.nextInt(fresh.size)]
        }
        for (i in retries.indices) retries[i] = retries[i].first to retries[i].second - 1
        lastItems.addLast(target)
        while (lastItems.size > 6) lastItems.removeFirst()
        val choices = (HeadCopyContent.distractors(target, pool, random) + target).shuffled(random)
        return HeadCopyItem(target, choices)
    }

    fun record(target: String, correct: Boolean): Change? {
        if (!correct && retries.none { it.first == target }) retries += target to 3
        recent.addLast(correct)
        while (recent.size > 10) recent.removeFirst()
        if (recent.size >= 10 && recentCorrect >= 8) {
            val up = HeadCopyLevel.entries.drop(level.ordinal + 1).firstOrNull { HeadCopyContent.available(it, chars) }
            if (up != null) { setLevel(up); return Change.UP }
        }
        val last8 = recent.takeLast(8)
        if (last8.size == 8 && last8.count { it } < 4) {
            val down = HeadCopyLevel.entries.take(level.ordinal).lastOrNull { HeadCopyContent.available(it, chars) }
            if (down != null) { setLevel(down); return Change.DOWN }
        }
        return null
    }
}
