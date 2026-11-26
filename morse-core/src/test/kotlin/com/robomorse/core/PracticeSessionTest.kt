package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PracticeSessionTest {
    private val order = Curriculum.KOCH_ORDER_LCWO

    private fun session(learned: Int = 2, stats: Map<Char, ItemStats> = emptyMap(), seed: Int = 1, mode: PracticeMode = PracticeMode.KOCH) =
        PracticeSession(mode, order, learned, stats, nowMs = { 1_000_000L }, random = Random(seed))

    private fun answerAll(s: PracticeSession, rounds: Int, rt: Double = 400.0, correctness: (Char) -> Boolean) {
        repeat(rounds) {
            val p = s.nextPrompt()
            s.submit(p, p.map { c -> Answer(if (correctness(c)) c else 'X', rt) })
        }
    }

    @Test
    fun promptsUseOnlyLearnedCharacters() {
        val s = session(learned = 5)
        repeat(200) {
            val p = s.nextPrompt()
            assertTrue(p.length in 3..5)
            assertTrue(p.all { it in "KMURE" }, p)
        }
    }

    @Test
    fun icrPromptsAreSingleCharacters() {
        val s = session(mode = PracticeMode.ICR)
        repeat(50) { assertEquals(1, s.nextPrompt().length) }
    }

    @Test
    fun weakCharactersGetMoreWeight() {
        val strong = ItemStats('K', acc = 0.98, rtMs = 450.0, attempts = 100, correct = 98)
        val weak = ItemStats('M', acc = 0.5, rtMs = 1500.0, attempts = 100, correct = 50)
        val s = session(stats = mapOf('K' to strong, 'M' to weak))
        val w = s.weights()
        assertTrue(w.getValue('M') > w.getValue('K') * 2, w.toString())
    }

    @Test
    fun confusionsAreRecordedAndInjected() {
        val s = session(learned = 4)
        s.submit("KKK", listOf(Answer('R', 500.0), Answer('K', 500.0), Answer('K', 500.0)))
        val next = s.nextPrompt()
        assertTrue('K' in next && 'R' in next, next)
        assertEquals(1, s.finish().confusions['K' to 'R'])
    }

    @Test
    fun unlockRequiresSustainedAccuracy() {
        val s = session()
        answerAll(s, 5) { true }
        assertFalse(s.unlockReady(), "too few attempts")
        answerAll(s, 40) { true }
        assertTrue(s.unlockReady())
        assertEquals('U', s.unlockNext())
        assertFalse(s.unlockReady(), "new character has no data yet")
    }

    @Test
    fun noUnlockWhenStruggling() {
        val s = session()
        answerAll(s, 60) { it != 'M' }
        assertFalse(s.unlockReady())
    }

    @Test
    fun weakBiasRisesWhenTooEasyAndFallsWhenTooHard() {
        val easy = session()
        answerAll(easy, 30) { true }
        assertTrue(easy.weakBias > 1.0)
        val hard = session()
        answerAll(hard, 30) { false }
        assertTrue(hard.weakBias < 1.0)
    }

    @Test
    fun masteryReflectsSpeedAndAccuracy() {
        val scale = SpeedScale(450.0, 1850.0)
        val fast = ItemStats('K', acc = 0.95, rtMs = 450.0, attempts = 50)
        val slow = fast.copy(rtMs = 1850.0)
        assertTrue(LearnerModel.mastery(fast, scale) > 0.85)
        assertTrue(LearnerModel.mastery(slow, scale) < 0.6)
        assertEquals(MasteryTier.INSTANT, MasteryTier.of(0.95))
        assertEquals(MasteryTier.NEW, MasteryTier.of(0.1))
    }

    @Test
    fun finishSchedulesReview() {
        val s = session()
        answerAll(s, 20) { true }
        val summary = s.finish()
        val k = summary.finalStats.getValue('K')
        assertEquals(1.0, k.srsStabilityDays, 1e-9)
        assertEquals(1_000_000L + LearnerModel.DAY_MS, k.srsDueMs)
        assertNotNull(summary.tierChanges['K'])
    }

    @Test
    fun speedPolicy() {
        assertEquals(11, SpeedPolicy.next(10, 20, listOf(0.92, 0.95)))
        assertEquals(10, SpeedPolicy.next(10, 20, listOf(0.95)))
        assertEquals(9, SpeedPolicy.next(10, 20, listOf(0.7)))
        assertEquals(20, SpeedPolicy.next(20, 20, listOf(0.99, 0.99)))
    }

    @Test
    fun chanceCorrection() {
        assertEquals(0.0, chanceCorrected(0.5, 2), 1e-9)
        assertEquals(0.8, chanceCorrected(0.9, 2), 1e-9)
    }
}
