package dev.tuwelmcp

import dev.lecturetubemcp.api.LectureTubeService
import dev.tuwelmcp.files.FileText
import dev.tuwelmcp.moodle.MoodleClient
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Attacks that course content could trigger through the model. */
class SecurityTest {
    @Test
    fun tokenOnlyGoesToTuwelItself() {
        MoodleClient.requireTuwelUrl("https://tuwel.tuwien.ac.at/webservice/pluginfile.php/1/mod_resource/content/0/a.pdf")
        MoodleClient.requireTuwelUrl("https://TUWEL.tuwien.ac.at:443/pluginfile.php/1/a.pdf")
        for (evil in listOf(
            "https://tuwel.tuwien.ac.at.evil.com/webservice/pluginfile.php/1/a.pdf",
            "https://tuwel.tuwien.ac.at@evil.com/webservice/pluginfile.php/1/a.pdf",
            "http://tuwel.tuwien.ac.at/pluginfile.php/1/a.pdf",
            "https://tuwel.tuwien.ac.at:8443/pluginfile.php/1/a.pdf",
            "https://evil.com/?https://tuwel.tuwien.ac.at/pluginfile.php",
            "file:///etc/passwd",
            "not a url",
        )) assertFailsWith<IllegalArgumentException>(evil) { MoodleClient.requireTuwelUrl(evil) }
    }

    @Test
    fun officeFilesCannotReadLocalFiles() {
        val secret = Files.createTempFile("secret", ".txt").also { Files.writeString(it, "TOP-SECRET-MARKER") }
        val entity = """<!DOCTYPE x [<!ENTITY leak SYSTEM "${secret.toUri()}">]>"""
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val xlsx = zip(
            "xl/sharedStrings.xml" to """<?xml version="1.0"?>$entity<sst xmlns="$ns"><si><t>A&leak;</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>""",
        )
        val docx = zip("word/document.xml" to """<?xml version="1.0"?>$entity<document><p><t>A&leak;</t></p></document>""")
        for ((name, bytes) in listOf("x.xlsx" to xlsx, "x.docx" to docx)) {
            val text = runCatching { FileText.extract(name, null, bytes).text }.getOrNull().orEmpty()
            assertFalse("TOP-SECRET-MARKER" in text, "$name leaked a local file: $text")
        }
    }

    @Test
    fun graphqlToolStaysReadOnly() {
        for (doc in listOf("mutation { x }", ",mutation { x }", "﻿mutation { x }", "query { a }\nmutation { b }", "subscription { s }", "Mutation { x }")) {
            assertFalse(LectureTubeService.isReadOnlyDocument(doc), doc)
        }
        for (doc in listOf("query { x }", "{ series { mutationCount } }", "query { search(q: \"mutation\") { id } }", "# mutation\nquery { x }")) {
            assertTrue(LectureTubeService.isReadOnlyDocument(doc), doc)
        }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()
}
