package com.robomorse.core

import kotlin.random.Random

/**
 * A short focused drill on two characters you keep mixing up: one character at a time,
 * always one of the two, so every answer is a straight choice between them. Contrasting
 * the two directly, with feedback, is how confusable sounds are best told apart.
 *
 * It ends once [PASS] of the last [WINDOW] answers are right, or after [MAX_ITEMS].
 */
class PairDrill(val a: Char, val b: Char, private val random: Random = Random.Default) {
    private val recent = ArrayDeque<Boolean>()
    private val history = mutableListOf<Char>()

    var items: Int = 0
        private set
    var correct: Int = 0
        private set

    /** Last [WINDOW] answers were good enough. */
    val passed: Boolean get() = recent.size >= WINDOW && recent.count { it } >= PASS
    val finished: Boolean get() = passed || items >= MAX_ITEMS

    /** The next character, never the same one three times running. */
    fun next(): Char {
        val n = history.size
        val pick = if (n >= 2 && history[n - 1] == history[n - 2]) {
            if (history[n - 1] == a) b else a
        } else if (random.nextBoolean()) a else b
        history += pick
        return pick
    }

    fun record(right: Boolean) {
        items++
        if (right) correct++
        recent.addLast(right)
        while (recent.size > WINDOW) recent.removeFirst()
    }

    companion object {
        const val WINDOW = 8
        const val PASS = 7
        const val MAX_ITEMS = 24

        /** Mixed up at least this often, and in at least this share of answers on the two, to earn a warm-up drill. */
        const val WARMUP_MIN_MIXUPS = 4
        const val WARMUP_MIN_RATE = 0.10

        /**
         * The pair most often mixed up (either way round) in [recent] answers, as
         * (sent, typed) pairs, if it is bad enough to be worth a warm-up drill: at least
         * [WARMUP_MIN_MIXUPS] mix-ups, making up at least [WARMUP_MIN_RATE] of the answers
         * on those two characters. Only pairs of characters in [learned] count.
         */
        fun worstPair(recent: List<Pair<Char, Char?>>, learned: Set<Char>): Pair<Char, Char>? {
            val mixups = recent.mapNotNull { (sent, typed) ->
                if (typed == null || typed == sent || sent !in learned || typed !in learned) null
                else if (sent < typed) sent to typed else typed to sent
            }.groupingBy { it }.eachCount()
            val (pair, n) = mixups.maxByOrNull { it.value } ?: return null
            if (n < WARMUP_MIN_MIXUPS) return null
            val onPair = recent.count { it.first == pair.first || it.first == pair.second }
            return if (n.toDouble() / onPair >= WARMUP_MIN_RATE) pair else null
        }
    }
}
