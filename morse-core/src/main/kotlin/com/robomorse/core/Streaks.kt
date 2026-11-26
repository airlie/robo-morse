package com.robomorse.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class StreakInfo(val days: Int, val freezesUsedThisMonth: Int, val practicedToday: Boolean)

/**
 * Forgiving streaks: a missed day is covered automatically by a "freeze", up to
 * [freezesPerMonth] per calendar month. Two missed days in a row always end the streak.
 */
object Streaks {
    fun compute(practiceDays: Set<LocalDate>, today: LocalDate, freezesPerMonth: Int = 2): StreakInfo {
        val practicedToday = today in practiceDays
        var day = if (practicedToday) today else today.minusDays(1)
        val used = HashMap<YearMonth, Int>()
        var streak = 0
        var lastWasFreeze = false
        while (true) {
            if (day in practiceDays) {
                streak++
                lastWasFreeze = false
            } else {
                val ym = YearMonth.from(day)
                val n = used.getOrDefault(ym, 0)
                // A freeze only bridges a gap: there must be earlier practice to connect to.
                if (lastWasFreeze || n >= freezesPerMonth || streak == 0 || !practiceDays.any { it < day }) break
                used[ym] = n + 1
                lastWasFreeze = true
            }
            day = day.minusDays(1)
        }
        return StreakInfo(streak, used.getOrDefault(YearMonth.from(today), 0), practicedToday)
    }

    /** Distinct practice days in the Monday-start week containing [today]. */
    fun daysThisWeek(practiceDays: Set<LocalDate>, today: LocalDate): Int {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return practiceDays.count { !it.isBefore(monday) && !it.isAfter(today) }
    }
}
