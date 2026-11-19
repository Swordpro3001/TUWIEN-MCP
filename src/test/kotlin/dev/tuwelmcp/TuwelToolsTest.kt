package dev.tuwelmcp

import dev.lecturetubemcp.auth.LoginFlow
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.auth.Session
import dev.testkit.FakeHttp
import dev.testkit.FakeLectureTube
import dev.testkit.FakeTuwel
import dev.testkit.args
import dev.testkit.call
import dev.testkit.text
import dev.tuwelmcp.mcp.TuwelMcp
import dev.tuwelmcp.moodle.MoodleClient
import dev.tuwelmcp.moodle.TuwelService
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every TUWEL tool against a canned Moodle + LectureTube backend. */
class TuwelToolsTest {
    private val http = FakeHttp(FakeTuwel.handler)
    private val session = Session(emptyList(), tokens = mapOf(MoodleClient.TOKEN_KEY to "tok"))
    private val svc = TuwelService(MoodleClient({ session }, http), FakeLectureTube.service(http, session))
    private val server = TuwelMcp(svc, LoginManager(LoginFlow(emptyList()))).build()

    private fun ok(tool: String, vararg a: Pair<String, Any?>): String {
        val r = server.call(tool, args(*a))
        assertFalse(r.isError == true, "$tool failed: ${r.text()}")
        return r.text()
    }

    private fun fails(tool: String, vararg a: Pair<String, Any?>): String {
        val r = server.call(tool, args(*a))
        assertTrue(r.isError == true, "$tool should fail: ${r.text()}")
        return r.text()
    }

    @Test fun coursesAndSearch() {
        val c = ok("list_courses", "filter" to "all")
        listOf("\"classification\":\"all\"", "\"favourite\":true", "\"progressPercent\":40", "\"category\":\"Math\"").forEach { assertContains(c, it) }
        val s = ok("search_courses", "query" to "Algebra")
        assertContains(s, "\"enrollable\":true")
        assertContains(fails("search_courses"), "query")
    }

    @Test fun courseStructure() {
        val c = ok("get_course", "courseId" to 42)
        assertContains(c, "\"name\":\"Week 1: Sets\"")
        assertContains(c, "\"externalUrl\":\"https://en.wikipedia.org/wiki/Group\"")
        assertContains(c, "\"completed\":true")
        assertContains(c, "\"Opened\":")
        assertContains(c, "\"kind\":\"lecture\"")
        assertFalse(c.contains("Hidden"))
        assertContains(ok("get_course", "courseId" to 42, "section" to "1", "details" to true), "Sets and maps")
        assertContains(ok("get_course", "courseId" to 42, "section" to "week"), "Exercise sheet")
        assertContains(fails("get_course", "courseId" to 42, "section" to "nope"), "No section matching")
        assertContains(fails("get_course", "courseId" to 404), "Can't find course")
        assertContains(fails("get_course", "courseId" to "abc"), "courseId")
    }

    @Test fun searchInCourse() {
        val s = ok("search_course", "courseId" to 42, "query" to "group", "deep" to true)
        assertContains(s, "Lecture slides about groups")
        assertContains(ok("search_course", "courseId" to 42, "query" to "recursion", "deep" to true), "Welcome page")
        assertContains(ok("search_course", "courseId" to 42, "query" to "sets"), "\"where\":\"section\"")
        assertContains(ok("search_course", "courseId" to 42, "query" to "code.zip"), "\"files\":[")
    }

    @Test fun updates() {
        val u = ok("course_updates", "courseId" to 42, "days" to 3)
        assertContains(u, "\"name\":\"Slides\"")
        assertContains(u, "\"what\":\"configuration\"")
    }

    @Test fun filesListAndRead() {
        assertContains(ok("list_files", "courseId" to 42, "type" to "pdf"), "\"count\":2")
        assertContains(ok("list_files", "courseId" to 42, "section" to "0", "type" to "video"), "lecture.mp4")
        val pdf = ok("read_file", "courseId" to 42, "cmid" to 1, "filename" to "slides.pdf", "pages" to "2")
        assertContains(pdf, "Lagrange theorem page 2")
        assertContains(ok("read_file", "url" to "${FakeTuwel.F}/notes.md", "maxChars" to 10), "truncated")
        assertContains(fails("read_file", "courseId" to 42, "cmid" to 1), "pass filename")
        assertContains(fails("read_file", "courseId" to 42, "cmid" to 77), "No matching file")
        assertContains(fails("read_file"), "Pass either url")
        assertContains(fails("read_file", "url" to "https://evil.example/x.pdf"), "Only https://tuwel.tuwien.ac.at/")
        assertContains(ok("read_file", "courseId" to 42, "cmid" to 5, "filename" to "src/code.zip"), "ZIP archive with 3 entries")
    }

    @Test fun zipArchives() {
        val zip = "${FakeTuwel.F}/code.zip"
        assertContains(ok("read_zip", "url" to zip), "Main.java")
        assertContains(ok("read_zip", "url" to zip, "entry" to "Main.java"), "class Main")
        assertContains(ok("read_zip", "url" to zip, "readAll" to true), "# Template")
        val img = server.call("read_zip", args("url" to zip, "entry" to "logo.png"))
        assertEquals(1, img.content.filterIsInstance<ImageContent>().size)
        assertContains(fails("read_zip", "url" to zip, "entry" to "nope.txt"), "nope.txt")
    }

    @Test fun modules() {
        assertContains(ok("read_module", "courseId" to 42, "cmid" to 2), "recursion and groups")
        assertContains(ok("read_module", "courseId" to 42, "cmid" to 8), "## 1")
        assertContains(ok("read_module", "courseId" to 42, "cmid" to 3), "Link: https://en.wikipedia.org/wiki/Group")
        val a = ok("read_module", "courseId" to 42, "cmid" to 4)
        assertContains(a, "Prove Lagrange")
        assertContains(a, "Attachment: hw1.pdf")
        assertContains(ok("read_module", "courseId" to 42, "cmid" to 1), "Files: slides.pdf, notes.md")
        assertContains(fails("read_module", "courseId" to 42, "cmid" to 999), "No module 999")
    }

    @Test fun assignmentsAndGrades() {
        val all = ok("list_assignments")
        assertContains(all, "\"overdue\":false")
        assertContains(all, "\"overdue\":true")
        assertContains(all, "\"maxGrade\":10")
        assertContains(ok("list_assignments", "courseId" to 42), "\"submission\":\"submitted\"")
        val d = ok("get_assignment", "assignId" to 100)
        listOf("\"instructions\":\"Upload a PDF\"", "\"file.maxfilesubmissions\":\"1\"", "\"grade\":\"9 / 10\"", "\"feedback\":\"Well done\"").forEach { assertContains(d, it) }
        assertFalse(d.contains("comments.enabled"))
        assertContains(fails("get_assignment", "assignId" to 5), "not found")
        assertContains(ok("get_grades"), "\"course\":\"104.271 Algebra (2025W)\"")
        val g = ok("get_grades", "courseId" to 42)
        listOf("\"item\":\"Homework 1\"", "\"item\":\"Course total\"", "\"weight\":\"50 %\"", "\"feedback\":\"Nice\"").forEach { assertContains(g, it) }
    }

    @Test fun deadlinesAndCalendar() {
        val d = ok("upcoming_deadlines", "days" to 9999)
        listOf("Homework 1 is due", "\"actionable\":true", "\"overdue\":true", "\"instance\":100").forEach { assertContains(d, it) }
        val c = ok("calendar", "courseId" to 42)
        listOf("\"durationMinutes\":90", "\"location\":\"HS 1\"", "\"description\":\"Bring ID\"").forEach { assertContains(c, it) }
        assertTrue(c.indexOf("\"name\":\"Me\"") < c.indexOf("\"name\":\"Exam\""), "sorted by start")
    }

    @Test fun forumsAndNotifications() {
        assertContains(ok("list_forums", "courseId" to 42), "\"discussions\":1")
        val d = ok("list_discussions", "forumId" to 11, "perPage" to 500)
        assertContains(d, "\"pinned\":true")
        assertContains(d, "The exam is on Monday")
        val p = ok("read_discussion", "discussionId" to 21)
        assertContains(p, "\"replyTo\":31")
        assertContains(p, "room.pdf")
        val n = ok("notifications", "unreadOnly" to true)
        assertContains(n, "You got 9")
        assertFalse(n.contains("Old news"))
        assertContains(ok("notifications"), "Old news")
    }

    @Test fun recordingsOfCourse() {
        val r = ok("course_recordings", "courseId" to 42)
        assertContains(r, "\"title\":\"104.271 Algebra (2025W)\"") // series via LTI link, course ACL and LVA number
        assertContains(r, "lecture.mp4")
        assertContains(r, "none yet (transcribe_recording)")
        assertContains(r, "\"id\":\"evKgSNekCVlvX\"")
    }

    @Test fun listening() {
        assertContains(ok("listen_recording", "lecture" to "evKgSNekCVlvX", "fromMinute" to 1), "Heute geht es um Gruppen")
        assertContains(fails("listen_recording", "lecture" to "evNoCaptions1"), "no captions")
        assertContains(fails("listen_recording", "courseId" to 42, "cmid" to 5, "filename" to "lecture.mp4"), "has no transcript yet")
    }

    @Test fun summarizeCollectsMaterial() {
        val s = ok("summarize", "courseId" to 42, "focus" to "exam", "language" to "German", "maxChars" to 600_000)
        listOf(
            "Focus: exam.", "Write in German.", "# 104.271 Algebra (2025W)", "Groups, rings, fields", "Lagrange theorem page 1",
            "huge.pdf skipped", "[video lecture.mp4: no transcript yet", "Template", "Hallo und willkommen", "Course recording series",
        ).forEach { assertContains(s, it) }
        val one = ok("summarize", "courseId" to 42, "cmids" to listOf(10), "includeFiles" to false, "includeRecordings" to false)
        assertContains(one, "Exercise sheet")
        assertFalse(one.contains("Slides"))
        assertContains(ok("summarize", "courseId" to 42, "section" to "General", "maxChars" to 1), "## Section 0: General")
    }

    @Test fun authStatusAndLogout() {
        val a = ok("auth_status")
        assertContains(a, "TUWEL: logged in as Ada Student (e123), via app token.")
        assertContains(ok("logout"), "removed")
    }

    @Test fun promptsExist() {
        assertTrue(server.prompts.isNotEmpty())
        kotlinx.coroutines.runBlocking {
            for ((name, p) in server.prompts) {
                val req = io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams(name, p.prompt.arguments.orEmpty().associate { it.name to "42" }),
                )
                assertTrue(p.messageProvider(dev.testkit.noClient, req).messages.isNotEmpty(), name)
            }
        }
    }
}
