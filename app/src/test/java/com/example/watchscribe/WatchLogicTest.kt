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

    @Test fun batteryEstimate() {
        val h = 3_600_000L
        val now = 10 * h
        // 10 % in 2 h at 50 % → 10 h left
        assertEquals(10.0, Battery.estimateHours(listOf(now - 2 * h to 60, now - h to 55), now, 50)!!, 0.01)
        // too soon, or nothing used yet → no guess
        assertEquals(null, Battery.estimateHours(listOf(now - 10 * 60_000L to 60), now, 58))
        assertEquals(null, Battery.estimateHours(listOf(now - 2 * h to 60), now, 60))
        assertEquals("~10 h battery", Battery.format(10.2))
        assertEquals("<1 h battery", Battery.format(0.4))
    }

    @Test fun forgetPlan() {
        val start = 1_000_000L
        assertEquals(Forget.Action.Keep, Forget.plan(start, 60.0, start + 60_000))
        assertEquals(Forget.Action.Delete, Forget.plan(start, 60.0, start + 1_000))
        assertEquals(Forget.Action.Delete, Forget.plan(start, 60.0, start - 5_000))
        assertEquals(Forget.Action.Trim(20.0), Forget.plan(start, 60.0, start + 20_000))
        assertEquals(java.util.Calendar.getInstance().apply { set(2026, 9, 13, 10, 42, 5); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis,
            Forget.startOf("chunk_20261013_104205.wav"))
    }

    @Test fun trimFileKeepsHeaderValid() {
        val f = java.io.File.createTempFile("chunk", ".wav")
        f.writeBytes(WavChunkWriter.header(rate, rate * 2L * 10) + ByteArray(rate * 2 * 10))
        assertEquals(10.0, WavChunkWriter.seconds(f), 0.001)
        WavChunkWriter.trimFile(f, 4.0)
        assertEquals(44L + rate * 2 * 4, f.length())
        assertEquals(4.0, WavChunkWriter.seconds(f), 0.001)
        val dataLen = java.nio.ByteBuffer.wrap(f.readBytes(), 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(rate * 2 * 4, dataLen)
        f.delete()
    }

    @Test fun shortDurationForComplication() {
        assertEquals("0m", Stats.shortDuration(0))
        assertEquals("12m", Stats.shortDuration(700))
        assertEquals("1h05", Stats.shortDuration(3900))
    }

    @Test fun chunkQueryCarriesMarksAndMemo() {
        val dir = java.nio.file.Files.createTempDirectory("q").toFile()
        val f = java.io.File(dir, "chunk_20261013_104205.wav").apply { writeBytes(ByteArray(44)) }
        assertEquals("", ChunkSync.query(f))
        java.io.File(dir, f.name + ".marks").writeText("12.3,45.0")
        java.io.File(dir, f.name + ".memo").writeText("1")
        assertEquals("?m=12.3,45.0&memo=1", ChunkSync.query(f))
        dir.deleteRecursively()
    }
}
