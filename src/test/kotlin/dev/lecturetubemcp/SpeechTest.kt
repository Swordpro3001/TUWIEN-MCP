package dev.lecturetubemcp

import dev.lecturetubemcp.api.Cue
import dev.lecturetubemcp.media.JobFile
import dev.lecturetubemcp.media.Speech
import dev.lecturetubemcp.media.Transcriber
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpeechTest {
    private val rate = Speech.RATE

    /** [segments]: (seconds, loud?) */
    private fun audio(vararg segments: Pair<Double, Boolean>): FloatArray {
        val rnd = Random(1)
        val out = ArrayList<Float>()
        for ((sec, loud) in segments) repeat((sec * rate).toInt()) { i ->
            out += if (loud) (0.3 * sin(i * 2 * Math.PI * 220 / rate) * (0.6 + 0.4 * sin(i * 2 * Math.PI * 3.0 / rate))).toFloat()
            else (rnd.nextFloat() - 0.5f) * 0.002f // faint room noise
        }
        return out.toFloatArray()
    }

    @Test fun skipsLongSilenceKeepsSpeech() {
        // 100 s pre-roll silence, 20 s speech, 1.5 s pause, 10 s speech, 40 s silence, 5 s speech
        val a = audio(100.0 to false, 20.0 to true, 1.5 to false, 10.0 to true, 40.0 to false, 5.0 to true)
        val w = Speech.windows(a)
        assertEquals(2, w.size, w.toString())
        assertTrue(w[0].start / rate in 99..100 && w[0].end / rate in 131..132, "first window joins the short pause: $w")
        assertTrue(w[1].start / rate in 170..171, w.toString())
        assertTrue(Speech.windows(audio(60.0 to false)).isEmpty())
    }

    @Test fun cleansHallucinations() {
        val junk = (0 until 91).map { Cue(it * 1500L, it * 1500L + 1000, "Das war's.") } +
            (0 until 42).map { Cue(140_000L + it * 3000, 140_000L + it * 3000 + 2000, "Das ist ein sehr guter Punkt.") }
        val real = listOf(
            Cue(146_520, 150_000, "Guten Morgen."), Cue(152_520, 160_000, "Wir haben gestern über die Symmetriegruppe begonnen."),
            Cue(200_000, 205_000, "Untertitel im Auftrag des ZDF, 2021"), Cue(210_000, 212_000, "SWR 2020"),
        )
        val cleaned = Speech.clean(junk + real).map { it.text }
        assertEquals(listOf("Guten Morgen.", "Wir haben gestern über die Symmetriegruppe begonnen."), cleaned)
    }

    @Test fun recoversFinishedJobOfDeadProcess() {
        val key = "test-recover-" + System.nanoTime()
        val work = Config.dataDir.resolve("transcripts/work").resolve(MediaKeys.base(key) + "#small")
        Files.createDirectories(work)
        Files.writeString(work.resolve("job.json"), Json.encodeToString(JobFile.serializer(),
            JobFile(key, "Test lecture", "small", "de", parts = listOf(listOf(0.0, 60.0), listOf(60.0, 120.0)))))
        Files.writeString(work.resolve("part0.a0.vtt.partial"), "WEBVTT\n\n")
        Files.writeString(work.resolve("part0.a1.vtt"), "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nErster Teil.\n\n")
        Files.writeString(work.resolve("part1.a0.vtt"), "WEBVTT\n\n00:01:02.000 --> 00:01:05.000\nZweiter Teil.\n\n")
        val (cues, meta) = assertNotNull(Transcriber.cached(key))
        assertEquals(listOf("Erster Teil.", "Zweiter Teil."), cues.map { it.text })
        assertEquals("Test lecture", meta.title)
        assertTrue(!Files.exists(work), "work dir removed after recovery")
    }
}

/** Mirrors Transcriber's file naming for the test. */
private object MediaKeys { fun base(key: String) = dev.lecturetubemcp.media.MediaCache.safeName(key) }
