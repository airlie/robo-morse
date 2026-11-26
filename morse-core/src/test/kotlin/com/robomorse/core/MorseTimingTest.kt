package com.robomorse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorseTimingTest {
    @Test
    fun ditLengthFollowsParis() {
        assertEquals(60.0, MorseTiming(20.0).ditMs, 1e-9)
        assertEquals(120.0, MorseTiming(10.0).ditMs, 1e-9)
    }

    @Test
    fun standardSpacingWithoutFarnsworth() {
        val t = MorseTiming(20.0)
        assertEquals(180.0, t.charGapMs, 1e-9)
        assertEquals(420.0, t.wordGapMs, 1e-9)
    }

    @Test
    fun farnsworthStretchesOnlyGaps() {
        val t = MorseTiming(20.0, 10.0)
        assertEquals(60.0, t.ditMs, 1e-9)
        assertTrue(t.charGapMs > 180.0)
        // The word PARIS plus a word gap should take 60 s / effWpm = 6 s.
        val paris = MorseRenderer.render("PARIS", t, leadInMs = 0.0).totalMs + t.wordGapMs
        assertEquals(6000.0, paris, 1.0)
    }

    @Test
    fun parisAtStandardSpeedIsFiftyUnits() {
        val t = MorseTiming(20.0)
        val total = MorseRenderer.render("PARIS", t, leadInMs = 0.0).totalMs + t.wordGapMs
        assertEquals(50 * t.ditMs, total, 1e-6)
    }

    @Test
    fun renderTracksCharacterEnds() {
        val t = MorseTiming(20.0)
        val r = MorseRenderer.render("ET", t, leadInMs = 100.0)
        assertEquals(listOf('E', 'T'), r.chars)
        assertEquals(160.0, r.charEndMs[0], 1e-9)
        assertEquals(160.0 + 180.0 + 180.0, r.charEndMs[1], 1e-9)
        assertEquals(r.charEndMs.last(), r.totalMs, 1e-9)
    }

    @Test
    fun tableRoundTrips() {
        MorseCode.table.forEach { (c, p) -> assertEquals(c, MorseCode.charOf(p)) }
        assertNull(MorseCode.patternOf('#'))
        assertEquals(MorseCode.table.size, MorseCode.table.values.toSet().size)
    }

    @Test
    fun kochOrderCoversTableExactlyOnce() {
        val order = Curriculum.KOCH_ORDER_LCWO
        assertEquals(order.length, order.toSet().size)
        assertEquals(MorseCode.table.keys, order.toSet())
    }
}
