package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyTest {
    private val all = MorseCode.table.keys

    @Test
    fun wordsUseOnlyTheGivenCharacters() {
        val chars = Curriculum.learned(Curriculum.KOCH_ORDER_LCWO, 12).toSet()
        val words = CopyContent.wordsFor(CopyLevel.WORDS, chars)
        assertTrue(words.isNotEmpty())
        assertTrue(words.all { w -> w.all { it in chars } }, words.toString())
        assertTrue(CopyContent.wordsFor(CopyLevel.SHORT_WORDS, chars).all { it.length in 2..3 })
    }

    @Test
    fun callsignsNeedLettersAndDigits() {
        assertFalse(CopyContent.available(CopyLevel.CALLSIGNS, ('A'..'Z').toSet()))
        assertTrue(CopyContent.available(CopyLevel.CALLSIGNS, all))
        assertFalse(CopyContent.available(CopyLevel.SHORT_WORDS, "KM".toSet()))
    }

    @Test
    fun aMissComesBackThreeItemsLater() {
        val d = CopyDrill(CopyLevel.WORDS, all, Random(5))
        val first = d.next()
        d.record(first, "X".repeat(first.length))
        val next = (1..4).map { d.next().also { w -> d.record(w, w) } }
        assertEquals(first, next[3], next.toString())
    }

    @Test
    fun levelsMoveUpAndDown() {
        val d = CopyDrill(CopyLevel.SHORT_WORDS, all, Random(2))
        var change: CopyDrill.Change? = null
        repeat(10) { val w = d.next(); change = d.record(w, w).second ?: change }
        assertEquals(CopyDrill.Change.UP, change)
        assertEquals(CopyLevel.WORDS, d.level)
        change = null
        repeat(8) { val w = d.next(); change = d.record(w, "").second ?: change }
        assertEquals(CopyDrill.Change.DOWN, change)
        assertEquals(CopyLevel.SHORT_WORDS, d.level)
    }

    @Test
    fun perCharacterScoring() {
        assertEquals(listOf(true, false, true), CopyResult("CQD", "CXD", false).perChar)
    }
}
