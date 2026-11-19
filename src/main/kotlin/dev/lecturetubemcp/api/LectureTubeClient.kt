package dev.lecturetubemcp.api

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.auth.Session
import dev.lecturetubemcp.auth.SessionStore
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ApiException(message: String, val notAuthorized: Boolean = false) : Exception(message)

/** Thin HTTP layer for Tobira GraphQL, the Opencast Search API and the CDN. */
class LectureTubeClient(
    private val sessionProvider: () -> Session? = { SessionStore.current },
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER) // a redirect means "go to Shibboleth", i.e. not logged in
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {

    val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun request(url: String, session: Session?): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", Config.USER_AGENT)
        val host = URI.create(url).host
        session?.cookieHeader(host)?.let { b.header("Cookie", it) }
        return b
    }

    /**
     * Executes a GraphQL document. Returns `data`. GraphQL `errors` become [ApiException].
     * Pass [session] explicitly to test a candidate session (used during login).
     */
    suspend fun graphql(query: String, variables: JsonObject = JsonObject(emptyMap()), session: Session? = sessionProvider()): JsonObject {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString()
        val req = request("${Config.TOBIRA}/graphql", session)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() != 200) {
            throw ApiException("GraphQL HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
        }
        val obj = json.parseToJsonElement(resp.body()).jsonObject
        val errors = obj["errors"] as? JsonArray
        if (errors != null && errors.isNotEmpty()) {
            val first = errors[0].jsonObject
            val kind = (first["extensions"] as? JsonObject)?.get("kind")?.let { (it as? JsonPrimitive)?.content }
            val msg = errors.joinToString("; ") { (it.jsonObject["message"] as? JsonPrimitive)?.content ?: it.toString() }
            throw ApiException(msg, notAuthorized = kind == "NOT_AUTHORIZED")
        }
        return (obj["data"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    suspend fun opencast(path: String, params: Map<String, Any?>): JsonObject {
        val qs = params.filterValues { it != null && it.toString().isNotEmpty() }
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v.toString(), Charsets.UTF_8)}" }
        val url = "${Config.OPENCAST}$path" + if (qs.isNotEmpty()) "?$qs" else ""
        val resp = http.sendAsync(request(url, sessionProvider()).GET().build(), HttpResponse.BodyHandlers.ofString()).await()
        when (resp.statusCode()) {
            200 -> {
                val body = resp.body()
                // Opencast answers forbidden paths with an HTML "Opencast :: Forbidden" page and status 200.
                if (!body.trimStart().startsWith("{")) {
                    throw ApiException("Opencast denied access to $path (not permitted for this account)", notAuthorized = true)
                }
                return json.parseToJsonElement(body).jsonObject
            }
            301, 302, 303, 307 -> throw ApiException("Opencast requires login for $path", notAuthorized = true)
            else -> throw ApiException("Opencast HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
        }
    }

    suspend fun getText(url: String): String {
        val resp = http.sendAsync(request(url, sessionProvider()).GET().build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
        if (resp.statusCode() != 200) throw ApiException("HTTP ${resp.statusCode()} for $url")
        return resp.body()
    }

    suspend fun getBytes(url: String, maxBytes: Int = 5_000_000): ByteArray {
        val resp = http.sendAsync(request(url, sessionProvider()).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        if (resp.statusCode() != 200) throw ApiException("HTTP ${resp.statusCode()} for $url")
        val b = resp.body()
        if (b.size > maxBytes) throw ApiException("File too large (${b.size} bytes): $url")
        return b
    }

    /** Opencast `/info/me.json`: who the Opencast node thinks we are. */
    suspend fun opencastMe(): JsonObject? = runCatching { opencast("/info/me.json", emptyMap()) }.getOrNull()

    /** Returns (username, displayName) or null when the session is anonymous or expired. */
    suspend fun currentUser(session: Session? = sessionProvider()): Pair<String, String>? {
        val d = graphql("{ currentUser { username displayName } }", session = session)
        val u = d["currentUser"] as? JsonObject ?: return null
        val name = (u["username"] as? JsonPrimitive)?.content ?: return null
        return name to ((u["displayName"] as? JsonPrimitive)?.content ?: name)
    }
}

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonElement?.d(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
fun JsonElement?.b(): Boolean? = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Opencast JSON returns a single object instead of a one-element array. Normalizes to a list. */
fun JsonElement?.asList(): List<JsonElement> = when (this) {
    null, is JsonNull -> emptyList()
    is JsonArray -> this
    else -> listOf(this)
}
