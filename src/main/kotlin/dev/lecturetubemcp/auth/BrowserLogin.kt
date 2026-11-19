package dev.lecturetubemcp.auth

import dev.lecturetubemcp.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class BrowserKind { FIREFOX, CHROMIUM }

/** A browser we can automate: [command] starts it (may be a prefix like `flatpak run org.mozilla.firefox`). */
data class BrowserExe(val kind: BrowserKind, val command: List<String>, val name: String, val isSystemDefault: Boolean = false) {
    constructor(kind: BrowserKind, path: String) : this(kind, listOf(path), File(path).name)
    val path: String get() = command.joinToString(" ")
    /** Separate login profile per browser (profiles of different browsers are not compatible). */
    val profileName: String get() = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { kind.name.lowercase() }
}

/** A browser started by us with a remote-debugging channel. */
interface BrowserDriver {
    val process: Process
    suspend fun cookies(): List<StoredCookie>
    suspend fun setCookies(cookies: List<StoredCookie>)
    suspend fun open(url: String)
    suspend fun close()
}

object Browsers {
    private val chromiumNames = listOf(
        "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "microsoft-edge-stable",
        "brave", "brave-browser", "opera", "opera-gx", "opera-beta", "vivaldi", "vivaldi-stable", "thorium-browser", "yandex-browser",
    )
    private val firefoxNames = listOf("firefox", "firefox-esr", "librewolf", "zen-browser", "floorp", "waterfox")

    private val macApps = listOf(
        "Google Chrome", "Chromium", "Microsoft Edge", "Brave Browser", "Opera", "Opera GX", "Vivaldi", "Arc", "Firefox", "LibreWolf", "Zen", "Floorp",
    ).map { "/Applications/$it.app/Contents/MacOS/" + if (it == "Firefox" || it == "LibreWolf" || it == "Floorp" || it == "Zen") it.lowercase() else it }

    private fun windowsApps(): List<String> {
        val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))
        return roots.flatMap { r ->
            listOf(
                "$r\\Google\\Chrome\\Application\\chrome.exe", "$r\\Microsoft\\Edge\\Application\\msedge.exe",
                "$r\\BraveSoftware\\Brave-Browser\\Application\\brave.exe", "$r\\Programs\\Opera\\opera.exe",
                "$r\\Programs\\Opera GX\\opera.exe", "$r\\Vivaldi\\Application\\vivaldi.exe", "$r\\Mozilla Firefox\\firefox.exe",
            )
        }
    }

    private fun onPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence()
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath

    fun kindOf(path: String): BrowserKind = DefaultBrowser.classify(path) ?: BrowserKind.CHROMIUM

    /** The OS default browser, if it can be automated and its program was found. */
    fun systemDefault(): Pair<BrowserExe?, String?> {
        val d = DefaultBrowser.detect() ?: return null to null
        if (d.kind == null) return null to "Your default browser (${d.name}) can't be automated for the login"
        if (d.command.isEmpty()) return null to "Your default browser (${d.name}) was found but its program could not be located"
        return BrowserExe(d.kind, d.command, d.name, isSystemDefault = true) to null
    }

    /** Installed browsers that can be automated, in fallback order (Chromium-based first, then Firefox-based). */
    fun detect(): List<BrowserExe> {
        val out = mutableListOf<BrowserExe>()
        chromiumNames.mapNotNullTo(out) { n -> onPath(n)?.let { BrowserExe(BrowserKind.CHROMIUM, it) } }
        firefoxNames.mapNotNullTo(out) { n -> onPath(n)?.let { BrowserExe(BrowserKind.FIREFOX, it) } }
        (macApps + windowsApps()).filter { File(it).isFile }.mapTo(out) { BrowserExe(kindOf(it), it) }
        return out.distinctBy { runCatching { File(it.command.first()).canonicalPath }.getOrDefault(it.path) }
    }

    /** Last decision, for messages ("Using your default browser …"). */
    @Volatile var lastChoiceNote: String = ""
        private set

    /**
     * Order: explicit [preference] (name or path) → `LECTURETUBE_BROWSER` → the OS default browser →
     * other installed browsers.
     */
    fun pick(preference: String?): BrowserExe {
        val p = preference?.trim()?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("auto", "default") }
        val env = System.getenv("LECTURETUBE_BROWSER")?.takeIf { it.isNotBlank() }
        val (sysDefault, why) = systemDefault()
        val installed = detect()
        val choice: BrowserExe? = when {
            p != null && (p.contains('/') || p.contains('\\')) -> BrowserExe(kindOf(p), p)
            p != null -> {
                val q = p.lowercase()
                val wantKind = when (q) { "firefox" -> BrowserKind.FIREFOX; "chrome", "chromium" -> BrowserKind.CHROMIUM; else -> null }
                (listOfNotNull(sysDefault) + installed).firstOrNull { it.name.lowercase().contains(q) }
                    ?: wantKind?.let { k -> (listOfNotNull(sysDefault) + installed).firstOrNull { it.kind == k } }
                    ?: throw IllegalStateException("Browser '$p' not found. Installed: ${installed.joinToString { it.name }.ifEmpty { "none" }}.")
            }
            env != null -> BrowserExe(kindOf(env), env)
            sysDefault != null -> sysDefault
            else -> installed.firstOrNull()
        }
        lastChoiceNote = when {
            choice == null -> ""
            choice.isSystemDefault -> "your default browser ${choice.name}"
            why != null && p == null && env == null -> "${choice.name} ($why)"
            else -> choice.name
        }
        return choice ?: throw IllegalStateException(
            (why?.let { "$it, and no " } ?: "No ") + "other supported browser was found (Chrome, Edge, Brave, Opera, Vivaldi, Chromium, Firefox …). " +
                "Set LECTURETUBE_BROWSER to the browser executable, or set LECTURETUBE_COOKIE to a Cookie header copied from the browser.",
        )
    }

    suspend fun launch(exe: BrowserExe, headless: Boolean): BrowserDriver = when (exe.kind) {
        BrowserKind.FIREFOX -> FirefoxDriver.launch(exe.command, exe.profileName, headless)
        BrowserKind.CHROMIUM -> ChromiumDriver.launch(exe.command, exe.profileName, headless)
    }
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Browsers we started; killed if the JVM exits (Ctrl+C, crash) so they don't keep the login profile locked. */
private val liveBrowsers = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>().also { set ->
    Runtime.getRuntime().addShutdownHook(Thread { set.forEach { p -> (p.descendants().toList() + p.toHandle()).forEach { it.destroyForcibly() } } })
}

private fun startProcess(cmd: List<String>): Process =
    ProcessBuilder(cmd)
        // Never let browser output reach stdout: stdout is the MCP JSON-RPC channel.
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .start()
        .also { liveBrowsers += it }

/**
 * A browser left over from an earlier run (JVM killed before it could clean up) still holds our login profile, and a
 * new launch would hand off to it and exit (Chromium: code 21). Ask it to close through its debugging port, recorded in
 * the profile's `DevToolsActivePort` (written by Chromium itself, by us for Firefox). Works the same on every OS.
 */
internal suspend fun closeLeftover(profile: Path, kind: BrowserKind) {
    val lines = runCatching { Files.readAllLines(profile.resolve(PORT_FILE)) }.getOrNull() ?: return
    val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return
    runCatching {
        val rpc = WsRpc.connect("ws://127.0.0.1:$port${lines.getOrElse(1) { "" }.trim()}")
        try {
            if (kind == BrowserKind.FIREFOX) rpc.call("session.new", buildJsonObject { putJsonObject("capabilities") {} }, timeoutMs = 5_000)
            rpc.call(if (kind == BrowserKind.FIREFOX) "browser.close" else "Browser.close", timeoutMs = 5_000)
        } finally {
            rpc.close()
        }
    }.onFailure { return } // nothing listening: no leftover (or not ours)
    // The profile lock is released only once the process is gone, a moment after the port closes.
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < 10.seconds &&
        (portOpen(port) || listOf("SingletonLock", "lock").any { Files.exists(profile.resolve(it), java.nio.file.LinkOption.NOFOLLOW_LINKS) })
    ) delay(200)
}

private const val PORT_FILE = "DevToolsActivePort"

private fun portOpen(port: Int): Boolean =
    runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess

/**
 * The browser command may be a wrapper script that forks the real browser, so kill the whole tree and wait for it:
 * a browser still shutting down keeps the profile locked for the next launch.
 */
internal fun Process.destroyTree(wait: Duration = 5.seconds) {
    val all = descendants().toList() + toHandle() // collect before killing: orphans get reparented and drop out of descendants()
    all.forEach { it.destroy() }
    val deadline = System.nanoTime() + wait.inWholeNanoseconds
    for (h in all) {
        runCatching { h.onExit().get(maxOf(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS) }
        if (h.isAlive) h.destroyForcibly()
    }
    liveBrowsers -= this
}

private fun JsonPrimitive.asLong(): Long? = runCatching { double.toLong() }.getOrNull()

/** Firefox via WebDriver BiDi (`--remote-debugging-port`). */
class FirefoxDriver private constructor(override val process: Process, private val rpc: WsRpc) : BrowserDriver {
    override suspend fun cookies(): List<StoredCookie> {
        val res = rpc.call("storage.getCookies")
        return res["cookies"]?.jsonArray.orEmpty().map { it.jsonObject }.map { c ->
            StoredCookie(
                name = c["name"].str() ?: "",
                value = c["value"]?.jsonObject?.get("value").str() ?: "",
                domain = c["domain"].str() ?: "",
                path = c["path"].str() ?: "/",
                expires = (c["expiry"] as? JsonPrimitive)?.asLong(),
                secure = c["secure"]?.toString() == "true",
                httpOnly = c["httpOnly"]?.toString() == "true",
            )
        }
    }

    override suspend fun setCookies(cookies: List<StoredCookie>) {
        for (c in cookies) {
            runCatching {
                rpc.call(
                    "storage.setCookie",
                    buildJsonObject {
                        putJsonObject("cookie") {
                            put("name", c.name)
                            putJsonObject("value") { put("type", "string"); put("value", c.value) }
                            put("domain", c.domain)
                            put("path", c.path)
                            put("secure", c.secure)
                            put("httpOnly", c.httpOnly)
                            c.expires?.let { put("expiry", it) }
                        }
                    },
                )
            }
        }
    }

    override suspend fun open(url: String) {
        val tree = rpc.call("browsingContext.getTree")
        val ctx = tree["contexts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("context").str()
            ?: throw RpcException("no browsing context")
        rpc.call("browsingContext.navigate", buildJsonObject { put("context", ctx); put("url", url); put("wait", "none") })
    }

    override suspend fun close() {
        runCatching { rpc.call("browser.close", timeoutMs = 5_000) }
        rpc.close()
        withContext(Dispatchers.IO) { process.destroyTree() }
    }

    companion object {
        suspend fun launch(exe: List<String>, profileName: String, headless: Boolean): FirefoxDriver = withContext(Dispatchers.IO) {
            val profile = Config.dataDir.resolve("profiles").resolve(profileName)
            Files.createDirectories(profile)
            Files.writeString(
                profile.resolve("user.js"),
                """
                user_pref("browser.aboutwelcome.enabled", false);
                user_pref("browser.startup.homepage_override.mstone", "ignore");
                user_pref("startup.homepage_welcome_url", "");
                user_pref("datareporting.policy.dataSubmissionEnabled", false);
                user_pref("browser.shell.checkDefaultBrowser", false);
                user_pref("remote.active-protocols", 1);
                """.trimIndent(),
            )
            closeLeftover(profile, BrowserKind.FIREFOX)
            Files.deleteIfExists(profile.resolve("lock"))
            val port = freePort()
            val cmd = (exe + listOf("--new-instance", "--no-remote", "--profile", profile.toString(), "--remote-debugging-port", port.toString())).toMutableList()
            if (headless) cmd += "--headless"
            cmd += "about:blank"
            val proc = startProcess(cmd)
            val rpc = retryConnect(proc) { WsRpc.connect("ws://127.0.0.1:$port/session") }
            Files.writeString(profile.resolve(PORT_FILE), "$port\n/session") // so a later run can close this one if it's left over
            rpc.call("session.new", buildJsonObject { putJsonObject("capabilities") {} })
            FirefoxDriver(proc, rpc)
        }
    }
}

/** Chrome/Chromium/Edge/Brave via the Chrome DevTools Protocol. */
class ChromiumDriver private constructor(override val process: Process, private val rpc: WsRpc) : BrowserDriver {
    override suspend fun cookies(): List<StoredCookie> {
        val res = rpc.call("Storage.getCookies")
        return (res["cookies"] as? JsonArray).orEmpty().map { it.jsonObject }.map { c ->
            StoredCookie(
                name = c["name"].str() ?: "",
                value = c["value"].str() ?: "",
                domain = c["domain"].str() ?: "",
                path = c["path"].str() ?: "/",
                expires = (c["expires"] as? JsonPrimitive)?.asLong()?.takeIf { it > 0 },
                secure = c["secure"]?.toString() == "true",
                httpOnly = c["httpOnly"]?.toString() == "true",
            )
        }
    }

    override suspend fun setCookies(cookies: List<StoredCookie>) {
        if (cookies.isEmpty()) return
        runCatching {
            rpc.call(
                "Storage.setCookies",
                buildJsonObject {
                    putJsonArray("cookies") {
                        cookies.forEach { c ->
                            addJsonObject {
                                put("name", c.name); put("value", c.value); put("domain", c.domain); put("path", c.path)
                                put("secure", c.secure); put("httpOnly", c.httpOnly)
                                c.expires?.let { put("expires", it) }
                            }
                        }
                    }
                },
            )
        }
    }

    override suspend fun open(url: String) {
        val targets = rpc.call("Target.getTargets")["targetInfos"]?.jsonArray.orEmpty().map { it.jsonObject }
        val page = targets.firstOrNull { it["type"].str() == "page" }?.get("targetId").str()
        if (page == null) {
            rpc.call("Target.createTarget", buildJsonObject { put("url", url) }); return
        }
        val sid = rpc.call("Target.attachToTarget", buildJsonObject { put("targetId", page); put("flatten", true) })["sessionId"].str()
        rpc.call("Page.navigate", buildJsonObject { put("url", url) }, sessionId = sid)
    }

    override suspend fun close() {
        runCatching { rpc.call("Browser.close", timeoutMs = 5_000) }
        rpc.close()
        withContext(Dispatchers.IO) { process.destroyTree() }
    }

    companion object {
        suspend fun launch(exe: List<String>, profileName: String, headless: Boolean): ChromiumDriver = withContext(Dispatchers.IO) {
            val profile: Path = Config.dataDir.resolve("profiles").resolve(profileName)
            Files.createDirectories(profile)
            closeLeftover(profile, BrowserKind.CHROMIUM)
            val portFile = profile.resolve(PORT_FILE)
            Files.deleteIfExists(portFile)
            val cmd = (exe + listOf(
                "--user-data-dir=$profile", "--remote-debugging-port=0",
                "--no-first-run", "--no-default-browser-check",
            )).toMutableList()
            if (headless) cmd += "--headless=new" else cmd += "--new-window"
            cmd += "about:blank"
            val proc = startProcess(cmd)
            val rpc = retryConnect(proc) {
                val lines = Files.readAllLines(portFile)
                WsRpc.connect("ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}")
            }
            ChromiumDriver(proc, rpc)
        }
    }
}

private suspend fun <T> retryConnect(proc: Process, timeout: Duration = 30.seconds, block: suspend () -> T): T {
    val start = TimeSource.Monotonic.markNow()
    var last: Throwable? = null
    while (start.elapsedNow() < timeout) {
        if (!proc.isAlive) {
            throw IllegalStateException(
                "Browser exited immediately (code ${proc.exitValue()}). Is the login browser profile already open in another window? " +
                    "Close it and try again.",
            )
        }
        try {
            return block()
        } catch (c: CancellationException) {
            proc.destroyTree(); throw c
        } catch (t: Throwable) {
            last = t
            delay(300)
        }
    }
    proc.destroyTree()
    throw IllegalStateException("Could not connect to the browser's debugging port: ${last?.message}")
}

data class LoginResult(val ok: Boolean, val message: String, val session: Session? = null)

/**
 * TU Wien SSO login across several services with one browser session:
 * 1. If IdP cookies from an earlier login are stored, try a **silent** headless login first.
 * 2. Otherwise (or if that fails) open a visible browser at the primary target's login page and
 *    wait for the user to log in.
 * 3. Visit the remaining targets; the IdP session makes these complete without interaction.
 * 4. Store the cookies of all [Config.SESSION_HOSTS] in the shared session file.
 */
class LoginFlow(
    private val targets: List<SsoTarget>,
    private val log: (String) -> Unit = {},
) {
    private fun relevant(cookies: List<StoredCookie>): List<StoredCookie> {
        val now = java.time.Instant.now().epochSecond
        return cookies.filter { c -> Config.SESSION_HOSTS.any { c.matches(it, now) } }
    }

    /** [interactive] = false only tries the silent login. */
    suspend fun run(preference: String?, timeout: Duration, interactive: Boolean = true): LoginResult {
        val exe = Browsers.pick(preference)
        val idpCookies = SessionStore.current?.cookiesFor(Config.IDP_HOST).orEmpty()
        if (idpCookies.isNotEmpty()) {
            log("Trying silent login with the stored TU Wien SSO session …")
            val r = runCatching { attempt(exe, headless = true, timeout = 5.seconds, seed = idpCookies) }
                .getOrElse { LoginResult(false, "silent login failed: ${it.message}") }
            if (r.ok || !interactive) return r
            log("Silent login not possible (SSO session expired). Opening the login page …")
        } else if (!interactive) {
            return LoginResult(false, "No stored SSO session; an interactive login is required.")
        }
        log("Opening ${Browsers.lastChoiceNote.ifEmpty { exe.name }} at the TU Wien login page …")
        return attempt(exe, headless = false, timeout = timeout, seed = emptyList())
    }

    private suspend fun attempt(exe: BrowserExe, headless: Boolean, timeout: Duration, seed: List<StoredCookie>): LoginResult {
        val driver = Browsers.launch(exe, headless)
        val source = "browser:${exe.kind.name.lowercase()}"
        try {
            driver.setCookies(seed)
            val primary = targets.first()
            driver.open(primary.loginUrl)
            val start = TimeSource.Monotonic.markNow()
            var who: String? = null
            var session = Session(emptyList(), source = source)
            while (start.elapsedNow() < timeout) {
                if (!driver.process.isAlive) return LoginResult(false, "The browser was closed before the login finished.")
                session = Session(relevant(driver.cookies()), source = source)
                who = runCatching { primary.check(session) }.getOrNull()
                if (who != null) break
                delay(1500)
            }
            if (who == null) {
                return LoginResult(false, "Timed out after ${timeout.inWholeSeconds}s waiting for the ${primary.name} login.")
            }
            log("${primary.name}: logged in as $who.")
            persist(session)

            val results = mutableListOf("${primary.name}: $who")
            for (t in targets.drop(1)) {
                runCatching { driver.open(t.loginUrl) }
                val t0 = TimeSource.Monotonic.markNow()
                var ok: String? = null
                while (t0.elapsedNow() < 25.seconds && driver.process.isAlive) {
                    session = runCatching { Session(relevant(driver.cookies()), source = source) }.getOrNull() ?: break
                    ok = runCatching { t.check(session) }.getOrNull()
                    if (ok != null) break
                    delay(1000)
                }
                log("${t.name}: ${if (ok != null) "ok" else "not logged in"}")
                results += "${t.name}: ${ok ?: "failed"}"
                persist(session)
            }
            val final = SessionStore.current ?: session
            return LoginResult(true, "Logged in. " + results.joinToString("; ") + ".", final)
        } finally {
            runCatching { driver.close() }
        }
    }

    /** Merge fresh browser cookies for the known hosts into the shared session (tokens are kept). */
    private fun persist(fresh: Session) {
        SessionStore.update { cur ->
            (cur ?: Session(emptyList())).replacingHosts(Config.SESSION_HOSTS, fresh.cookies)
                .copy(source = fresh.source, savedAt = java.time.Instant.now().toString())
        }
    }
}

/** Keeps at most one login running in the background so an MCP tool call can return early. */
class LoginManager(private val flow: LoginFlow) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var running: Deferred<LoginResult>? = null

    @Volatile
    var lastResult: LoginResult? = null
        private set

    val inProgress: Boolean get() = running?.isActive == true

    @Synchronized
    fun start(preference: String?, timeout: Duration): Deferred<LoginResult> {
        running?.takeIf { it.isActive }?.let { return it }
        val d = scope.async {
            val r = runCatching { flow.run(preference, timeout) }
                .getOrElse { LoginResult(false, "Login failed: ${it.message}") }
            lastResult = r
            r
        }
        running = d
        return d
    }
}
