package dev.lecturetubemcp.auth

import dev.lecturetubemcp.Config
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A service behind TU Wien SSO. [check] returns a human-readable user label if [Session]
 * is logged in there, else null. The first target of a login needs the user to log in
 * interactively; the following targets normally complete silently thanks to the IdP session.
 */
class SsoTarget(
    val name: String,
    val host: String,
    val loginUrl: String,
    val check: suspend (Session) -> String?,
)

/** The TU Wien services this project knows. Both MCP servers log in to all of them at once. */
object SsoTargets {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private fun req(url: String, s: Session) = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", Config.USER_AGENT)
        .apply { s.cookieHeader(URI.create(url).host)?.let { header("Cookie", it) } }

    val lectureTube = SsoTarget("LectureTube", Config.TOBIRA_HOST, Config.TOBIRA_LOGIN_URL) { s ->
        if (!s.hasShibSession(Config.TOBIRA_HOST)) return@SsoTarget null
        val body = """{"query":"{ currentUser { username displayName } }"}"""
        val r = http.sendAsync(
            req("${Config.TOBIRA}/graphql", s).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).await()
        val u = runCatching {
            Json.parseToJsonElement(r.body()).jsonObject["data"]?.jsonObject?.get("currentUser") as? JsonObject
        }.getOrNull() ?: return@SsoTarget null
        "${(u["displayName"] as? JsonPrimitive)?.content} (${(u["username"] as? JsonPrimitive)?.content})"
    }

    val opencast = SsoTarget("LectureTube Opencast", Config.OPENCAST_HOST, Config.OPENCAST_LOGIN_URL) { s ->
        if (s.hasShibSession(Config.OPENCAST_HOST)) "ok" else null
    }

    /** Logged in to TUWEL if `/my/` does not redirect to the login page. */
    val tuwel = SsoTarget("TUWEL", Config.TUWEL_HOST, Config.TUWEL_LOGIN_URL) { s ->
        if (s.cookiesFor(Config.TUWEL_HOST).none { it.name.startsWith("MoodleSession") }) return@SsoTarget null
        val r = http.sendAsync(req("${Config.TUWEL}/my/", s).GET().build(), HttpResponse.BodyHandlers.ofString()).await()
        if (r.statusCode() != 200) return@SsoTarget null
        Regex("""data-userid="(\d+)"|"userid"\s*:\s*(\d+)""").find(r.body())
            ?.let { m -> "user id " + (m.groupValues[1].ifEmpty { m.groupValues[2] }) } ?: "ok"
    }

    /** Primary target first, then the others. */
    fun orderedFor(primary: SsoTarget): List<SsoTarget> = listOf(primary) + listOf(lectureTube, tuwel, opencast).filter { it !== primary }
}
