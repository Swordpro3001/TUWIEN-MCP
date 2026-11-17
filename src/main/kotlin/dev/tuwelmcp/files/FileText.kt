package dev.tuwelmcp.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

data class ZipItem(val path: String, val size: Long, val compressedSize: Long, val isDirectory: Boolean)

/** Result of reading a file: text, an image for the model to look at, or an archive listing. */
data class Extracted(
    val text: String? = null,
    val image: Pair<ByteArray, String>? = null,
    val entries: List<ZipItem>? = null,
    val note: String? = null,
)

/**
 * Turns course files into text an LLM can read. Everything happens in memory;
 * ZIP archives are read directly without unpacking to disk (except a temp file for random access).
 */
object FileText {
    private val textExt = setOf(
        "txt", "md", "markdown", "rst", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "log",
        "tex", "bib", "sty", "cls", "py", "java", "kt", "kts", "scala", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb",
        "php", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "sql", "sh", "bash", "zsh", "ps1", "bat", "m", "r", "jl",
        "hs", "ml", "lisp", "clj", "erl", "ex", "exs", "swift", "dart", "lua", "pl", "asm", "s", "vhd", "vhdl", "v", "sv",
        "gradle", "properties", "dockerfile", "makefile", "cmake", "srt", "vtt", "ics", "svg", "gitignore", "env",
    )
    private val imageExt = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")
    private val json = Json { ignoreUnknownKeys = true }

    fun ext(name: String): String {
        val base = name.substringAfterLast('/').lowercase()
        if (base == "makefile" || base == "dockerfile") return base
        return base.substringAfterLast('.', "")
    }

    fun isZip(name: String, mime: String?, bytes: ByteArray?): Boolean =
        ext(name) == "zip" || mime?.contains("zip") == true && ext(name) !in setOf("docx", "pptx", "xlsx", "odt", "odp", "ods", "jar") ||
            (bytes != null && ext(name).isEmpty() && bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())

    /**
     * @param pages optional PDF page range like "1-5" or "3".
     */
    fun extract(name: String, mime: String?, bytes: ByteArray, pages: String? = null, depth: Int = 0): Extracted {
        val e = ext(name)
        return try {
            when {
                e == "pdf" || mime == "application/pdf" -> Extracted(text = pdf(bytes, pages))
                e == "docx" -> Extracted(text = ooxml(bytes, Regex("word/(document|footnotes|endnotes)\\.xml"), setOf("t"), setOf("p", "tr"), setOf("tab"), slideHeaders = false))
                e == "pptx" -> Extracted(text = ooxml(bytes, Regex("ppt/(slides|notesSlides)/(slide|notesSlide)\\d+\\.xml"), setOf("t"), setOf("p"), emptySet(), slideHeaders = true))
                e == "xlsx" -> Extracted(text = xlsx(bytes))
                e in setOf("odt", "odp", "ods") -> Extracted(text = odf(bytes))
                e == "ipynb" -> Extracted(text = notebook(bytes))
                e in setOf("html", "htm", "xhtml") || mime?.startsWith("text/html") == true -> Extracted(text = htmlToText(decode(bytes)))
                e in imageExt -> Extracted(image = bytes to imageExt.getValue(e))
                isZip(name, mime, bytes) -> Extracted(entries = zipList(bytes), note = if (depth > 0) "nested archive" else null)
                e in setOf("doc", "ppt", "xls", "rar", "7z", "gz", "tgz", "tar", "mp4", "mp3", "wav", "mkv", "mov") ->
                    Extracted(note = "Format .$e is not supported for text extraction.")
                e in textExt || mime?.startsWith("text/") == true || looksLikeText(bytes) -> Extracted(text = decode(bytes))
                else -> Extracted(note = "Binary file (${mime ?: "unknown type"}, ${bytes.size} bytes); no text extracted.")
            }
        } catch (t: Throwable) {
            Extracted(note = "Could not read $name: ${t::class.simpleName}: ${t.message}")
        }
    }

    private fun <T> withZip(bytes: ByteArray, block: (ZipFile) -> T): T {
        val tmp = Files.createTempFile("tuwel-mcp", ".zip")
        try {
            Files.write(tmp, bytes)
            val zf = try {
                ZipFile(tmp.toFile(), Charsets.UTF_8).also { z -> z.entries().toList() }
            } catch (_: ZipException) {
                ZipFile(tmp.toFile(), Charset.forName("CP437")) // old Windows zips with non-UTF-8 names
            } catch (_: IllegalArgumentException) {
                ZipFile(tmp.toFile(), Charset.forName("CP437"))
            }
            return zf.use(block)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun zipList(bytes: ByteArray): List<ZipItem> = withZip(bytes) { z ->
        z.entries().toList()
            .filterNot { it.name.startsWith("__MACOSX/") || it.name.endsWith(".DS_Store") }
            .map { ZipItem(it.name, it.size, it.compressedSize, it.isDirectory) }
    }

    fun zipEntry(bytes: ByteArray, path: String, maxBytes: Long = 50L * 1024 * 1024): ByteArray = withZip(bytes) { z ->
        val entry = z.getEntry(path) ?: z.entries().toList().firstOrNull { it.name.equals(path, true) || it.name.endsWith("/$path") }
            ?: throw IllegalArgumentException("No entry '$path' in the archive.")
        require(entry.size <= maxBytes) { "Entry is too large (${entry.size} bytes)." }
        z.getInputStream(entry).use { it.readNBytes(maxBytes.toInt()) }
    }

    /** Reads all text-like entries of an archive into one document with `=== path ===` headers. */
    fun zipAllText(bytes: ByteArray, maxChars: Int, depth: Int = 0): String = withZip(bytes) { z ->
        val sb = StringBuilder()
        val skipped = mutableListOf<String>()
        for (entry in z.entries().toList().sortedBy { it.name }) {
            if (entry.isDirectory || entry.name.startsWith("__MACOSX/") || entry.name.endsWith(".DS_Store")) continue
            if (sb.length >= maxChars) { skipped += entry.name; continue }
            if (entry.size > 30L * 1024 * 1024) { skipped += "${entry.name} (too large)"; continue }
            val data = z.getInputStream(entry).use { it.readAllBytes() }
            val ex = if (isZip(entry.name, null, data) && depth < 2) {
                Extracted(text = "[nested archive]\n" + zipAllText(data, maxChars - sb.length, depth + 1))
            } else {
                extract(entry.name, null, data, depth = depth + 1)
            }
            when {
                ex.text != null -> sb.append("\n=== ").append(entry.name).append(" ===\n").append(ex.text.trim()).append('\n')
                ex.image != null -> skipped += "${entry.name} (image, read it with entry=…)"
                else -> skipped += "${entry.name} (${ex.note ?: "binary"})"
            }
        }
        if (skipped.isNotEmpty()) sb.append("\n[Not included: ").append(skipped.joinToString("; ")).append("]")
        sb.toString()
    }

    private fun pdf(bytes: ByteArray, pages: String?): String = Loader.loadPDF(bytes).use { doc ->
        val n = doc.numberOfPages
        val (from, to) = parseRange(pages, n)
        val stripper = PDFTextStripper()
        val sb = StringBuilder("[PDF, $n pages${if (from != 1 || to != n) ", showing $from–$to" else ""}]\n")
        for (p in from..to) {
            stripper.startPage = p; stripper.endPage = p
            val t = stripper.getText(doc).trim()
            sb.append("\n--- page ").append(p).append(" ---\n").append(t.ifEmpty { "[no text on this page, probably a scanned image]" }).append('\n')
        }
        sb.toString()
    }

    private fun parseRange(r: String?, n: Int): Pair<Int, Int> {
        if (r.isNullOrBlank()) return 1 to n
        val parts = r.split('-').map { it.trim().toIntOrNull() }
        val a = (parts.getOrNull(0) ?: 1).coerceIn(1, n)
        val b = (if (parts.size > 1) parts[1] ?: n else a).coerceIn(a, n)
        return a to b
    }

    private fun zipTexts(bytes: ByteArray, nameFilter: Regex): List<Pair<String, ByteArray>> = withZip(bytes) { z ->
        z.entries().toList().filter { nameFilter.matches(it.name) }
            .sortedWith(compareBy({ it.name.substringBeforeLast('/') }, { Regex("\\d+").find(it.name.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }))
            .map { it.name to z.getInputStream(it).use { s -> s.readAllBytes() } }
    }

    /** Office files come from other course members: no DTDs, so no external entities that could read local files. */
    private fun safeXml(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    private fun xmlText(xml: ByteArray, textTags: Set<String>?, breakTags: Set<String>, tabTags: Set<String>): String {
        val r = safeXml().createXMLStreamReader(ByteArrayInputStream(xml))
        val sb = StringBuilder()
        var depthInText = 0
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val n = r.localName
                    if (textTags == null || n in textTags) depthInText++
                    if (n in tabTags) sb.append('\t')
                    if (n == "br" || n == "line-break") sb.append('\n')
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (depthInText > 0) sb.append(r.text)
                XMLStreamConstants.END_ELEMENT -> {
                    val n = r.localName
                    if (textTags == null || n in textTags) depthInText--
                    if (n in breakTags) sb.append('\n')
                }
            }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun ooxml(bytes: ByteArray, parts: Regex, textTags: Set<String>, breakTags: Set<String>, tabTags: Set<String>, slideHeaders: Boolean): String {
        val files = zipTexts(bytes, parts)
        return files.joinToString("\n\n") { (name, xml) ->
            val text = xmlText(xml, textTags, breakTags, tabTags)
            if (slideHeaders) {
                val num = Regex("\\d+").find(name.substringAfterLast('/'))?.value
                (if (name.contains("notesSlide")) "--- notes for slide $num ---\n" else "--- slide $num ---\n") + text
            } else text
        }
    }

    private fun xlsx(bytes: ByteArray): String {
        val shared = zipTexts(bytes, Regex("xl/sharedStrings\\.xml")).firstOrNull()?.second?.let { xml ->
            // Each <si> is one string; collect <t> texts per <si>.
            val out = mutableListOf<String>()
            val r = safeXml().createXMLStreamReader(ByteArrayInputStream(xml))
            var cur: StringBuilder? = null; var inT = false
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) { "si" -> cur = StringBuilder(); "t" -> inT = true }
                    XMLStreamConstants.CHARACTERS -> if (inT) cur?.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) { "si" -> { out += cur.toString(); cur = null }; "t" -> inT = false }
                }
            }
            out
        }.orEmpty()
        val sheets = zipTexts(bytes, Regex("xl/worksheets/sheet\\d+\\.xml"))
        return sheets.joinToString("\n\n") { (name, xml) ->
            val sb = StringBuilder("--- ${name.substringAfterLast('/').removeSuffix(".xml")} ---\n")
            val r = safeXml().createXMLStreamReader(ByteArrayInputStream(xml))
            var type: String? = null; var inV = false; var rows = 0
            val row = mutableListOf<String>()
            while (r.hasNext() && rows < 2000) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "c" -> type = r.getAttributeValue(null, "t")
                        "v", "t" -> inV = true
                    }
                    XMLStreamConstants.CHARACTERS -> if (inV) {
                        row += if (type == "s") shared.getOrNull(r.text.trim().toIntOrNull() ?: -1) ?: r.text else r.text
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "v", "t" -> inV = false
                        "row" -> { if (row.isNotEmpty()) sb.append(row.joinToString("\t")).append('\n'); row.clear(); rows++ }
                    }
                }
            }
            sb.toString()
        }
    }

    private fun odf(bytes: ByteArray): String {
        val content = zipTexts(bytes, Regex("content\\.xml")).firstOrNull()?.second ?: return ""
        return xmlText(content, null, setOf("p", "h", "table-row", "page"), setOf("tab", "table-cell"))
    }

    private fun notebook(bytes: ByteArray): String {
        val nb = json.parseToJsonElement(decode(bytes)).jsonObject
        return (nb["cells"] as? JsonArray).orEmpty().joinToString("\n\n") { c ->
            val o = c.jsonObject
            val type = (o["cell_type"] as? JsonPrimitive)?.content ?: "?"
            val src = when (val s = o["source"]) {
                is JsonArray -> s.joinToString("") { (it as JsonPrimitive).content }
                is JsonPrimitive -> s.content
                else -> ""
            }
            val outputs = (o["outputs"] as? JsonArray).orEmpty().mapNotNull { out ->
                (out.jsonObject["text"] as? JsonArray)?.joinToString("") { (it as JsonPrimitive).content }
                    ?: ((out.jsonObject["data"] as? JsonObject)?.get("text/plain") as? JsonArray)?.joinToString("") { (it as JsonPrimitive).content }
            }.joinToString("\n").take(2000)
            "[$type]\n$src" + if (outputs.isNotBlank()) "\n[output]\n$outputs" else ""
        }
    }

    fun decode(bytes: ByteArray): String {
        val utf8: CharsetDecoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        return try {
            utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }.removePrefix("\uFEFF")
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.take(4096)
        val ctrl = sample.count { b -> val i = b.toInt() and 0xff; i < 9 || (i in 14..31) }
        return ctrl * 100 / sample.size < 2
    }

    private val entityMap = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")

    /** Light HTML → text: keeps paragraphs, list items and links. */
    fun htmlToText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var s = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<a\\s[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")) { m ->
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val href = m.groupValues[1]
            if (text.isEmpty() || text == href) href else "$text ($href)"
        }
        s = s.replace(Regex("(?i)<(iframe|video|source)\\s[^>]*src=\"([^\"]+)\"[^>]*>")) { m -> " [${m.groupValues[1]}: ${m.groupValues[2]}] " }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|tr|table|ul|ol|section|article|blockquote|pre)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "\n• ")
            .replace(Regex("(?i)<h[1-6][^>]*>"), "\n## ")
            .replace(Regex("(?i)</t[dh]>"), "\t")
            .replace(Regex("<[^>]+>"), "")
        entityMap.forEach { (k, v) -> s = s.replace(k, v) }
        s = s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return s.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
