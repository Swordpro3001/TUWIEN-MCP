package dev.tuwelmcp.moodle

import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.api.arr
import dev.lecturetubemcp.api.get
import dev.lecturetubemcp.api.s
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A LectureTube recording or series referenced by a TUWEL course. */
data class RecordingRef(
    /** "lecture" or "series". */
    val kind: String,
    /** Something [LectureTubeService.event] / [LectureTubeService.series] accepts (key, Tobira ID or UUID). */
    val ref: String,
    /** Where it was found: a module name, "course ACL" or "LVA number". */
    val foundIn: String,
    val title: String? = null,
)

/**
 * Finds LectureTube recordings for a TUWEL course using three signals:
 * 1. Links in the course: `/!v/<key>`, `/!s/<key>`, `/~embed/!v/…`, Opencast player URLs, in module
 *    URLs, descriptions, pages and LTI launch parameters.
 * 2. Opencast series whose ACL grants `TUWEL_<courseId>_Learner` (set by the TUWEL ↔ LectureTube integration).
 * 3. Series whose title starts with the course's LVA number (e.g. `101.883`) for the same semester.
 */
class Recordings(private val lt: LectureTubeService) {
    private val uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val patterns = listOf(
        "lecture" to Regex("""video\.tuwien\.ac\.at/(?:~embed/)?!v/(:$uuid|[A-Za-z0-9_-]{11})"""),
        "series" to Regex("""video\.tuwien\.ac\.at/(?:~embed/)?!s/(:$uuid|[A-Za-z0-9_-]{11})"""),
        "lecture" to Regex("""lecturetube\.video\.tuwien\.ac\.at/(?:play/|[a-z/]*watch\.html\?id=|engage/[^"'\s]*\?id=)($uuid)"""),
    )

    fun linksIn(text: String, foundIn: String): List<RecordingRef> =
        patterns.flatMap { (kind, re) -> re.findAll(text).map { RecordingRef(kind, it.groupValues[1].removePrefix(":"), foundIn) } }

    // Opencast series list with ACLs, cached for 30 minutes (≈5 requests with a login).
    private val cacheLock = Mutex()
    private var seriesCache: List<JsonElement>? = null
    private var cacheTime = 0L

    private suspend fun allSeries(): List<JsonElement> = cacheLock.withLock {
        val now = System.currentTimeMillis()
        seriesCache?.takeIf { now - cacheTime < 30 * 60_000 }?.let { return it }
        val out = mutableListOf<JsonElement>()
        var offset = 0
        while (true) {
            val page = lt.client.opencast("/search/series.json", mapOf("limit" to 250, "offset" to offset))
            val res = page["result"].arr()
            out.addAll(res)
            offset += 250
            if (res.size < 250 || offset > 20_000) break
        }
        seriesCache = out; cacheTime = now
        out
    }

    private fun roles(series: JsonElement): Set<String> =
        (series["searchable_acl"]["read"].arr() + series["searchable_acl"]["write"].arr()).mapNotNull { it.s() }.toSet()

    suspend fun seriesForCourse(courseId: Long): List<RecordingRef> =
        allSeries().filter { s -> roles(s).any { it.startsWith("TUWEL_${courseId}_") } }.mapNotNull { s ->
            val id = s["dc"]["identifier"].arr().firstOrNull().s() ?: return@mapNotNull null
            RecordingRef("series", id, "course ACL (TUWEL_${courseId}_Learner)", s["dc"]["title"].arr().firstOrNull().s())
        }

    /** LVA number like `101.883` or `183.A42`, and a semester like `2025W`, from a course name. */
    fun lvaAndSemester(vararg names: String?): Pair<String?, String?> {
        val all = names.filterNotNull().joinToString(" ")
        val lva = Regex("""\b(\d{3}\.[0-9A-Z]{3})\b""").find(all)?.groupValues?.get(1)
        val sem = Regex("""\b(20\d{2})\s?([SW])\b|\b([SW])S?\s?(20\d{2})\b""").find(all)?.let { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + m.groupValues[2] else m.groupValues[4] + m.groupValues[3]
        }
        return lva to sem
    }

    suspend fun seriesByLva(lva: String, semester: String?): List<RecordingRef> =
        allSeries().filter { s ->
            val t = s["dc"]["title"].arr().firstOrNull().s().orEmpty()
            t.startsWith(lva) && (semester == null || t.contains("($semester)"))
        }.mapNotNull { s ->
            val id = s["dc"]["identifier"].arr().firstOrNull().s() ?: return@mapNotNull null
            RecordingRef("series", id, "LVA number $lva${semester?.let { " ($it)" } ?: ""}", s["dc"]["title"].arr().firstOrNull().s())
        }

    /** All signals combined, de-duplicated (lectures that belong to a found series are kept separately). */
    suspend fun forCourse(course: JsonObject?, courseId: Long, contentsText: List<Pair<String, String>>): List<RecordingRef> {
        val found = mutableListOf<RecordingRef>()
        contentsText.forEach { (where, text) -> found += linksIn(text, where) }
        found += runCatching { seriesForCourse(courseId) }.getOrDefault(emptyList())
        val (lva, sem) = lvaAndSemester(course?.get("fullname").s(), course?.get("shortname").s(), course?.get("idnumber").s())
        if (lva != null) found += runCatching { seriesByLva(lva, sem) }.getOrDefault(emptyList())
        return found.distinctBy { it.kind + ":" + it.ref.lowercase() }
    }
}
