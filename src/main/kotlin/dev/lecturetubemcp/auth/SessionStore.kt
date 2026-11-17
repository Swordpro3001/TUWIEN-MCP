package dev.lecturetubemcp.auth

import dev.lecturetubemcp.Config
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    /** Host-only cookies have no leading dot; domain cookies start with '.'. */
    val domain: String,
    val path: String = "/",
    /** Epoch seconds, or null for browser-session cookies (typical for Shibboleth). */
    val expires: Long? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
) {
    fun matches(host: String, nowEpoch: Long): Boolean {
        if (expires != null && expires in 1..<nowEpoch) return false
        val d = domain.lowercase()
        val h = host.lowercase()
        return if (d.startsWith(".")) h == d.substring(1) || h.endsWith(d) else h == d
    }
}

@Serializable
data class Session(
    val cookies: List<StoredCookie>,
    val savedAt: String = Instant.now().toString(),
    val username: String? = null,
    val displayName: String? = null,
    /** How the session was obtained: "browser:firefox", "browser:chromium", "manual", "env". */
    val source: String = "manual",
    /** Non-cookie credentials, e.g. the TUWEL (Moodle) web service token. */
    val tokens: Map<String, String> = emptyMap(),
) {
    fun cookiesFor(host: String): List<StoredCookie> {
        val now = Instant.now().epochSecond
        return cookies.filter { it.matches(host, now) }
    }

    /** Replaces all cookies of the given hosts with [fresh], keeping everything else. */
    fun replacingHosts(hosts: Collection<String>, fresh: List<StoredCookie>): Session {
        val now = Instant.now().epochSecond
        val kept = cookies.filterNot { c -> hosts.any { h -> c.matches(h, now) } }
        return copy(cookies = kept + fresh)
    }

    fun cookieHeader(host: String): String? {
        val now = Instant.now().epochSecond
        val list = cookies.filter { it.matches(host, now) }
        return if (list.isEmpty()) null else list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun hasShibSession(host: String): Boolean {
        val now = Instant.now().epochSecond
        return cookies.any { it.name.startsWith("_shibsession_") && it.matches(host, now) }
    }
}

/**
 * Holds the current session in memory and persists it to [Config.sessionFile]
 * (owner-only permissions where the file system supports it).
 */
object SessionStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var current: Session? = null
        private set

    fun load() {
        System.getenv("LECTURETUBE_COOKIE")?.takeIf { it.isNotBlank() }?.let {
            current = fromCookieHeader(it, Config.TOBIRA_HOST, source = "env")
            return
        }
        val f = Config.sessionFile
        if (Files.exists(f)) {
            current = runCatching { json.decodeFromString<Session>(Files.readString(f)) }.getOrNull()
        }
    }

    /** Atomically updates the stored session (or creates one). */
    @Synchronized
    fun update(block: (Session?) -> Session) = save(block(current))

    fun save(session: Session) {
        current = session
        val dir = Config.dataDir
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "session", ".tmp")
        restrict(tmp)
        Files.writeString(tmp, json.encodeToString(session))
        Files.move(tmp, Config.sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        restrict(Config.sessionFile)
    }

    fun clear() {
        current = null
        Files.deleteIfExists(Config.sessionFile)
    }

    /**
     * Parses a `Cookie:` header value (`a=1; b=2`) as copied from browser dev tools.
     * The cookies are bound to [host] (host-only).
     */
    fun fromCookieHeader(header: String, host: String, source: String = "manual"): Session {
        val cookies = header.removePrefix("Cookie:").split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else StoredCookie(part.substring(0, i).trim(), part.substring(i + 1).trim(), host)
        }
        return Session(cookies = cookies, source = source)
    }

    private fun restrict(p: Path) {
        runCatching { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")) }
    }
}
