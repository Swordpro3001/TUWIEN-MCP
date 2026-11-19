package dev.testkit

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Canned TUWEL (Moodle) backend: web-service functions by name, plus pluginfile downloads. */
object FakeTuwel {
    const val F = "https://tuwel.tuwien.ac.at/webservice/pluginfile.php/1/mod_resource/content/0"
    private val now = Instant.now().epochSecond
    private val soon = now + 3 * 86_400
    private val past = now - 3 * 86_400

    private fun file(name: String, mime: String, size: Int = 1000, path: String = "/") =
        """{"type":"file","filename":"$name","filepath":"$path","filesize":$size,"mimetype":"$mime","fileurl":"$F/$name","timemodified":$past}"""

    val contents = """[
      {"id":1,"section":0,"name":"General","summary":"<p>Welcome to <b>Algebra</b></p>","modules":[
        {"id":1,"name":"Slides","modname":"resource","description":"<p>Lecture slides about groups</p>","url":"https://tuwel.tuwien.ac.at/mod/resource/view.php?id=1",
         "contents":[${file("slides.pdf", "application/pdf")},${file("notes.md", "text/markdown")}],
         "dates":[{"label":"Opened:","timestamp":$past}],"completion":1,"completiondata":{"state":1}},
        {"id":2,"name":"Welcome page","modname":"page","description":"",
         "contents":[${file("index.html", "text/html")}]},
        {"id":3,"name":"Wiki","modname":"url","contents":[{"type":"url","filename":"Wiki","fileurl":"https://en.wikipedia.org/wiki/Group"}]},
        {"id":4,"name":"Homework 1","modname":"assign","description":"<p>Solve exercises</p>","contents":[]},
        {"id":5,"name":"Code","modname":"folder","contents":[${file("code.zip", "application/zip", path = "/src/")},${file("lecture.mp4", "video/mp4", 300_000_000)},
         ${file("huge.pdf", "application/pdf", 100_000_000)}]},
        {"id":6,"name":"Recordings","modname":"lti","instance":9,"contents":[]},
        {"id":7,"name":"Hidden","modname":"label","uservisible":false,"visible":0,"contents":[]},
        {"id":8,"name":"Script","modname":"book","contents":[${file("index.html", "text/html", path = "/1/")}]},
        {"id":9,"name":"Announcements","modname":"forum","description":"<p>See https://video.tuwien.ac.at/!v/KgSNekCVlvX</p>","contents":[]}]},
      {"id":2,"section":1,"name":"Week 1: Sets","summary":"<p>Sets and maps</p>","modules":[
        {"id":10,"name":"Exercise sheet","modname":"resource","contents":[${file("sheet1.txt", "text/plain")}]}]}
    ]"""

    private val course = """{"id":42,"fullname":"104.271 Algebra (2025W)","shortname":"ALG","summary":"<p>Groups, rings, fields</p>","idnumber":""}"""

    private val assign = """{"id":42,"fullname":"104.271 Algebra (2025W)","assignments":[
        {"id":100,"cmid":4,"name":"Homework 1","duedate":$soon,"cutoffdate":${soon + 3600},"allowsubmissionsfromdate":$past,"grade":10,
         "intro":"<p>Prove Lagrange</p>","activity":"<p>Upload a PDF</p>","introattachments":[{"filename":"hw1.pdf","fileurl":"$F/hw1.pdf","filesize":5}],
         "configs":[{"plugin":"file","name":"maxfilesubmissions","value":"1"},{"plugin":"comments","name":"enabled","value":"1"}]},
        {"id":101,"cmid":11,"name":"Old homework","duedate":$past,"grade":0,"intro":"","introattachments":[]}]}"""

    fun ws(fn: String, p: Map<String, String>): String = when (fn) {
        "core_webservice_get_site_info" -> """{"userid":7,"fullname":"Ada Student","username":"e123"}"""
        "core_course_get_enrolled_courses_by_timeline_classification" ->
            """{"courses":[{"id":42,"fullname":"104.271 Algebra (2025W)","shortname":"ALG","coursecategory":"Math","startdate":$past,"enddate":$soon,
               "progress":40,"isfavourite":true,"viewurl":"https://tuwel.tuwien.ac.at/course/view.php?id=42"}]}"""
        "core_course_search_courses" -> """{"total":1,"courses":[{"id":42,"fullname":"104.271 Algebra (2025W)","shortname":"ALG","categoryname":"Math",
               "summary":"<p>Groups</p>","enrollmentmethods":["self","guest"]}]}"""
        "core_course_get_courses_by_field" -> """{"courses":[$course]}"""
        "core_course_get_contents" -> if (p["courseid"] == "404") """{"exception":"x","errorcode":"invalidrecord","message":"Can't find course"}""" else contents
        "mod_assign_get_assignments" -> """{"courses":[$assign,{"id":43,"fullname":"Empty","assignments":[]}]}"""
        "mod_assign_get_submission_status" -> """{"lastattempt":{"submission":{"status":"submitted","timemodified":$past},"gradingstatus":"graded"},
               "feedback":{"gradefordisplay":"<b>9</b> / 10","plugins":[{"editorfields":[{"text":"<p>Well done</p>"}]}]}}"""
        "core_calendar_get_action_events_by_timesort" -> """{"events":[{"timesort":$soon,"name":"Homework 1 is due","course":{"fullname":"Algebra","id":42},
               "modulename":"assign","instance":100,"action":{"name":"Add submission","actionable":true},"overdue":true,"url":"https://tuwel.tuwien.ac.at/x"}]}"""
        "core_calendar_get_calendar_events" -> """{"events":[{"timestart":$soon,"timeduration":5400,"name":"Exam","eventtype":"course","modulename":"quiz",
               "courseid":42,"location":"HS 1","description":"<p>Bring ID</p>"},{"timestart":$now,"timeduration":0,"name":"Me","eventtype":"user","courseid":1}]}"""
        "gradereport_overview_get_course_grades" -> """{"grades":[{"courseid":42,"grade":"9.00"}]}"""
        "gradereport_user_get_grade_items" -> """{"usergrades":[{"coursefullname":"104.271 Algebra (2025W)","gradeitems":[
               {"itemname":"Homework 1","itemtype":"mod","itemmodule":"assign","gradeformatted":"9.00","percentageformatted":"90 %","rangeformatted":"0–10",
                "weightformatted":"50 %","feedback":"<p>Nice</p>","gradedatesubmitted":$past},
               {"itemname":null,"itemtype":"course","gradeformatted":"9.00","weightformatted":"-"}]}]}"""
        "mod_forum_get_forums_by_courses" -> """[{"id":11,"cmid":9,"name":"Announcements","type":"news","numdiscussions":1,"intro":"<p>News</p>"}]"""
        "mod_forum_get_forum_discussions" -> """{"discussions":[{"discussion":21,"name":"Exam date","userfullname":"Prof","created":$past,"timemodified":$past,
               "numreplies":1,"pinned":true,"message":"<p>The exam is on Monday</p>"}]}"""
        "mod_forum_get_discussion_posts" -> """{"posts":[{"id":31,"subject":"Exam date","author":{"fullname":"Prof"},"timecreated":$past,"parentid":0,
               "message":"<p>Monday</p>","attachments":[{"filename":"room.pdf","fileurl":"$F/room.pdf"}]},
               {"id":32,"subject":"Re: Exam date","author":{"fullname":"Ada"},"timecreated":$now,"parentid":31,"message":"Thanks"}]}"""
        "message_popup_get_popup_notifications" -> """{"unreadcount":1,"notifications":[
               {"timecreated":$now,"subject":"New grade","smallmessage":"","fullmessagehtml":"<p>You got 9</p>","contexturl":"https://tuwel.tuwien.ac.at/g","read":false},
               {"timecreated":$past,"subject":"Old","smallmessage":"Old news","read":true}]}"""
        "core_course_get_updates_since" -> """{"instances":[{"id":1,"updates":[{"name":"configuration","timeupdated":$now}]},{"id":99,"updates":[]}]}"""
        "mod_lti_get_tool_launch_data" -> """{"parameters":[{"name":"custom_series","value":"https://video.tuwien.ac.at/!s/AbCdEfGhIjK"}]}"""
        else -> """{"exception":"x","errorcode":"invalidfunction","message":"Unknown $fn"}"""
    }

    val pdf: ByteArray by lazy {
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            repeat(2) { i ->
                val page = org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page)
                org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f); cs.showText("Lagrange theorem page ${i + 1}"); cs.endText()
                }
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    val zip: ByteArray by lazy {
        ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("Main.java")); z.write("class Main { /* group */ }".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("README.md")); z.write("# Template".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("logo.png")); z.write(png); z.closeEntry()
            }
        }.toByteArray()
    }

    val png: ByteArray by lazy {
        ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    }

    fun download(name: String): Resp = when (name) {
        "slides.pdf", "huge.pdf", "hw1.pdf" -> Resp(200, pdf, mapOf("Content-Type" to listOf("application/pdf")))
        "code.zip" -> Resp(200, zip, mapOf("Content-Type" to listOf("application/zip")))
        "index.html" -> text("<html><body><h1>Welcome</h1><p>Today: recursion and groups.</p></body></html>", contentType = "text/html")
        "notes.md", "sheet1.txt" -> text("# Notes\nA group has an identity element.", contentType = "text/plain")
        "logo.png" -> Resp(200, png, mapOf("Content-Type" to listOf("image/png")))
        "moved.pdf" -> redirect("$F/slides.pdf", 302)
        "private.pdf" -> redirect("https://tuwel.tuwien.ac.at/login/index.php", 302)
        "loginpage.pdf" -> text("<a href='/login/index.php'>Log in</a>", contentType = "text/html")
        else -> text("missing", 404, "text/plain")
    }

    /** Moodle with a valid web-service token: WS calls and token-authenticated downloads. */
    val handler: (Req) -> Resp = { r ->
        when {
            r.uri.host != "tuwel.tuwien.ac.at" -> FakeLectureTube.handler(r)
            r.path == "/webservice/rest/server.php" -> text(ws(r.params.getValue("wsfunction"), r.params))
            "pluginfile.php" in r.path -> download(r.path.substringAfterLast('/'))
            else -> text("not found", 404, "text/plain")
        }
    }
}
