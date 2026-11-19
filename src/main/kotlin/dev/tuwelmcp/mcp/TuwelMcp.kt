package dev.tuwelmcp.mcp

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.api.ApiException
import dev.lecturetubemcp.api.arr
import dev.lecturetubemcp.api.d
import dev.lecturetubemcp.api.get
import dev.lecturetubemcp.api.s
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.auth.SessionStore
import dev.lecturetubemcp.auth.SsoTargets
import dev.lecturetubemcp.media.MediaException
import dev.lecturetubemcp.media.Transcriber
import dev.lecturetubemcp.media.registerMediaTools
import dev.tuwelmcp.files.FileText
import dev.tuwelmcp.moodle.MoodleException
import dev.tuwelmcp.moodle.OpencastActivity
import dev.tuwelmcp.moodle.TuwelService
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private val prettyJson = Json { prettyPrint = true }

private const val INSTRUCTIONS = """
Unofficial MCP server for TUWEL (https://tuwel.tuwien.ac.at), the Moodle e-learning platform of TU Wien.
It reads the student's courses, materials, assignments, deadlines, grades and forums, reads course files
directly (PDF, Office, notebooks, code, ZIP archives incl. their contents) and can "listen" to the
LectureTube recordings linked to a course by reading their transcripts.

Getting started: call `auth_status`; if not logged in call `login` (opens the TU Wien SSO page in a browser;
the student logs in, the session is reused automatically, also for LectureTube).

Typical flows:
- What's due?           → upcoming_deadlines, list_assignments, get_assignment
- Study a week/topic    → list_courses → get_course(courseId, section) → read_file / read_zip / read_module
- Lecture recordings    → course_recordings(courseId) → listen_recording(lecture, Opencast recording url or video file)
- No captions?          → transcribe_recording creates them from the MP4 (background job; transcription_status
                          waits and returns the transcript when ready, no polling needed); afterwards
                          listen_recording / summarize use them
- What's on the slides? → analyze_keyframes (scene changes as image grid, then atMinutes for details)
- Summaries             → summarize(courseId, section) (collects pages, files and transcripts)
Cite course, file names, page numbers and [hh:mm:ss] timestamps in answers.
"""

class TuwelMcp(private val svc: TuwelService, private val logins: LoginManager) {

    fun build(): Server {
        val server = Server(
            Implementation(name = "tuwel-mcp", version = Config.VERSION, title = "TU Wien TUWEL"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            INSTRUCTIONS.trimIndent(),
        )
        auth(server)
        courses(server)
        files(server)
        activities(server)
        recordingsAndSummaries(server)
        registerMediaTools(
            server,
            listOf(
                "lecture" to prop("string", "A LectureTube lecture (ev…, URL or UUID from course_recordings), or …"),
                "url" to prop("string", "… a TUWEL video file URL or a TUWEL Opencast recording URL (…/mod/opencast/view.php?id=…&e=…), or …"),
                "courseId" to prop("integer", "… courseId + cmid (+ filename) of a video file in a course."),
                "cmid" to prop("integer", "Module id of the video file."),
                "filename" to prop("string", "File name, if the module has several files."),
            ),
            resolve = { a ->
                a.str("lecture")?.let { svc.lectureTarget(it) }
                    ?: a.str("url")?.takeIf { OpencastActivity.parseUrl(it) != null }?.let { svc.opencastTarget(it) }
                    ?: svc.fileTarget(a.str("url"), a.long("courseId"), a.long("cmid"), a.str("filename"))
            },
            onError = { e ->
                when (e) {
                    is MoodleException -> (e.message ?: "TUWEL error") + if (e.needsLogin && !(e.message ?: "").contains("login")) " Call `login`." else ""
                    is ApiException -> (e.message ?: "LectureTube error") + if (e.notAuthorized && !(e.message ?: "").contains("login")) " Call `login`." else ""
                    is MediaException, is IllegalArgumentException -> e.message
                    else -> null
                }
            },
        )
        prompts(server)
        return server
    }

    private fun auth(server: Server) {
        server.tool(
            "login",
            "Log in to TUWEL (and LectureTube) with TU Wien SSO. Opens a browser at the TU Wien login page; the " +
                "student logs in there (incl. 2FA). Cookies and a TUWEL app token are then stored and reused. If a recent " +
                "SSO session exists, the login happens silently in the background. The window always opens in the " +
                "user's default browser; it cannot be chosen here.",
            schema(
                "waitSeconds" to prop("integer", "How long this call waits (default 90). The login continues in the background."),
            ),
            readOnly = false,
        ) { a ->
            val job = logins.start(null, 600.seconds)
            try {
                val r = withTimeout((a.int("waitSeconds") ?: 90).coerceIn(0, 600).seconds) { job.await() }
                if (!r.ok) return@tool err(r.message)
                val tok = runCatching { svc.moodle.acquireToken() }
                ok(r.message + if (tok.isSuccess) "\nTUWEL app token stored." else "\nNo TUWEL app token (${tok.exceptionOrNull()?.message}); using the web session.")
            } catch (_: TimeoutCancellationException) {
                ok("A browser window with the TU Wien login page is open. Please log in there, then call auth_status.")
            }
        }

        server.tool("auth_status", "Check whether TUWEL and LectureTube are logged in and as whom.", schema()) {
            val s = SessionStore.current
            val tuwel = runCatching { svc.siteInfo() }
            val lt = s?.let { runCatching { SsoTargets.lectureTube.check(it) }.getOrNull() }
            ok(
                buildString {
                    append(tuwel.fold({ "TUWEL: logged in as ${it["fullname"].s()} (${it["username"].s()}), via ${if (svc.moodle.token != null) "app token" else "web session"}." }, { "TUWEL: not logged in (${it.message})." }))
                    append("\nLectureTube: ").append(lt?.let { "logged in as $it." } ?: "not logged in (public recordings only).")
                    if (s != null) append("\nSession saved ${s.savedAt}.")
                    if (logins.inProgress) append("\nA browser login is in progress.")
                    logins.lastResult?.takeIf { !it.ok }?.let { append("\nLast login attempt: ${it.message}") }
                    if (tuwel.isFailure || lt == null) append("\nCall `login` to sign in.")
                },
            )
        }

        server.tool("logout", "Forget the stored TUWEL/LectureTube session and app token on this computer.", schema(), readOnly = false) {
            SessionStore.clear()
            ok("Local session and token removed. (The TUWEL app token can also be revoked in TUWEL under Preferences → Security keys.)")
        }
    }

    private fun courses(server: Server) {
        server.tool(
            "list_courses",
            "The student's TUWEL courses.",
            schema("filter" to prop("string", "inprogress (default), past, future, all, favourites")),
        ) { a -> json(svc.courses(a.str("filter") ?: "inprogress")) }

        server.tool(
            "search_courses",
            "Search all TUWEL courses by name or LVA number (also courses the student is not enrolled in).",
            schema("query" to prop("string", "e.g. \"Algebra\" or \"104.271\""), required = listOf("query")),
        ) { a -> json(svc.searchCourses(a.reqStr("query"))) }

        server.tool(
            "get_course",
            "Structure of a course: sections (weeks/topics) with their activities and resources, files, dates, " +
                "completion state and detected LectureTube links. Use `section` to focus on one section.",
            schema(
                "courseId" to prop("integer", "TUWEL course id (from list_courses)."),
                "section" to prop("string", "Optional section number or part of its name."),
                "details" to prop("boolean", "Full descriptions instead of shortened ones (default false)."),
                required = listOf("courseId"),
            ),
        ) { a -> json(svc.contents(a.reqLong("courseId"), a.str("section"), a.bool("details") ?: false)) }

        server.tool(
            "search_course",
            "Find material in a course by keyword: section and activity names, descriptions, file names, and with " +
                "deep=true also the text of pages.",
            schema(
                "courseId" to prop("integer", "Course id."), "query" to prop("string", "Keyword."),
                "deep" to prop("boolean", "Also search page contents (slower)."),
                required = listOf("courseId", "query"),
            ),
        ) { a -> json(svc.searchCourse(a.reqLong("courseId"), a.reqStr("query"), a.bool("deep") ?: false)) }

        server.tool(
            "course_updates",
            "What changed in a course recently (new or updated files, activities, posts, grades).",
            schema("courseId" to prop("integer", "Course id."), "days" to prop("integer", "Look back this many days (default 7)."), required = listOf("courseId")),
        ) { a ->
            val courseId = a.reqLong("courseId")
            val since = Instant.now().epochSecond - (a.int("days") ?: 7) * 86_400L
            val res = svc.moodle.call("core_course_get_updates_since", buildJsonObject { put("courseid", courseId); put("since", since) })
            val names = svc.rawContents(courseId).flatMap { it["modules"].arr() }.associate { it["id"].d()?.toLong() to (it["name"].s() to it["modname"].s()) }
            json(
                buildJsonObject {
                    put("since", svc.time(since))
                    putJsonArray("updates") {
                        res["instances"].arr().forEach { i ->
                            val id = i["id"].d()?.toLong()
                            addJsonObject {
                                put("cmid", id); names[id]?.let { put("name", it.first); put("type", it.second) }
                                putJsonArray("changes") {
                                    i["updates"].arr().forEach { u -> addJsonObject { put("what", u["name"].s()); put("when", svc.time(u["timeupdated"].d()?.toLong())) } }
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private val fileCache = object : LinkedHashMap<String, Pair<ByteArray, String?>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String?>>?) = size > 4
    }

    private suspend fun bytesOf(url: String): Pair<ByteArray, String?> =
        synchronized(fileCache) { fileCache[url] } ?: svc.moodle.download(url).also { synchronized(fileCache) { fileCache[url] = it } }

    private fun fileArgs(vararg extra: Pair<String, JsonObject>, required: List<String> = emptyList()) = schema(
        "url" to prop("string", "File URL from get_course / list_files (either this …)"),
        "courseId" to prop("integer", "… or the course id"),
        "cmid" to prop("integer", "… plus the module id (cmid)"),
        "filename" to prop("string", "… plus the file name (needed if the module has several files, e.g. a folder)"),
        *extra,
        required = required,
    )

    private fun files(server: Server) {
        server.tool(
            "list_files",
            "All files of a course (lecture notes, slides, exercise sheets, ZIP archives …) with size, type and URL.",
            schema(
                "courseId" to prop("integer", "Course id."),
                "section" to prop("string", "Optional section number or name."),
                "type" to prop("string", "Optional filter on file extension or mimetype, e.g. pdf, zip, video."),
                required = listOf("courseId"),
            ),
        ) { a ->
            val t = a.str("type")?.lowercase()
            val list = svc.files(a.reqLong("courseId"), a.str("section"))
                .filter { t == null || it.filename.lowercase().endsWith(".$t") || it.mimetype?.lowercase()?.contains(t) == true }
            json(
                buildJsonObject {
                    put("count", list.size)
                    putJsonArray("files") {
                        list.forEach { f ->
                            addJsonObject {
                                put("filename", (f.path.trimStart('/') + f.filename)); put("module", f.module); put("cmid", f.cmid)
                                put("section", f.section); put("size", f.size); put("mimetype", f.mimetype)
                                put("modified", svc.time(f.modified)); put("url", f.url)
                            }
                        }
                    }
                },
            )
        }

        server.tool(
            "read_file",
            "Read a course file directly and return its text: PDF (with page markers; select pages with `pages`), " +
                "Word/PowerPoint/Excel (docx/pptx/xlsx), OpenDocument, Jupyter notebooks, HTML, Markdown, LaTeX, source code, " +
                "CSV. Images are returned as images. For ZIP archives the entries are listed (use read_zip to read them).",
            fileArgs(
                "pages" to prop("string", "PDF pages, e.g. \"1-10\" or \"7\"."),
                "maxChars" to prop("integer", "Maximum characters returned (default 60000)."),
            ),
        ) { a ->
            val (url, name) = svc.resolveFile(a.str("url"), a.long("courseId"), a.long("cmid"), a.str("filename"))
            val (bytes, ct) = bytesOf(url)
            val ex = FileText.extract(name, ct, bytes, a.str("pages"))
            when {
                ex.text != null -> ok(truncate("# $name\n\n${ex.text}", a.int("maxChars") ?: 60_000, "Use `pages` to read further pages."))
                ex.image != null -> CallToolResult(listOf(TextContent("# $name"), ImageContent(Base64.getEncoder().encodeToString(ex.image.first), ex.image.second)))
                ex.entries != null -> ok("# $name is a ZIP archive with ${ex.entries.size} entries. Use read_zip to read them.\n\n" + listing(ex.entries))
                else -> err("# $name\n${ex.note}")
            }
        }

        server.tool(
            "read_zip",
            "Read a ZIP archive from TUWEL directly, without unpacking: list its entries, read one entry (any format " +
                "read_file supports, incl. nested ZIPs), or read all text entries at once (e.g. all source files of a code template).",
            fileArgs(
                "entry" to prop("string", "Path of the entry to read. Omit to get the listing."),
                "readAll" to prop("boolean", "Read all text-like entries into one document (default false)."),
                "pages" to prop("string", "For a PDF entry: page range."),
                "maxChars" to prop("integer", "Maximum characters returned (default 80000)."),
            ),
        ) { a ->
            val (url, name) = svc.resolveFile(a.str("url"), a.long("courseId"), a.long("cmid"), a.str("filename"))
            val (bytes, _) = bytesOf(url)
            val max = a.int("maxChars") ?: 80_000
            val entry = a.str("entry")
            when {
                entry != null -> {
                    val data = FileText.zipEntry(bytes, entry)
                    val ex = FileText.extract(entry, null, data, a.str("pages"), depth = 1)
                    when {
                        ex.text != null -> ok(truncate("# $name → $entry\n\n${ex.text}", max, "Use `pages` for long PDFs."))
                        ex.image != null -> CallToolResult(listOf(TextContent("# $name → $entry"), ImageContent(Base64.getEncoder().encodeToString(ex.image.first), ex.image.second)))
                        ex.entries != null -> ok("# $name → $entry (nested archive)\n\n" + listing(ex.entries) + "\n\nNested entries can't be addressed directly; use readAll=true to read everything.")
                        else -> err("# $name → $entry\n${ex.note}")
                    }
                }
                a.bool("readAll") == true -> ok(truncate("# $name (all text entries)\n" + FileText.zipAllText(bytes, max), max, "Read single entries with `entry`."))
                else -> ok("# $name\n\n" + listing(FileText.zipList(bytes)))
            }
        }

        server.tool(
            "read_module",
            "Readable text of an activity or resource page: Moodle pages, books, labels, URLs, assignment descriptions.",
            schema("courseId" to prop("integer", "Course id."), "cmid" to prop("integer", "Module id (cmid)."), required = listOf("courseId", "cmid")),
        ) { a ->
            val (title, text) = svc.moduleText(a.reqLong("courseId"), a.reqLong("cmid"))
            ok(truncate("# $title\n\n$text", 80_000, ""))
        }
    }

    private fun activities(server: Server) {
        server.tool(
            "upcoming_deadlines",
            "Upcoming deadlines and to-dos across all courses (assignment due dates, quizzes, …), from the TUWEL timeline.",
            schema("days" to prop("integer", "How many days ahead (default 14).")),
        ) { a -> json(svc.deadlines((a.int("days") ?: 14).coerceIn(1, 365))) }

        server.tool(
            "calendar",
            "Calendar events (lectures, exams, course and personal events) for the next days.",
            schema("days" to prop("integer", "Days ahead (default 14)."), "courseId" to prop("integer", "Optional course filter.")),
        ) { a -> json(svc.calendar((a.int("days") ?: 14).coerceIn(1, 365), a.long("courseId"))) }

        server.tool(
            "list_assignments",
            "Assignments of one course (with submission/grading status) or of all courses.",
            schema("courseId" to prop("integer", "Optional course id. With it, submission status is included.")),
        ) { a -> json(svc.assignments(a.long("courseId"), withStatus = a.long("courseId") != null)) }

        server.tool(
            "get_assignment",
            "Full description of an assignment: instructions, attached files, due dates, submission settings, your status, grade and feedback.",
            schema("assignId" to prop("integer", "Assignment id (from list_assignments)."), required = listOf("assignId")),
        ) { a -> json(svc.assignmentDetails(a.reqLong("assignId"))) }

        server.tool(
            "get_grades",
            "Grades: overview of all courses, or every grade item of one course with feedback.",
            schema("courseId" to prop("integer", "Optional course id for the detailed grade report.")),
        ) { a -> json(svc.grades(a.long("courseId"))) }

        server.tool(
            "list_forums",
            "Forums of a course (announcements, Q&A …).",
            schema("courseId" to prop("integer", "Course id."), required = listOf("courseId")),
        ) { a -> json(svc.forums(a.reqLong("courseId"))) }

        server.tool(
            "list_discussions",
            "Discussions of a forum, newest activity first.",
            schema("forumId" to prop("integer", "Forum id (from list_forums)."), "page" to prop("integer", "Default 0."), "perPage" to prop("integer", "Default 20."), required = listOf("forumId")),
        ) { a -> json(svc.discussions(a.reqLong("forumId"), a.int("page") ?: 0, a.int("perPage") ?: 20)) }

        server.tool(
            "read_discussion",
            "All posts of a forum discussion.",
            schema("discussionId" to prop("integer", "Discussion id."), required = listOf("discussionId")),
        ) { a -> json(svc.discussionPosts(a.reqLong("discussionId"))) }

        server.tool(
            "notifications",
            "Recent TUWEL notifications (new posts, grades, assignment reminders …).",
            schema("limit" to prop("integer", "Default 20."), "unreadOnly" to prop("boolean", "Default false.")),
        ) { a -> json(svc.notifications(a.int("limit") ?: 20, a.bool("unreadOnly") ?: false)) }
    }

    private fun recordingsAndSummaries(server: Server) {
        server.tool(
            "course_recordings",
            "Recordings belonging to a TUWEL course: LectureTube links, embedded players, LTI activities, the course's recording series " +
                "(matched via course permissions and LVA number), and the recordings of TUWEL Opencast activities (with a url for " +
                "listen_recording / transcribe_recording). Shows which lectures have transcripts.",
            schema("courseId" to prop("integer", "Course id."), required = listOf("courseId")),
        ) { a -> json(svc.courseRecordings(a.reqLong("courseId"))) }

        server.tool(
            "listen_recording",
            "\"Listen\" to a recording: returns its spoken content as a transcript with [hh:mm:ss] timestamps, from " +
                "LectureTube captions or from a transcript generated with transcribe_recording. Works for LectureTube lectures, " +
                "TUWEL Opencast recordings (url from course_recordings) and video files uploaded to TUWEL. If there is no transcript yet, it says so; then call transcribe_recording. Long lectures can be read in windows.",
            schema(
                "lecture" to prop("string", "Lecture id (ev…), LectureTube URL or Opencast UUID (from course_recordings), or …"),
                "url" to prop("string", "… a TUWEL video file URL or TUWEL Opencast recording URL (…/mod/opencast/view.php?id=…&e=…), or courseId + cmid (+ filename)."),
                "courseId" to prop("integer", "Course id of a video file."), "cmid" to prop("integer", "Module id of a video file."),
                "filename" to prop("string", "File name, if the module has several files."),
                "lang" to prop("string", "Caption language, e.g. de or en."),
                "fromMinute" to prop("integer", "Window start."), "toMinute" to prop("integer", "Window end."),
                "maxChars" to prop("integer", "Default 100000."),
            ),
        ) { a ->
            val r = a.str("lecture")?.let { svc.listen(it, a.str("lang"), a.int("fromMinute"), a.int("toMinute"), a.int("maxChars") ?: 100_000) }
                ?: a.str("url")?.let { OpencastActivity.parseUrl(it) }?.let { (_, id) ->
                    svc.listenFile(id, Transcriber.cached(id)?.second?.title ?: "Opencast recording $id", a.int("fromMinute"), a.int("toMinute"), a.int("maxChars") ?: 100_000)
                }
                ?: run {
                    val (url, name) = svc.resolveFile(a.str("url"), a.long("courseId"), a.long("cmid"), a.str("filename"))
                    svc.listenFile(svc.fileKey(url), name, a.int("fromMinute"), a.int("toMinute"), a.int("maxChars") ?: 100_000)
                }
            if (r.ok) ok(r.text) else err(r.text)
        }

        server.tool(
            "summarize",
            "Summarise course material: collects the texts of a course or one section (descriptions, pages, readable files " +
                "incl. PDFs/slides/ZIPs, and transcripts of linked recordings) and produces a structured summary. If the " +
                "client supports MCP sampling the summary is generated directly; otherwise the collected material is returned " +
                "together with summarisation instructions.",
            schema(
                "courseId" to prop("integer", "Course id."),
                "section" to prop("string", "Section number or name (e.g. a week). Omit for the whole course (large!)."),
                "cmids" to prop("array", "Optional: only these modules."),
                "focus" to prop("string", "Optional focus, e.g. \"exam preparation\", \"definitions\", \"what do I need for assignment 2\"."),
                "language" to prop("string", "Output language (default: same as the material)."),
                "includeFiles" to prop("boolean", "Read files (default true)."),
                "includeRecordings" to prop("boolean", "Include transcripts of linked recordings (default true)."),
                "maxChars" to prop("integer", "Material budget in characters (default 150000)."),
                required = listOf("courseId"),
            ),
        ) { a ->
            val cmids = (a["cmids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
            val (material, sources) = svc.material(
                a.reqLong("courseId"), a.str("section"), cmids,
                a.bool("includeFiles") ?: true, a.bool("includeRecordings") ?: true, (a.int("maxChars") ?: 150_000).coerceIn(5_000, 600_000),
            )
            val instruction = summaryInstruction(a.str("focus"), a.str("language"))
            val sampled = runCatching {
                withTimeout(240.seconds) {
                    createMessage(
                        CreateMessageRequest(
                            CreateMessageRequestParams(
                                maxTokens = 4000,
                                systemPrompt = "You summarise university course material for a TU Wien student. Be accurate and cite sources.",
                                messages = listOf(SamplingMessage(Role.User, listOf(TextContent("$instruction\n\n---\n\n$material")))),
                            ),
                        ),
                    )
                }
            }.getOrNull()
            val summary = sampled?.content?.filterIsInstance<TextContent>()?.joinToString("\n") { it.text }
            if (summary != null) {
                ok("$summary\n\n---\nSources: ${sources.joinToString("; ")}")
            } else {
                ok("$instruction\n\nSources: ${sources.joinToString("; ")}\n\n---\n\n$material")
            }
        }
    }

    private fun summaryInstruction(focus: String?, language: String?) = buildString {
        append("Summarise the following TUWEL course material.")
        focus?.let { append(" Focus: $it.") }
        append(
            " Structure: 1) overview (3–5 sentences); 2) key topics and concepts with short explanations; " +
                "3) important definitions, formulas (LaTeX) and algorithms; 4) organisational facts (deadlines, exam modalities, " +
                "submission rules) if present; 5) open questions or things to review. Cite the source of each point " +
                "(file name + page, section, or recording + [hh:mm:ss]).",
        )
        append(language?.let { " Write in $it." } ?: " Write in the language of the material.")
    }

    private fun prompts(server: Server) {
        fun prompt(name: String, desc: String, args: List<PromptArgument>, text: (Map<String, String>) -> String) =
            server.addPrompt(name, desc, args) { req -> GetPromptResult(listOf(PromptMessage(Role.User, TextContent(text(req.arguments.orEmpty()))))) }

        prompt("weekly_briefing", "What's new and what's due across all my TUWEL courses.", emptyList()) {
            "Give me a briefing for this week using the TUWEL tools: call upcoming_deadlines (14 days), notifications, " +
                "and for each in-progress course from list_courses call course_updates (7 days). List deadlines first " +
                "(sorted, with course and what to submit), then new material and announcements per course, then suggested priorities."
        }
        prompt(
            "study_week", "Summarise one week/section of a course including slides, files and recordings.",
            listOf(PromptArgument("course", "Course id or name", required = true), PromptArgument("section", "Week / section number or name", required = true)),
        ) { a ->
            "Find the TUWEL course \"${a["course"]}\" (list_courses / search_courses if it is not an id). For section " +
                "\"${a["section"]}\": call get_course with that section, read the slides/handouts with read_file (and read_zip " +
                "for archives), find the matching lecture with course_recordings and listen_recording, then write a study " +
                "summary with key concepts, formulas (LaTeX), examples and 5 self-test questions. Cite files, pages and timestamps."
        }
        prompt(
            "assignment_helper", "Understand an assignment: requirements, materials and relevant lecture content (no ready-made solutions).",
            listOf(PromptArgument("course", "Course id or name", required = true), PromptArgument("assignment", "Assignment name", required = false)),
        ) { a ->
            "For TUWEL course \"${a["course"]}\" find the assignment ${a["assignment"]?.let { "\"$it\"" } ?: "that is due next"} " +
                "(list_assignments, get_assignment). Read its description and attached files (read_file / read_zip for code " +
                "templates). Explain the requirements, deliverables, deadline and grading, and point to the course material and " +
                "lecture timestamps that cover the needed concepts. Do not write the solution; help me plan it."
        }
        prompt(
            "exam_prep", "Exam preparation for a course from its material and recordings.",
            listOf(PromptArgument("course", "Course id or name", required = true)),
        ) { a ->
            "Prepare me for the exam of TUWEL course \"${a["course"]}\": check the course page and forums for exam modalities, " +
                "use summarize per section, and create a topic checklist, the most important definitions/theorems/formulas, " +
                "and 15 exam-style questions with answers, each citing the source (file + page or recording + timestamp)."
        }
    }

    private fun listing(entries: List<dev.tuwelmcp.files.ZipItem>): String =
        entries.filterNot { it.isDirectory }.joinToString("\n") { "${it.path}  (${human(it.size)})" } +
            "\n\n${entries.count { !it.isDirectory }} files, ${human(entries.sumOf { it.size })} uncompressed"

    private fun human(n: Long) = when {
        n < 1024 -> "$n B"; n < 1024 * 1024 -> "${n / 1024} KB"; else -> "%.1f MB".format(n / 1048576.0)
    }

    private fun truncate(text: String, max: Int, hint: String) =
        if (text.length <= max) text else text.substring(0, max) + "\n\n[… truncated at $max characters. $hint]"

    private fun Server.tool(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        readOnly: Boolean = true,
        handler: suspend ClientConnection.(JsonObject) -> CallToolResult,
    ) {
        addTool(name = name, description = description, inputSchema = inputSchema, toolAnnotations = ToolAnnotations(readOnlyHint = readOnly, openWorldHint = true)) { req ->
            try {
                handler(req.arguments ?: JsonObject(emptyMap()))
            } catch (e: MoodleException) {
                err((e.message ?: "TUWEL error") + if (e.needsLogin && !(e.message ?: "").contains("login")) " Call the `login` tool." else "")
            } catch (e: ApiException) {
                err((e.message ?: "LectureTube error") + if (e.notAuthorized && !(e.message ?: "").contains("login")) " Call the `login` tool." else "")
            } catch (e: IllegalArgumentException) {
                err(e.message ?: "invalid argument")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) =
        ToolSchema(properties = buildJsonObject { props.forEach { (k, v) -> put(k, v) } }, required = required.ifEmpty { null })

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type); put("description", description)
        if (type == "array") put("items", buildJsonObject { put("type", "integer") })
    }

    private fun ok(text: String) = CallToolResult(listOf<ContentBlock>(TextContent(text)))
    private fun err(text: String) = CallToolResult(listOf<ContentBlock>(TextContent(text)), isError = true)
    private fun json(o: JsonElement) = ok(prettyJson.encodeToString(JsonElement.serializer(), o))

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.reqStr(k: String) = str(k) ?: throw IllegalArgumentException("Missing required argument '$k'.")
    private fun JsonObject.reqLong(k: String) = long(k) ?: throw IllegalArgumentException("Missing required argument '$k'.")
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
}
