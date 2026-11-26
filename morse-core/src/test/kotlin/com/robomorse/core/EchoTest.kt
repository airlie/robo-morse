package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EchoTest {
    private val all = MorseCode.table.keys
    private val km = setOf('K', 'M')

    @Test
    fun allWordsAreSendable() {
        EchoContent.words.forEach { w -> assertTrue(w.all { MorseCode.isSupported(it) }, w) }
    }

    @Test
    fun contentUsesOnlyAllowedCharacters() {
        val r = Random(3)
        repeat(200) {
            assertTrue(EchoContent.next(EchoLevel.GROUPS, km, r).all { it in km })
        }
        val chars = "KMURESNAPTLWI".toSet()
        repeat(100) {
            assertTrue(EchoContent.next(EchoLevel.WORDS, chars, r).all { it in chars })
        }
    }

    @Test
    fun levelsNeedEnoughCharacters() {
        assertFalse(EchoContent.available(EchoLevel.WORDS, km))
        assertFalse(EchoContent.available(EchoLevel.CALLSIGNS, km))
        assertTrue(EchoContent.available(EchoLevel.CALLSIGNS, all))
        assertTrue(EchoContent.available(EchoLevel.WORDS, all))
    }

    @Test
    fun callsignsLookRight() {
        val r = Random(5)
        repeat(200) {
            val c = EchoContent.callsign(r)
            assertTrue(Regex("^[A-Z0-9]{1,2}[0-9][A-Z]{1,3}$").matches(c), c)
        }
    }

    @Test
    fun progressMovesUpAndSkipsUnavailableLevels() {
        val p = EchoProgress(EchoLevel.LETTERS, km)
        repeat(9) { assertNull(p.record(true, 80, auto = true)) }
        assertEquals(EchoProgress.Change.UP, p.record(true, 80, auto = true))
        assertEquals(EchoLevel.PAIRS, p.level)
        repeat(10) { p.record(true, 80, auto = true) }
        assertEquals(EchoLevel.GROUPS, p.level)
        // Words and callsigns aren't possible with K and M, so it stays.
        repeat(10) { assertNull(p.record(true, 80, auto = true)) }
        assertEquals(EchoLevel.GROUPS, p.level)
    }

    @Test
    fun poorRhythmHoldsTheLevelAndFailureMovesDown() {
        val p = EchoProgress(EchoLevel.PAIRS, all)
        repeat(10) { p.record(true, 40, auto = true) }
        assertEquals(EchoLevel.PAIRS, p.level)
        repeat(8) { p.record(false, null, auto = true) }
        assertEquals(EchoLevel.LETTERS, p.level)
    }

    @Test
    fun manualModeNeverChangesLevel() {
        val p = EchoProgress(EchoLevel.LETTERS, all)
        repeat(20) { assertNull(p.record(true, 90, auto = false)) }
        assertEquals(EchoLevel.LETTERS, p.level)
    }
}
