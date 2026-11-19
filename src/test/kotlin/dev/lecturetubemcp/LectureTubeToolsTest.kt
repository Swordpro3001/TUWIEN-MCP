package dev.lecturetubemcp

import dev.lecturetubemcp.auth.LoginFlow
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.mcp.LectureTubeMcp
import dev.testkit.FakeHttp
import dev.testkit.FakeLectureTube
import dev.testkit.args
import dev.testkit.call
import dev.testkit.redirect
import dev.testkit.text
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every LectureTube tool against a canned backend: arguments in, the text Claude would see out. */
class LectureTubeToolsTest {
    private val http = FakeHttp(FakeLectureTube.handler)
    private val server = LectureTubeMcp(FakeLectureTube.service(http), LoginManager(LoginFlow(emptyList()))).build()

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

    @Test fun lectureDetails() {
        val t = ok("get_lecture", "lecture" to "https://video.tuwien.ac.at/!v/KgSNekCVlvX")
        assertContains(t, "\"title\":\"Algebra VO lvX\"")
        assertContains(t, "\"slideCount\":3")
        assertContains(t, "https://video.tuwien.ac.at/!v/KgSNekCVlvX")
        assertContains(ok("get_lecture", "lecture" to FakeLectureTube.EVENT_UUID), FakeLectureTube.EVENT_UUID)
    }

    @Test fun lectureErrors() {
        assertContains(fails("get_lecture", "lecture" to "evForbidden12"), "not allowed")
        assertContains(fails("get_lecture", "lecture" to "evMissing1234"), "No lecture found")
        assertContains(fails("get_lecture", "lecture" to "not an id"), "Unrecognized ID")
        assertContains(fails("get_lecture"), "lecture")
    }

    @Test fun transcriptFromCaptions() {
        val t = ok("get_transcript", "lecture" to "evKgSNekCVlvX")
        assertContains(t, "transcript language: de")
        assertContains(t, "Gruppen")
        assertContains(ok("get_transcript", "lecture" to "evKgSNekCVlvX", "format" to "vtt"), "WEBVTT")
        val cues = ok("get_transcript", "lecture" to "evKgSNekCVlvX", "format" to "cues", "lang" to "en", "fromMinute" to 1, "toMinute" to 2)
        assertContains(cues, "Heute geht es um Gruppen")
        assertFalse(cues.contains("willkommen"))
    }

    @Test fun transcriptMissingSuggestsTranscription() {
        val t = fails("get_transcript", "lecture" to "evNoCaptions1")
        assertContains(t, "no captions")
        assertContains(t, "transcribe_recording")
        assertContains(t, "3 detected slides")
    }

    @Test fun slides() {
        val r = server.call("get_slides", args("lecture" to "evKgSNekCVlvX", "maxImages" to 2))
        assertContains(r.text(), "3 in window, 2 images attached")
        assertEquals(2, r.content.filterIsInstance<ImageContent>().size)
        assertContains(ok("get_slides", "lecture" to "evKgSNekCVlvX", "maxImages" to 0, "fromMinute" to 1, "toMinute" to 5), "1 in window")
        assertContains(fails("get_slides", "lecture" to "evKgSNekCVlvX", "fromMinute" to 30), "in this time window")
    }

    @Test fun searchAndBrowse() {
        val s = ok("search_lectures", "query" to "Gruppen", "type" to "event", "from" to "2025-01-01", "to" to "2025-12-31")
        assertContains(s, "\"id\":\"evKgSNekCVlvX\"")
        assertContains(s, "\"seriesId\":\"srAbCdEfGhIjK\"")
        assertContains(s, "\"type\":\"page\"")
        assertContains(s, "\"type\":\"playlist\"")
        assertContains(s, "\"at\":\"00:01:05\"")
        assertContains(fails("search_lectures", "query" to "x"), "too short")
        val b = ok("browse_pages", "path" to "/lectures")
        listOf("Welcome", "Some text", "\"seriesId\":\"srAbCdEfGhIjK\"", "\"lecture\":\"VO 1\"", "\"playlist\":\"PL\"").forEach { assertContains(b, it) }
        assertContains(fails("browse_pages", "path" to "/nowhere"), "No page")
    }

    @Test fun seriesAndListings() {
        val s = ok("get_series", "series" to "srAbCdEfGhIjK")
        assertContains(s, "\"lectureCount\":1")
        assertContains(s, "not accessible")
        assertContains(ok("get_series", "series" to FakeLectureTube.SERIES_UUID), "~rss/series/AbCdEfGhIjK")
        assertContains(fails("get_series", "series" to "srMissing1234"), "No series found")
        val l = ok("list_lectures", "series" to "srAbCdEfGhIjK", "limit" to 999)
        assertContains(l, "\"totalIsLowerBound\":true")
        assertContains(l, "\"captions\":1")
        assertContains(l, "\"slides\":1")
        assertContains(ok("list_lectures", "series" to FakeLectureTube.SERIES_UUID, "query" to "Gruppen"), "VO 1")
        assertContains(fails("list_lectures", "series" to "srMissing1234"), "Unknown series")
        assertContains(ok("list_series", "query" to "Algebra"), "104.271 Algebra (2025W)")
        assertTrue(http.requests.any { it.params["sid"] == FakeLectureTube.SERIES_UUID })
    }

    @Test fun profileAndOwnContent() {
        assertContains(ok("my_profile"), "Ada Student")
        assertContains(ok("my_videos", "limit" to 500), "Mine")
        assertContains(ok("my_series"), "\"totalCount\":0")
        assertContains(ok("auth_status"), "Logged in as Ada Student (e123)")
        assertContains(ok("auth_status"), "Opencast node user: e123")
    }

    @Test fun rawGraphqlIsReadOnly() {
        assertContains(ok("graphql_query", "query" to "{ anything }", "variables" to buildJsonObject { put("a", 1) }), "echo")
        assertContains(fails("graphql_query", "query" to "mutation { deleteAll }"), "Only queries")
        assertContains(fails("graphql_query", "query" to "{ mutationLike }"), "call the `login` tool")
    }

    @Test fun logoutClearsSession() {
        assertContains(ok("logout"), "removed")
    }

    @Test fun clientMapsHttpFailures(): Unit = runBlocking {
        val svc = FakeLectureTube.service(FakeHttp { r ->
            when (r.path) {
                "/graphql" -> text("boom", 500)
                "/forbidden.json" -> text("<html>Opencast :: Forbidden</html>", contentType = "text/html")
                "/login.json" -> redirect("/Shibboleth.sso/Login")
                else -> text("x", 503)
            }
        })
        val c = svc.client
        assertContains(runCatching { c.graphql("{ a }") }.exceptionOrNull()!!.message!!, "GraphQL HTTP 500")
        assertTrue((runCatching { c.opencast("/forbidden.json", emptyMap()) }.exceptionOrNull() as dev.lecturetubemcp.api.ApiException).notAuthorized)
        assertContains(runCatching { c.opencast("/login.json", emptyMap()) }.exceptionOrNull()!!.message!!, "requires login")
        assertContains(runCatching { c.opencast("/other.json", mapOf("a" to 1)) }.exceptionOrNull()!!.message!!, "HTTP 503")
        assertContains(runCatching { c.getText("https://cdn.video.tuwien.ac.at/x") }.exceptionOrNull()!!.message!!, "HTTP 503")
        assertContains(runCatching { c.getBytes("https://cdn.video.tuwien.ac.at/x") }.exceptionOrNull()!!.message!!, "HTTP 503")
        assertEquals(null, c.opencastMe())
    }

    @Test fun bigDownloadsAreRefused(): Unit = runBlocking {
        val c = FakeLectureTube.service(FakeHttp { dev.testkit.Resp(200, ByteArray(20)) }).client
        assertContains(runCatching { c.getBytes("https://cdn.video.tuwien.ac.at/x", maxBytes = 10) }.exceptionOrNull()!!.message!!, "too large")
    }

    @Test fun promptsAreListed() {
        assertEquals(setOf("summarize_lecture", "exam_prep"), server.prompts.keys)
        runBlocking {
            val p = server.prompts.getValue("summarize_lecture").messageProvider(
                dev.testkit.noClient,
                io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams("summarize_lecture", mapOf("lecture" to "evX", "language" to "en")),
                ),
            )
            assertContains(p.messages.first().content.toString(), "evX")
        }
    }
}
