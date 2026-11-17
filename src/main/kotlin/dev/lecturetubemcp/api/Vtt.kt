package dev.lecturetubemcp.api

data class Cue(val startMs: Long, val endMs: Long, val text: String)

object Vtt {
    private val timing = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})\s+-->\s+((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})""")
    private val tags = Regex("<[^>]+>")

    fun parseTime(t: String): Long {
        val parts = t.replace(',', '.').split(':')
        val sec = parts.last().toDouble()
        val min = parts[parts.size - 2].toLong()
        val hrs = if (parts.size == 3) parts[0].toLong() else 0L
        return (hrs * 3600_000) + (min * 60_000) + (sec * 1000).toLong()
    }

    fun parse(vtt: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = vtt.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i])
            if (m == null) { i++; continue }
            val start = parseTime(m.groupValues[1])
            val end = parseTime(m.groupValues[2])
            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(lines[i].trim())
                i++
            }
            val clean = tags.replace(text.toString(), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("\\s+"), " ").trim()
            if (clean.isNotEmpty()) cues += Cue(start, end, clean)
        }
        return dedupe(cues)
    }

    /** ASR captions often repeat the previous line, or overlap it. Drop exact repeats and trim overlaps. */
    private fun dedupe(cues: List<Cue>): List<Cue> {
        val out = mutableListOf<Cue>()
        for (c in cues) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text == c.text && c.startMs - prev.endMs < 2000) {
                out[out.size - 1] = prev.copy(endMs = maxOf(prev.endMs, c.endMs)); continue
            }
            if (prev != null && c.text.startsWith(prev.text) && c.startMs - prev.endMs < 2000) {
                out[out.size - 1] = c.copy(startMs = prev.startMs); continue
            }
            out += c
        }
        return out
    }

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /**
     * Merges cues into paragraphs, each starting with a `[hh:mm:ss]` marker, so a summary
     * can cite timestamps without carrying one timestamp per caption line.
     */
    fun toParagraphs(cues: List<Cue>, paragraphSeconds: Int = 60): String {
        if (cues.isEmpty()) return ""
        val sb = StringBuilder()
        var paraStart = -1L
        for (c in cues) {
            if (paraStart < 0 || c.startMs - paraStart >= paragraphSeconds * 1000L) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append('[').append(fmt(c.startMs)).append("] ")
                paraStart = c.startMs
            } else {
                sb.append(' ')
            }
            sb.append(c.text)
        }
        return sb.toString()
    }
}
