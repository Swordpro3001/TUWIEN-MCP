package dev.tuwelmcp.moodle

import dev.lecturetubemcp.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Recordings published through a TUWEL Opencast activity (`/mod/opencast/view.php`). LectureTube refuses these
 * episodes, but the activity page embeds a player config whose MP4s lie on the public LectureTube CDN.
 */
object OpencastActivity {
    data class Episode(val cmid: Long, val id: String, val title: String) {
        val url: String get() = "${Config.TUWEL}/mod/opencast/view.php?id=$cmid&e=$id"
    }

    data class Stream(val content: String, val height: Int, val url: String)

    private val link = Regex("""/mod/opencast/view\.php\?id=(\d+)(?:&|&amp;)e=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
    private val listed = Regex("""<li class="episode" title="([^"]*)">\s*<a href="[^"]*?e=([0-9a-f-]{36})"""")

    private val collection = Regex("""/mod/opencast/view\.php\?id=(\d+)""")

    /** cmid of an activity URL without `e=` (the collection page), or null. */
    fun parseCollection(url: String): Long? = if (parseUrl(url) != null) null else collection.find(url)?.groupValues?.get(1)?.toLong()

    /** (cmid, episode id) of an activity URL with `e=`, or null. */
    fun parseUrl(url: String): Pair<Long, String>? = link.find(url)?.let { it.groupValues[1].toLong() to it.groupValues[2] }

    /** Episodes listed on a collection page; a single-recording page yields its own episode. */
    fun episodes(cmid: Long, html: String): List<Episode> {
        val list = listed.findAll(html).map { Episode(cmid, it.groupValues[2], unescape(it.groupValues[1])) }.distinctBy { it.id }.toList()
        if (list.isNotEmpty()) return list
        val meta = jsonAfter(html, "\"metadata\":") as? JsonObject ?: return emptyList()
        val id = (meta["id"] as? JsonPrimitive)?.content ?: return emptyList()
        return listOf(Episode(cmid, id, (meta["title"] as? JsonPrimitive)?.content ?: id))
    }

    /** Title and MP4 streams of an episode page's player config. */
    fun player(html: String): Pair<String?, List<Stream>> {
        val title = ((jsonAfter(html, "\"metadata\":") as? JsonObject)?.get("title") as? JsonPrimitive)?.content
        val streams = (jsonAfter(html, "\"streams\":") as? JsonArray).orEmpty().flatMap { st ->
            val content = (st.jsonObject["content"] as? JsonPrimitive)?.content ?: "presentation"
            (st.jsonObject["sources"] as? JsonObject)?.get("mp4")?.jsonArray.orEmpty().mapNotNull { m ->
                val src = m.jsonObject["src"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val h = (m.jsonObject["res"] as? JsonObject)?.get("h")?.jsonPrimitive?.intOrNull ?: 0
                Stream(content, h, src).takeIf { src.startsWith("https://") }
            }
        }
        return title to streams
    }

    /** Smallest file for speech-to-text; for frames the camera or slides track closest to 720p. */
    fun pick(streams: List<Stream>, forAudio: Boolean, track: String = "auto"): Stream {
        if (forAudio) return streams.minBy { it.height }
        val want = if (track == "camera") "presenter" else "presentation"
        val pool = streams.filter { it.content == want }.ifEmpty { streams }
        return pool.filter { it.height >= 720 }.minByOrNull { it.height } ?: pool.maxBy { it.height }
    }

    /** The JSON value right after [key] (object or array), found by matching brackets outside strings. */
    private fun jsonAfter(html: String, key: String): kotlinx.serialization.json.JsonElement? {
        val start = html.indexOf(key).takeIf { it >= 0 }?.plus(key.length) ?: return null
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && (c == '{' || c == '[') -> depth++
                !inString && (c == '}' || c == ']') -> if (--depth == 0) return runCatching { Json.parseToJsonElement(html.substring(start, i + 1)) }.getOrNull()
            }
            i++
        }
        return null
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }.replace("&amp;", "&")
}
