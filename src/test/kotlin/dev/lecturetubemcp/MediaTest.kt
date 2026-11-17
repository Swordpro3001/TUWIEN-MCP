package dev.lecturetubemcp

import dev.lecturetubemcp.api.Cue
import dev.lecturetubemcp.media.Frame
import dev.lecturetubemcp.media.Keyframes
import dev.lecturetubemcp.media.MediaTools
import dev.lecturetubemcp.media.WhisperModels
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaTest {
    private fun img(bg: Color, draw: (java.awt.Graphics2D) -> Unit = {}): ByteArray {
        val i = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        val g = i.createGraphics(); g.color = bg; g.fillRect(0, 0, 1280, 720); draw(g); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(i, "jpg", it) }.toByteArray()
    }

    private val black = img(Color.BLACK)
    private val blackWithWebcam = img(Color.BLACK) { g -> g.color = Color(200, 170, 150); g.fillRect(1100, 150, 180, 120) }
    private fun slide(title: String) = img(Color.WHITE) { g ->
        g.color = Color(40, 60, 140); g.font = Font(Font.SANS_SERIF, Font.BOLD, 48); g.drawString(title, 80, 120)
        g.color = Color.DARK_GRAY; g.font = Font(Font.SANS_SERIF, Font.PLAIN, 28); g.drawString("• one short bullet", 100, 220)
    }

    @Test fun blankDetection() {
        assertTrue(Keyframes.isBlank(black))
        assertTrue(Keyframes.isBlank(blackWithWebcam), "paused screen share with webcam inset")
        assertFalse(Keyframes.isBlank(slide("Bis 1980er Jahre")), "sparse white slide must be kept")
    }

    @Test fun dedupeDropsRepeatsAndBlanks() {
        val a = slide("Leibrente"); val b = slide("Gauß und Sterbetafeln — eine ganz andere Folie mit viel mehr Text")
        val frames = listOf(Frame(0, black, 1280, 720), Frame(1000, a, 1280, 720), Frame(2000, a, 1280, 720), Frame(3000, blackWithWebcam, 1280, 720), Frame(4000, b, 1280, 720))
        assertEquals(listOf(1000L, 4000L), Keyframes.dedupe(frames).map { it.timeMs })
    }

    @Test fun contactSheet() {
        val frames = (0 until 5).map { Frame(it * 60_000L, slide("Folie $it"), 1280, 720) }
        val sheet = ImageIO.read(ByteArrayInputStream(Keyframes.grid(frames, { "${it.timeMs / 60_000} min" })))
        assertEquals(3 * 480, sheet.width) // 5 frames → 3 columns × 2 rows
        assertEquals(2 * 270, sheet.height)
    }

    @Test fun transcriptWindowAndTruncation() {
        val cues = (0 until 600).map { Cue(it * 10_000L, it * 10_000L + 9_000, "Satz Nummer $it über Sterbetafeln und Leibrenten.") }
        val win = MediaTools.transcriptText("H\n", cues, fromMinute = 10, toMinute = 12)
        assertTrue(win.startsWith("H\n[00:10:00]")); assertFalse(win.contains("[00:12:"))
        val cut = MediaTools.transcriptText("H\n", cues, null, null, maxChars = 2000)
        assertContains(cut, "Continue with fromMinute=")
    }

    @Test fun mergeAfterRetryDropsOverlap() {
        val first = listOf(Cue(0, 4000, "A"), Cue(4000, 8000, "B"), Cue(8000, 9500, "C"))
        val retry = listOf(Cue(8100, 9600, "C'"), Cue(9600, 12000, "D")) // resumed at 8 s, overlaps C
        assertEquals(listOf("A", "B", "C", "D"), dev.lecturetubemcp.media.mergeCues(first + retry).map { it.text })
    }

    @Test fun modelAliases() {
        assertEquals("small", WhisperModels.default)
        assertTrue(WhisperModels.file("turbo").toString().endsWith("ggml-large-v3-turbo-q5_0.bin"))
    }
}
