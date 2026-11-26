package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefresherTest {
    private fun session(learned: Int, seed: Int = 3) = PracticeSession(
        PracticeMode.REFRESH, Curriculum.KOCH_ORDER_LCWO, learned, emptyMap(),
        nowMs = { 1_000_000L }, random = Random(seed),
    )

    private fun round(s: PracticeSession, correct: Boolean = true): List<Char> {
        val p = s.nextPrompt()
        assertTrue(p.all { it in s.active }, "$p outside ${s.active}")
        s.submit(p, p.map { Answer(if (correct) it else 'X', 500.0) })
        return s.refresherAdvance()
    }

    @Test
    fun startsWithTheFirstTwoCharactersInShortGroups() {
        val s = session(learned = 10)
        assertEquals(listOf('K', 'M'), s.active)
        repeat(50) { assertTrue(s.nextPrompt().length in 1..2) }
    }

    @Test
    fun bringsCharactersBackTwoAtATimeUntilComplete() {
        val s = session(learned = 7)
        val returned = mutableListOf<Char>()
        var rounds = 0
        while (!s.refresherComplete && rounds < 200) {
            returned += round(s)
            rounds++
        }
        assertTrue(s.refresherComplete)
        assertEquals("URESN".toList(), returned)
        assertEquals(7, s.active.size)
    }

    @Test
    fun staysPutWhileStruggling() {
        val s = session(learned = 10)
        repeat(30) { assertTrue(round(s, correct = false).isEmpty()) }
        assertEquals(2, s.active.size)
        assertFalse(s.refresherComplete)
    }

    @Test
    fun neverOffersAnUnlock() {
        val s = session(learned = 4)
        repeat(60) { round(s) }
        assertFalse(s.unlockReady())
    }

    @Test
    fun kochSessionsUseEveryLearnedCharacter() {
        val s = PracticeSession(PracticeMode.KOCH, Curriculum.KOCH_ORDER_LCWO, 5, emptyMap())
        assertEquals(5, s.active.size)
        s.unlockNext()
        assertEquals(6, s.active.size)
    }
}
