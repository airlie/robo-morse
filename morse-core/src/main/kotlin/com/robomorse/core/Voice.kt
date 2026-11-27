package com.robomorse.core

import kotlin.random.Random

/**
 * Voice answers: turning what a speech recognizer heard into characters. Phonetic
 * words ("Kilo") are the reliable way; letter names ("kay") are accepted too, along
 * with the spellings recognizers commonly produce for them.
 */
object VoiceAnswers {
    val phonetic: Map<Char, String> = mapOf(
        'A' to "Alfa", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Echo", 'F' to "Foxtrot",
        'G' to "Golf", 'H' to "Hotel", 'I' to "India", 'J' to "Juliett", 'K' to "Kilo", 'L' to "Lima",
        'M' to "Mike", 'N' to "November", 'O' to "Oscar", 'P' to "Papa", 'Q' to "Quebec", 'R' to "Romeo",
        'S' to "Sierra", 'T' to "Tango", 'U' to "Uniform", 'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray",
        'Y' to "Yankee", 'Z' to "Zulu", '0' to "Zero", '1' to "One", '2' to "Two", '3' to "Three",
        '4' to "Four", '5' to "Five", '6' to "Six", '7' to "Seven", '8' to "Eight", '9' to "Nine",
    )

    /** Every spoken form we accept for each character, lower case. */
    private val spoken: Map<String, Char> = buildMap {
        phonetic.forEach { (ch, word) -> put(word.lowercase().replace("-", ""), ch) }
        val extra = mapOf(
            'A' to "alpha ay eh", 'B' to "bee be", 'C' to "see sea cee", 'D' to "dee",
            'E' to "ee", 'F' to "ef eff", 'G' to "gee jee", 'H' to "aitch haitch", 'I' to "eye aye",
            'J' to "jay juliet", 'K' to "kay okay killo keelo", 'L' to "el ell", 'M' to "em", 'N' to "en",
            'O' to "oh", 'P' to "pee pea", 'Q' to "queue cue kew", 'R' to "are ar our", 'S' to "ess es",
            'T' to "tee tea", 'U' to "you yu ewe", 'V' to "vee", 'W' to "doubleyou whisky",
            'X' to "ex xray", 'Y' to "why wye", 'Z' to "zed zee",
            '0' to "nought", '1' to "won", '2' to "to too", '3' to "tree", '4' to "for fore fower",
            '5' to "fife", '8' to "ate", '9' to "niner",
        )
        extra.forEach { (ch, words) -> words.split(' ').forEach { put(it, ch) } }
    }

    private fun tokens(heard: String): List<String> =
        heard.lowercase()
            .replace("x-ray", "xray").replace("x ray", "xray")
            .replace("double you", "doubleyou").replace("double u", "doubleyou")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    /**
     * What [heard] spells out: each phonetic word or letter name becomes its character,
     * and anything else (a word like "the", or "KM" already as letters) is taken as written.
     */
    fun parse(heard: String): String = tokens(heard).joinToString("") { t ->
        spoken[t]?.toString() ?: t.uppercase()
    }

    /**
     * Whether any of the recognizer's best guesses is [target]. A guess matches if it
     * spells the target out, or (for words) if it is the word itself.
     */
    fun matches(target: String, guesses: List<String>): Boolean {
        val want = target.uppercase().filter { it.isLetterOrDigit() }
        return guesses.take(3).any { g ->
            parse(g) == want || g.uppercase().filter { it.isLetterOrDigit() } == want
        }
    }

    /** What to show as "heard": the top guess, spelled out. */
    fun heardAs(guesses: List<String>): String = guesses.firstOrNull()?.let { parse(it) } ?: ""

    /** Phonetic words the recognizer should expect. */
    val vocabulary: List<String> = phonetic.values.toList()
}

enum class VoiceLevel(val label: String, val description: String) {
    CHARACTERS("Characters", "One character at a time"),
    GROUPS("Groups", "Two or three characters"),
    WORDS("Words", "Short words: say the word"),
}

/**
 * Runs a "say it" drill: picks characters, groups or words from [chars], brings misses
 * back three items later, and moves up after 8 of the last 10 right (down after fewer
 * than 4 of the last 8).
 */
class VoiceDrill(level: VoiceLevel, private val chars: Set<Char>, private val random: Random = Random.Default) {
    var level: VoiceLevel = level
        private set
    private val recent = ArrayDeque<Boolean>()
    private val lastItems = ArrayDeque<String>()
    private val retries = mutableListOf<Pair<String, Int>>()

    enum class Change { UP, DOWN }

    companion object {
        fun words(chars: Set<Char>) = CopyContent.words.filter { w -> w.length in 2..5 && w.all { it in chars } }

        fun available(level: VoiceLevel, chars: Set<Char>): Boolean = when (level) {
            VoiceLevel.CHARACTERS -> chars.isNotEmpty()
            VoiceLevel.GROUPS -> chars.size >= 2
            VoiceLevel.WORDS -> words(chars).size >= 10
        }
    }

    fun setLevel(l: VoiceLevel) {
        level = l
        recent.clear()
        retries.clear()
    }

    private fun fresh(): String {
        val list = chars.toList()
        return when (level) {
            VoiceLevel.CHARACTERS -> list[random.nextInt(list.size)].toString()
            VoiceLevel.GROUPS -> (1..random.nextInt(2, 4)).map { list[random.nextInt(list.size)] }.joinToString("")
            VoiceLevel.WORDS -> words(chars).let { it[random.nextInt(it.size)] }
        }
    }

    fun next(): String {
        val due = retries.indexOfFirst { it.second <= 0 }
        val item = if (due >= 0) {
            retries.removeAt(due).first
        } else {
            // Avoid an immediate repeat; with only a couple of characters, allow it eventually.
            var pick: String
            var tries = 0
            do {
                pick = fresh()
                tries++
            } while (tries < 10 && pick in lastItems.takeLast(if (chars.size <= 3) 1 else 4))
            pick
        }
        for (i in retries.indices) retries[i] = retries[i].first to retries[i].second - 1
        lastItems.addLast(item)
        while (lastItems.size > 6) lastItems.removeFirst()
        return item
    }

    fun record(target: String, correct: Boolean): Change? {
        if (!correct && retries.none { it.first == target }) retries += target to 3
        recent.addLast(correct)
        while (recent.size > 10) recent.removeFirst()
        if (recent.size >= 10 && recent.count { it } >= 8) {
            val up = VoiceLevel.entries.drop(level.ordinal + 1).firstOrNull { available(it, chars) }
            if (up != null) { setLevel(up); return Change.UP }
        }
        val last8 = recent.takeLast(8)
        if (last8.size == 8 && last8.count { it } < 4) {
            val down = VoiceLevel.entries.take(level.ordinal).lastOrNull { available(it, chars) }
            if (down != null) { setLevel(down); return Change.DOWN }
        }
        return null
    }
}
