package dev.lecturetubemcp

import dev.lecturetubemcp.auth.BrowserExe
import dev.lecturetubemcp.auth.BrowserKind
import dev.lecturetubemcp.auth.Browsers
import dev.lecturetubemcp.auth.Doctor
import dev.lecturetubemcp.auth.LoginFlow
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.auth.Session
import dev.lecturetubemcp.auth.SessionStore
import dev.lecturetubemcp.auth.SsoTarget
import dev.lecturetubemcp.auth.StoredCookie
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the installed browsers headless through DevTools (Chromium family) and WebDriver BiDi (Firefox family).
 * Nothing goes to the network: pages are data: URLs and the "SSO" checks only look at cookies.
 * Each part is skipped when no browser of that kind is installed (CI's Ubuntu and Windows runners have Chrome and Firefox).
 */
class BrowserAutomationTest {
    private fun browser(kind: BrowserKind): BrowserExe? = Browsers.detect().firstOrNull { it.kind == kind }

    /**
     * Tests reuse the same login profiles, and a browser keeps its profile locked for a moment after close()
     * returns; a launch in that moment hands off to the dying browser and exits (Chromium code 21). Wait it out.
     */
    @AfterTest fun cleanup() {
        SessionStore.clear()
        val profiles = Config.dataDir.resolve("profiles")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && java.nio.file.Files.isDirectory(profiles) &&
            java.nio.file.Files.list(profiles).use { s -> s.toList() }.any { p ->
                listOf("SingletonLock", "lock").any { java.nio.file.Files.exists(p.resolve(it), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            }
        ) Thread.sleep(100)
    }

    private fun driverRoundTrip(kind: BrowserKind) = runBlocking {
        val exe = browser(kind) ?: return@runBlocking
        val d = Browsers.launch(exe, headless = true)
        try {
            d.setCookies(listOf(StoredCookie("probe", "42", "example.test", secure = false, httpOnly = false, expires = 4_000_000_000)))
            d.open("data:text/html,<h1>hi</h1>")
            assertTrue(d.cookies().any { it.name == "probe" && it.value == "42" }, "${exe.name}: cookie round trip")
        } finally {
            d.close()
        }
        assertFalse(d.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS).not(), "${exe.name} still running after close")
    }

    @Test fun chromiumDriver() = driverRoundTrip(BrowserKind.CHROMIUM)

    @Test fun firefoxDriver() = driverRoundTrip(BrowserKind.FIREFOX)

    /** A silent login: the stored IdP cookie is put into a headless browser, every target accepts it, cookies are stored. */
    @Test fun silentLoginAcrossTargets() = runBlocking {
        val exe = browser(BrowserKind.CHROMIUM) ?: browser(BrowserKind.FIREFOX) ?: return@runBlocking
        val idp = StoredCookie("SimpleSAMLSessionID", "idp-1", Config.IDP_HOST, expires = 4_000_000_000)
        SessionStore.save(Session(listOf(idp)))
        fun target(name: String) = SsoTarget(name, Config.IDP_HOST, "data:text/html,$name") { s ->
            s.cookiesFor(Config.IDP_HOST).firstOrNull { it.value == "idp-1" }?.let { "Ada" }
        }
        val log = mutableListOf<String>()
        val r = LoginFlow(listOf(target("First"), target("Second")), log::add).run(exe.path, 30.seconds, interactive = false)
        assertTrue(r.ok, r.message)
        assertContains(r.message, "First: Ada; Second: Ada")
        assertTrue(log.any { it.startsWith("Trying silent login") }, log.toString())
        assertEquals("idp-1", SessionStore.current!!.cookiesFor(Config.IDP_HOST).single().value)
        assertEquals("browser:${exe.kind.name.lowercase()}", SessionStore.current!!.source)
    }

    @Test fun silentLoginNeedsAStoredSession() = runBlocking {
        val exe = Browsers.detect().firstOrNull() ?: return@runBlocking
        SessionStore.clear()
        val r = LoginFlow(emptyList()).run(exe.path, 5.seconds, interactive = false)
        assertFalse(r.ok)
        assertContains(r.message, "interactive login is required")
    }

    @Test fun loginManagerReportsFailures() = runBlocking {
        val m = LoginManager(LoginFlow(emptyList()))
        val r = m.start("/no/such/browser", 1.seconds).await()
        assertFalse(r.ok)
        assertContains(r.message, "Login failed")
        assertEquals(r, m.lastResult)
        assertFalse(m.inProgress)
    }

    @Test fun pickingBrowsers() {
        assertContains(assertFailsWith<IllegalStateException> { Browsers.pick("netscape-navigator") }.message!!, "not found")
        assertEquals(BrowserKind.FIREFOX, Browsers.pick("/opt/firefox/firefox").kind)
        assertEquals("/opt/x/chrome", Browsers.pick("/opt/x/chrome").path)
        val any = Browsers.detect().firstOrNull() ?: return
        assertEquals(any.kind, Browsers.pick(if (any.kind == BrowserKind.FIREFOX) "firefox" else "chromium").kind)
        assertTrue(Browsers.pick(null).name.isNotEmpty())
        assertTrue(Browsers.lastChoiceNote.isNotEmpty())
        assertTrue(any.profileName.matches(Regex("[a-z0-9-]+")))
    }

    @Test fun doctorReports() = runBlocking {
        val out = mutableListOf<String>()
        val code = Doctor.run(out::add)
        assertTrue(code in 0..1)
        assertTrue(out.any { it.contains("Data directory") }, out.toString())
        assertTrue(out.any { it.contains("ffmpeg") }, out.toString())
    }
}
