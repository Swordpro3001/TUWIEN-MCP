package dev.tuwelmcp.moodle

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.auth.Session
import dev.lecturetubemcp.auth.SessionStore
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

class MoodleException(message: String, val errorCode: String? = null, val needsLogin: Boolean = false) : Exception(message)

/**
 * Access to TUWEL (Moodle 5.x).
 *
 * Preferred: the Moodle **web service** API (`/webservice/rest/server.php`) with a
 * `moodle_mobile_app` token. The token is obtained from the browser session through
 * `/admin/tool/mobile/launch.php`, the same mechanism the official Moodle app uses with SSO,
 * and stays valid after the web session ends.
 * Fallback: the AJAX endpoint (`/lib/ajax/service.php`) with the session cookie + `sesskey`.
 */
class MoodleClient(
    private val sessionProvider: () -> Session? = { SessionStore.current },
    val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    val json = Json { ignoreUnknownKeys = true }
    private val tokenLock = Mutex()

    @Volatile private var sesskey: String? = null

    companion object {
        const val TOKEN_KEY = "tuwel.wstoken"
        const val PRIVATE_TOKEN_KEY = "tuwel.privatetoken"
        val base = Config.TUWEL

        /** URLs may come from course content via the model, so only https URLs on the TUWEL host itself are fetched. */
        fun requireTuwelUrl(url: String): String {
            val u = runCatching { URI(url) }.getOrNull()
            require(
                u != null && u.scheme == "https" && u.host.equals(Config.TUWEL_HOST, ignoreCase = true) &&
                    u.rawUserInfo == null && (u.port == -1 || u.port == 443),
            ) { "Only https://${Config.TUWEL_HOST}/ URLs are allowed, not: $url" }
            return url
        }
    }

    val token: String? get() = System.getenv("TUWEL_TOKEN")?.takeIf { it.isNotBlank() } ?: sessionProvider()?.tokens?.get(TOKEN_KEY)

    private fun cookieReq(url: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(90))
        .header("User-Agent", Config.USER_AGENT)
        .apply { sessionProvider()?.cookieHeader(URI.create(url).host)?.let { header("Cookie", it) } }

    /** Calls a Moodle external function with whatever credentials are available. */
    suspend fun call(function: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        val t = token ?: tokenLock.withLock { token ?: runCatching { acquireToken() }.getOrNull() }
        if (t != null) {
            try {
                return callWs(t, function, args)
            } catch (e: MoodleException) {
                if (e.errorCode != "invalidtoken") throw e
                // Token revoked or expired: drop it and retry with a fresh one or the session.
                SessionStore.update { s -> (s ?: Session(emptyList())).let { it.copy(tokens = it.tokens - TOKEN_KEY - PRIVATE_TOKEN_KEY) } }
                val fresh = runCatching { acquireToken() }.getOrNull()
                if (fresh != null) return callWs(fresh, function, args)
            }
        }
        return callAjax(function, args)
    }

    suspend fun callWs(token: String, function: String, args: JsonObject): JsonElement {
        val form = buildString {
            append("wstoken=").append(enc(token))
            append("&wsfunction=").append(enc(function))
            append("&moodlewsrestformat=json&moodlewssettingfilter=true&moodlewssettingfileurl=true")
            flatten("", args).forEach { (k, v) -> append('&').append(enc(k)).append('=').append(enc(v)) }
        }
        val req = HttpRequest.newBuilder(URI.create("$base/webservice/rest/server.php"))
            .timeout(Duration.ofSeconds(90))
            .header("User-Agent", Config.USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        val body = resp.body().trim()
        if (body.isEmpty() || body == "null") return JsonNull
        val el = json.parseToJsonElement(body)
        if (el is JsonObject && el["exception"] != null) {
            val code = (el["errorcode"] as? JsonPrimitive)?.content
            throw MoodleException("$function: ${(el["message"] as? JsonPrimitive)?.content}", code)
        }
        return el
    }

    suspend fun callAjax(function: String, args: JsonObject): JsonElement {
        val key = sesskey ?: refreshSesskey()
        val payload = buildJsonArray { addJsonObject { put("index", 0); put("methodname", function); put("args", args) } }
        val req = cookieReq("$base/lib/ajax/service.php?sesskey=${enc(key)}&info=${enc(function)}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        val el = json.parseToJsonElement(resp.body())
        if (el is JsonObject && el["error"] != null) {
            val code = (el["errorcode"] as? JsonPrimitive)?.content
            if (code == "invalidsesskey" || code == "servicerequireslogin") {
                sesskey = null
                throw MoodleException("Not logged in to TUWEL. Call the `login` tool.", code, needsLogin = true)
            }
            throw MoodleException("$function: ${(el["error"] as? JsonPrimitive)?.content}", code)
        }
        val first = (el as JsonArray).first().jsonObject
        if ((first["error"] as? JsonPrimitive)?.content == "true") {
            val ex = first["exception"]?.jsonObject
            val code = (ex?.get("errorcode") as? JsonPrimitive)?.content
            throw MoodleException("$function: ${(ex?.get("message") as? JsonPrimitive)?.content}", code, needsLogin = code == "servicerequireslogin")
        }
        return first["data"] ?: JsonNull
    }

    /** Fetches `/my/` with the session cookie and extracts `sesskey` from `M.cfg`. */
    suspend fun refreshSesskey(): String {
        val resp = http.sendAsync(cookieReq("$base/my/").GET().build(), HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() != 200) throw MoodleException("Not logged in to TUWEL. Call the `login` tool.", needsLogin = true)
        val k = Regex(""""sesskey":"([^"]+)"""").find(resp.body())?.groupValues?.get(1)
            ?: throw MoodleException("Could not find sesskey on the TUWEL page.")
        sesskey = k
        return k
    }

    /**
     * Obtains a `moodle_mobile_app` token from the logged-in browser session:
     * `launch.php` answers with a redirect to `moodlemobile://token=<base64(md5(wwwroot+passport):::token:::privatetoken)>`.
     */
    suspend fun acquireToken(): String {
        val session = sessionProvider() ?: throw MoodleException("Not logged in.", needsLogin = true)
        if (session.cookiesFor(Config.TUWEL_HOST).isEmpty()) throw MoodleException("No TUWEL session.", needsLogin = true)
        val passport = UUID.randomUUID().toString().replace("-", "").take(16)
        val url = "$base/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=$passport&urlscheme=moodlemobile"
        val resp = http.sendAsync(cookieReq(url).GET().build(), HttpResponse.BodyHandlers.discarding()).await()
        val loc = resp.headers().firstValue("Location").orElse("")
        if (!loc.startsWith("moodlemobile://token=")) {
            throw MoodleException("TUWEL did not issue an app token (status ${resp.statusCode()}).", needsLogin = loc.contains("/login/"))
        }
        val decoded = String(Base64.getDecoder().decode(loc.removePrefix("moodlemobile://token=").substringBefore('&')))
        val parts = decoded.split(":::")
        val expectedSig = md5(base + passport)
        if (parts.size < 2 || !parts[0].equals(expectedSig, ignoreCase = true)) throw MoodleException("Unexpected token response from TUWEL.")
        val tok = parts[1]
        SessionStore.update { s ->
            val cur = s ?: Session(emptyList())
            cur.copy(tokens = cur.tokens + (TOKEN_KEY to tok) + (parts.getOrNull(2)?.let { mapOf(PRIVATE_TOKEN_KEY to it) } ?: emptyMap()))
        }
        return tok
    }

    /**
     * Downloads a TUWEL file. Web-service URLs (`/webservice/pluginfile.php/…`) get the token
     * appended. Normal `/pluginfile.php/…` URLs use the session cookie, or are rewritten to the
     * web-service variant when only a token is available.
     */
    suspend fun download(fileUrl: String, maxBytes: Long = 200L * 1024 * 1024): Pair<ByteArray, String?> {
        var url = requireTuwelUrl(fileUrl)
        val t = token
        if (t != null) {
            if (url.contains("/pluginfile.php/") && !url.contains("/webservice/pluginfile.php/")) {
                url = url.replace("$base/pluginfile.php/", "$base/webservice/pluginfile.php/")
            }
            if (url.contains("/webservice/pluginfile.php/") && !url.contains("token=")) {
                url += (if (url.contains('?')) "&" else "?") + "token=" + enc(t)
            }
        }
        var current = url
        repeat(5) {
            val resp = http.sendAsync(cookieReq(current).GET().build(), HttpResponse.BodyHandlers.ofInputStream()).await()
            when (resp.statusCode()) {
                200 -> resp.body().use { input ->
                    val bytes = input.readNBytes((maxBytes + 1).toInt().coerceAtMost(Int.MAX_VALUE - 8))
                    if (bytes.size > maxBytes) throw MoodleException("File is larger than ${maxBytes / 1024 / 1024} MB.")
                    val ct = resp.headers().firstValue("Content-Type").orElse(null)
                    if (ct?.startsWith("text/html") == true && String(bytes.take(2000).toByteArray()).contains("login/index.php")) {
                        throw MoodleException("TUWEL asked for a login while downloading. Call the `login` tool.", needsLogin = true)
                    }
                    return bytes to ct
                }
                301, 302, 303, 307, 308 -> {
                    val loc = resp.headers().firstValue("Location").orElse("")
                    if (loc.contains("/login/")) throw MoodleException("TUWEL requires login for this file. Call the `login` tool.", needsLogin = true)
                    current = URI.create(current).resolve(loc).toString()
                }
                else -> throw MoodleException("HTTP ${resp.statusCode()} downloading $fileUrl")
            }
        }
        throw MoodleException("Too many redirects downloading $fileUrl")
    }

    /**
     * URL + headers for downloading a (large) TUWEL file to disk: the WS token is appended to
     * web-service URLs, otherwise the session cookie is sent. Used for video files.
     */
    fun mediaRequest(fileUrl: String): Pair<String, Map<String, String>> {
        var url = requireTuwelUrl(fileUrl)
        val t = token
        if (t != null) {
            if (url.contains("/pluginfile.php/") && !url.contains("/webservice/pluginfile.php/")) {
                url = url.replace("$base/pluginfile.php/", "$base/webservice/pluginfile.php/")
            }
            if (!url.contains("token=")) url += (if (url.contains('?')) "&" else "?") + "token=" + enc(t)
            return url to emptyMap()
        }
        val cookie = sessionProvider()?.cookieHeader(URI.create(url).host)
        return url to (cookie?.let { mapOf("Cookie" to it) } ?: emptyMap())
    }

    /** GET an HTML page with the session cookie (for pages not covered by the API). */
    suspend fun page(path: String): String {
        val resp = http.sendAsync(cookieReq(if (path.startsWith("http")) requireTuwelUrl(path) else base + path).GET().build(), HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() in 300..399) throw MoodleException("TUWEL requires login for $path. Call the `login` tool.", needsLogin = true)
        if (resp.statusCode() != 200) throw MoodleException("HTTP ${resp.statusCode()} for $path")
        return resp.body()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Moodle REST expects PHP-style form keys: `courseids[0]=1&options[0][name]=x`. */
    private fun flatten(prefix: String, el: JsonElement): List<Pair<String, String>> = when (el) {
        is JsonObject -> el.flatMap { (k, v) -> flatten(if (prefix.isEmpty()) k else "$prefix[$k]", v) }
        is JsonArray -> el.flatMapIndexed { i, v -> flatten("$prefix[$i]", v) }
        is JsonNull -> emptyList()
        is JsonPrimitive -> listOf(prefix to when (el.content) { "true" -> if (el.isString) "true" else "1"; "false" -> if (el.isString) "false" else "0"; else -> el.content })
    }
}
