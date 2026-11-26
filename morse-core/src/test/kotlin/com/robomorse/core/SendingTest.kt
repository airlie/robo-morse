package com.robomorse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendingTest {
    /** Sends [text] into [d] with the given timing; returns the end time. */
    private fun send(
        d: SendDecoder,
        text: String,
        dit: Double,
        dahRatio: Double = 3.0,
        charGap: Double = 3.0,
        start: Long = 1000,
        jitter: (Int) -> Double = { 0.0 },
    ): Long {
        var t = start.toDouble()
        var n = 0
        text.split(' ').forEachIndexed { wi, word ->
            if (wi > 0) t += 4 * dit // word gap = 7 (3 char gap below + 4)
            word.forEachIndexed { ci, c ->
                if (ci > 0 || wi > 0) t += (charGap - 1) * dit
                MorseCode.patternOf(c)!!.forEachIndexed { ei, el ->
                    if (ei > 0 || ci > 0 || wi > 0) t += dit * (1 + jitter(n++))
                    d.keyDown(t.toLong())
                    t += (if (el == '.') dit else dit * dahRatio) * (1 + jitter(n++))
                    d.keyUp(t.toLong())
                }
            }
        }
        d.tick((t + 10 * dit).toLong())
        return t.toLong()
    }

    @Test
    fun decodesCleanSending() {
        val d = SendDecoder(initialWpm = 20.0)
        send(d, "CQ DE K1ABC", dit = 60.0)
        assertEquals("CQ DE K1ABC ", d.text)
    }

    @Test
    fun adaptsToASlowerSender() {
        // Starts assuming 25 WPM; the sender is at 12 WPM (dit 100 ms).
        val d = SendDecoder(initialWpm = 25.0)
        send(d, "PARIS PARIS", dit = 100.0)
        assertTrue(d.text.endsWith("PARIS "), d.text)
        assertEquals(12.0, d.estimatedWpm, 1.0)
    }

    @Test
    fun unknownPatternIsMarked() {
        val d = SendDecoder(20.0)
        // Eight dits is not a character.
        var t = 0L
        repeat(8) { d.keyDown(t); t += 60; d.keyUp(t); t += 60 }
        d.tick(t + 1000)
        assertEquals("▯ ", d.text)
    }

    @Test
    fun fistScoreRewardsEvenTiming() {
        val good = SendDecoder(20.0).also { send(it, "PARIS PARIS", dit = 60.0) }
        val bad = SendDecoder(20.0).also {
            send(it, "PARIS PARIS", dit = 60.0, dahRatio = 2.0, charGap = 2.2, jitter = { n -> if (n % 2 == 0) 0.3 else -0.25 })
        }
        val g = assertNotNull(FistAnalyzer.analyze(good.intervals))
        val b = assertNotNull(FistAnalyzer.analyze(bad.intervals))
        assertTrue(g.score >= 90, "good=${g.score}")
        assertTrue(b.score < g.score - 25, "good=${g.score} bad=${b.score}")
        assertEquals(3.0, g.dahDitRatio!!, 0.05)
        assertEquals(20.0, g.estWpm, 0.5)
    }

    @Test
    fun beginnerScoresInTheMiddleAndImprovementShows() {
        fun score(dahRatio: Double, jitter: Double) = SendDecoder(12.0).let {
            val rnd = kotlin.random.Random(7)
            send(it, "KM KM MK KM", dit = 100.0, dahRatio = dahRatio, charGap = 4.0, jitter = { rnd.nextDouble(-jitter, jitter) })
            assertNotNull(FistAnalyzer.analyze(it.intervals))
        }
        val beginner = score(dahRatio = 2.2, jitter = 0.15)
        val better = score(dahRatio = 2.6, jitter = 0.10)
        val good = score(dahRatio = 2.9, jitter = 0.04)
        assertTrue(beginner.score in 35..80, "beginner=${beginner.score}")
        assertTrue(better.score > beginner.score + 5, "beginner=${beginner.score} better=${better.score}")
        assertTrue(good.score >= 85, "good=${good.score}")
        assertTrue("dah" in beginner.tip.lowercase(), beginner.tip)
    }

    @Test
    fun touchTimingNoiseIsNotPenalised() {
        // ±7 ms of jitter at 20 WPM is what a touchscreen alone produces.
        val d = SendDecoder(20.0)
        send(d, "PARIS PARIS", dit = 60.0, jitter = { n -> if (n % 2 == 0) 7.0 / 60 else -7.0 / 60 })
        val r = assertNotNull(FistAnalyzer.analyze(d.intervals))
        assertTrue(r.score >= 90, "score=${r.score} parts=${r.parts}")
    }

    @Test
    fun lettersBreakingApartIsCaught() {
        // Every element followed by a letter-sized gap, so K and M decode as T E T and T T.
        val spaced = SendDecoder(12.0).also { s ->
            var t = 0L
            repeat(12) { i ->
                s.keyDown(t); t += if (i % 3 == 1) 100 else 300
                s.keyUp(t); t += 400
            }
        }
        val r = assertNotNull(FistAnalyzer.analyze(spaced.intervals))
        assertTrue(r.score < 60, "score=${r.score}")
        assertTrue("breaking apart" in r.tip, r.tip)

        // The same letters sent properly are not flagged.
        val good = SendDecoder(12.0).also { send(it, "KM KM KM", dit = 100.0) }
        assertTrue(assertNotNull(FistAnalyzer.analyze(good.intervals)).score >= 90)
    }

    @Test
    fun tooLittleDataGivesNoReport() {
        assertNull(FistAnalyzer.analyze(listOf(KeyInterval(true, 60.0))))
    }

    @Test
    fun keyerSqueezeAlternates() {
        val k = IambicKeyer(IambicMode.A)
        k.paddlePressed(dit = true, ditDown = true, dahDown = false)
        assertEquals(Element.DIT, k.next(ditDown = true, dahDown = true))
        assertEquals(Element.DAH, k.next(ditDown = true, dahDown = true))
        assertEquals(Element.DIT, k.next(ditDown = true, dahDown = true))
        assertNull(k.next(ditDown = false, dahDown = false))
    }

    @Test
    fun keyerDahMemory() {
        val k = IambicKeyer(IambicMode.A)
        assertEquals(Element.DIT, k.next(ditDown = true, dahDown = false))
        // Dah tapped and released during the dit: it is remembered.
        k.paddlePressed(dit = false, ditDown = true, dahDown = true)
        assertEquals(Element.DAH, k.next(ditDown = false, dahDown = false))
        assertNull(k.next(ditDown = false, dahDown = false))
    }

    @Test
    fun modeBSendsOneExtraAfterSqueezeRelease() {
        val a = IambicKeyer(IambicMode.A)
        val b = IambicKeyer(IambicMode.B)
        for (k in listOf(a, b)) {
            assertEquals(Element.DIT, k.next(ditDown = true, dahDown = true))
            assertEquals(Element.DAH, k.next(ditDown = true, dahDown = true))
        }
        // Both paddles released during that dah.
        assertNull(a.next(ditDown = false, dahDown = false))
        assertEquals(Element.DIT, b.next(ditDown = false, dahDown = false))
        assertNull(b.next(ditDown = false, dahDown = false))
    }
}
