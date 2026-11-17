package dev.lecturetubemcp.media

import dev.lecturetubemcp.api.Cue
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice activity detection and Whisper hallucination filtering.
 *
 * Whisper invents text on silence and noise (pre-roll before a lecture, breaks, the end of a recording):
 * typical outputs are a phrase repeated dozens of times ("Das war's.", "Das ist ein sehr guter Punkt.") or
 * subtitle credits it learned from TV data ("Untertitel im Auftrag des ZDF"). We therefore only send audio
 * that contains speech, and filter what still slips through.
 */
object Speech {
    const val RATE = 16_000
    private const val FRAME = 480 // 30 ms

    /** A stretch of audio to transcribe, in samples relative to the analysed buffer. */
    data class Window(val start: Int, val end: Int)

    /**
     * Finds speech in [audio] and groups it into windows for Whisper. Gaps up to [joinGapSec] stay inside a
     * window (natural pauses); longer silences are skipped. Windows are at most [maxWindowSec] long.
     */
    fun windows(audio: FloatArray, joinGapSec: Double = 3.0, maxWindowSec: Double = 60.0): List<Window> =
        windowsFromLevels(levels(audio), joinGapSec, maxWindowSec).map { Window(it.start, it.end.coerceAtMost(audio.size)) }

    /** Loudness (dBFS) of each 30 ms frame. */
    fun levels(audio: FloatArray): DoubleArray = DoubleArray(audio.size / FRAME) { f ->
        var s = 0.0
        for (i in f * FRAME until (f + 1) * FRAME) s += audio[i] * audio[i]
        20 * log10(sqrt(s / FRAME) + 1e-9)
    }

    /** Streaming variant of [levels]: feed 16-bit PCM blocks, get frame levels; only ~1 MB per audio hour is kept. */
    class LevelMeter {
        private val out = ArrayList<Double>()
        private var acc = 0.0
        private var n = 0
        fun feed(samples: FloatArray, count: Int = samples.size) {
            for (i in 0 until count) {
                acc += samples[i] * samples[i]; n++
                if (n == FRAME) { out += 20 * log10(sqrt(acc / FRAME) + 1e-9); acc = 0.0; n = 0 }
            }
        }
        fun levels(): DoubleArray = out.toDoubleArray()
    }

    /** Speech windows (in samples) from frame levels; see [windows]. */
    fun windowsFromLevels(db: DoubleArray, joinGapSec: Double = 3.0, maxWindowSec: Double = 60.0): List<Window> {
        val frames = db.size
        if (frames == 0) return emptyList()
        val floor = db.sorted()[frames / 10] // 10th percentile = background level
        val threshold = (floor + 10).coerceIn(-55.0, -30.0)
        // Raw speech regions (in frames), merging gaps < 0.8 s and dropping blips < 0.3 s.
        val regions = mutableListOf<IntArray>()
        var f = 0
        while (f < frames) {
            if (db[f] <= threshold) { f++; continue }
            val s = f
            while (f < frames && db[f] > threshold) f++
            val last = regions.lastOrNull()
            if (last != null && s - last[1] < frames(0.8)) last[1] = f else regions += intArrayOf(s, f)
        }
        val speech = regions.filter { it[1] - it[0] >= frames(0.3) }
        if (speech.isEmpty()) return emptyList()
        val pad = frames(0.25)
        val total = frames * FRAME
        val out = mutableListOf<Window>()
        var gs = speech[0][0]; var ge = speech[0][1]
        for (r in speech.drop(1)) {
            if (r[0] - ge <= frames(joinGapSec) && r[1] - gs <= frames(maxWindowSec)) ge = r[1]
            else { out += win(gs - pad, ge + pad, total); gs = r[0]; ge = r[1] }
        }
        out += win(gs - pad, ge + pad, total)
        return out
    }

    private fun frames(sec: Double) = (sec * RATE / FRAME).toInt()
    private fun win(sf: Int, ef: Int, n: Int) = Window((sf * FRAME).coerceAtLeast(0), (ef * FRAME).coerceAtMost(n))

    private val credits = Regex(
        "untertitel(ung)? (im auftrag|des|der|von)|amara\\.org|subtitles by|captions by|transcribed by|" +
            "copyright .*(zdf|ard|wdr|swr)|^\\W*(zdf|swr|ard|wdr|ndr|br|orf|funk)( ?\\d{4})?\\W*$",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Stateful filter (one per transcription): drops subtitle credits, consecutive duplicates, and any text that
     * occurs 3 or more times within [windowMs] (keeping its first occurrence).
     */
    class Filter(private val windowMs: Long = 120_000, private val maxRepeats: Int = 2) {
        private val recent = ArrayDeque<Pair<Long, String>>()
        private var last: String? = null
        private var lastMs = Long.MIN_VALUE

        fun accept(startMs: Long, text: String): Boolean {
            val n = normalize(text)
            if (n.isEmpty() || credits.containsMatchIn(text)) return false
            while (recent.isNotEmpty() && startMs - recent.first().first > windowMs) recent.removeFirst()
            val seen = recent.count { it.second == n }
            recent.addLast(startMs to n)
            // An immediate repeat (within 15 s) is a duplicate; the same words later on are legitimate.
            if (n == last && startMs - lastMs < 15_000) return false
            last = n; lastMs = startMs
            // Short phrases repeated over and over are hallucinations; long sentences don't repeat by chance.
            return seen < maxRepeats || n.length > 80
        }
    }

    /** Applies [Filter] to a finished list of cues (used when merging parts). */
    fun clean(cues: List<Cue>): List<Cue> {
        val f = Filter()
        // Count over the whole list first: a phrase that appears very often overall is dropped everywhere.
        val counts = cues.groupingBy { normalize(it.text) }.eachCount()
        return cues.sortedBy { it.startMs }.filter { c ->
            val n = normalize(c.text)
            (counts[n] ?: 0) < 10 || n.length > 80
        }.filter { f.accept(it.startMs, it.text) }
    }
}
