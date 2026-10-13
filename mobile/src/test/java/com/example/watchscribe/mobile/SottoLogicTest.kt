package com.example.watchscribe.mobile

import com.example.watchscribe.mobile.ai.Gemma
import com.example.watchscribe.mobile.ai.Transcriber
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.Recording
import com.example.watchscribe.mobile.data.Seg
import com.example.watchscribe.mobile.data.Wav
import com.example.watchscribe.mobile.ui.GroupBy
import com.example.watchscribe.mobile.ui.SortBy
import com.example.watchscribe.mobile.ui.ViewOptions
import com.example.watchscribe.mobile.ui.totalLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Core logic of the phone app, run on the computer (no phone needed). */
class SottoLogicTest {

    // ---------- security: what the phone accepts from the watch
    @Test fun acceptsOnlyWatchChunkNames() {
        assertTrue(ChunkReceiverService.isValidName("chunk_20261011_104200.wav"))
        assertFalse(ChunkReceiverService.isValidName("../chunk_20261011_104200.wav"))
        assertFalse(ChunkReceiverService.isValidName("chunk_20261011_104200.wav/../../x"))
        assertFalse(ChunkReceiverService.isValidName("evil.apk"))
        assertFalse(ChunkReceiverService.isValidName("chunk_2026_1.wav"))
        assertFalse(ChunkReceiverService.isValidName(""))
    }

    // ---------- audio files
    private fun tone(seconds: Double, rate: Int, amp: Double, file: File): File {
        val n = (seconds * rate).toInt()
        file.outputStream().use { o ->
            o.write(Wav.header(rate, n * 2L))
            val b = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = (amp * 32767 * sin(2 * PI * 220 * i / rate)).toInt()
                b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte()
            }
            o.write(b)
        }
        return file
    }

    @Test fun wavHeaderGivesRightDuration() {
        val f = tone(3.0, 32_000, 0.3, File.createTempFile("tone", ".wav"))
        assertEquals(3.0, Wav.seconds(f), 0.01)
        assertEquals(48_000, Transcriber.load16k(f).size) // 32 kHz -> 16 kHz
        val p = Wav.peaks(f, 16)
        assertEquals(16, p.size)
        assertTrue(p.all { it in 0f..1f })
    }

    // ---------- transcription helpers
    @Test fun segmentsCutLongAudioAndSkipSilence() {
        val sr = 16_000
        // like real talk: 2 s of voice, 1 s of near-silence, repeated
        val speech = FloatArray(sr * 40) { i ->
            val talking = (i / sr) % 3 < 2
            ((if (talking) 0.3 else 0.002) * sin(2 * PI * 200 * i / sr)).toFloat()
        }
        val pieces = Transcriber.segment(speech)
        assertTrue("40 s should become several pieces", pieces.size >= 3)
        pieces.forEach { (a, b) -> assertTrue((b - a) / sr.toDouble() <= 14.01) }
        assertEquals(0, pieces.first().first)
        assertEquals(speech.size, pieces.last().second)

        val mostlySilent = FloatArray(sr * 30) { if (it in sr * 5 until sr * 8) (0.3 * sin(2.0 * PI * 200 * it / sr)).toFloat() else 0.0005f }
        assertTrue("silent parts are dropped", Transcriber.segment(mostlySilent).size < 3)
        assertTrue(Transcriber.segment(FloatArray(0)).isEmpty())
    }

    @Test fun loopGuardCatchesRepeatedText() {
        assertTrue(Transcriber.looksLikeLoop("что я не знаю ".repeat(8)))
        assertFalse(Transcriber.looksLikeLoop("We agreed to send the offer on Monday and review the budget next week with Anna."))
        assertFalse(Transcriber.looksLikeLoop("да да да")) // short answers are fine
    }

    // ---------- AI output parsing
    @Test fun parsesGemmaJsonEvenWithChatter() {
        val s = Gemma.parse("Sure! ```json\n{\"title\":\"Trip planning\",\"summary\":\"We chose Kraków.\",\"actions\":[\"Book hotel\",\" \"]}\n```")
        assertNotNull(s)
        assertEquals("Trip planning", s!!.title)
        assertEquals(listOf("Book hotel"), s.actions)
        assertNull(Gemma.parse("no json here"))
        assertNull(Gemma.parse("{broken"))
    }

    // ---------- library filters
    private fun rec(id: String, start: Long, sec: Double, place: String? = null, starred: Boolean = false, text: String? = null) = Recording(
        id = id, clean = File(id), raw = null, start = start, seconds = sec, sizeBytes = 0, title = "Note $id", customTitle = false,
        starred = starred, place = place, lat = null, lon = null, peaks = emptyList(),
        segments = text?.let { listOf(Seg(0.0, sec, it)) }, lang = null, sync = null, marks = emptyList(),
        summary = null, actions = emptyList(), speakers = 0,
    )

    @Test fun filtersSortAndGroup() {
        val now = System.currentTimeMillis()
        val all = listOf(
            rec("a", now - 1_000, 12.0, "Home", text = "budget review"),
            rec("b", now - 60_000, 400.0, "Office", starred = true),
            rec("c", now - 10 * 86_400_000L, 90.0, null),
        )
        val o = ViewOptions()
        assertEquals("short notes are shown by default", 3, o.apply(all).size)
        assertEquals(listOf("a", "b", "c"), o.apply(all).map { it.id })
        o.sortBy = SortBy.LONGEST; assertEquals("b", o.apply(all).first().id)
        o.starredOnly = true; assertEquals(listOf("b"), o.apply(all).map { it.id }); o.starredOnly = false
        o.lastDays = 7; assertEquals(2, o.apply(all).size); o.lastDays = null
        o.query = "budget"; assertEquals(listOf("a"), o.apply(all).map { it.id }); o.query = ""
        o.hideShort = true; assertFalse(o.apply(all).any { it.id == "a" }); o.hideShort = false
        o.groupBy = GroupBy.PLACE
        assertEquals(setOf("Home", "Office", "Unknown place"), o.group(o.apply(all)).map { it.first }.toSet())
        o.reset(); assertEquals(3, o.apply(all).size)
        assertEquals("3 notes · 9 min", totalLabel(all))
    }

    @Test fun timeLabels() {
        assertEquals("1:05", Exporter.stamp(65.4))
        assertEquals("1:01:01", Exporter.length(3661.0))
    }

    // ---------- speakers: how many people are in a note
    private fun voices(seed: Long, counts: List<Int>, noise: Double): List<FloatArray> {
        val r = java.util.Random(seed)
        val centers = counts.map { FloatArray(192) { r.nextGaussian().toFloat() }.let { v -> val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }); FloatArray(192) { (v[it] / n).toFloat() } } }
        return counts.flatMapIndexed { c, n -> List(n) { FloatArray(192) { (centers[c][it] + r.nextGaussian() * noise).toFloat() } } }
    }
    private fun people(e: List<FloatArray>) = Transcriber.cluster(e, DoubleArray(e.size) { 1.0 + it % 5 }, -1).max() + 1

    @Test fun countsSpeakersAutomatically() {
        for (seed in 1L..4L) for (noise in listOf(0.047, 0.065, 0.08)) {
            assertEquals("one voice", 1, people(voices(seed, listOf(30), noise)))
            assertEquals("two voices", 2, people(voices(seed, listOf(20, 15), noise)))
            assertEquals("two voices, one says little", 2, people(voices(seed, listOf(30, 4), noise)))
            assertEquals("three voices", 3, people(voices(seed, listOf(15, 10, 8), noise)))
        }
    }

    @Test fun usesTheNumberTheUserChose() {
        val e = voices(7, listOf(20, 15), 0.05)
        assertEquals(3, Transcriber.cluster(e, DoubleArray(e.size) { 1.0 }, 3).max() + 1)
        assertEquals(1, Transcriber.cluster(e, DoubleArray(e.size) { 1.0 }, 1).max() + 1)
        assertEquals(0, Transcriber.cluster(listOf(e[0]), doubleArrayOf(1.0), -1).single())
    }

    @Test fun speakerChangesInsideATurnAreSteadied() {
        fun sm(vararg l: Int) = Transcriber.smoothLabels(l).toList()
        assertEquals(listOf(0, 0, 0, 0, 0), sm(0, 0, 1, 0, 0))          // one odd window is noise
        assertEquals(listOf(0, 0, 0, 1, 1, 1), sm(0, 0, 0, 1, 1, 1))    // a real change is kept
        assertEquals(listOf(0, 0, 0, 0), sm(1, 0, 0, 0))
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 1), sm(0, 0, 1, 1, 0, 1, 1))
        assertEquals(listOf(2), sm(2))
    }
}
