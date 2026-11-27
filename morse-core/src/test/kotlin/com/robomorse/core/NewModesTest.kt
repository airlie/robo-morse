package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LadderTest {
    @Test
    fun speedClimbsOnHitsAndDropsOnMisses() {
        val l = CallsignLadder(20, calls = 4, random = Random(1))
        val a = l.next(); val r1 = l.record(a, a, replayed = false)
        assertEquals(a.length * 20, r1.points)
        assertEquals(22, l.wpm)
        val b = l.next(); val r2 = l.record(b, b, replayed = true)
        assertEquals(b.length * 11, r2.points)
        assertEquals(24, l.wpm)
        val c = l.next(); l.record(c, "", replayed = false)
        assertEquals(22, l.wpm)
        assertEquals(22, l.peakWpm)
        assertFalse(l.finished)
        l.record(l.next(), "", false)
        assertTrue(l.finished)
    }

    @Test
    fun speedStaysInRange() {
        val l = CallsignLadder(6, random = Random(3))
        repeat(3) { l.record(l.next(), "", false) }
        assertEquals(LADDER_MIN_WPM, l.wpm)
    }
}

class HeadCopyTest {
    private val all = MorseCode.table.keys

    @Test
    fun levelsHaveEnoughMaterial() {
        HeadCopyLevel.entries.forEach { assertTrue(HeadCopyContent.available(it, all), it.name) }
        assertFalse(HeadCopyContent.available(HeadCopyLevel.SHORT, "KM".toSet()))
    }

    @Test
    fun choicesAreFourDistinctAndIncludeTheAnswer() {
        val d = HeadCopyDrill(HeadCopyLevel.WORDS, all, Random(4))
        repeat(20) {
            val item = d.next()
            assertEquals(4, item.choices.toSet().size)
            assertTrue(item.target in item.choices)
            assertTrue(item.choices.all { it.length in 4..5 })
        }
    }

    @Test
    fun distractorsLookAlike() {
        val pool = listOf("UR RST 599", "UR RST 579", "UR RST 559", "CQ CQ DE", "GM OM", "TU 73", "73 ES GL")
        val d = HeadCopyContent.distractors("UR RST 599", pool, Random(1))
        assertTrue(d.containsAll(listOf("UR RST 579", "UR RST 559")), d.toString())
    }

    @Test
    fun aMissComesBackAndLevelsMove() {
        val d = HeadCopyDrill(HeadCopyLevel.SHORT, all, Random(9))
        val first = d.next().target
        d.record(first, false)
        val after = (1..4).map { d.next().target.also { t -> d.record(t, true) } }
        assertEquals(first, after[3])
        var change: HeadCopyDrill.Change? = null
        repeat(10) { change = d.record(d.next().target, true) ?: change }
        assertEquals(HeadCopyDrill.Change.UP, change)
        assertEquals(HeadCopyLevel.WORDS, d.level)
    }
}

class OnAirTest {
    @Test
    fun contactHasThreeOversWithFieldsInTheText() {
        val c = OnAirContent.contact("W1AW", Random(2))
        assertEquals(3, c.overs.size)
        c.overs.forEach { over ->
            assertTrue(over.fields.isNotEmpty())
            over.fields.forEach { f -> assertTrue(f.answer in over.text, "${f.answer} in ${over.text}") }
            assertTrue(over.text.all { it == ' ' || it in MorseCode.table.keys }, over.text)
        }
        assertTrue(c.overs[1].text.startsWith("W1AW DE ${c.theirCall}"))
        assertTrue(c.overs[0].shorthand.any { it.first == "CQ" })
    }

    @Test
    fun fieldMatchingIsLenientWhereItShouldBe() {
        assertTrue(OnAirField(OnAirContent.POWER, "100").matches("100w"))
        assertTrue(OnAirField(OnAirContent.NAME, "BOB").matches(" bob "))
        assertFalse(OnAirField(OnAirContent.RST, "599").matches("579"))
    }
}
