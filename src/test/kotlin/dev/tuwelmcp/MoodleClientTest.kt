package dev.tuwelmcp

import dev.lecturetubemcp.auth.Session
import dev.lecturetubemcp.auth.StoredCookie
import dev.testkit.FakeHttp
import dev.testkit.FakeTuwel
import dev.testkit.Req
import dev.testkit.Resp
import dev.testkit.redirect
import dev.testkit.text
import dev.tuwelmcp.moodle.MoodleClient
import dev.tuwelmcp.moodle.MoodleException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Credentials and transport of the TUWEL client: app token, its renewal, the AJAX fallback and downloads. */
class MoodleClientTest {
    private val base = "https://tuwel.tuwien.ac.at"
    private val cookie = Session(listOf(StoredCookie("MoodleSession", "s1", "tuwel.tuwien.ac.at")))

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** launch.php the way Moodle answers it: a redirect to the app scheme with a signed token. */
    private fun launch(r: Req, token: String = "fresh"): Resp {
        val passport = r.params.getValue("passport")
        val payload = Base64.getEncoder().encodeToString("${md5(base + passport)}:::$token:::priv".toByteArray())
        return redirect("moodlemobile://token=$payload")
    }

    private fun ws(r: Req) = text(
        if (r.params["wstoken"] == "old") """{"exception":"x","errorcode":"invalidtoken","message":"Invalid token"}"""
        else FakeTuwel.ws(r.params.getValue("wsfunction"), r.params),
    )

    @Test fun acquiresTokenFromBrowserSession(): Unit = runBlocking {
        val http = FakeHttp { r -> if (r.path.endsWith("launch.php")) launch(r) else ws(r) }
        val c = MoodleClient({ cookie }, http)
        assertEquals("Ada Student", c.call("core_webservice_get_site_info").jsonObject["fullname"].toString().trim('"'))
        assertTrue(http.requests.any { it.params["wstoken"] == "fresh" })
        assertTrue(http.requests.first().headers.firstValue("Cookie").orElse("").contains("MoodleSession=s1"))
    }

    @Test fun renewsRevokedToken(): Unit = runBlocking {
        val http = FakeHttp { r -> if (r.path.endsWith("launch.php")) launch(r) else ws(r) }
        val c = MoodleClient({ cookie.copy(tokens = mapOf(MoodleClient.TOKEN_KEY to "old")) }, http)
        c.call("core_webservice_get_site_info")
        assertEquals(listOf("old", "fresh"), http.requests.mapNotNull { it.params["wstoken"] })
    }

    @Test fun badTokenResponsesAreRejected(): Unit = runBlocking {
        val wrongSig = FakeHttp { redirect("moodlemobile://token=" + Base64.getEncoder().encodeToString("nope:::t".toByteArray())) }
        assertContains(assertFailsWith<MoodleException> { MoodleClient({ cookie }, wrongSig).acquireToken() }.message!!, "Unexpected token")
        val toLogin = FakeHttp { redirect("$base/login/index.php") }
        assertTrue(assertFailsWith<MoodleException> { MoodleClient({ cookie }, toLogin).acquireToken() }.needsLogin)
        assertTrue(assertFailsWith<MoodleException> { MoodleClient({ null }, toLogin).acquireToken() }.needsLogin)
        assertTrue(assertFailsWith<MoodleException> { MoodleClient({ Session(emptyList()) }, toLogin).acquireToken() }.needsLogin)
    }

    @Test fun fallsBackToAjaxWithSesskey(): Unit = runBlocking {
        var ajax: Req? = null
        val http = FakeHttp { r ->
            when {
                r.path.endsWith("launch.php") -> text("", 200)
                r.path == "/my/" -> text("""<script>M.cfg = {"sesskey":"abc123"};</script>""", contentType = "text/html")
                r.path == "/lib/ajax/service.php" -> { ajax = r; text("""[{"error":false,"data":{"userid":7}}]""") }
                else -> text("?", 404)
            }
        }
        val c = MoodleClient({ cookie }, http)
        assertEquals("7", c.call("core_webservice_get_site_info").jsonObject["userid"].toString())
        assertEquals("abc123", ajax!!.params["sesskey"])
        assertContains(ajax!!.body, "core_webservice_get_site_info")
        c.call("x") // sesskey is reused
        assertEquals(1, http.requests.count { it.path == "/my/" })
    }

    @Test fun ajaxErrors(): Unit = runBlocking {
        fun client(answer: String, my: Resp = text(""""sesskey":"k"""")) = MoodleClient({ cookie }, FakeHttp { r ->
            when (r.path) { "/my/" -> my; "/lib/ajax/service.php" -> text(answer); else -> text("", 200) }
        })
        assertTrue(assertFailsWith<MoodleException> { client("""{"error":"x","errorcode":"invalidsesskey"}""").callAjax("f", JsonObject(emptyMap())) }.needsLogin)
        assertEquals("other", assertFailsWith<MoodleException> { client("""{"error":"bad","errorcode":"other"}""").callAjax("f", JsonObject(emptyMap())) }.errorCode)
        val ex = assertFailsWith<MoodleException> {
            client("""[{"error":true,"exception":{"errorcode":"servicerequireslogin","message":"Log in"}}]""").callAjax("f", JsonObject(emptyMap()))
        }
        assertTrue(ex.needsLogin)
        assertTrue(assertFailsWith<MoodleException> { client("[]", my = redirect("/login")).refreshSesskey() }.needsLogin)
        assertContains(assertFailsWith<MoodleException> { client("[]", my = text("<html/>")).refreshSesskey() }.message!!, "sesskey")
    }

    @Test fun wsSendsPhpStyleArrays(): Unit = runBlocking {
        val http = FakeHttp { text("null") }
        val c = MoodleClient({ null }, http)
        c.callWs("t", "f", buildJsonObject {
            put("ids", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(1)); add(kotlinx.serialization.json.JsonPrimitive(2)) })
            put("flag", true); put("off", false); put("s", "true")
            put("o", buildJsonObject { put("name", "x") })
        })
        val p = http.requests.single().params
        assertEquals(listOf("1", "2", "1", "0", "true", "x"), listOf(p["ids[0]"], p["ids[1]"], p["flag"], p["off"], p["s"], p["o[name]"]))
    }

    @Test fun downloads(): Unit = runBlocking {
        val withToken = MoodleClient({ cookie.copy(tokens = mapOf(MoodleClient.TOKEN_KEY to "t")) }, FakeHttp(FakeTuwel.handler))
        val (bytes, ct) = withToken.download("$base/pluginfile.php/1/mod_resource/content/0/notes.md")
        assertContains(String(bytes), "identity element")
        assertEquals("text/plain", ct)
        assertContains(withToken.download("${FakeTuwel.F}/moved.pdf").second!!, "pdf")
        assertTrue(assertFailsWith<MoodleException> { withToken.download("${FakeTuwel.F}/private.pdf") }.needsLogin)
        assertTrue(assertFailsWith<MoodleException> { withToken.download("${FakeTuwel.F}/loginpage.pdf") }.needsLogin)
        assertContains(assertFailsWith<MoodleException> { withToken.download("${FakeTuwel.F}/gone.pdf") }.message!!, "HTTP 404")
        assertContains(assertFailsWith<MoodleException> { withToken.download("${FakeTuwel.F}/slides.pdf", maxBytes = 10) }.message!!, "larger than")
        val loop = MoodleClient({ cookie }, FakeHttp { r -> redirect(r.uri.toString() + "x", 302) })
        assertContains(assertFailsWith<MoodleException> { loop.download("$base/pluginfile.php/a") }.message!!, "Too many redirects")
        assertFailsWith<IllegalArgumentException> { withToken.download("http://tuwel.tuwien.ac.at/x") }
    }

    @Test fun mediaRequestUsesTokenOrCookie() {
        val tok = MoodleClient({ cookie.copy(tokens = mapOf(MoodleClient.TOKEN_KEY to "t")) }, FakeHttp { text("") })
        assertEquals("$base/webservice/pluginfile.php/1/v.mp4?token=t" to emptyMap(), tok.mediaRequest("$base/pluginfile.php/1/v.mp4"))
        assertEquals("$base/webservice/pluginfile.php/1/v.mp4?a=1&token=t", tok.mediaRequest("$base/webservice/pluginfile.php/1/v.mp4?a=1").first)
        val (u, h) = MoodleClient({ cookie }, FakeHttp { text("") }).mediaRequest("$base/pluginfile.php/1/v.mp4")
        assertEquals("$base/pluginfile.php/1/v.mp4", u)
        assertEquals("MoodleSession=s1", h["Cookie"])
    }

    @Test fun pages(): Unit = runBlocking {
        val c = MoodleClient({ cookie }, FakeHttp { r -> when (r.path) { "/ok" -> text("hi"); "/login" -> redirect("/x"); else -> text("", 500) } })
        assertEquals("hi", c.page("/ok"))
        assertEquals("hi", c.page("$base/ok"))
        assertTrue(assertFailsWith<MoodleException> { c.page("/login") }.needsLogin)
        assertContains(assertFailsWith<MoodleException> { c.page("/err") }.message!!, "HTTP 500")
    }

    @Test fun onlyTuwelUrlsAreFetched() {
        for (bad in listOf("https://tuwel.tuwien.ac.at.evil.com/x", "https://user@tuwel.tuwien.ac.at/x", "https://tuwel.tuwien.ac.at:8443/x", "ftp://tuwel.tuwien.ac.at/x", "::")) {
            assertFailsWith<IllegalArgumentException>(bad) { MoodleClient.requireTuwelUrl(bad) }
        }
        assertEquals("https://TUWEL.tuwien.ac.at:443/x", MoodleClient.requireTuwelUrl("https://TUWEL.tuwien.ac.at:443/x"))
    }
}
