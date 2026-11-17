package dev.lecturetubemcp.auth

import java.io.File
import java.util.concurrent.TimeUnit

/** The user's default web browser as configured in the operating system. */
data class DefaultBrowserInfo(
    /** Human-readable name, e.g. "Opera GX" or "org.mozilla.firefox". */
    val name: String,
    /** Command to start it (may be a prefix like `flatpak run org.mozilla.firefox`); empty if not resolvable. */
    val command: List<String>,
    /** Null if this browser can't be automated (e.g. Safari). */
    val kind: BrowserKind?,
)

object DefaultBrowser {
    private val os = System.getProperty("os.name").lowercase()

    fun detect(): DefaultBrowserInfo? = runCatching {
        when {
            os.contains("win") -> windows()
            os.contains("mac") -> mac()
            else -> linux()
        }
    }.getOrNull()

    /** FIREFOX speaks WebDriver BiDi, CHROMIUM the DevTools protocol; null = not automatable. */
    fun classify(vararg hints: String): BrowserKind? {
        val h = hints.joinToString(" ").lowercase()
        return when {
            listOf("firefox", "librewolf", "waterfox", "floorp", "zen-browser", "zen.desktop", "app.zen_browser", "icecat", "mullvadbrowser").any { it in h } -> BrowserKind.FIREFOX
            listOf("chrome", "chromium", "msedge", "microsoft-edge", "edgemac", "brave", "opera", "vivaldi", "yandex", "thorium", "helium", "cromite", "ungoogled").any { it in h } -> BrowserKind.CHROMIUM
            else -> null
        }
    }

    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        p.inputStream.bufferedReader().readText().trim().takeIf { p.exitValue() == 0 && it.isNotEmpty() }
    }.getOrNull()

    private fun linux(): DefaultBrowserInfo? {
        val desktopId = run("xdg-settings", "get", "default-web-browser")
            ?: run("xdg-mime", "query", "default", "x-scheme-handler/https")
            ?: return null
        val file = desktopDirs().map { File(it, desktopId) }.firstOrNull { it.isFile }
        val entry = file?.let { parseDesktopEntry(it.readLines()) }
        val command = entry?.second?.let { resolveCommand(it) }.orEmpty()
        val name = entry?.first ?: desktopId.removeSuffix(".desktop")
        return DefaultBrowserInfo(name, command, classify(desktopId, command.joinToString(" ")))
    }

    private fun desktopDirs(): List<String> {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
        val dataDirs = (System.getenv("XDG_DATA_DIRS") ?: "/usr/local/share:/usr/share").split(':')
        return (listOf(dataHome) + dataDirs + listOf("$home/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share", "/var/lib/snapd/desktop"))
            .map { "$it/applications" }
    }

    /** Returns (Name, Exec) of the `[Desktop Entry]` group. */
    fun parseDesktopEntry(lines: List<String>): Pair<String?, String?> {
        var inMain = false
        var name: String? = null
        var exec: String? = null
        for (l in lines) {
            val t = l.trim()
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (!inMain) continue
            if (t.startsWith("Name=") && name == null) name = t.substringAfter('=')
            if (t.startsWith("Exec=") && exec == null) exec = t.substringAfter('=')
        }
        return name to exec
    }

    /** Tokenises an Exec line, drops field codes (%U, %f …) and `env VAR=…` prefixes, resolves the program on PATH. */
    fun resolveCommand(exec: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in exec) {
            when {
                c == '"' -> quoted = !quoted
                c == ' ' && !quoted -> { if (cur.isNotEmpty()) tokens += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        var t = tokens.filterNot { it.matches(Regex("%[a-zA-Z]")) }
        if (t.firstOrNull() == "env") t = t.drop(1).dropWhile { it.contains('=') && !it.startsWith("-") }
        if (t.isEmpty()) return emptyList()
        // Flatpak: keep `flatpak run <app-id>`; for normal browsers keep only the program, since we add our own arguments.
        if (File(t[0]).name == "flatpak") {
            val run = t.indexOf("run")
            val appId = t.drop(run + 1).firstOrNull { !it.startsWith("-") } ?: return emptyList()
            return listOf(which("flatpak") ?: t[0], "run", appId)
        }
        val prog = if (t[0].contains('/')) t[0] else which(t[0]) ?: return emptyList()
        return listOf(prog)
    }

    private fun which(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath

    private fun mac(): DefaultBrowserInfo? {
        val plist = run(
            "defaults", "read",
            "${System.getProperty("user.home")}/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure", "LSHandlers",
        ) ?: return DefaultBrowserInfo("Safari", emptyList(), null) // no entry = system default (Safari)
        // Each handler is a { … } dict; find the one for the https scheme.
        val bundleId = Regex("""\{[^{}]*?LSHandlerURLScheme\s*=\s*https;[^{}]*?\}""").find(plist)?.value
            ?.let { Regex("""LSHandlerRoleAll\s*=\s*"?([\w.\-]+)"?;""").find(it)?.groupValues?.get(1) }
            ?: return DefaultBrowserInfo("Safari", emptyList(), null)
        if (bundleId.equals("com.apple.safari", true)) return DefaultBrowserInfo("Safari", emptyList(), null)
        val app = run("mdfind", "kMDItemCFBundleIdentifier == '$bundleId'")?.lines()?.firstOrNull { it.endsWith(".app") }
        val exe = app?.let { a -> run("defaults", "read", "$a/Contents/Info", "CFBundleExecutable")?.let { "$a/Contents/MacOS/$it" } }
        return DefaultBrowserInfo(app?.substringAfterLast('/')?.removeSuffix(".app") ?: bundleId, listOfNotNull(exe), classify(bundleId, app ?: ""))
    }

    private fun windows(): DefaultBrowserInfo? {
        val q = run("reg", "query", """HKCU\Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice""", "/v", "ProgId") ?: return null
        val progId = Regex("""ProgId\s+REG_SZ\s+(\S+)""").find(q)?.groupValues?.get(1) ?: return null
        val cmd = run("reg", "query", """HKCR\$progId\shell\open\command""", "/ve")
        val exe = cmd?.let { Regex(""""([^"]+\.exe)"""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) ?: Regex("""(\S+\.exe)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return DefaultBrowserInfo(progId, listOfNotNull(exe), classify(progId, exe ?: ""))
    }
}
