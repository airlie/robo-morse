package com.robomorse.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StreaksTest {
    private val today = LocalDate.of(2026, 11, 26)
    private fun days(vararg back: Long) = back.map { today.minusDays(it) }.toSet()

    @Test
    fun consecutiveDays() {
        assertEquals(3, Streaks.compute(days(0, 1, 2), today).days)
    }

    @Test
    fun notYetPracticedTodayKeepsYesterdaysStreak() {
        val s = Streaks.compute(days(1, 2), today)
        assertEquals(2, s.days)
        assertEquals(false, s.practicedToday)
    }

    @Test
    fun singleMissedDayIsFrozen() {
        val s = Streaks.compute(days(0, 2, 3), today)
        assertEquals(3, s.days)
        assertEquals(1, s.freezesUsedThisMonth)
    }

    @Test
    fun twoMissedDaysBreakTheStreak() {
        assertEquals(1, Streaks.compute(days(0, 3, 4), today).days)
    }

    @Test
    fun onlyTwoFreezesPerMonth() {
        // Practice every other day: gaps on 1, 3, 5 days ago; only two may be frozen.
        assertEquals(3, Streaks.compute(days(0, 2, 4, 6), today).days)
    }

    @Test
    fun weekCount() {
        // 2026-11-26 is a Thursday; Monday is 3 days back.
        assertEquals(2, Streaks.daysThisWeek(days(0, 3, 4), today))
    }
}
