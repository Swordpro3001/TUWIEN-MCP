package dev.tuwelmcp

import dev.lecturetubemcp.api.LectureTubeClient
import dev.lecturetubemcp.api.LectureTubeService
import dev.tuwelmcp.files.FileText
import dev.tuwelmcp.moodle.Recordings
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileTextTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    private fun pdf(vararg pages: String): ByteArray {
        PDDocument().use { doc ->
            pages.forEach { text ->
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(72f, 700f); cs.showText(text); cs.endText()
                }
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test fun pdfPagesAndRange() {
        val b = pdf("Erste Seite Analysis", "Second page Fourier")
        val all = FileText.extract("vo.pdf", "application/pdf", b).text!!
        assertContains(all, "[PDF, 2 pages]"); assertContains(all, "--- page 2 ---"); assertContains(all, "Fourier")
        val p2 = FileText.extract("vo.pdf", null, b, pages = "2").text!!
        assertTrue("Analysis" !in p2 && "Fourier" in p2)
    }

    @Test fun docxPptxXlsx() {
        val docx = zip("word/document.xml" to """<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Hallo</w:t></w:r><w:r><w:t xml:space="preserve"> Welt</w:t></w:r></w:p><w:p><w:r><w:t>Zeile 2</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
        assertEquals("Hallo Welt\nZeile 2", FileText.extract("a.docx", null, docx).text)
        val pptx = zip(
            "ppt/slides/slide10.xml" to """<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>Zehn</a:t></a:r></a:p></p:sld>""".toByteArray(),
            "ppt/slides/slide2.xml" to """<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>Zwei</a:t></a:r></a:p></p:sld>""".toByteArray(),
        )
        val t = FileText.extract("s.pptx", null, pptx).text!!
        assertTrue(t.indexOf("slide 2") < t.indexOf("slide 10"), t)
        val xlsx = zip(
            "xl/sharedStrings.xml" to """<sst xmlns="x"><si><t>Name</t></si><si><t>Punkte</t></si></sst>""".toByteArray(),
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="x"><sheetData><row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row><row><c t="inlineStr"><is><t>Anna</t></is></c><c><v>42</v></c></row></sheetData></worksheet>""".toByteArray(),
        )
        assertContains(FileText.extract("g.xlsx", null, xlsx).text!!, "Name\tPunkte\nAnna\t42")
    }

    @Test fun zipListReadAllAndNested() {
        val inner = zip("deep.txt" to "tief".toByteArray())
        val z = zip(
            "src/Main.java" to "class Main {}".toByteArray(),
            "doc/angabe.pdf" to pdf("Aufgabe 1"),
            "img.png" to byteArrayOf(0x89.toByte(), 'P'.code.toByte()),
            "inner.zip" to inner,
            "__MACOSX/._x" to byteArrayOf(1),
        )
        val ex = FileText.extract("vorlage.zip", "application/zip", z)
        assertNotNull(ex.entries); assertEquals(4, ex.entries.size)
        val all = FileText.zipAllText(z, 100_000)
        assertContains(all, "=== src/Main.java ==="); assertContains(all, "Aufgabe 1"); assertContains(all, "tief"); assertContains(all, "img.png (image")
        assertEquals("class Main {}", String(FileText.zipEntry(z, "Main.java")))
    }

    @Test fun htmlToText() {
        val t = FileText.htmlToText("""<h2>Woche 1</h2><p>Siehe <a href="https://video.tuwien.ac.at/!v/KgSNekCVlvX">Aufzeichnung</a>&nbsp;&amp; Folien</p><ul><li>A</li><li>B</li></ul><script>x()</script>""")
        assertContains(t, "## Woche 1"); assertContains(t, "Aufzeichnung (https://video.tuwien.ac.at/!v/KgSNekCVlvX) & Folien"); assertContains(t, "• B")
        assertTrue("x()" !in t)
    }

    @Test fun recordingLinksAndLva() {
        val r = Recordings(LectureTubeService(LectureTubeClient()))
        val html = """<iframe src="https://video.tuwien.ac.at/~embed/!v/:97c381cc-88f8-44ab-a98e-98d10716e96d"></iframe>
            <a href="https://video.tuwien.ac.at/!s/D_IbwaCU8_t">Serie</a> https://lecturetube.video.tuwien.ac.at/play/5a029239-f0a3-4afe-8c7c-b3e495d5df6a"""
        val found = r.linksIn(html, "test").map { it.kind to it.ref }
        assertEquals(
            listOf("lecture" to "97c381cc-88f8-44ab-a98e-98d10716e96d", "series" to "D_IbwaCU8_t", "lecture" to "5a029239-f0a3-4afe-8c7c-b3e495d5df6a"),
            found,
        )
        assertEquals("101.883" to "2025W", r.lvaAndSemester("101.883 Technische Mathematik (VO) 2025W"))
        assertEquals("183.A42" to "2026S", r.lvaAndSemester("SS 2026", "183.A42 VU Software"))
    }
}
