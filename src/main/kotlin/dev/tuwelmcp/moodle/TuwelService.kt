package dev.tuwelmcp.moodle

import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.api.arr
import dev.lecturetubemcp.api.b
import dev.lecturetubemcp.api.d
import dev.lecturetubemcp.api.get
import dev.lecturetubemcp.api.obj
import dev.lecturetubemcp.api.s
import dev.lecturetubemcp.api.transcript
import dev.lecturetubemcp.api.TranscriptResult
import dev.lecturetubemcp.api.audioSource
import dev.lecturetubemcp.api.cuesFor
import dev.lecturetubemcp.api.videoSource
import dev.lecturetubemcp.media.JobState
import dev.lecturetubemcp.media.MediaCache
import dev.lecturetubemcp.media.MediaSource
import dev.lecturetubemcp.media.MediaTarget
import dev.lecturetubemcp.media.MediaTools
import dev.lecturetubemcp.media.Transcriber
import dev.tuwelmcp.files.Extracted
import dev.tuwelmcp.files.FileText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CourseFile(
    val courseId: Long, val cmid: Long, val module: String, val modname: String, val section: String,
    val filename: String, val path: String, val size: Long, val mimetype: String?, val url: String, val modified: Long?,
)

/** Domain operations on TUWEL (Moodle web services), returning compact JSON for the LLM. */
class TuwelService(val moodle: MoodleClient, val lectureTube: LectureTubeService) {
    val recordings = Recordings(lectureTube)
    private val vienna = ZoneId.of("Europe/Vienna")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun time(epoch: Long?): String? = epoch?.takeIf { it > 0 }?.let { fmt.format(Instant.ofEpochSecond(it).atZone(vienna)) }

    private var cachedUserId: Long? = null

    suspend fun siteInfo(): JsonObject = moodle.call("core_webservice_get_site_info").jsonObject

    suspend fun userId(): Long = cachedUserId ?: (siteInfo()["userid"].d()?.toLong() ?: throw MoodleException("No user id")).also { cachedUserId = it }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    /** classification: inprogress (default), future, past, all, favourites, hidden. */
    suspend fun courses(classification: String = "inprogress"): JsonObject {
        val res = moodle.call("core_course_get_enrolled_courses_by_timeline_classification", args {
            put("classification", classification); put("limit", 0); put("offset", 0); put("sort", "fullname")
        })
        val list = res["courses"].arr()
        return buildJsonObject {
            put("classification", classification); put("count", list.size)
            putJsonArray("courses") {
                list.forEach { c ->
                    addJsonObject {
                        put("id", c["id"].d()?.toLong()); put("name", c["fullname"].s()); put("shortname", c["shortname"].s())
                        c["coursecategory"].s()?.let { put("category", it) }
                        put("start", time(c["startdate"].d()?.toLong())); time(c["enddate"].d()?.toLong())?.let { put("end", it) }
                        c["progress"].d()?.let { put("progressPercent", it.toInt()) }
                        if (c["isfavourite"].b() == true) put("favourite", true)
                        put("url", c["viewurl"].s())
                    }
                }
            }
        }
    }

    suspend fun searchCourses(query: String, page: Int = 0): JsonObject {
        val res = moodle.call("core_course_search_courses", args {
            put("criterianame", "search"); put("criteriavalue", query); put("page", page); put("perpage", 25)
        })
        return buildJsonObject {
            put("total", res["total"] ?: JsonPrimitive(0))
            putJsonArray("courses") {
                res["courses"].arr().forEach { c ->
                    addJsonObject {
                        put("id", c["id"].d()?.toLong()); put("name", c["fullname"].s()); put("shortname", c["shortname"].s())
                        put("category", c["categoryname"].s())
                        FileText.htmlToText(c["summary"].s()).takeIf { it.isNotBlank() }?.let { put("summary", it.take(300)) }
                        put("enrollable", c["enrollmentmethods"].arr().any { it.s() == "self" })
                    }
                }
            }
        }
    }

    suspend fun course(courseId: Long): JsonObject? = runCatching {
        moodle.call("core_course_get_courses_by_field", args { put("field", "id"); put("value", courseId.toString()) })["courses"].arr().firstOrNull()?.obj()
    }.getOrNull()

    suspend fun rawContents(courseId: Long): JsonArray =
        moodle.call("core_course_get_contents", args { put("courseid", courseId) }) as? JsonArray
            ?: throw MoodleException("Unexpected response for course $courseId")

    /** Course structure. [section] filters by section number or (part of) its name. */
    suspend fun contents(courseId: Long, section: String? = null, details: Boolean = false): JsonObject {
        val secs = filterSections(rawContents(courseId), section)
        return buildJsonObject {
            put("courseId", courseId)
            putJsonArray("sections") {
                secs.forEach { sec ->
                    addJsonObject {
                        put("section", sec["section"].d()?.toInt()); put("name", sec["name"].s())
                        FileText.htmlToText(sec["summary"].s()).takeIf { it.isNotBlank() }?.let { put("summary", if (details) it else it.take(400)) }
                        putJsonArray("modules") {
                            sec["modules"].arr().forEach { m ->
                                if (m["uservisible"].b() == false && m["visible"].d() == 0.0) return@forEach
                                addJsonObject {
                                    put("cmid", m["id"].d()?.toLong()); put("type", m["modname"].s()); put("name", m["name"].s())
                                    m["url"].s()?.let { put("url", it) }
                                    FileText.htmlToText(m["description"].s()).takeIf { it.isNotBlank() }?.let { put("description", if (details) it else it.take(300)) }
                                    m["dates"].arr().forEach { dt -> dt["label"].s()?.let { l -> put(l.trimEnd(':'), time(dt["timestamp"].d()?.toLong())) } }
                                    val files = m["contents"].arr().filter { it["type"].s() == "file" }
                                    if (files.isNotEmpty() && m["modname"].s() != "page" && m["modname"].s() != "book") {
                                        putJsonArray("files") {
                                            files.forEach { f ->
                                                addJsonObject {
                                                    put("name", (f["filepath"].s()?.takeIf { it != "/" } ?: "") + f["filename"].s())
                                                    put("size", f["filesize"].d()?.toLong()); put("mimetype", f["mimetype"].s())
                                                    put("url", f["fileurl"].s())
                                                }
                                            }
                                        }
                                    }
                                    if (m["modname"].s() == "url") m["contents"].arr().firstOrNull()?.get("fileurl").s()?.let { put("externalUrl", it) }
                                    val lt = recordings.linksIn(m.toString(), m["name"].s() ?: "")
                                    if (lt.isNotEmpty()) put("lectureTube", buildJsonArray { lt.forEach { r -> addJsonObject { put("kind", r.kind); put("ref", r.ref) } } })
                                    m["completiondata"]["state"].d()?.let { st -> if (m["completion"].d() != 0.0) put("completed", st >= 1) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun filterSections(all: JsonArray, section: String?): List<JsonElement> {
        if (section.isNullOrBlank()) return all
        section.toIntOrNull()?.let { n -> return all.filter { it["section"].d()?.toInt() == n } }
        return all.filter { it["name"].s()?.contains(section, ignoreCase = true) == true }
            .ifEmpty { throw MoodleException("No section matching '$section'.") }
    }

    /** Every file in a course (resources, folders, assignment/intro attachments, …). */
    suspend fun files(courseId: Long, section: String? = null): List<CourseFile> =
        filterSections(rawContents(courseId), section).flatMap { sec ->
            sec["modules"].arr().flatMap { m ->
                m["contents"].arr().filter { it["type"].s() == "file" }.map { f ->
                    CourseFile(
                        courseId, m["id"].d()?.toLong() ?: 0, m["name"].s() ?: "", m["modname"].s() ?: "", sec["name"].s() ?: "",
                        f["filename"].s() ?: "", f["filepath"].s() ?: "/", f["filesize"].d()?.toLong() ?: 0, f["mimetype"].s(),
                        f["fileurl"].s() ?: "", f["timemodified"].d()?.toLong(),
                    )
                }
            }
        }

    /** Readable text of a page, book, label, URL or any module's description. */
    suspend fun moduleText(courseId: Long, cmid: Long): Pair<String, String> {
        val (sec, m) = rawContents(courseId).firstNotNullOfOrNull { sec ->
            sec["modules"].arr().firstOrNull { it["id"].d()?.toLong() == cmid }?.let { sec to it }
        } ?: throw MoodleException("No module $cmid in course $courseId.")
        val name = m["name"].s() ?: ""
        val sb = StringBuilder()
        FileText.htmlToText(m["description"].s()).takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
        when (m["modname"].s()) {
            "page", "book" -> m["contents"].arr().filter { it["filename"].s()?.endsWith(".html") == true }.forEach { f ->
                val (bytes, _) = moodle.download(f["fileurl"].s()!!)
                if (m["modname"].s() == "book") sb.append("## ").append(f["filepath"].s()?.trim('/')).append('\n')
                sb.append(FileText.htmlToText(FileText.decode(bytes))).append("\n\n")
            }
            "url" -> m["contents"].arr().firstOrNull()?.get("fileurl").s()?.let { sb.append("Link: ").append(it) }
            "assign" -> assignmentByCmid(courseId, cmid)?.let { a ->
                FileText.htmlToText(a["intro"].s()).let { sb.append(it).append("\n") }
                a["introattachments"].arr().forEach { f -> sb.append("Attachment: ${f["filename"].s()} (${f["fileurl"].s()})\n") }
            }
            else -> {}
        }
        val files = m["contents"].arr().filter { it["type"].s() == "file" && it["filename"].s()?.endsWith(".html") != true }
        if (files.isNotEmpty()) sb.append("\nFiles: ").append(files.joinToString { it["filename"].s() ?: "?" })
        return "$name (${m["modname"].s()}, section \"${sec["name"].s()}\")" to sb.toString().trim()
    }

    /** Resolves a file reference: a fileurl, or courseId + cmid + filename (filename optional if the module has one file). */
    suspend fun resolveFile(url: String?, courseId: Long?, cmid: Long?, filename: String?): Pair<String, String> {
        if (!url.isNullOrBlank()) return url to url.substringBefore('?').substringAfterLast('/').let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        requireNotNull(courseId) { "Pass either url, or courseId + cmid (+ filename)." }
        val candidates = files(courseId).filter { cmid == null || it.cmid == cmid }
            .filter { filename == null || it.filename.equals(filename, true) || (it.path + it.filename).trimStart('/').equals(filename.trimStart('/'), true) }
        val f = when {
            candidates.isEmpty() -> throw MoodleException("No matching file in course $courseId.")
            candidates.size > 1 && filename == null -> throw MoodleException(
                "Module has ${candidates.size} files; pass filename. Files: " + candidates.joinToString { it.path.trimStart('/') + it.filename },
            )
            else -> candidates.first()
        }
        return f.url to f.filename
    }

    suspend fun readFile(url: String, name: String, pages: String?): Extracted {
        val (bytes, ct) = moodle.download(url)
        return FileText.extract(name, ct, bytes, pages)
    }

    private suspend fun assignmentByCmid(courseId: Long, cmid: Long): JsonElement? =
        moodle.call("mod_assign_get_assignments", args { putJsonArray("courseids") { add(JsonPrimitive(courseId)) } })["courses"].arr()
            .flatMap { it["assignments"].arr() }.firstOrNull { it["cmid"].d()?.toLong() == cmid }

    private fun kotlinx.serialization.json.JsonArrayBuilder.add(p: JsonPrimitive) { this.add(element = p) }

    suspend fun assignments(courseId: Long?, withStatus: Boolean): JsonObject {
        val res = moodle.call("mod_assign_get_assignments", args {
            if (courseId != null) putJsonArray("courseids") { add(JsonPrimitive(courseId)) }
        })
        val now = Instant.now().epochSecond
        return buildJsonObject {
            putJsonArray("courses") {
                for (c in res["courses"].arr()) {
                    val list = c["assignments"].arr()
                    if (list.isEmpty()) continue
                    addJsonObject {
                        put("courseId", c["id"].d()?.toLong()); put("course", c["fullname"].s())
                        putJsonArray("assignments") {
                            for (a in list.sortedBy { it["duedate"].d() ?: 0.0 }) {
                                val due = a["duedate"].d()?.toLong() ?: 0
                                addJsonObject {
                                    put("assignId", a["id"].d()?.toLong()); put("cmid", a["cmid"].d()?.toLong()); put("name", a["name"].s())
                                    put("due", time(due)); time(a["cutoffdate"].d()?.toLong())?.let { put("cutoff", it) }
                                    time(a["allowsubmissionsfromdate"].d()?.toLong())?.let { put("opens", it) }
                                    if (due > 0) put("overdue", due < now)
                                    a["grade"].d()?.takeIf { it > 0 }?.let { put("maxGrade", it) }
                                    val att = a["introattachments"].arr()
                                    if (att.isNotEmpty()) put("attachments", buildJsonArray { att.forEach { f -> addJsonObject { put("name", f["filename"].s()); put("url", f["fileurl"].s()) } } })
                                    if (withStatus && courseId != null) {
                                        runCatching { submissionStatus(a["id"].d()!!.toLong()) }.getOrNull()?.let { put("status", it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun submissionStatus(assignId: Long): JsonObject {
        val r = moodle.call("mod_assign_get_submission_status", args { put("assignid", assignId) })
        val sub = r["lastattempt"]["submission"] ?: r["lastattempt"]["teamsubmission"]
        return buildJsonObject {
            put("submission", sub["status"].s() ?: "none")
            put("graded", r["lastattempt"]["gradingstatus"].s())
            time(sub["timemodified"].d()?.toLong())?.let { put("lastModified", it) }
            r["feedback"]["gradefordisplay"].s()?.let { put("grade", FileText.htmlToText(it)) }
            r["feedback"]["plugins"].arr().flatMap { it["editorfields"].arr() }.mapNotNull { FileText.htmlToText(it["text"].s()).takeIf(String::isNotBlank) }
                .takeIf { it.isNotEmpty() }?.let { put("feedback", it.joinToString("\n")) }
        }
    }

    suspend fun assignmentDetails(assignId: Long): JsonObject {
        val a = moodle.call("mod_assign_get_assignments", args {})["courses"].arr()
            .flatMap { c -> c["assignments"].arr().map { c to it } }.firstOrNull { it.second["id"].d()?.toLong() == assignId }
            ?: throw MoodleException("Assignment $assignId not found in your courses.")
        val (c, x) = a
        return buildJsonObject {
            put("course", c["fullname"].s()); put("name", x["name"].s()); put("cmid", x["cmid"].d()?.toLong())
            put("due", time(x["duedate"].d()?.toLong())); time(x["cutoffdate"].d()?.toLong())?.let { put("cutoff", it) }
            put("description", FileText.htmlToText(x["intro"].s()))
            x["activity"].s()?.let { FileText.htmlToText(it).takeIf(String::isNotBlank)?.let { t -> put("instructions", t) } }
            put("attachments", buildJsonArray { x["introattachments"].arr().forEach { f -> addJsonObject { put("name", f["filename"].s()); put("url", f["fileurl"].s()); put("size", f["filesize"].d()?.toLong()) } } })
            putJsonObject("config") { x["configs"].arr().filter { it["plugin"].s() in setOf("file", "onlinetext") }.forEach { put("${it["plugin"].s()}.${it["name"].s()}", it["value"].s()) } }
            runCatching { submissionStatus(assignId) }.getOrNull()?.let { put("status", it) }
        }
    }

    suspend fun deadlines(days: Int): JsonObject {
        val now = Instant.now().epochSecond
        val res = moodle.call("core_calendar_get_action_events_by_timesort", args {
            put("timesortfrom", now - 86_400); put("timesortto", now + days * 86_400L); put("limitnum", 50)
        })
        return buildJsonObject {
            put("from", time(now - 86_400)); put("to", time(now + days * 86_400L))
            putJsonArray("events") {
                res["events"].arr().forEach { e ->
                    addJsonObject {
                        put("when", time(e["timesort"].d()?.toLong())); put("name", e["name"].s())
                        put("course", e["course"]["fullname"].s()); put("courseId", e["course"]["id"].d()?.toLong())
                        put("type", e["modulename"].s()); e["instance"].d()?.let { put("instance", it.toLong()) }
                        e["action"]["name"].s()?.let { put("action", it) }
                        e["action"]["actionable"].b()?.let { put("actionable", it) }
                        if (e["overdue"].b() == true) put("overdue", true)
                        put("url", e["url"].s())
                    }
                }
            }
        }
    }

    suspend fun calendar(days: Int, courseId: Long?): JsonObject {
        val now = Instant.now().epochSecond
        val res = moodle.call("core_calendar_get_calendar_events", args {
            putJsonObject("events") { if (courseId != null) putJsonArray("courseids") { add(JsonPrimitive(courseId)) } }
            putJsonObject("options") { put("userevents", true); put("siteevents", true); put("timestart", now - 3600); put("timeend", now + days * 86_400L) }
        })
        return buildJsonObject {
            putJsonArray("events") {
                res["events"].arr().sortedBy { it["timestart"].d() ?: 0.0 }.forEach { e ->
                    addJsonObject {
                        put("start", time(e["timestart"].d()?.toLong()))
                        e["timeduration"].d()?.takeIf { it > 0 }?.let { put("durationMinutes", (it / 60).toInt()) }
                        put("name", e["name"].s()); put("type", e["eventtype"].s()); e["modulename"].s()?.let { put("module", it) }
                        e["courseid"].d()?.takeIf { it > 1 }?.let { put("courseId", it.toLong()) }
                        e["location"].s()?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                        FileText.htmlToText(e["description"].s()).takeIf { it.isNotBlank() }?.let { put("description", it.take(300)) }
                    }
                }
            }
        }
    }

    suspend fun grades(courseId: Long?): JsonObject {
        val uid = userId()
        if (courseId == null) {
            val res = moodle.call("gradereport_overview_get_course_grades", args { put("userid", uid) })
            val names = runCatching { courses("all")["courses"].arr().associate { it["id"].d()?.toLong() to it["name"].s() } }.getOrDefault(emptyMap())
            return buildJsonObject {
                putJsonArray("courses") {
                    res["grades"].arr().forEach { g ->
                        addJsonObject {
                            val id = g["courseid"].d()?.toLong()
                            put("courseId", id); put("course", names[id]); put("grade", g["grade"].s())
                        }
                    }
                }
            }
        }
        val res = moodle.call("gradereport_user_get_grade_items", args { put("courseid", courseId); put("userid", uid) })
        val ug = res["usergrades"].arr().firstOrNull()
        return buildJsonObject {
            put("courseId", courseId); put("course", ug["coursefullname"].s() ?: (course(courseId)?.get("fullname").s()))
            putJsonArray("items") {
                ug["gradeitems"].arr().forEach { gi ->
                    addJsonObject {
                        put("item", gi["itemname"].s() ?: if (gi["itemtype"].s() == "course") "Course total" else gi["itemtype"].s())
                        gi["itemmodule"].s()?.let { put("type", it) }
                        put("grade", gi["gradeformatted"].s()); gi["percentageformatted"].s()?.let { put("percent", it) }
                        gi["rangeformatted"].s()?.let { put("range", it) }; gi["weightformatted"].s()?.takeIf { it.isNotBlank() && it != "-" }?.let { put("weight", it) }
                        FileText.htmlToText(gi["feedback"].s()).takeIf { it.isNotBlank() }?.let { put("feedback", it) }
                        time(gi["gradedatesubmitted"].d()?.toLong())?.let { put("submitted", it) }
                    }
                }
            }
        }
    }

    suspend fun forums(courseId: Long): JsonObject {
        val res = moodle.call("mod_forum_get_forums_by_courses", args { putJsonArray("courseids") { add(JsonPrimitive(courseId)) } })
        return buildJsonObject {
            putJsonArray("forums") {
                (res as? JsonArray).orEmpty().forEach { f ->
                    addJsonObject {
                        put("forumId", f["id"].d()?.toLong()); put("cmid", f["cmid"].d()?.toLong()); put("name", f["name"].s()); put("type", f["type"].s())
                        f["numdiscussions"].d()?.let { put("discussions", it.toInt()) }
                        FileText.htmlToText(f["intro"].s()).takeIf { it.isNotBlank() }?.let { put("intro", it.take(300)) }
                    }
                }
            }
        }
    }

    suspend fun discussions(forumId: Long, page: Int, perPage: Int): JsonObject {
        val res = moodle.call("mod_forum_get_forum_discussions", args {
            put("forumid", forumId); put("sortorder", 1); put("page", page); put("perpage", perPage.coerceIn(1, 50))
        })
        return buildJsonObject {
            putJsonArray("discussions") {
                res["discussions"].arr().forEach { d ->
                    addJsonObject {
                        put("discussionId", d["discussion"].d()?.toLong()); put("subject", d["name"].s() ?: d["subject"].s())
                        put("author", d["userfullname"].s()); put("created", time(d["created"].d()?.toLong()))
                        put("lastPost", time(d["timemodified"].d()?.toLong())); put("replies", d["numreplies"].d()?.toInt())
                        if (d["pinned"].b() == true) put("pinned", true)
                        put("preview", FileText.htmlToText(d["message"].s()).take(400))
                    }
                }
            }
        }
    }

    suspend fun discussionPosts(discussionId: Long): JsonObject {
        val res = moodle.call("mod_forum_get_discussion_posts", args { put("discussionid", discussionId); put("sortby", "created"); put("sortdirection", "ASC") })
        return buildJsonObject {
            putJsonArray("posts") {
                res["posts"].arr().forEach { p ->
                    addJsonObject {
                        put("postId", p["id"].d()?.toLong()); put("subject", p["subject"].s())
                        put("author", p["author"]["fullname"].s()); put("created", time(p["timecreated"].d()?.toLong()))
                        p["parentid"].d()?.takeIf { it > 0 }?.let { put("replyTo", it.toLong()) }
                        put("message", FileText.htmlToText(p["message"].s()))
                        val att = p["attachments"].arr()
                        if (att.isNotEmpty()) put("attachments", buildJsonArray { att.forEach { f -> addJsonObject { put("name", f["filename"].s()); put("url", f["fileurl"].s()) } } })
                    }
                }
            }
        }
    }

    suspend fun notifications(limit: Int, unreadOnly: Boolean): JsonObject {
        val uid = userId()
        val res = moodle.call("message_popup_get_popup_notifications", args { put("useridto", uid); put("limit", limit.coerceIn(1, 50)); put("offset", 0) })
        return buildJsonObject {
            res["unreadcount"].d()?.let { put("unread", it.toInt()) }
            putJsonArray("notifications") {
                res["notifications"].arr().filter { !unreadOnly || it["read"].b() != true }.forEach { n ->
                    addJsonObject {
                        put("time", time(n["timecreated"].d()?.toLong())); put("subject", n["subject"].s())
                        put("text", (n["smallmessage"].s()?.takeIf { it.isNotBlank() } ?: FileText.htmlToText(n["fullmessagehtml"].s())).take(500))
                        n["contexturl"].s()?.let { put("url", it) }; put("read", n["read"].b() ?: false)
                    }
                }
            }
        }
    }

    /** Searches section names/summaries, module names/descriptions and file names (and page text with deep=true). */
    suspend fun searchCourse(courseId: Long, query: String, deep: Boolean): JsonObject {
        val q = query.lowercase()
        val hits = mutableListOf<JsonObject>()
        for (sec in rawContents(courseId)) {
            val secName = sec["name"].s() ?: ""
            if (secName.lowercase().contains(q) || FileText.htmlToText(sec["summary"].s()).lowercase().contains(q)) {
                hits += buildJsonObject { put("where", "section"); put("section", secName) }
            }
            for (m in sec["modules"].arr()) {
                val name = m["name"].s() ?: ""
                val desc = FileText.htmlToText(m["description"].s())
                val fileHits = m["contents"].arr().mapNotNull { it["filename"].s() }.filter { it.lowercase().contains(q) }
                var snippet: String? = null
                if (name.lowercase().contains(q)) snippet = name
                else if (desc.lowercase().contains(q)) snippet = around(desc, q)
                else if (deep && m["modname"].s() == "page") {
                    val t = runCatching { moduleText(courseId, m["id"].d()!!.toLong()).second }.getOrDefault("")
                    if (t.lowercase().contains(q)) snippet = around(t, q)
                }
                if (snippet != null || fileHits.isNotEmpty()) hits += buildJsonObject {
                    put("where", m["modname"].s()); put("cmid", m["id"].d()?.toLong()); put("name", name); put("section", secName)
                    snippet?.let { put("match", it) }; if (fileHits.isNotEmpty()) put("files", buildJsonArray { fileHits.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        return buildJsonObject { put("query", query); put("hits", JsonArray(hits)) }
    }

    private fun around(text: String, q: String): String {
        val i = text.lowercase().indexOf(q)
        return (if (i > 80) "…" else "") + text.substring((i - 80).coerceAtLeast(0), (i + q.length + 120).coerceAtMost(text.length)).replace('\n', ' ') + "…"
    }

    /** LectureTube recordings belonging to a course, with their lectures. */
    suspend fun courseRecordings(courseId: Long): JsonObject {
        val contents = rawContents(courseId)
        val texts = mutableListOf<Pair<String, String>>()
        for (sec in contents) {
            texts += "section ${sec["name"].s()}" to sec.toString()
            for (m in sec["modules"].arr()) {
                if (m["modname"].s() == "lti") {
                    m["instance"].d()?.toLong()?.let { inst ->
                        runCatching { moodle.call("mod_lti_get_tool_launch_data", args { put("toolid", inst) }) }.getOrNull()
                            ?.let { texts += "LTI ${m["name"].s()}" to it.toString() }
                    }
                }
                if (m["modname"].s() == "page") {
                    runCatching { moduleText(courseId, m["id"].d()!!.toLong()).second }.getOrNull()?.let { texts += "page ${m["name"].s()}" to it }
                }
            }
        }
        val found = recordings.forCourse(course(courseId), courseId, texts)
        return buildJsonObject {
            put("courseId", courseId)
            if (found.isEmpty()) put("note", "No LectureTube recordings found for this course (links, course ACL or LVA number). Recordings restricted to course members need a LectureTube login; call `login`.")
            putJsonArray("series") {
                for (r in found.filter { it.kind == "series" }) {
                    addJsonObject {
                        put("foundIn", r.foundIn)
                        val s = runCatching { lectureTube.series(r.ref) }
                        s.onSuccess { put("series", it) }.onFailure { put("ref", r.ref); r.title?.let { t -> put("title", t) }; put("error", it.message) }
                    }
                }
            }
            val activities = contents.flatMap { it["modules"].arr() }.filter { it["modname"].s() == "opencast" }
            if (activities.isNotEmpty()) putJsonArray("opencastActivities") {
                for (m in activities) {
                    val cmid = m["id"].d()?.toLong() ?: continue
                    addJsonObject {
                    put("module", m["name"].s()); put("cmid", cmid)
                    runCatching { OpencastActivity.episodes(cmid, moodle.page("/mod/opencast/view.php?id=$cmid")) }
                        .onSuccess { eps ->
                            put("hint", "Use the url with listen_recording / transcribe_recording / analyze_keyframes.")
                            putJsonArray("recordings") {
                                eps.forEach { e ->
                                    addJsonObject {
                                        put("title", e.title); put("url", e.url)
                                        put("transcript", if (Transcriber.cached(e.id) != null) "available (listen_recording)" else "none yet (transcribe_recording)")
                                    }
                                }
                            }
                        }
                        .onFailure { put("error", it.message) }
                    }
                }
            }
            val videos = files(courseId).filter { isMedia(it.filename, it.mimetype) }
            if (videos.isNotEmpty()) putJsonArray("videoFiles") {
                videos.forEach { f ->
                    addJsonObject {
                        put("filename", f.filename); put("module", f.module); put("cmid", f.cmid); put("section", f.section)
                        put("sizeMB", f.size / 1_048_576); put("url", f.url)
                        val key = fileKey(f.url)
                        put("transcript", when {
                            Transcriber.cached(key) != null -> "available (listen_recording)"
                            Transcriber.job(key)?.state?.let { it != JobState.FAILED && it != JobState.DONE } == true -> "transcription running"
                            else -> "none yet (transcribe_recording)"
                        })
                    }
                }
            }
            putJsonArray("lectures") {
                for (r in found.filter { it.kind == "lecture" }) {
                    addJsonObject {
                        put("foundIn", r.foundIn)
                        val e = runCatching { lectureTube.event(r.ref) }
                        e.onSuccess { ev ->
                            put("id", ev.id); put("title", ev.title); put("duration", dev.lecturetubemcp.api.Vtt.fmt(ev.durationMs))
                            put("captions", buildJsonArray { ev.captions.forEach { add(JsonPrimitive(it.first ?: "?")) } })
                            if (ev.captions.isEmpty()) put("transcript", when {
                                Transcriber.cached(ev.opencastId) != null -> "generated transcript available"
                                ev.hasAuthorizedData -> "no captions: use transcribe_recording"
                                else -> "no captions and no media access (login?)"
                            })
                        }.onFailure { put("ref", r.ref); put("error", it.message) }
                    }
                }
            }
        }
    }

    suspend fun listen(lecture: String, lang: String?, fromMinute: Int?, toMinute: Int?, maxChars: Int) =
        lectureTube.transcript(lecture, lang = lang, fromMinute = fromMinute, toMinute = toMinute, maxChars = maxChars)

    private val videoExt = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "mp3", "m4a", "wav", "ogg", "opus", "flac")

    fun isMedia(name: String, mime: String?) =
        mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true || FileText.ext(name) in videoExt

    /** Transcript cache key of a TUWEL file: independent of the token in the URL. */
    fun fileKey(url: String) = "tuwel-" + MediaCache.sha1(url.substringBefore('?').replace("/webservice/pluginfile.php/", "/pluginfile.php/"))

    suspend fun lectureTarget(lecture: String): MediaTarget {
        val e = lectureTube.event(lecture)
        return MediaTarget(
            key = e.opencastId, title = e.title, language = null, hasCaptions = e.captions.isNotEmpty(),
            audio = { lectureTube.audioSource(e) }, video = { tr -> lectureTube.videoSource(e, tr) }, cues = { lectureTube.cuesFor(e) },
        )
    }

    /** A recording of a TUWEL Opencast activity; transcripts share the Opencast id as key with LectureTube. */
    suspend fun opencastTarget(url: String): MediaTarget {
        val (cmid, id) = OpencastActivity.parseUrl(url) ?: throw MoodleException("Not a TUWEL Opencast recording URL (…/mod/opencast/view.php?id=…&e=…).")
        val (title, streams) = OpencastActivity.player(moodle.page("/mod/opencast/view.php?id=$cmid&e=$id"))
        if (streams.isEmpty()) throw MoodleException("This Opencast activity offers no downloadable video.")
        val name = title ?: "Opencast recording $id"
        fun source(s: OpencastActivity.Stream) = MediaSource("oc-$id-${s.content}-${s.height}", name, s.url)
        return MediaTarget(
            key = id, title = name, language = null, hasCaptions = false,
            audio = { source(OpencastActivity.pick(streams, forAudio = true)) },
            video = { track -> source(OpencastActivity.pick(streams, forAudio = false, track = track)) },
            cues = { Transcriber.cached(id)?.first },
        )
    }

    suspend fun fileTarget(url: String?, courseId: Long?, cmid: Long?, filename: String?): MediaTarget {
        val (fileUrl, name) = resolveFile(url, courseId, cmid, filename)
        if (!isMedia(name, null)) throw MoodleException("\"$name\" is not a video/audio file (use read_file for documents).")
        val key = fileKey(fileUrl)
        val source = suspend {
            val (u, headers) = moodle.mediaRequest(fileUrl)
            MediaSource(key, name, u, headers)
        }
        return MediaTarget(
            key = key, title = name, language = null, hasCaptions = false,
            audio = source, video = { _ -> source() }, cues = { Transcriber.cached(key)?.first },
        )
    }

    /** Transcript of a TUWEL video file (generated earlier with transcribe_recording). */
    fun listenFile(key: String, name: String, fromMinute: Int?, toMinute: Int?, maxChars: Int): TranscriptResult {
        val cached = Transcriber.cached(key)
            ?: return TranscriptResult(
                false,
                (Transcriber.job(key)?.takeIf { it.state != JobState.DONE && it.state != JobState.FAILED }
                    ?.let { "Transcription running: ${it.summary()}. Wait for it with transcription_status (returns as soon as it is ready)." }
                    ?: Transcriber.foreignStatus(key)?.let { "Transcription running: $it. Wait for it with transcription_status (returns as soon as it is ready)." }
                    ?: "\"$name\" has no transcript yet.") +
                    " Create one with transcribe_recording (same arguments).",
                name,
            )
        val header = "# $name\nTranscript generated by ${dev.lecturetubemcp.media.sourceLabel(cached.second)}. " +
            "Expect recognition errors in names and technical terms.\n\n"
        return TranscriptResult(true, MediaTools.transcriptText(header, cached.first, fromMinute, toMinute, maxChars = maxChars), name)
    }

    /**
     * Collects study material for summaries: section texts, pages, readable files and (optionally)
     * recording transcripts, as one Markdown document limited to [maxChars].
     */
    suspend fun material(
        courseId: Long, section: String?, cmids: List<Long>?, includeFiles: Boolean, includeRecordings: Boolean, maxChars: Int,
    ): Pair<String, List<String>> {
        val sources = mutableListOf<String>()
        val sb = StringBuilder()
        val budget = { maxChars - sb.length }
        val c = course(courseId)
        sb.append("# ").append(c?.get("fullname").s() ?: "Course $courseId").append("\n")
        FileText.htmlToText(c?.get("summary").s()).takeIf { it.isNotBlank() && section == null }?.let { sb.append(it.take(1500)).append("\n") }
        val secs = filterSections(rawContents(courseId), section)
        val ltRefs = mutableListOf<RecordingRef>()
        loop@ for (sec in secs) {
            val mods = sec["modules"].arr().filter { cmids == null || it["id"].d()?.toLong() in cmids }
            if (mods.isEmpty() && cmids != null) continue
            sb.append("\n## Section ").append(sec["section"].d()?.toInt()).append(": ").append(sec["name"].s()).append('\n')
            FileText.htmlToText(sec["summary"].s()).takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            for (m in mods) {
                if (budget() <= 0) break@loop
                val name = m["name"].s() ?: ""; val mod = m["modname"].s() ?: ""; val cmid = m["id"].d()?.toLong() ?: 0
                ltRefs += recordings.linksIn(m.toString(), name)
                sb.append("\n### ").append(name).append(" [").append(mod).append(", cmid ").append(cmid).append("]\n")
                when (mod) {
                    "page", "book", "label", "url", "assign" -> runCatching { moduleText(courseId, cmid).second }.getOrNull()?.let {
                        sb.append(it.take(budget().coerceAtLeast(0))).append('\n'); sources += "$mod: $name"
                    }
                    else -> FileText.htmlToText(m["description"].s()).takeIf { it.isNotBlank() }?.let { sb.append(it.take(2000)).append('\n') }
                }
                if (includeFiles && mod in setOf("resource", "folder")) {
                    for (f in m["contents"].arr().filter { it["type"].s() == "file" }) {
                        if (budget() <= 2000) { sb.append("[more files omitted: budget reached]\n"); break }
                        val fname = f["filename"].s() ?: continue
                        if (isMedia(fname, f["mimetype"].s())) {
                            val t = listenFile(fileKey(f["fileurl"].s()!!), fname, null, null, (budget() / 2).coerceAtLeast(2000))
                            if (includeRecordings && t.ok) { sb.append("\n#### Recording: ").append(fname).append('\n').append(t.text).append('\n'); sources += "recording: $fname" }
                            else sb.append("[video $fname: ${if (t.ok) "transcript omitted" else "no transcript yet (transcribe_recording)"}]\n")
                            continue
                        }
                        val size = f["filesize"].d()?.toLong() ?: 0
                        if (size > 60L * 1024 * 1024) { sb.append("[$fname skipped: ${size / 1024 / 1024} MB]\n"); continue }
                        val ex = runCatching { readFile(f["fileurl"].s()!!, fname, null) }.getOrElse { Extracted(note = it.message) }
                        val body = when {
                            ex.text != null -> ex.text
                            ex.entries != null -> runCatching {
                                FileText.zipAllText(moodle.download(f["fileurl"].s()!!).first, (budget() / 2).coerceAtLeast(2000))
                            }.getOrElse { "[archive: ${ex.entries.size} entries]" }
                            else -> "[${ex.note ?: "not readable"}]"
                        }
                        sb.append("\n#### File: ").append(fname).append('\n').append(body.take((budget() / 2).coerceAtLeast(1000))).append('\n')
                        sources += "file: $fname"
                    }
                }
            }
        }
        if (includeRecordings && budget() > 3000) {
            if (section == null && cmids == null) ltRefs += runCatching { recordings.seriesForCourse(courseId) }.getOrDefault(emptyList())
            val lectures = ltRefs.filter { it.kind == "lecture" }.distinctBy { it.ref }.take(5)
            for (r in lectures) {
                if (budget() <= 3000) break
                val t = runCatching { listen(r.ref, null, null, null, (budget() / 2).coerceAtLeast(3000)) }.getOrNull() ?: continue
                sb.append("\n## Recording (found in ${r.foundIn})\n").append(t.text).append('\n')
                if (t.ok) sources += "recording: ${t.title}"
            }
            val series = ltRefs.filter { it.kind == "series" }.distinctBy { it.ref }
            if (series.isNotEmpty()) sb.append("\n[Course recording series: ${series.joinToString { it.title ?: it.ref }}. Use course_recordings and listen_recording for individual lectures.]\n")
        }
        val text = if (sb.length > maxChars) sb.substring(0, maxChars) + "\n[… material truncated at $maxChars characters]" else sb.toString()
        return text to sources
    }
}
