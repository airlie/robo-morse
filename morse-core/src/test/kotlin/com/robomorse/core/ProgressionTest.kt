package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionTest {
    private val order = Curriculum.KOCH_ORDER_LCWO

    private fun session(learned: Int = 2, start: Int = 1, stats: Map<Char, ItemStats> = emptyMap()) =
        PracticeSession(PracticeMode.KOCH, order, learned, stats, nowMs = { 1_000_000L }, random = Random(3), startGroupLength = start)

    private fun round(s: PracticeSession, right: (Char) -> Boolean): String {
        val p = s.nextPrompt()
        s.submit(p, p.map { c -> Answer(if (right(c)) c else other(s, c), 400.0) })
        return p
    }

    private fun other(s: PracticeSession, c: Char) = s.active.first { it != c }

    @Test
    fun groupsStartShortAndGrowWithAccuracy() {
        val s = session()
        assertEquals(1, s.nextPrompt().length)
        val lengths = mutableListOf<Int>()
        repeat(60) { lengths += round(s) { true }.length }
        assertEquals(MAX_GROUP_LENGTH, s.groupLength)
        // One step at a time.
        lengths.zipWithNext().forEach { (x, y) -> assertTrue(y <= maxOf(x, 4) + 1, lengths.toString()) }
    }

    @Test
    fun groupsShortenWhenStruggling() {
        val s = session(start = 3)
        repeat(4) { round(s) { false } }
        assertTrue(s.groupLength < 3)
    }

    @Test
    fun unlockWaitsForLongerGroups() {
        val solid = "KM".associate { it to ItemStats(it, acc = 0.97, rtMs = 450.0, attempts = 200, correct = 194) }
        val s = session(stats = solid)
        // 50 right answers at length 1 or 2 isn't enough while groups are still short.
        while (s.groupLength < UNLOCK_MIN_GROUP_LENGTH) {
            assertFalse(s.unlockReady())
            round(s) { true }
        }
        repeat(20) { round(s) { true } }
        assertTrue(s.unlockReady())
    }

    @Test
    fun newCharacterDropsToSinglesAndIsFocused() {
        val s = session(start = MAX_GROUP_LENGTH)
        val c = s.unlockNext()!!
        assertEquals(1, s.groupLength)
        val picks = (1..NEW_FOCUS_PICKS).map { s.nextPrompt().single() }
        assertTrue(picks.count { it == c } >= NEW_FOCUS_PICKS / 4, picks.toString())
    }

    @Test
    fun repeatedMixUpsTriggerAFocusPair() {
        val s = session(learned = 4, start = 1)
        assertNull(s.focusPairReady())
        s.submit("K", listOf(Answer('M', 400.0)))
        s.submit("M", listOf(Answer('K', 400.0)))
        assertNull(s.focusPairReady())
        s.submit("K", listOf(Answer('M', 400.0)))
        assertEquals('K' to 'M', s.focusPairReady())
        s.focusPairStarted()
        assertNull(s.focusPairReady())
    }

    @Test
    fun sideDrillDoesNotCountTowardProgress() {
        val s = session(start = 2)
        repeat(40) { s.submit("K", listOf(Answer('K', 400.0)), progress = false) }
        assertEquals(2, s.groupLength)
        assertEquals(0.0, s.rollingAccuracy)
        assertEquals(40, s.attemptCount)
    }

    @Test
    fun pairDrillAlternatesAndEnds() {
        val d = PairDrill('K', 'M', Random(1))
        val seq = (1..30).map { d.next() }
        assertTrue(seq.all { it == 'K' || it == 'M' })
        seq.windowed(3).forEach { assertFalse(it.toSet().size == 1, seq.toString()) }
        val e = PairDrill('K', 'M', Random(1))
        repeat(7) { e.record(true) }
        assertFalse(e.finished)
        e.record(false)
        assertTrue(e.passed)
        val f = PairDrill('K', 'M')
        repeat(PairDrill.MAX_ITEMS) { f.record(it % 2 == 0) }
        assertTrue(f.finished)
        assertFalse(f.passed)
        assertNotNull(f)
    }
}
