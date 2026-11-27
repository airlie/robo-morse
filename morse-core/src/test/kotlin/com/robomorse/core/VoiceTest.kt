package com.robomorse.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTest {
    @Test
    fun phoneticsAndLetterNamesParse() {
        assertEquals("KM", VoiceAnswers.parse("Kilo Mike"))
        assertEquals("K", VoiceAnswers.parse("okay"))
        assertEquals("W1X", VoiceAnswers.parse("whiskey one x-ray"))
        assertEquals("W", VoiceAnswers.parse("double you"))
        assertEquals("599", VoiceAnswers.parse("five niner niner"))
        assertEquals("KM", VoiceAnswers.parse("KM"))
    }

    @Test
    fun matchingUsesTheTopGuesses() {
        assertTrue(VoiceAnswers.matches("K", listOf("Kilo")))
        assertTrue(VoiceAnswers.matches("M", listOf("Mic", "Mike")))
        assertTrue(VoiceAnswers.matches("THE", listOf("the")))
        assertTrue(VoiceAnswers.matches("UR", listOf("uniform Romeo")))
        assertFalse(VoiceAnswers.matches("K", listOf("Mike")))
        assertFalse(VoiceAnswers.matches("K", listOf("a", "b", "c", "kilo")))
    }

    @Test
    fun drillUsesOnlyGivenCharacters() {
        val d = VoiceDrill(VoiceLevel.GROUPS, "KM".toSet(), Random(1))
        repeat(20) {
            val item = d.next()
            assertTrue(item.length in 2..3 && item.all { it in "KM" }, item)
        }
        assertFalse(VoiceDrill.available(VoiceLevel.WORDS, "KM".toSet()))
    }

    @Test
    fun levelsMoveUp() {
        val d = VoiceDrill(VoiceLevel.CHARACTERS, "KMURES".toSet(), Random(3))
        var change: VoiceDrill.Change? = null
        repeat(10) { change = d.record(d.next(), true) ?: change }
        assertEquals(VoiceDrill.Change.UP, change)
        assertEquals(VoiceLevel.GROUPS, d.level)
    }
}
