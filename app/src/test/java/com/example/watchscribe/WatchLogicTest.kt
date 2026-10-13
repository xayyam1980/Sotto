package com.example.watchscribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Core audio logic of the watch app, run on the computer. */
class WatchLogicTest {
    private val rate = 32_000
    private val frame = rate / 50 // 20 ms

    private fun sine(amp: Int, hz: Double = 300.0, offset: Int = 0) =
        ShortArray(frame) { (amp * sin(2 * PI * hz * (it + offset) / rate)).toInt().toShort() }

    @Test fun vadIgnoresQuietRoomAndHearsSpeech() {
        val vad = Vad()
        repeat(200) { assertFalse("room noise is not speech", vad.isSpeech(sine(40), frame)) }
        assertTrue("voice is speech", vad.isSpeech(sine(3000), frame))
        // keeps listening ~1 s after speech stops (hangover), then stops
        repeat(10) { assertTrue(vad.isSpeech(sine(40), frame)) }
        repeat(60) { vad.isSpeech(sine(40), frame) }
        assertFalse(vad.isSpeech(sine(40), frame))
    }

    @Test fun highPassRemovesDcButKeepsVoice() {
        val hp = HighPass(rate, 80.0)
        val dc = ShortArray(rate) { 5000 }
        hp.process(dc, dc.size)
        assertTrue("DC offset removed", abs(dc.last().toInt()) < 50)

        val hp2 = HighPass(rate, 80.0)
        var peak = 0
        repeat(50) { i ->
            val v = sine(10000, 1000.0, i * frame)
            hp2.process(v, v.size)
            if (i > 5) peak = maxOf(peak, v.maxOf { abs(it.toInt()) })
        }
        assertTrue("1 kHz voice kept (peak=$peak)", peak > 9000)
    }

    @Test fun durationText() {
        assertEquals("1 min", Stats.duration(30))
        assertEquals("1 h 5 min", Stats.duration(3900))
    }
}
