package com.robomorse.core

object Curriculum {
    /** Koch order as used by LCWO. */
    const val KOCH_ORDER_LCWO = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X"

    /** Number of characters a brand-new learner starts with (Koch: two). */
    const val STARTING_CHARACTERS = 2

    fun learned(order: String, count: Int): List<Char> =
        order.take(count.coerceIn(0, order.length)).toList()

    fun nextToUnlock(order: String, count: Int): Char? = order.getOrNull(count)
}

/** Chance-corrected accuracy: 0 = no better than guessing among [k] options, 1 = perfect. */
fun chanceCorrected(accuracy: Double, k: Int): Double {
    if (k <= 1) return accuracy
    val chance = 1.0 / k
    return ((accuracy - chance) / (1 - chance)).coerceIn(0.0, 1.0)
}
