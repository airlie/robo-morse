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
    }
}
