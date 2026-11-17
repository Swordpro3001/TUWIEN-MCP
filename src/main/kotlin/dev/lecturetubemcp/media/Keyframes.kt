package dev.lecturetubemcp.media

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.ceil
import kotlin.math.sqrt

data class Frame(val timeMs: Long, val jpeg: ByteArray, val width: Int, val height: Int)

/** Keyframe extraction from lecture videos with ffmpeg, plus de-duplication and contact sheets. */
object Keyframes {
    init { System.setProperty("java.awt.headless", "true") }

    private data class Scored(val n: Int, val t: Double, val score: Double)

    /**
     * @param mode "scenes": the strongest picture changes (slide changes), adaptively chosen; "interval": one frame every [intervalSec].
     * @param threshold minimum scene score for "scenes" (null = adaptive; slide changes typically score 0.02–0.1).
     * Only keyframes are decoded (`-skip_frame nokey`), so a 90-minute video takes seconds, not minutes.
     */
    suspend fun extract(
        video: Path, fromSec: Double, toSec: Double?, mode: String, threshold: Double?, intervalSec: Int, maxFrames: Int, width: Int,
    ): List<Frame> {
        val ffmpeg = Ffmpeg.require()
        val input = mutableListOf<String>()
        if (fromSec > 0) input += listOf("-ss", "$fromSec")
        input += listOf("-skip_frame", "nokey", "-i", video.toString())
        if (toSec != null) input += listOf("-t", "${toSec - fromSec}")

        val (c1, o1) = Ffmpeg.run(listOf(ffmpeg, "-nostdin", "-hide_banner") + input + listOf("-an", "-vf", "select='gte(scene\\,0)',metadata=print:key=lavfi.scene_score", "-f", "null", "-"), 1800)
        if (c1 != 0) throw MediaException("ffmpeg failed: ${o1.lines().takeLast(3).joinToString(" ")}")
        val all = mutableListOf<Scored>()
        var n = -1; var t = 0.0
        for (line in o1.lineSequence()) {
            Regex("""frame:(\d+)\s+pts:\s*-?\d+\s+pts_time:([\d.]+)""").find(line)?.let { n = it.groupValues[1].toInt(); t = it.groupValues[2].toDouble() }
            Regex("""lavfi\.scene_score=([\d.]+)""").find(line)?.let { if (n >= 0) all += Scored(n, t, it.groupValues[1].toDouble()) }
        }
        if (all.isEmpty()) return emptyList()
        val duration = all.last().t - all.first().t

        val chosen: List<Scored> = if (mode == "interval") {
            pickEvenly(all, intervalSec.toDouble())
        } else {
            val floor = threshold ?: 0.008
            val changes = all.filter { it.score > floor }.sortedByDescending { it.score }.take(maxFrames * 2)
            val picked = (listOf(all.first()) + changes).sortedBy { it.t }.toMutableList()
            // Fill long stretches without changes (e.g. blackboard or camera-only recordings).
            val gap = (duration / maxFrames).coerceAtLeast(30.0)
            for (fill in pickEvenly(all, gap)) {
                if (picked.none { kotlin.math.abs(it.t - fill.t) < gap * 0.75 }) picked += fill
            }
            picked.sortedBy { it.t }.distinctBy { it.n }
        }

        val work = Files.createTempDirectory("keyframes")
        try {
            val frames = chosen.chunked(60).flatMapIndexed { ci, chunk ->
                val expr = chunk.joinToString("+") { "eq(n\\,${it.n})" }
                val pattern = work.resolve("c${ci}_%04d.jpg").toString()
                val (code, out) = Ffmpeg.run(
                    listOf(ffmpeg, "-nostdin", "-hide_banner") + input +
                        listOf("-an", "-vf", "select='$expr',scale='min($width,iw)':-2", "-fps_mode", "vfr", "-q:v", "3", pattern), 1800,
                )
                if (code != 0) throw MediaException("ffmpeg keyframe extraction failed: ${out.lines().takeLast(3).joinToString(" ")}")
                val files = Files.list(work).use { st -> st.filter { it.fileName.toString().startsWith("c${ci}_") }.sorted().toList() }
                files.zip(chunk).map { (f, sc) ->
                    val bytes = Files.readAllBytes(f)
                    val img = ImageIO.read(ByteArrayInputStream(bytes))
                    Frame(((fromSec + sc.t) * 1000).toLong(), bytes, img?.width ?: 0, img?.height ?: 0) to sc.score
                }
            }
            val scoreOf = frames.associate { it.first.timeMs to it.second }
            val unique = dedupe(frames.map { it.first })
            return if (unique.size <= maxFrames) unique
            else unique.sortedByDescending { if (it == unique.first()) Double.MAX_VALUE else scoreOf[it.timeMs] ?: 0.0 }.take(maxFrames).sortedBy { it.timeMs }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /** One keyframe roughly every [every] seconds. */
    private fun pickEvenly(all: List<Scored>, every: Double): List<Scored> {
        val out = mutableListOf<Scored>()
        var next = all.first().t
        for (s in all) if (s.t >= next) { out += s; next = s.t + every }
        return out
    }

    /** Single frames at the given times (seconds), e.g. to look at one slide in full resolution. */
    suspend fun at(video: Path, timesSec: List<Double>, width: Int): List<Frame> {
        val ffmpeg = Ffmpeg.require()
        val work = Files.createTempDirectory("frames")
        try {
            return timesSec.sorted().mapIndexedNotNull { i, t ->
                val out = work.resolve("at$i.jpg")
                val (code, _) = Ffmpeg.run(
                    listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-ss", "$t", "-i", video.toString(),
                        "-frames:v", "1", "-vf", "scale='min($width,iw)':-2", "-q:v", "2", "-y", out.toString()), 120,
                )
                if (code != 0 || !Files.exists(out)) return@mapIndexedNotNull null
                val bytes = Files.readAllBytes(out)
                val img = ImageIO.read(ByteArrayInputStream(bytes))
                Frame((t * 1000).toLong(), bytes, img?.width ?: 0, img?.height ?: 0)
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Removes blank frames (black screen, empty projector) and frames that look almost the same as the
     * previous kept frame (same slide).
     */
    fun dedupe(frames: List<Frame>, maxBlockDiff: Double = 6.0): List<Frame> {
        val nonBlank = frames.filterNot { runCatching { isBlank(it.jpeg) }.getOrDefault(false) }.ifEmpty { frames.take(1) }
        val kept = mutableListOf<Frame>()
        var last: IntArray? = null
        for (f in nonBlank) {
            val t = runCatching { thumb(f.jpeg) }.getOrNull()
            if (t != null && last != null && maxBlockDifference(t, last) < maxBlockDiff) continue
            kept += f; last = t
        }
        return kept
    }

    private const val TW = 128
    private const val TH = 72

    private fun thumb(jpeg: ByteArray): IntArray {
        val img = ImageIO.read(ByteArrayInputStream(jpeg))
        val small = BufferedImage(TW, TH, BufferedImage.TYPE_BYTE_GRAY)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, TW, TH, null); dispose()
        }
        return IntArray(TW * TH) { small.raster.getSample(it % TW, it / TW, 0) }
    }

    /**
     * Largest mean absolute difference over 8×8 blocks. A new line of text on a slide changes a few blocks
     * strongly while JPEG noise between two frames of the same slide stays low everywhere.
     */
    private fun maxBlockDifference(a: IntArray, b: IntArray): Double {
        var max = 0.0
        for (by in 0 until TH / 8) for (bx in 0 until TW / 8) {
            var sum = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                val k = (by * 8 + y) * TW + bx * 8 + x
                sum += kotlin.math.abs(a[k] - b[k])
            }
            max = maxOf(max, sum / 64.0)
        }
        return max
    }

    /**
     * True for frames without content: (almost) uniform pictures, or mostly black screens (≥ 85 % dark pixels,
     * e.g. a paused screen share with only the small webcam inset). Sparse white slides are kept.
     */
    fun isBlank(jpeg: ByteArray): Boolean {
        val img = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return false
        val small = BufferedImage(64, 36, BufferedImage.TYPE_BYTE_GRAY)
        small.createGraphics().apply { drawImage(img, 0, 0, 64, 36, null); dispose() }
        val v = IntArray(64 * 36) { small.raster.getSample(it % 64, it / 64, 0) }
        val mean = v.average()
        val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        val dark = v.count { it < 25 }.toDouble() / v.size
        return sd < 6.0 || dark >= 0.85
    }

    /** Contact sheet: frames in a grid, each labelled with its timestamp. */
    fun grid(frames: List<Frame>, label: (Frame) -> String, cellWidth: Int = 480): ByteArray {
        require(frames.isNotEmpty())
        val cols = ceil(sqrt(frames.size.toDouble())).toInt().coerceIn(1, 4)
        val rows = ceil(frames.size / cols.toDouble()).toInt()
        val first = frames.first()
        val cellH = if (first.width > 0) cellWidth * first.height / first.width else cellWidth * 9 / 16
        val img = BufferedImage(cols * cellWidth, rows * cellH, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.DARK_GRAY; g.fillRect(0, 0, img.width, img.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        frames.forEachIndexed { i, f ->
            val x = (i % cols) * cellWidth; val y = (i / cols) * cellH
            ImageIO.read(ByteArrayInputStream(f.jpeg))?.let { g.drawImage(it, x, y, cellWidth, cellH, null) }
            val text = label(f)
            val w = g.fontMetrics.stringWidth(text) + 12
            g.color = Color(0, 0, 0, 170); g.fillRect(x + 4, y + 4, w, 26)
            g.color = Color.WHITE; g.drawString(text, x + 10, y + 23)
            g.color = Color.BLACK; g.stroke = BasicStroke(2f); g.drawRect(x, y, cellWidth, cellH)
        }
        g.dispose()
        return jpeg(img, 0.82f)
    }

    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val bo = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bo).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return bo.toByteArray()
    }
}
